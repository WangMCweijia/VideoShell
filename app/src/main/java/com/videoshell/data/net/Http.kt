package com.videoshell.data.net

import com.videoshell.util.decodeBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
     * 超时收短到「快速失败」区间：12s→8s。
     * 反正失败后会重试，与其在一个连不通的地址上等 12 秒，不如早点换下一次。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ipv4FirstDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .cookieJar(cookieJar)
            .addInterceptor(uaFallback)
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
            .dns(ipv4FirstDns)
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
            .header("Accept", "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8")
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
