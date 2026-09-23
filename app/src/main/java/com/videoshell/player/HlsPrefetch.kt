package com.videoshell.player

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * 预取用的共享线程池。
 *
 * **进程级固定大小**，所以没有任何人需要负责 shutdown（线程是 daemon，随进程走）；
 * 这样调用方（`PlayerActivity.buildSource`）不必管理生命周期 —— 换个源就 new 一个新的
 * [HlsPrefetch]，旧实例的任务跑完自然被回收。
 */
internal object HlsPrefetchPool {
    /**
     * 并发度 = 4。
     *
     * 参考潇洒 TVBox 本地包的 `quark_thread_limit:32` 是**本地代理把整集下到磁盘**的场景，
     * 那里可以开到几十条。我们是"边播边预热"：4 条足够把单连接的吞吐瓶颈摊开
     * （3~4 条同域并发通常是吞吐拐点），又不至于和播放器抢带宽、抢 mediaClient
     * 的每 host 并发额度（见 `Http.mediaClient` 的 `maxRequestsPerHost = 8`）。
     */
    const val SIZE = 4

    val pool: ExecutorService = Executors.newFixedThreadPool(SIZE) { r ->
        Thread(r, "hls-prefetch").apply { isDaemon = true }
    }
}

/**
 * ## HLS 分片**并发预取**（v1.0.65）—— 治网盘/转码流的"播放一顿一顿"
 *
 * ### 症状与根因
 *
 * 夸克这类网盘给的 `media.m3u8` 是**转码流**，分片在 CDN 上、**单连接限速**。
 * ExoPlayer 拉 HLS 是**一个 Loader 顺序拉**（点播清单也不例外）：分片 A 下完才去下 B。
 * 于是有效吞吐 = 单连接限速，一旦码率高于它，播放器就反复把缓冲耗干 ——
 * 表现是「刚起播很顺，几秒后开始转圈，等一会儿又顺」，而**网络诊断一切正常**。
 *
 * 解开这个结的办法不是"请求头/重试"（那是另外几类问题的解），而是**同时开几条连接**：
 * 多条 TCP 各自限速，合起来就把有效带宽抬上去了。潇洒 TVBox 本地包正是这么干的
 * （`quark_thread_limit`），差别只是它代理整集、我们只预热"接下来要播的那几片"。
 *
 * ### 为什么不做本地代理（TVBox 那种 127.0.0.1 转发的写法）
 *
 * 实测这条路**不需要**：网盘的 m3u8/ts 只认 Cookie、ts 支持 `Range`、且不加密
 * （见 PITFALLS §4.60），也就是说播放器**直连就能播**，本地代理存在的唯一理由是"并发"。
 * 而并发完全可以在 [androidx.media3.datasource.DataSource] 这一层做掉：
 * 清单返回时把"接下来几片"提前拉进内存，播放器来读时就命中、零等待。
 * 代价小得多 —— 不用起 HTTP 服务、不用改 `m3u8` 正文（分片地址一个字符都不用重写）、
 * 不用操心端口/生命周期，也就不会引入"代理挂了整集全黑"这种新故障面。
 *
 * ### 只对**点播**清单生效
 *
 * 直播清单（没有 `#EXT-X-ENDLIST`）的分片窗口一直在变，预取到的内容下一轮就不在清单里了，
 * 拉它纯属浪费带宽。master 清单（有 `#EXT-X-STREAM-INF`）里根本没有分片行。
 * 两种情况都在 [onPlaylist] 里直接放弃。
 *
 * ### 三条自我约束（都不是优化，是"别帮倒忙"）
 *
 * 1. **字节上限**（[maxBytes]）：预取是拿内存换流畅，必须封顶并按最久未用淘汰；
 *    单片就超过上限的**不缓存**（否则会把已有缓存全冲光，命中率反而崩）。
 * 2. **在飞上限**（[threads]）：永远不超过线程池大小，且同一片不重复提交。
 * 3. **连续失败就停**（[FAILS_TO_STOP]）：连续几次拿不到分片，说明对方在限流/边缘有问题，
 *    这时正确的动作是**把带宽让给播放器**（它自己会重试、有退避），而不是继续并发抢。
 *
 * ### 可测性
 *
 * 拉片的动作是**注入**的（[fetch]），且本类只依赖 JDK 与 kotlin-stdlib（不碰 Android、
 * 不碰 media3）—— 所以离线 harness 能用假 fetch 把上面每一条约束都真跑一遍
 * （`tools/verify/HlsPrefetchTest.java`），不是靠读源码文本猜。
 *
 * @param fetch     拉一整片，返回原始字节；失败返回 null（**不要抛**，抛了也当失败处理）
 * @param onEvent   记录一行（生产接 `PlayLog.record`；判定"机制有没有生效"只看这里）
 * @param threads   同时最多几条在飞
 * @param window    从播放位置往后预取几片
 * @param maxBytes  缓存字节上限
 */
class HlsPrefetch(
    private val fetch: (String) -> ByteArray?,
    private val onEvent: (String) -> Unit = {},
    private val threads: Int = HlsPrefetchPool.SIZE,
    private val window: Int = DEFAULT_WINDOW,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {

    companion object {
        /** 往后看 8 片：按 2~6 秒一片，约等于 20~50 秒的提前量，够覆盖起播与一次卡顿 */
        const val DEFAULT_WINDOW = 8

        /** 缓存上限 48MB：4 条 × 若干片 / 单片 1~6MB，足够装下窗口又不至于把低端机压死 */
        const val DEFAULT_MAX_BYTES = 48L * 1024 * 1024

        /** 连续失败几次就停（对应"对方在限流"） */
        const val FAILS_TO_STOP = 3

        /** 少于这么多片不值得预取：ExoPlayer 自己拉也就几秒，预热反而抢它的带宽 */
        const val MIN_SEGMENTS = 6
    }

    private val lock = Any()
    private val cache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private val inFlight = HashSet<String>()
    private var bytes = 0L

    /** 当前清单的分片序列（顺序 = 播放顺序）与它的下标索引 */
    private var order: List<String> = emptyList()
    private var index: Map<String, Int> = emptyMap()
    private var playlistUrl = ""

    /**
     * 播放器最近一次请求到的分片下标。
     *
     * 初值 **-1**（不是 0）：[schedule] 从 `cursor + 1` 开始排 —— 用 0 当起点会**跳过第 0 片**，
     * 而第 0 片恰恰是起播最要紧的那一片（离线 harness 用 `cachedCount()` 钉住了这条）。
     */
    private var cursor = -1

    /**
     * 已经排过队的最大下标：**永不回头重拉**。
     *
     * 这条是离线 harness 抓出来的真缺陷（P7b）：字节上限一到，缓存里最旧的片会被淘汰，
     * 而"窗口"是按 `cursor` 算的 —— 淘汰掉的片立刻又落回窗口里、于是被重新拉一遍，
     * 拉回来又被淘汰 …… 变成**无限拉取**，把带宽烧光、还和播放器抢连接。
     * 内存换流畅是有代价的，但代价不能是死循环。
     */
    private var queuedUpTo = -1
    private var fails = 0
    private var stopped = false

    /** 换清单时自增：旧清单的在飞任务回来时直接作废（否则会把上一个源的分片塞进新缓存） */
    private var generation = 0

    private var hits = 0
    private var pulled = 0

    // ------------------------------------------------------------------ 清单

    /**
     * 清单到手（**已规范化**的正文）时调用。
     *
     * 同一份清单重复调用是幂等的（`HlsFixDataSource` 在换字幕重建媒体源时会再走一遍），
     * 但不适合预取的清单会被静默忽略 —— 忽略是**有意**的，别加日志刷屏。
     */
    fun onPlaylist(url: String, body: String) {
        val segs = segmentsOf(body) ?: return
        if (segs.size < MIN_SEGMENTS) return
        synchronized(lock) {
            if (url == playlistUrl && segs == order) return
            generation++
            cache.clear()
            bytes = 0L
            inFlight.clear()
            order = segs
            index = HashMap<String, Int>(segs.size * 2).also { m ->
                for (i in segs.indices) m[segs[i]] = i
            }
            playlistUrl = url
            cursor = -1
            queuedUpTo = -1
            fails = 0
            stopped = false
        }
        onEvent("⚡ 分片并发预取：本清单 ${segs.size} 片，并发 $threads，缓存上限 ${maxBytes / 1024 / 1024}MB")
        schedule()
    }

    /**
     * 从清单正文里取分片序列；返回 null 表示**这份清单不该预取**。
     *
     * 判据全部是清单自身的形状（与站点、域名无关）：
     *  - 不是 m3u8；
     *  - master 清单（`#EXT-X-STREAM-INF`，行里是子清单不是分片）;
     *  - 直播清单（无 `#EXT-X-ENDLIST`）⇒ 窗口一直在变，预取没有意义。
     */
    private fun segmentsOf(body: String): List<String>? {
        if (!body.contains("#EXTM3U")) return null
        if (body.contains("#EXT-X-STREAM-INF")) return null
        if (!body.contains("#EXT-X-ENDLIST")) return null
        val out = ArrayList<String>()
        for (raw in body.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            out.add(line)
        }
        return if (out.isEmpty()) null else out
    }

    // ------------------------------------------------------------------ 播放侧

    /**
     * 取一片。
     *
     * 只在 `position == 0 && 无 Range` 时由调用方来问（见 [HlsFixDataSource]）——
     * 带 Range 的请求是 seek 造成的局部读，跟"整片"不是一回事，不能拿缓存去糊。
     */
    fun get(url: String): ByteArray? = synchronized(lock) {
        val v = cache[url]
        if (v != null) hits++ else pulled++
        v
    }

    /** 播放器开始拉某一片（未命中缓存）：把播放位置记下来，预取跟在它后面跑 */
    fun onSegment(url: String) {
        val i = index[url] ?: return
        synchronized(lock) { if (i > cursor) cursor = i }
        schedule()
    }

    // ------------------------------------------------------------------ 调度

    /**
     * 从播放位置往后，把窗口内还没缓存的片排进线程池（在飞数受 [threads] 约束）。
     *
     * 窗口的**右边界只由播放位置决定**（`cursor + 1 + window`），左起点再被 [queuedUpTo]
     * 兜一层 —— "排过队的不再排"，这样窗口是**单向往前走**的：seek 到远处会自动重开一段窗口，
     * 而缓存淘汰不会把已经拉过的片又变回"待拉"（见 [queuedUpTo] 的注释）。
     */
    private fun schedule() {
        synchronized(lock) {
            if (stopped || order.isEmpty()) return
            val to = min(order.size, cursor + 1 + window)
            var i = max(cursor + 1, queuedUpTo + 1)
            while (i < to) {
                if (inFlight.size >= threads) break
                val idx = i
                i++
                val u = order[idx]
                queuedUpTo = idx
                if (cache.containsKey(u) || inFlight.contains(u)) continue
                inFlight.add(u)
                val g = generation
                runCatching { HlsPrefetchPool.pool.execute { task(u, g) } }
            }
        }
    }

    private fun task(url: String, gen: Int) {
        val data = runCatching { fetch(url) }.getOrNull()
        val msg = synchronized(lock) {
            if (gen != generation) null          // 换清单了：这次结果作废（不能污染新缓存）
            else {
                inFlight.remove(url)
                if (data != null && data.isNotEmpty()) {
                    putLocked(url, data)
                    fails = 0
                    null
                } else {
                    fails++
                    if (fails >= FAILS_TO_STOP && !stopped) {
                        stopped = true
                        "⏸ 预取已停（连续 $fails 片拿不到，把带宽让给播放）"
                    } else null
                }
            }
        }
        msg?.let(onEvent)
        val cont = synchronized(lock) { !stopped && gen == generation }
        if (cont) schedule()
    }

    /**
     * 记账 + 按最久未用淘汰。
     *
     * 两个细节：`fetch` 是**真字节**（不是分片元信息），所以这里存的就是要交给解码器的内容；
     * 单片超过上限时**不缓存但不报错** —— 直接放弃这一片，已有缓存保持不动。
     */
    private fun putLocked(url: String, data: ByteArray) {
        if (data.size > maxBytes) return
        cache.remove(url)?.let { bytes -= it.size }
        cache[url] = data
        bytes += data.size
        val iter = cache.entries.iterator()
        while (bytes > maxBytes && iter.hasNext()) {
            val eldest = iter.next()
            if (eldest.key == url) continue
            bytes -= eldest.value.size
            iter.remove()
        }
    }

    // ------------------------------------------------------------------ 观测 / 收尾

    /** 一行统计，供播放记录看"预取到底有没有用"（命中 0 说明白开了一池子线程） */
    fun stats(): String = synchronized(lock) {
        "预取：命中 $hits / 网络 $pulled，缓存 ${bytes / 1024}KB（${cache.size} 片）" +
                if (stopped) "，已停" else ""
    }

    /** 本会话不再预取（已缓存的仍然可用） */
    fun stop() {
        synchronized(lock) { stopped = true }
    }

    /** 清空（换源/换集）；在飞任务的结果会被 generation 机制丢弃 */
    fun reset() {
        synchronized(lock) {
            generation++
            cache.clear()
            bytes = 0L
            inFlight.clear()
            order = emptyList()
            index = emptyMap()
            playlistUrl = ""
            cursor = -1
            queuedUpTo = -1
            fails = 0
            stopped = false
        }
    }

    /**
     * 等在飞任务全部结束（**给离线 harness 用**，让断言不必靠 sleep 猜时间）。
     * @return 是否在 [timeoutMs] 内等到了空闲
     */
    fun awaitIdle(timeoutMs: Long = 5000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) { if (inFlight.isEmpty()) return true }
            try {
                Thread.sleep(5)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return synchronized(lock) { inFlight.isEmpty() }
    }

    /** 缓存里有多少片（harness 断言用） */
    fun cachedCount(): Int = synchronized(lock) { cache.size }

    /** 缓存占用字节（harness 断言用） */
    fun cachedBytes(): Long = synchronized(lock) { bytes }

    /** 被播放器问过几次（命中 + 未命中），harness 断言用 */
    fun lookupCount(): Int = synchronized(lock) { pulled + hits }

    /** 是否已停（harness 断言用） */
    fun isStopped(): Boolean = synchronized(lock) { stopped }
}
