package com.videoshell.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.videoshell.data.net.NetLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * 用 OkHttp 实现 Media3 的 [HttpDataSource]，**取代 ExoPlayer 自带的 `DefaultHttpDataSource`**。
 *
 * ## 为什么必须换掉默认那个
 *
 * App 里其它所有网络动作（站点自检、解析、嗅探候选探测）都走 [com.videoshell.data.net.Http]
 * 的 OkHttp；只有播放器走 `DefaultHttpDataSource`，而它底层是 `HttpURLConnection` ——
 * **两套完全不同的网络栈**：不同的 DNS 解析顺序、不同的连接池、不同的超时/重试、
 * 不同的 Cookie 处理。
 *
 * 于是出现了本项目最难查的一类现象：**同一台设备、同一个地址，自检能 200/206，播放器却打不开**。
 * 自检全绿永远无法证明播放能成，因为它们根本不是同一个请求。前面几次"修好了又没好"
 * 都栽在这里。
 *
 * 换成 OkHttp 之后，播放器白捡 Http 里那套已验证过的东西：
 * IPv4 优先解析、内存 CookieJar、`retryOnConnectionFailure`、统一超时；
 * 而且两条路只剩一条，**任何差异都会立刻暴露而不是藏在栈的实现里**。
 *
 * 顺带把播放器真正发出的每个请求写进 [NetLog]（标注 `播放器`）——
 * 以后自检报告里能直接看到播放器这一侧发生了什么，不用再靠用户的截图猜。
 */
class OkHttpDataSource(
    private val client: OkHttpClient,
    private val userAgent: String,
    defaultRequestProperties: Map<String, String>
) : BaseDataSource(/* isNetwork = */ true), HttpDataSource {

    private companion object {
        /** NetLog 里的来源标注 */
        const val TAG = "播放器"
    }

    private val defaultProps = HashMap(defaultRequestProperties)
    private val props = HashMap<String, String>()

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var stream: InputStream? = null
    private var currentUri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var status = 0
    private var remaining = 0L
    private var opened = false

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun open(spec: DataSpec): Long {
        dataSpec = spec
        currentUri = spec.uri
        opened = false
        closeQuietly()
        transferInitializing(spec)

        val builder = Request.Builder().url(spec.uri.toString())
        val merged = HashMap<String, String>()
        merged.putAll(defaultProps)
        merged.putAll(props)
        merged.putAll(spec.httpRequestHeaders)
        if (merged.keys.none { it.equals("User-Agent", true) }) merged["User-Agent"] = userAgent
        for ((k, v) in merged) runCatching { builder.header(k, v) }

        // Range：seek / 定长读取时才发。整段读取（HLS 的 playlist 与分片）不发，
        // 保持"服务端整份返回"的语义 —— 与自检 probe 的取法一致。
        val pos = spec.position
        val len = spec.length
        if (pos != 0L || len != C.LENGTH_UNSET.toLong()) {
            val tail = if (len == C.LENGTH_UNSET.toLong()) "" else (pos + len - 1).toString()
            builder.header("Range", "bytes=$pos-$tail")
        }

        val t0 = System.currentTimeMillis()
        val r: Response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            val ms = System.currentTimeMillis() - t0
            NetLog.record(spec.uri.toString(), -1, ms,
                e.javaClass.simpleName + ": " + e.message, TAG)
            PlayLog.request(spec.uri.toString(), -1, ms)
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, spec, HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }

        response = r
        status = r.code
        headers = r.headers.toMultimap()
        val cost = System.currentTimeMillis() - t0
        NetLog.record(spec.uri.toString(), r.code, cost, null, TAG)
        PlayLog.request(spec.uri.toString(), r.code, cost)

        if (!r.isSuccessful) {
            val body = runCatching { r.peekBody(4096).bytes() }.getOrDefault(ByteArray(0))
            closeQuietly()
            throw HttpDataSource.InvalidResponseCodeException(
                r.code, r.message, null, r.headers.toMultimap(), spec, body
            )
        }

        // 跟随重定向后的真实地址（相对路径分片要靠它解析）
        currentUri = runCatching { Uri.parse(r.request.url.toString()) }.getOrNull() ?: spec.uri

        val body = r.body
        stream = body?.byteStream()
        val contentLength = runCatching { body?.contentLength() ?: -1L }.getOrDefault(-1L)
        remaining = when {
            len != C.LENGTH_UNSET.toLong() -> len
            // 206 时 contentLength 是"本次这一段的长度"，正是还能读多少；200 时是全量
            contentLength >= 0L -> contentLength
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(spec)
        opened = true
        return remaining
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val spec = dataSpec ?: return C.RESULT_END_OF_INPUT
        val src = stream ?: throw HttpDataSource.HttpDataSourceException.createForIOException(
            IOException("响应没有可读的流"),
            spec,
            HttpDataSource.HttpDataSourceException.TYPE_READ
        )
        val want = if (remaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(remaining, length.toLong()).toInt()
        }
        val n = try {
            src.read(buffer, offset, want)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, spec, HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }
        if (n == -1) {
            remaining = 0L
            return C.RESULT_END_OF_INPUT
        }
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun close() {
        try {
            closeQuietly()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun closeQuietly() {
        stream?.let { runCatching { it.close() } }
        stream = null
        response?.let { runCatching { it.close() } }
        response = null
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseCode(): Int = status

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun setRequestProperty(name: String, value: String) {
        props[name] = value
    }

    override fun clearRequestProperty(name: String) {
        props.remove(name)
    }

    override fun clearAllRequestProperties() {
        props.clear()
    }
}

/** 工厂：每次创建新的 [OkHttpDataSource]（播放器会为 playlist 与每个分片各取一个） */
class OkHttpDataSourceFactory(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val defaultRequestProperties: Map<String, String>
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        OkHttpDataSource(client, userAgent, defaultRequestProperties)
}
