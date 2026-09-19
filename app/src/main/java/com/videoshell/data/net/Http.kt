package com.videoshell.data.net

import com.videoshell.util.decodeBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
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
     */
    private val cookieJar = object : CookieJar {
        private val store = HashMap<String, MutableList<Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host].orEmpty().filter { it.matches(url) }
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
     */
    val mediaClient: OkHttpClient by lazy {
        client.newBuilder()
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
            .build()
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

    /** 单次尝试（不含重试） */
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
     */
    suspend fun postJson(
        url: String,
        json: String,
        referer: String? = null,
        ua: String = UA
    ): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay(RETRY_DELAY_MS[attempt])
            try {
                return@withContext oncePostJson(url, json, referer, ua)
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
        ua: String = UA
    ): String? = try {
        postJson(url, json, referer, ua)
    } catch (e: Exception) {
        null
    }

    /** 单次 JSON POST（不含重试） */
    private fun oncePostJson(url: String, json: String, referer: String?, ua: String): String {
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
