package com.videoshell.data.site

import com.videoshell.data.model.SiteConfig
import com.videoshell.data.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 「同一个站有好几个网址，哪个快用哪个」（v1.0.67）。
 *
 * ## 为什么要它
 *
 * 影视站的域名会轮换，其中**网盘分享站族**（快映 / 玩偶这类）最典型：同一个站同时挂着
 * 一串域名，主域名随时可能被墙、过期或换掉。潇洒 TVBox 本地包对这事的做法就是在源的配置里
 * 放一串网址、运行时挑能用的那个（实测清单见 `docs/网盘站源清单.md`：玩偶 4 个、快映 2 个…）。
 *
 * 旧做法是"一个站 = 一个地址"：主地址一死，整条记录就废了，用户只能删掉重加。
 * 现在 [SiteConfig.mirrors] 可以带一串备用地址，这里负责**挑**。
 *
 * ## 挑法：并发赛马，第一个活下来的赢
 *
 * `race()` 把每个候选地址**同时**探一遍（[Http.probeMs]），**谁先回来谁赢**，
 * 不等其余 —— 这正是用户说的「哪一个响应得快就用哪一个」。
 *
 * ⚠️ 别把它写成"挨个试、取第一个成功的"：那在**顺序里排第一的那个地址是死域名**时，
 * 用户要先白等一次连接超时。并发 + 先到先赢才真的用上了"快"这个信息。
 *
 * ## 三条不变量
 *
 * 1. **单地址站点零开销。** [candidates] 只有一项时直接返回它，一个探活请求都不发 ——
 *    这对现有用户是"行为逐字节不变"，非常重要（这个功能不该给不相关的人带来任何延迟）。
 * 2. **挑出来的结果**只活在内存里（[picks]），**不写盘**。域名该用哪个是"此刻的网络事实"，
 *    换网络、换时间都会变；写盘等于把一个临时结论固化成长期配置。
 *    用户在界面上看到的 `baseUrl` 始终是他自己填的那个。
 * 3. **失败要能自愈。** 请求失败时 [invalidate] 把这次的选中作废，下一次会重新赛马。
 *    另外"全败"也会被记下来（TTL 更短，90 秒），否则每次请求都会重新赛一遍 4 秒。
 *
 * ## 为什么调度本体是**阻塞**的、而且探针可注入
 *
 * `race()` 用的是自己的线程池 + 轮询（不是 `awaitAll`）—— 因为 `awaitAll` 会等**最慢**的
 * 那个候选，正好把"先到先赢"废掉。而这个调度（谁先到、全败怎么办、超时怎么收）
 * 恰恰是最容易写错的部分，所以探针做成 [Prober] **注入**：离线 harness
 * （`tools/verify/MirrorRaceTest.java`）能拿一个假的探针**真跑**它，断言
 * "快的那条赢"与"不慢的那条拖住"，而不是读源码猜。
 * （同一条思路见 [com.videoshell.player.HlsPrefetch] 的拉片动作注入。）
 */
object MirrorRace {

    /**
     * 探针：给一个地址，返回**耗时毫秒**；失败返回 **-1**。
     *
     * 做成接口是为了能在离线 harness 里换成桩 —— 见文件头最后一段。
     */
    fun interface Prober {
        fun probe(url: String): Long
    }

    /** 选中结果的保鲜期：域名可用与否是"此刻的网络事实"，太久了就该重挑 */
    private const val TTL_OK_MS = 30 * 60 * 1000L

    /**
     * 「所有候选都不可达」这条结论的保鲜期，比 [TTL_OK_MS] 短得多。
     *
     * 记它是因为：没有它的话，一个全灭的站**每次请求都要重赛一遍**（每次 4 秒起），
     * 而"现在谁都不通"这个结论在几十秒内是不会变的。
     */
    private const val TTL_FAIL_MS = 90 * 1000L

    /** 赛马整体封顶：探活自身 4 秒封顶（[Http.probeMs]），这里再宽一点兜住调度 */
    private const val RACE_TIMEOUT_MS = 5_500L

    /** 轮询间隔：只用来"发现赢家已经产生"，25ms 足够快而几乎不占 CPU */
    private const val POLL_MS = 25L

    /** 赛马线程池：**进程级固定大小**，daemon 线程随进程走，没人需要负责关它 */
    private val pool: ExecutorService = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "mirror-race").apply { isDaemon = true }
    }

    /** 后台预热用（见 [warmUp]）：与调用方生命周期无关，写的是本对象自己的进程级缓存 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** key → 这次挑中的地址 / 或"全败"的记账。只增不减地换，不落盘（见文件头第 2 条） */
    private class Pick(val url: String, val at: Long, val ok: Boolean)

    private val picks = ConcurrentHashMap<String, Pick>()

    /** 同一个站的赛马串行化：两个请求同时到、不该各探一遍 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 生产用的探针 */
    val HTTP_PROBER: Prober = Prober { url -> Http.probeMs(url) }

    // ------------------------------------------------------------------ 纯逻辑

    /**
     * 规范化一个地址：去空白、补 `http://`、去尾部 `/`。
     *
     * 不能用的（空、非 http(s) 协议、没有 host）返回**空串**，由调用方丢掉 ——
     * 这个函数不抛异常：它处理的是用户手输/粘贴的内容，抛异常等于把一次手滑变成一次崩溃。
     */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            // 已经带了别的协议（ftp://、quark://）⇒ 不是网页地址，别硬补 http://
            if (s.contains("://")) return ""
            s = "http://$s"
        }
        val m = Regex("^https?://[^/\\s]+", RegexOption.IGNORE_CASE).find(s) ?: return ""
        // host 必须是"像域名或 IP 的东西"：字母数字 + 点 + 横杠（可带端口），且**至少一个点**。
        // 这一条挡的是"用户把一句话粘进了输入框"——不拦的话 `这是一串中文` 会被上面那条正则
        // 认成合法 host（`[^/\s]+` 是含中文的），变成一个语法上通、永远探不通的候选；
        // `http://a` 这种连域名都不算的也在这里一并挡掉。
        val host = m.value.substringAfter("://")
        if (!Regex("^[A-Za-z0-9.\\-]+(:\\d+)?$").matches(host)) return ""
        if (!host.substringBefore(':').contains('.')) return ""
        return s.trimEnd('/')
    }

    /**
     * 候选地址：**`baseUrl` 在前，然后 [SiteConfig.mirrors]**，规范化 + 去重保序。
     *
     * `baseUrl` 必须留在候选里：它常是最快的那条（用户当初就是这么加的），
     * 而且它是"所有备用地址都不可达"时唯一确定的退路。
     *
     * ⚠️ 一律经 [SiteConfig.mirrorList] 取备用地址 —— `mirrors` 在老配置里是 **null**
     * （Gson 用 Unsafe 分配对象，Kotlin 的默认值不生效），直读就是一次 NPE。
     */
    fun candidates(s: SiteConfig): List<String> {
        val out = LinkedHashSet<String>()
        normalize(s.baseUrl).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (m in s.mirrorList()) {
            normalize(m).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out.toList()
    }

    /** 地址的短标签（提示 / 诊断用，不带 scheme） */
    fun label(url: String): String = RecipeStore.hostOf(url).ifBlank { url }

    /**
     * 并发探活 [cands]，**第一个活着回来的立刻胜出**，不等其余。
     *
     * 全部不可达（或整体超过 [timeoutMs] 仍无人成功）返回 **null**。
     *
     * [prober] 注入的理由见文件头。返回的一定是"**最快**回来的那个成功者"：
     * 用 `compareAndSet(null, …)` 抢第一个成功者，而不是"按列表顺序取第一个成功的"——
     * 后者会在顺序里靠前的候选是死域名时白等它超时，也就是说"快"这个信息完全没用上。
     *
     * ⚠️ 只有一个候选时也走同一条路径（同样受 [timeoutMs] 约束）：
     * 特判成"直接同步探一次"会让超时在那一种情况下失效，那正是最难发现的一类不对称。
     */
    fun race(
        cands: List<String>,
        prober: Prober,
        timeoutMs: Long = RACE_TIMEOUT_MS
    ): String? {
        if (cands.isEmpty()) return null
        val win = AtomicReference<String?>(null)
        val left = AtomicInteger(cands.size)
        for (u in cands) {
            pool.execute {
                val ms = runCatching { prober.probe(u) }.getOrDefault(-1L)
                if (ms >= 0) win.compareAndSet(null, u)
                left.decrementAndGet()
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            win.get()?.let { return it }
            // 全部探完且没人成功 ⇒ 立刻收场，不必干等到 deadline
            if (left.get() <= 0) return null
            Thread.sleep(POLL_MS)
        }
        return win.get()
    }

    // ------------------------------------------------------------------ 会话状态

    /** 记下来的那一条（含"全败"）；过期当作没有 */
    private fun rec(s: SiteConfig): Pick? {
        val p = picks[s.key] ?: return null
        val ttl = if (p.ok) TTL_OK_MS else TTL_FAIL_MS
        if (System.currentTimeMillis() - p.at > ttl) {
            picks.remove(s.key, p)
            return null
        }
        return p
    }

    /**
     * 本会话已挑中的**活地址**；没挑过 / 刚判定全败 ⇒ null。
     *
     * 同步、零网络 —— [AdapterFactory] 就是靠它把"当前该用的地址"套到每个适配器上的
     * （那个工厂必须保持同步，见它的文件头）。
     */
    fun cached(s: SiteConfig): String? = rec(s)?.takeIf { it.ok }?.url

    /**
     * 同步版：换成"当前该用的地址"。没挑过 / 全败 ⇒ **原样返回**（就是 `baseUrl`）。
     *
     * 注意**只换 `baseUrl`，不动 `key`**：`key` 是这个站在库里的身份（缓存、配方、左栏
     * 全靠它对齐），跟着地址变的话，同一趟搜索里前后两次创建适配器会拿到两个身份。
     */
    fun withCached(s: SiteConfig): SiteConfig =
        cached(s)?.let { if (it == s.baseUrl) s else s.copy(baseUrl = it) } ?: s

    /** 请求失败后作废这次的选中（自愈）：下一次会重新赛马 */
    fun invalidate(s: SiteConfig) {
        picks.remove(s.key)
    }

    /** 忘记所有会话结论（站点被删 / 被改过地址时用） */
    fun forget(key: String) {
        picks.remove(key)
    }

    // ------------------------------------------------------------------ 给调用方用的入口

    /**
     * 当前该用的地址。
     *
     * 代价分三档，**绝大多数调用落在前两档（零网络）**：
     *  1. 只有 `baseUrl` 一个候选（没设备用地址）⇒ **原样返回，零请求**；
     *  2. 本会话已经挑过 ⇒ 用缓存，**零请求**；
     *  3. 否则并发赛马一次（≤ 5.5 秒封顶），结果缓存 30 分钟。
     */
    suspend fun live(s: SiteConfig): String {
        val cands = candidates(s)
        if (cands.size <= 1) return cands.firstOrNull() ?: s.baseUrl
        rec(s)?.let { return if (it.ok) it.url else s.baseUrl }
        val mu = locks.computeIfAbsent(s.key) { Mutex() }
        return withContext(Dispatchers.IO) {
            mu.withLock {
                rec(s)?.let { return@withLock if (it.ok) it.url else s.baseUrl }
                val win = race(cands, HTTP_PROBER)
                picks[s.key] = Pick(win ?: s.baseUrl, System.currentTimeMillis(), win != null)
                win ?: s.baseUrl
            }
        }
    }

    /** 最常用的一档：拿到一个 `baseUrl` 已经换成活地址的副本（其余字段一字不动） */
    suspend fun of(s: SiteConfig): SiteConfig {
        val u = live(s)
        return if (u == s.baseUrl) s else s.copy(baseUrl = u)
    }

    /**
     * 后台预热一批站点：让"第一次真请求"直接打在活地址上，而不是先失败一次再换。
     *
     * 对**没有备用地址的站点直接跳过**（见文件头第 1 条）—— 所以对现有用户，
     * 这个调用是彻底的零开销，一个包都不会多发。
     *
     * 故意不给 [CoroutineScope] 参数：这是"顺手做的一件事"，与调用它的页面谁先死无关，
     * 写的是本对象自己的进程级缓存（同 [com.videoshell.player.HlsPrefetchPool] 的取舍）。
     */
    fun warmUp(sites: List<SiteConfig>) {
        for (s in sites) {
            if (candidates(s).size <= 1) continue
            if (rec(s) != null) continue
            scope.launch { runCatching { live(s) } }
        }
    }
}
