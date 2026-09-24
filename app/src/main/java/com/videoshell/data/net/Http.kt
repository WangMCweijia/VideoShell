package com.videoshell.data.net

import com.videoshell.util.decodeBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object Http {

    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 单个 URL 最多尝试 3 次（首次 + 2 次重试）。
     *
     * 为什么必须重试：手机网络下"每个新域名的第一次请求"很容易抖一下
     * （DNS 慢、首次握手超时、运营商链路抖动）。旧实现一次失败就返回 null，
     * 用户看到的结果是「分类栏整条消失」「播放打不开」—— 而同一个地址再点一次就好了。
     * 这种事不该让用户去点第二次。
     */
    private const val MAX_ATTEMPTS = 3

    /** 第 n 次尝试前的等待（第 0 次不等待） */
    private val RETRY_DELAY_MS = longArrayOf(0L, 400L, 1200L)

    /**
     * 内存 CookieJar。
     *
     * 必要：雷池（SafeLine）这类 WAF / 防盗链会在响应里下发 cookie，
     * 后续请求必须带上才不会被拦；旧实现没有 CookieJar（OkHttp 默认 NO_COOKIES），
     * 首屏之后的所有请求都是"裸奔"，容易被判定为异常流量。
     *
     * ## ★ 不能按「请求方 host」分桶（v1.0.53 的真根因）
     *
     * 旧实现是 `store[url.host]` 存、`store[url.host]` 取 —— 于是
     * **`Domain=.example.com` 的跨子域 cookie 永远送不出去**：
     * 响应来自 `api.example.com`，cookie 被记在 `api.example.com` 这一桶下，
     * 而真正要用它的 `video.example.com` 查自己的桶、什么也查不到。
     *
     * 实测（红果黄剧 huangju.net）：`/play/{id}` 在 `api.huangju.net` 下发
     * `CloudFront-Policy/Signature/Key-Pair-Id`（`Domain=.huangju.net`），
     * m3u8 与分片在 `video.huangju.net` 上做 CloudFront 签名校验 ——
     * 桶对不上 ⇒ 每个分片都 **403 `MissingKey`**，而自检、接口、详情全部正常。
     *
     * 正确做法：**不分桶**，一律交给 [Cookie.matches]（它按 `Domain` / `Path` /
     * `hostOnly` 自己判该不该发给这个 URL），顺带清掉已过期的，避免内存里越攒越多。
     * 键取 `name|domain|path` 三元组：同名 cookie 挂在不同域下要各占一格。
     */
    private val cookieJar = object : CookieJar {
        private val store = LinkedHashMap<String, Cookie>()

        private fun key(c: Cookie) = c.name + "|" + c.domain + "|" + c.path

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val now = System.currentTimeMillis()
            for (c in cookies) {
                if (c.expiresAt <= now) store.remove(key(c)) else store[key(c)] = c
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return store.values.filter { it.expiresAt > now && it.matches(url) }
        }
    }

    /**
     * # 显式 Cookie 优先（v1.0.66）—— 修「扫码登录成功、过一会儿就提示登录已过期」
     *
     * ## 症状与机理
     *
     * 网盘的登录态是 `DriveStore` 里的一整份 cookie（`__pus` / `__puus` / `__uid` …），
     * 由调用方以**显式 `Cookie` 头**挂上去（[com.videoshell.data.pan.PanCloudDrive]）——
     * 它属于「与站点解耦的另一套凭据」，刻意不进下面这个 CookieJar。
     *
     * 但 OkHttp 的 `BridgeInterceptor` 是**无条件**用 CookieJar 覆盖 `Cookie` 头的：
     *
     * ```java
     * List<Cookie> cookies = cookieJar.loadForRequest(userRequest.url());
     * if (!cookies.isEmpty()) requestBuilder.header("Cookie", cookieHeader(cookies));
     * ```
     *
     * 于是链条是：**第 1 次请求** jar 还空着 ⇒ 手写头生效 ⇒ 登录校验通过（用户看到"登录成功"）；
     * 而那次响应里服务端**必定**会 `Set-Cookie`（刷新 `__puus`、或只是下个埋点 cookie）
     * ⇒ jar 对该 host 非空 ⇒ **从第 2 次请求起，整份登录态被 jar 里那点 cookie 替换掉**
     * ⇒ 服务端回 `31001 require login` ⇒ 账号页显示「登录已过期」。
     *
     * 「服务端不会提前作废旧 cookie」这一点是**已排除过**的：PC 侧 spike 用同一份 cookie
     * 连打十几个接口（token→detail→save→task→play→delete）全部成功（见 PITFALLS §4.60）。
     * 所以"过期"不是服务端翻脸，是我们的头被换掉了。
     *
     * ## 为什么不用「换个没有 jar 的 client」绕过
     *
     * CookieJar 是站点解析与播放防盗链**必需**的（红果那种跨子域签名 cookie，见上面
     * `cookieJar` 的说明），而播放走的 [mediaClient] 是 `client.newBuilder()` 派生的同一套配置。
     * 单独给网盘拆一个 client，等于把「跨子域 cookie」这条能力在别的路径上悄悄关掉 ——
     * 又是"改一处漏一处"。
     *
     * ## 做法：tag 暂存 + network 位写回
     *
     * 请求若自带显式 `Cookie`，就把它另存进 `Request.tag`（[ExplicitCookie]）；
     * 在 **network 拦截器位**（`BridgeInterceptor` **之后**、真正发出之前）写回去 ——
     * 那里是最后写入者，必然生效。
     *
     * 用 tag 而不是"再塞一个自定义头"：后者会把这份凭据**多发给服务端一遍**，
     * 有的站会直接因此 400。
     *
     * 离线判定见 `tools/verify/OkHttpCookieJarTest.java`（本机起 HTTP 服务、不联网）：
     * 现状第 2 次服务器只收到 `srvmark=1`，修后每次都收到完整的登录态（含 `__puus`）；
     * 另有一组对照证明「不带显式 Cookie 时 jar 照常工作」（站点解析不受影响）。
     */
    private class ExplicitCookie(val value: String)

    /** application 位：把手写的 Cookie 收进 tag（必须早于 BridgeInterceptor） */
    private val stashExplicitCookie = Interceptor { chain ->
        val req = chain.request()
        val mine = req.header("Cookie")
        if (mine.isNullOrBlank()) {
            chain.proceed(req)
        } else {
            chain.proceed(
                req.newBuilder().tag(ExplicitCookie::class.java, ExplicitCookie(mine)).build()
            )
        }
    }

    /** network 位：把它写回 `Cookie` 头（晚于 BridgeInterceptor ⇒ 覆盖它） */
    private val restoreExplicitCookie = Interceptor { chain ->
        val req = chain.request()
        val mine = req.tag(ExplicitCookie::class.java)
        if (mine == null) chain.proceed(req)
        else chain.proceed(req.newBuilder().header("Cookie", mine.value).build())
    }

    /**
     * jar 里当前该 URL 应带的全部 cookie（name → value；同名取 `expiresAt` 最晚的）。
     *
     * 为什么必须有它（v1.0.69，修「网盘取流成功、分片直链 403」）：
     * 夸克的 `__puus` 是**滚动凭据** —— 每次 API 响应都会 `Set-Cookie` 下发新值
     * （[ExplicitCookie] 的注释里记着这个事实）。于是落盘快照里的 `__puus` 在登录后
     * **只新鲜一阵**；而网盘媒体域（`video-play-*.drive.quark.cn`）校验的就是它 ——
     * 快照旧了 ⇒ 每个分片 403。取流（token/save/v2/play）用的是同一份快照却仍成功，
     * 是 API 域对旧值宽容 —— **别把这个差异当成"凭据没问题"的证据**（E50）。
     *
     * jar 恰好握着最新值：这些 `Set-Cookie` 的 `Domain=.quark.cn`（宽域），
     * [cookieJar] 不分桶、按 [Cookie.matches] 派发 ⇒ jar 一直跟服务端同步。
     * 同名多版本时取 `expiresAt` 最晚的：滚动刷新会不断把过期时间往后推，
     * 最晚的那个就是最新下发的。
     */
    fun cookieValuesFor(url: String): Map<String, String> {
        val u = runCatching { url.toHttpUrl() }.getOrNull() ?: return emptyMap()
        val list = cookieJar.loadForRequest(u)
        val out = LinkedHashMap<String, String>()
        for (c in list.sortedBy { it.expiresAt }) out[c.name] = c.value
        return out
    }

    /**
     * 优先返回 IPv4 地址。
     *
     * 有些网络里 IPv6 地址**能解析但连不通**（黑洞）。OkHttp 默认按系统给的顺序试，
     * 会先卡在 IPv6 上直到 connect 超时 —— 表现就是"每个新域名的第一次请求都失败或极慢，
     * 第二次才正常"。把 IPv4 排到前面，一次就连上。
     * （纯 IPv6 网络下 v4 为空，原样返回，不受影响。）
     */
    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
            if (all.size < 2) return all
            val v4 = all.filterIsInstance<Inet4Address>()
            if (v4.isEmpty() || v4.size == all.size) return all
            return v4 + all.filterNot { it is Inet4Address }
        }
    }

    /**
     * UA 兜底拦截器：请求没显式带 User-Agent 就补成浏览器 UA。
     *
     * 必要：站点解析的各请求都手动带了 UA，但走这个 client 的**其它路径**
     * （如 Coil 封面请求，App.newImageLoader 用 callFactory 挂上来）不带 ——
     * OkHttp 默认 UA（okhttp/4.x）会被部分图床 CDN 直接拒绝，表现为
     * "解析正常、封面全 403"。在这里统一兜底，所有路径行为一致。
     */
    private val uaFallback = okhttp3.Interceptor { chain ->
        val req = chain.request()
        chain.proceed(
            if (req.header("User-Agent").isNullOrBlank()) {
                req.newBuilder().header("User-Agent", UA).build()
            } else req
        )
    }

    /**
     * DoH（DNS over HTTPS，阿里公共 DNS 223.5.5.5 / 223.6.6.6，RFC 8484）。
     *
     * 必要：图床/CDN 域名是"系统 DNS 污染"的重灾区 —— 站点主页能开、图片全挂
     * 就是典型症状（野果封面案例）。系统解析失败或返回空时回落到 DoH。
     * 选阿里而不选 Cloudflare/Google：境外 DoH 本身就常被墙，兜底会变成死路。
     */
    val dohDns: okhttp3.dnsoverhttps.DnsOverHttps by lazy {
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        okhttp3.dnsoverhttps.DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://223.5.5.5/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("223.5.5.5"),
                InetAddress.getByName("223.6.6.6")
            )
            .build()
    }

    /** DNS 判定的短期结论：可连通缓存久、连不通缓存短（好让网络恢复后自己好起来） */
    private data class DnsVerdict(val usable: Boolean, val at: Long)

    private val dnsVerdict = java.util.concurrent.ConcurrentHashMap<String, DnsVerdict>()

    /**
     * 系统 DNS 给的答案**连得上吗**（v1.0.30）。
     *
     * 为什么必须真连一次：**域名污染的特征是「有答案但连不上」**，不是「没有答案」。
     * 旧逻辑只在 `lookup` 抛异常或返回空时才回落 DoH —— 污染场景下系统 DNS 会爽快地
     * 返回一个错 IP，于是我们一路连到错地址，表现为「**站点主页能开、图床/CDN 整片打不开**」
     * （野果封面就是这个症状；本机 DNS 正常所以复现不出来，只能在设备侧自愈）。
     *
     * 成本控制（常态零开销）：
     * - 只探**第一个**地址、**400 ms** 超时（比一次正常连接的等待还短）；
     * - 结论按 `主机|答案` 缓存：可用 10 分钟、不可用 1 分钟（后者是为了能自愈）。
     *
     * 误判的代价也很小：探测失败只会让我们改用 DoH 再解析一次 ——
     * 若 DoH 给出的还是同一批地址，行为与原来完全一致。
     */
    private fun sysDnsUsable(hostname: String, ips: List<InetAddress>): Boolean {
        val key = hostname.lowercase() + "|" + ips.joinToString(",") { it.hostAddress ?: "" }
        val now = System.currentTimeMillis()
        dnsVerdict[key]?.let { v ->
            val ttl = if (v.usable) 10 * 60_000L else 60_000L
            if (now - v.at < ttl) return v.usable
        }
        val ok = try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(ips.first(), 443), 400)
                true
            }
        } catch (e: Exception) {
            false
        }
        dnsVerdict[key] = DnsVerdict(ok, now)
        return ok
    }

    /**
     * DNS 结果缓存（v1.0.31）。
     *
     * 为什么非加不可：设备侧系统 DNS 对图床域名**一条记录都没有**时（野果封面实测就是这个
     * 状态：系统无记录、DoH 有），每取一张图都要走一次 DoH 的 HTTPS 往返 —— 一个 30 张封面
     * 的网格就是 30 次，慢到 Coil 直接超时 ⇒ 表现为「自检单张能取到 206、界面却一片空白」。
     * 命中缓存后常态零额外解析，只是把"每次都问一遍"变成"5 分钟问一遍"。
     */
    private data class DnsEntry(val ips: List<InetAddress>, val at: Long)

    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, DnsEntry>()

    /** 5 分钟：够撑完一次刷页；真改了 IP 也能在几分钟内自己恢复 */
    private val DNS_TTL_MS = 5 * 60_000L

    /**
     * 韧性 DNS：系统优先（IPv4 前置），但**答案连不上/根本没有**时回落阿里 DoH（v1.0.30 起）。
     *
     * 封面请求、站点解析、播放器分片、解析服务接口共用这一套；
     * 任何一处因为「答案错」而失败，都会在这里被纠正后重连。
     */
    private val resilientDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val key = hostname.lowercase()
            dnsCache[key]?.let { e ->
                if (e.ips.isNotEmpty() && System.currentTimeMillis() - e.at < DNS_TTL_MS) {
                    return e.ips
                }
            }
            val sys = try {
                ipv4FirstDns.lookup(hostname)
            } catch (e: Exception) {
                emptyList()
            }
            val ips = if (sys.isNotEmpty() && sysDnsUsable(hostname, sys)) {
                sys
            } else {
                // 系统没答案，或有答案但连不上 —— 都去问 DoH；DoH 也没有就还是用系统的
                val doh = runCatching { dohDns.lookup(hostname) }.getOrElse { emptyList() }
                if (doh.isNotEmpty()) doh else sys
            }
            if (ips.isNotEmpty()) dnsCache[key] = DnsEntry(ips, System.currentTimeMillis())
            return ips
        }
    }

    /** 自检用：给出某域名在系统 DNS 与 DoH 下各自的解析结果，供报告对照 */
    fun dnsReport(hostname: String): String {
        fun fmt(list: List<InetAddress>) =
            list.joinToString(" ") { it.hostAddress ?: "?" }.ifBlank { "（无记录）" }
        val sys = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        val doh = runCatching { dohDns.lookup(hostname) }.getOrElse { listOf<InetAddress>() }
        // 关键：把「有答案但连不通」也点名 —— 这才是污染最典型、也最容易被漏掉的一态
        val verdict = when {
            sys.isEmpty() -> "系统无记录"
            sysDnsUsable(hostname, sys) -> "系统答案可连通"
            else -> "**系统答案连不上（疑似污染）⇒ 本次已自动改走 DoH**"
        }
        return "系统DNS: ${fmt(sys)}［$verdict］｜DoH: ${fmt(doh)}"
    }

    /**
     * 超时收短到「快速失败」区间：12s→8s。
     * 反正失败后会重试，与其在一个连不通的地址上等 12 秒，不如早点换下一次。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(resilientDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .cookieJar(cookieJar)
            // 显式 Cookie 优先：网盘那份整份凭据不能被 jar 里"服务端顺手下的"覆盖
            // （症状＝登录当次成功、之后永久 401，见 [stashExplicitCookie] 的说明）
            .addInterceptor(stashExplicitCookie)
            .addNetworkInterceptor(restoreExplicitCookie)
            .addInterceptor(uaFallback)
            // 加密图床（野果那类）：图片本身是 AES 密文，必须在这里解一层，
            // 否则 Coil 拿到的是「200 + 一坨非图片字节」，界面永远没有封面。
            // 三道门在 ImageCipher 里，非白名单图床零开销。
            .addInterceptor(ImageCipher.interceptor)
            .build()
    }

    /**
     * 播放器专用客户端。
     *
     * 与 [client] 共享 DNS / CookieJar / 连接池，但两处必须改：
     *  - **`callTimeout` 关掉（0 = 不限）**：它管的是"整个请求从发出到读完 body"的总时长，
     *    对下载一整个分片、甚至一路播下去的场景，22 秒会把正常的慢速下载直接掐死。
     *  - `readTimeout` 放到 20s：这是**单次读**的间隔上限，链路抖一下不至于立刻判死。
     *
     * 播放器走这一套（而不是 ExoPlayer 自带的 DefaultHttpDataSource）是有意为之：
     * 自检/解析/嗅探都用 OkHttp，只有播放器用 HttpURLConnection 的话，
     * 「自检全绿、播放打不开」这类问题永远查不清 —— 详见 OkHttpDataSource 的注释。
     *
     * ## 为什么必须自己换一个 Dispatcher（v1.0.65）
     *
     * OkHttp 的 `Dispatcher` **默认 `maxRequestsPerHost = 5`** —— 这是"同一个域名同时最多几个请求"。
     * 播放分片全在同一个 CDN 域上，而 v1.0.65 加了**并发预取**（[com.videoshell.player.HlsPrefetch]，
     * 默认 4 条在飞）。4 条预取 + 播放器自己的分片/清单请求正好顶到 5 ⇒ 播放器的请求会被
     * **排进 Dispatcher 队列等预取腾位置**：画面上就是"明明带宽够、却一顿一顿的"，
     * 而且从日志上看一切正常（没有错误、没有超时，只是慢）。
     *
     * 所以这里给播放器**单独一个 Dispatcher**（不动 [client] 的，普通请求没必要放宽），
     * 并把每 host 上限提到 8 = 4 条预取 + 4 条留给播放器自身（清单/分片/重试）的余量。
     */
    val mediaClient: OkHttpClient by lazy {
        client.newBuilder()
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 8
                }
            )
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 探测用短超时客户端：识别站点时并发打多个接口，不能让一个坏接口拖死整轮 */
    val fastClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(resilientDns)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .cookieJar(cookieJar)
            .addInterceptor(stashExplicitCookie)
            .addNetworkInterceptor(restoreExplicitCookie)
            .build()
    }

    /**
     * 探活专用：比 [fastClient] 再短一档。
     *
     * 探活是"顺手做的一件事"（在几个备用地址里挑最快的），**绝不能反过来拖住用户**。
     * 所以整体封顶 4 秒：连不上就认输，让别的候选去赢。
     */
    private val probeClient: OkHttpClient by lazy {
        fastClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 轻量探活（v1.0.67）：这个地址现在**通不通、有多快**。
     *
     * 返回耗时**毫秒**；失败返回 **-1**。
     *
     * ## 判据只有两条
     *
     * **HTTP 2xx**（重定向已跟随）+ **正文非空**（只 peek 前 1KB —— 首页可能几百 KB，
     * 探活不该把整页读下来，`peekBody` 恰好只把这一小段拉进内存）。
     *
     * 刻意**不做更严的判据**（比如"正文像不像一个影视站"）：探活的用途是在
     * **用户自己给的**几个地址里挑最快的一个，不是给外部输入做资格审查。
     * 判太严会把一个真活着、只是先说"正在检查浏览器"的站**判死**，
     * 那比"挑到慢的那个"糟得多 —— 慢还能用，判死等于把可选地址删了。
     *
     * ## 为什么不给 suspend 版本
     *
     * 它要在"并发赛马"里被**多个线程同时拿住**（见 [com.videoshell.data.site.MirrorRace]），
     * suspend 化在这里只多一层 Continuation。所以是**阻塞调用，须在 IO 线程上跑**。
     *
     * 刻意**不写 NetLog**：探活是后台噪声（一次搜索可能十几个），
     * 把性能日志冲掉之后，"刚才那次卡顿"就再也查不出来了。
     */
    fun probeMs(url: String, referer: String? = null): Long {
        val t0 = System.currentTimeMillis()
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
        if (!referer.isNullOrBlank()) b.header("Referer", referer)
        return try {
            probeClient.newCall(b.build()).execute().use { r ->
                val head = r.peekBody(1024).bytes()
                if (r.isSuccessful && head.isNotEmpty()) System.currentTimeMillis() - t0 else -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    suspend fun get(
        url: String,
        referer: String? = null,
        ua: String = UA,
        headers: Map<String, String> = emptyMap(),
        fast: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay(RETRY_DELAY_MS[attempt])
            try {
                return@withContext once(url, referer, ua, headers, fast)
            } catch (e: Exception) {
                last = e
                if (!worthRetry(e)) break
            }
        }
        throw last ?: IOException("请求失败：$url")
    }

    /** 单次尝试（不含重试）；`fast = true` 用短超时探测客户端 */
    private fun once(
        url: String,
        referer: String?,
        ua: String,
        headers: Map<String, String>,
        fast: Boolean
    ): String {
        val b = Request.Builder().url(url)
            .header("User-Agent", ua)
            // ⚠️ 这里**绝不能**写 `application/json`（2026-09-18 实测踩过）：
            // 部分站点的 nginx 按 Accept 做内容协商/反爬，一看到 application/json 就把
            // **整页 HTML 转义成 JSON 字符串**返回（`"<!DOCTYPE html>…"`、中文变 `\uXXXX`）。
            // 字节数看着正常，但站型判据一个中文词都匹配不到 ⇒ 整站被判「不是视频站」。
            // 对齐真实 Chrome 的文档 Accept，尾部 `*/*` 已足够让 JSON 接口照常返回 JSON。
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (!referer.isNullOrBlank()) b.header("Referer", referer)
        for ((k, v) in headers) b.header(k, v)
        val c = if (fast) fastClient else client
        val t0 = System.currentTimeMillis()
        try {
            c.newCall(b.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                NetLog.record(url, resp.code, System.currentTimeMillis() - t0)
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} @ $url")
                return decodeBody(bytes, resp.body?.contentType()?.charset()?.name())
            }
        } catch (e: Exception) {
            if (e !is IOException || !e.message.orEmpty().startsWith("HTTP ")) {
                NetLog.record(url, -1, System.currentTimeMillis() - t0, e.javaClass.simpleName + ": " + e.message)
            }
            throw e
        }
    }

    /**
     * **只试一次**的 GET（不重试，v1.0.56）。
     *
     * 给"一层里有 N 个候选、每个候选都值得快速一试"的路径用（更新镜像清单就是这种）：
     * [get] 的 3 次重试在那种场景下是**毒药** —— 最坏 8 个镜像 × 3 次 × 超时 = 两分钟，
     * 用户早就认定"点了没反应"。调用方自己决定要不要、以及怎么重试。
     */
    suspend fun getOnce(
        url: String,
        referer: String? = null,
        ua: String = UA,
        headers: Map<String, String> = emptyMap(),
        fast: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        once(url, referer, ua, headers, fast)
    }

    /**
     * 值不值得重试。
     * 网络类错误（超时、连接被重置、DNS 抖动）重试往往立刻就好；
     * 而 4xx 是内容层面的问题（404/403），重试只是浪费时间。
     */
    private fun worthRetry(e: Exception): Boolean {
        if (e !is IOException) return false
        val m = e.message.orEmpty()
        if (m.startsWith("HTTP ")) {
            val code = m.removePrefix("HTTP ").takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            return code == 429 || code >= 500
        }
        return true
    }

    suspend fun getOrNull(
        url: String,
        referer: String? = null,
        ua: String = UA,
        fast: Boolean = false
    ): String? = try {
        get(url, referer, ua, emptyMap(), fast)
    } catch (e: Exception) {
        null
    }

    /**
     * 表单 POST（`application/x-www-form-urlencoded`）。
     *
     * v1.0.25 新增：加密接口站（见 [com.videoshell.data.site.CryptRecipes]）一律要求
     * **POST + 表单体**，GET 过去只会收到「关键词无效」—— 这是实测结论，不是猜测。
     * 与 [get] 共用重试、CookieJar、NetLog 与韧性 DNS，行为保持一致。
     */
    suspend fun postForm(
        url: String,
        params: Map<String, String>,
        referer: String? = null,
        ua: String = UA,
        fast: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay(RETRY_DELAY_MS[attempt])
            try {
                return@withContext oncePost(url, params, referer, ua, fast)
            } catch (e: Exception) {
                last = e
                if (!worthRetry(e)) break
            }
        }
        throw last ?: IOException("请求失败：$url")
    }

    suspend fun postFormOrNull(
        url: String,
        params: Map<String, String>,
        referer: String? = null,
        ua: String = UA,
        fast: Boolean = false
    ): String? = try {
        postForm(url, params, referer, ua, fast)
    } catch (e: Exception) {
        null
    }

    /**
     * POST 一段 JSON（v1.0.31）。
     *
     * 播放器外壳有一类是「引导对象 + JSON 接口」：页面把 `url` / `t` / `key` 这些令牌**明文**
     * 摆进 `window.__XXX__={...}`，再用混淆 JS `POST /api/parse` 换真地址 —— 骚火的 hhplayer
     * 就是这种。**这类接口只认 JSON 体，用表单体过去会被拒**，所以不能复用 [postForm]。
     * 与 [postForm] 同享重试、CookieJar、NetLog 与韧性 DNS。
     *
     * [headers]（v1.0.65）给网盘类接口用：它们的登录态**不在**本进程的 CookieJar 里
     * （凭据是用户在「网盘账号」页登录后由 [com.videoshell.data.pan.DriveStore] 保管的），
     * 必须逐次显式带上 `Cookie`。放在这里而不是让调用方自己 newCall，是为了
     * **重试 / NetLog / 韧性 DNS 只有一份实现** —— 网盘请求同样会遇到 DNS 污染与瞬时失败。
     */
    suspend fun postJson(
        url: String,
        json: String,
        referer: String? = null,
        ua: String = UA,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay(RETRY_DELAY_MS[attempt])
            try {
                return@withContext oncePostJson(url, json, referer, ua, headers)
            } catch (e: Exception) {
                last = e
                if (!worthRetry(e)) break
            }
        }
        throw last ?: IOException("请求失败：$url")
    }

    suspend fun postJsonOrNull(
        url: String,
        json: String,
        referer: String? = null,
        ua: String = UA,
        headers: Map<String, String> = emptyMap()
    ): String? = try {
        postJson(url, json, referer, ua, headers)
    } catch (e: Exception) {
        null
    }

    /**
     * 单次 JSON POST：**不重试**、不抛异常，返回 `(HTTP 状态码, 响应体)`；网络失败返回 `(-1, "")`（v1.0.65）。
     *
     * 只给"尽力而为的收尾动作"用（云盘取流后清理转存产物）。与 [postJsonOrNull] 的区别：
     *  - **不做 5xx 退避重试**：那层重试（`0/400/1200ms` + 3 次请求 ≈ 2.4s）是为"这次必须成"的
     *    的请求准备的；而收尾动作接在**用户的等待路径**上（取流成功后、返回播放地址之前），
     *    让它白自旋 2.4 秒去撞一个**必定失败**的请求，纯粹是拖慢播放启动。
     *  - **把状态码交出来**：调用方要能区分"成了 / 4xx 别再试 / 5xx 稍后再试"这三种结局。
     *
     * ⚠️ 非 2xx 时 `oncePostJson` 抛的是 `IOException("HTTP <code> @ <url>")`，**正文拿不到**
     * ⇒ 第二个返回值是 `""`。所以这个 API 只适合"按状态码分派"的调用；要读 4xx 的正文
     * （比如 `code:23004` 那种），得走别的路子。清理逻辑不需要它 —— 4xx 一律"别再试"。
     *
     * 仍然记 `NetLog`（复用 [oncePostJson]），诊断信息不丢。
     */
    suspend fun postJsonOnceRaw(
        url: String,
        json: String,
        referer: String? = null,
        ua: String = UA,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            200 to oncePostJson(url, json, referer, ua, headers)
        } catch (e: Exception) {
            val m = e.message.orEmpty()
            val code = if (m.startsWith("HTTP ")) {
                m.removePrefix("HTTP ").takeWhile { it.isDigit() }.toIntOrNull() ?: -1
            } else {
                -1
            }
            code to ""
        }
    }

    /** 单次 JSON POST（不含重试） */
    private fun oncePostJson(
        url: String,
        json: String,
        referer: String?,
        ua: String,
        headers: Map<String, String> = emptyMap()
    ): String {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val b = Request.Builder().url(url)
            .post(body)
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (!referer.isNullOrBlank()) {
            b.header("Referer", referer)
            // 同表单 POST：有 Referer 就补 Origin，免得 WAF 403
            runCatching { b.header("Origin", originOf(referer)) }
        }
        for ((k, v) in headers) runCatching { b.header(k, v) }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(b.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                NetLog.record(url, resp.code, System.currentTimeMillis() - t0)
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} @ $url")
                return decodeBody(bytes, resp.body?.contentType()?.charset()?.name())
            }
        } catch (e: Exception) {
            if (e !is IOException || !e.message.orEmpty().startsWith("HTTP ")) {
                NetLog.record(url, -1, System.currentTimeMillis() - t0,
                    e.javaClass.simpleName + ": " + e.message)
            }
            throw e
        }
    }

    /** 单次表单 POST（不含重试） */
    private fun oncePost(
        url: String,
        params: Map<String, String>,
        referer: String?,
        ua: String,
        fast: Boolean
    ): String {
        val form = okhttp3.FormBody.Builder()
        for ((k, v) in params) form.add(k, v)
        val b = Request.Builder().url(url)
            .post(form.build())
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (!referer.isNullOrBlank()) {
            b.header("Referer", referer)
            // 表单 POST 带 Origin：部分站点的 WAF 对"有 Referer 没有 Origin"的 XHR 直接 403
            runCatching { b.header("Origin", originOf(referer)) }
        }
        val c = if (fast) fastClient else client
        val t0 = System.currentTimeMillis()
        try {
            c.newCall(b.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                NetLog.record(url, resp.code, System.currentTimeMillis() - t0)
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} @ $url")
                return decodeBody(bytes, resp.body?.contentType()?.charset()?.name())
            }
        } catch (e: Exception) {
            if (e !is IOException || !e.message.orEmpty().startsWith("HTTP ")) {
                NetLog.record(url, -1, System.currentTimeMillis() - t0,
                    e.javaClass.simpleName + ": " + e.message)
            }
            throw e
        }
    }

    /** `https://a.b/c` -> `https://a.b`（Origin 头用） */
    private fun originOf(url: String): String {
        val m = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE).find(url)
        return m?.groupValues?.get(1) ?: url
    }

    /**
     * 自检用：**整段取字节**（不解密、不转字符串）。
     *
     * 为什么需要它：加密图床用 Range 只能拿到**密文的一段**，AES-CBC 脱离块边界
     * 解不出来 —— 自检若还用 `bytes=0-1023`，看到的永远是"206 + 一坨乱码"，
     * 分不清是图床拒绝还是图片本来就是密的（v1.0.32 野果封面就卡在这里）。
     *
     * @return `(状态码, 字节, 失败原因)`；字节为 null 表示没读到
     */
    suspend fun fetchBytes(url: String, referer: String? = null): Triple<Int, ByteArray?, String> =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "image/*,*/*;q=0.8")
            if (!referer.isNullOrBlank()) b.header("Referer", referer)
            val t0 = System.currentTimeMillis()
            try {
                client.newCall(b.build()).execute().use { resp ->
                    val bytes = resp.body?.bytes()
                    NetLog.record(url, resp.code, System.currentTimeMillis() - t0, tag = "取字节 ${bytes?.size ?: 0}B")
                    Triple(resp.code, bytes, "")
                }
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - t0
                val why = e.javaClass.simpleName + ": " + (e.message ?: "")
                NetLog.record(url, -1, ms, why)
                Triple(-1, null, why)
            }
        }

    /**
     * 自检用：**整段取原始字节，绕过图片解密层**（v1.0.33）。
     *
     * [fetchBytes] 走的是 [client]，而 [client] 上挂着 [ImageCipher.interceptor] ——
     * 于是自检拿到手的字节**已经是被解密过的明文**。它因此再也分不清
     * 「图床本来就是明文」和「图床是密文、App 替你解开了」。
     *
     * v1.0.32 的自检报告正是这样自相矛盾的：同一份报告里 HTTP 记录写着
     * 「[加密图已解密 65472->65471B image/jpeg]」，而 [3a] 那节写着
     * 「字节判定：**已是明文图片**（图床改回明文了，解密层会自动跳过）」——
     * 用户据此会以为图床改版了，进而怀疑该不该删掉那对 media_key/media_iv。
     *
     * 要如实**分层**汇报，就必须拿得到响应原样字节：这里重建一个去掉了解密拦截器的
     * client（UA 兜底 / DNS / CookieJar / 其余拦截器全部沿用），拿到的就是 CDN 真正发来的东西。
     * **只在自检里用，不进任何业务链路。**
     */
    private val clientRaw: OkHttpClient by lazy {
        client.newBuilder().apply { interceptors().remove(ImageCipher.interceptor) }.build()
    }

    /**
     * 同 [fetchBytes]，但**不经过解密层**，返回 CDN 原样字节。
     * @return `(状态码, 字节, 失败原因)`；字节为 null 表示没读到
     */
    suspend fun fetchBytesRaw(url: String, referer: String? = null): Triple<Int, ByteArray?, String> =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "image/*,*/*;q=0.8")
            if (!referer.isNullOrBlank()) b.header("Referer", referer)
            val t0 = System.currentTimeMillis()
            try {
                clientRaw.newCall(b.build()).execute().use { resp ->
                    val bytes = resp.body?.bytes()
                    NetLog.record(
                        url, resp.code, System.currentTimeMillis() - t0,
                        tag = "取原样字节(未解密) ${bytes?.size ?: 0}B"
                    )
                    Triple(resp.code, bytes, "")
                }
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - t0
                val why = e.javaClass.simpleName + ": " + (e.message ?: "")
                NetLog.record(url, -1, ms, why)
                Triple(-1, null, why)
            }
        }

    /** 只取状态码（自检用）：不抛异常，任何情况都返回一个可读结果 */
    suspend fun probe(url: String, referer: String? = null, range: String? = null): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "*/*")
            if (!referer.isNullOrBlank()) b.header("Referer", referer)
            if (!range.isNullOrBlank()) b.header("Range", range)
            val t0 = System.currentTimeMillis()
            try {
                client.newCall(b.build()).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val ms = System.currentTimeMillis() - t0
                    NetLog.record(url, resp.code, ms)
                    val ct = resp.header("Content-Type").orEmpty()
                    val head = String(bytes, Charsets.UTF_8).take(120).replace("\n", "\\n")
                    resp.code to ("$ct | $head")
                }
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - t0
                NetLog.record(url, -1, ms, e.javaClass.simpleName + ": " + e.message)
                -1 to (e.javaClass.simpleName + ": " + e.message).orEmpty()
            }
        }

    /**
     * 真下一段数据并计时，返回 `(状态码, 实际字节数, 毫秒)`。
     *
     * 自检里原来的"首个分片"只请求 `bytes=0-1023` —— 1KB 在任何链路上都是秒回，
     * 于是它只能证明"地址存在"，**证明不了"这条链路扛得住播放"**。
     * 一个分片动辄几百 KB，链路若只有三五十 KB/s，播放器就会一直转圈。
     * 这里老老实实下 [maxBytes] 并把速度算出来。
     */
    suspend fun sample(
        url: String,
        referer: String? = null,
        maxBytes: Long = 512 * 1024
    ): Triple<Int, Long, Long> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
        if (!referer.isNullOrBlank()) b.header("Referer", referer)
        val t0 = System.currentTimeMillis()
        try {
            mediaClient.newCall(b.build()).execute().use { resp ->
                var n = 0L
                val buf = ByteArray(32 * 1024)
                resp.body?.byteStream()?.use { src ->
                    while (n < maxBytes) {
                        val want = minOf(buf.size.toLong(), maxBytes - n).toInt()
                        if (want <= 0) break
                        val r = src.read(buf, 0, want)
                        if (r < 0) break
                        n += r
                    }
                }
                val ms = System.currentTimeMillis() - t0
                NetLog.record(url, resp.code, ms)
                Triple(resp.code, n, ms)
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            NetLog.record(url, -1, ms, e.javaClass.simpleName + ": " + e.message)
            Triple(-1, 0L, ms)
        }
    }

    /**
     * 取 m3u8 正文用于**内容判断**（嗅探候选排序用）。
     *
     * 刻意只尝试一次、走短超时的 [fastClient]：这是排序过程中的一个探测动作，
     * 卡住一个候选就会拖慢整轮排序 —— 相对地，"正片还是广告"这个结论
     * 值得一次快速尝试，不值得三次重试。
     * 用 `peekBody` 限制读取量，避免遇到超大直播清单时把内存吃掉。
     */
    suspend fun getPlaylistOnce(url: String, referer: String? = null): String? =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "*/*")
            if (!referer.isNullOrBlank()) b.header("Referer", referer)
            val t0 = System.currentTimeMillis()
            try {
                fastClient.newCall(b.build()).execute().use { resp ->
                    NetLog.record(url, resp.code, System.currentTimeMillis() - t0)
                    if (!resp.isSuccessful) return@use null
                    // peekBody：最多取 1MB，够看几千个分片的清单，也不会被超大清单拖死
                    resp.peekBody(1L shl 20).string()
                }
            } catch (e: Exception) {
                NetLog.record(url, -1, System.currentTimeMillis() - t0,
                    e.javaClass.simpleName + ": " + e.message)
                null
            }
        }
}
