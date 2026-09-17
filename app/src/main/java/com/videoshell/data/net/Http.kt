package com.videoshell.data.net

import com.videoshell.util.decodeBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {

    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .cookieJar(cookieJar)
            .build()
    }

    /** 探测用短超时客户端：识别站点时并发打多个接口，不能让一个坏接口拖死整轮 */
    val fastClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
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
                decodeBody(bytes, resp.body?.contentType()?.charset()?.name())
            }
        } catch (e: Exception) {
            if (e !is IOException || !e.message.orEmpty().startsWith("HTTP ")) {
                NetLog.record(url, -1, System.currentTimeMillis() - t0, e.javaClass.simpleName + ": " + e.message)
            }
            throw e
        }
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
}
