package com.videoshell.data.net

import com.videoshell.util.decodeBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {

    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
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
        c.newCall(b.build()).execute().use { resp ->
            val body = resp.body ?: throw IOException("空响应")
            val bytes = body.bytes()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            decodeBody(bytes, body.contentType()?.charset()?.name())
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
}
