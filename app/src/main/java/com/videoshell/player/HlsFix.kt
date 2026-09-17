package com.videoshell.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max

/**
 * HLS playlist 规范化。
 *
 * 背景：不少"极速播放"源给的是**扁平 TS playlist**（几千个 1 秒分片），并且混杂了：
 *  - 跨目录的**插播广告分片**（如 `/video/adjump/time/xxx.ts`），靠 `#EXT-X-DISCONTINUITY` 与正片拼接；
 *  - 与 `#EXT-X-TARGETDURATION` 不符的分片时长（声明 2s，实际有 3s）。
 *
 * 浏览器端 hls.js 对这些"能忍则忍"，ExoPlayer 更严格，典型症状就是
 * **网页能播、壳子里播不了**。这里把 playlist 规范化成最标准的形式再喂给 ExoPlayer：
 *
 *  1) 分片路径一律**绝对化**，消除 baseUri 解析歧义；
 *  2) `#EXT-X-TARGETDURATION` 按实际最大分片时长修正；
 *  3) 剔除明确的**广告分片**及随之而来的 `#EXT-X-DISCONTINUITY`，让分片序列保持连续；
 *  4) 补齐 `#EXT-X-ENDLIST`。
 */
object HlsPlaylistFixer {

    /** 广告目录特征：`adjump` 即 ad-jump，是这类源最常见的插播广告目录名 */
    private val AD_PATH = Regex(
        "/(adjump|ad|ads|adv|advert|guanggao|gg|advertise)/",
        RegexOption.IGNORE_CASE
    )

    private val EXTINF = Regex("^#EXTINF:\\s*([\\d.]+)", RegexOption.IGNORE_CASE)

    /** 需要跟着分片走、不能提前输出的标签 */
    private val SEGMENT_TAGS = listOf(
        "#EXTINF", "#EXT-X-DISCONTINUITY", "#EXT-X-KEY", "#EXT-X-MAP",
        "#EXT-X-BYTERANGE", "#EXT-X-PROGRAM-DATE-TIME", "#EXT-X-CUE", "#EXT-X-DATERANGE"
    )

    /**
     * @param text        原始 playlist 全文
     * @param playlistUrl 该 playlist 自身的 URL（用于相对路径绝对化）
     * @return 规范化后的 playlist
     */
    fun fix(text: String, playlistUrl: String): String {
        if (!text.contains("#EXTM3U")) return text
        // master playlist（多码率清单）本身已经是标准形态，不要动它；
        // 需要修的是它下面那些「扁平 TS 媒体清单」。
        if (text.contains("#EXT-X-STREAM-INF")) return text

        val src = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val dir = playlistUrl.substringBeforeLast('/', "")
        val host = runCatching {
            val u = java.net.URI(playlistUrl)
            "${u.scheme}://${u.authority}"
        }.getOrNull().orEmpty()

        val head = StringBuilder()
        val body = StringBuilder()
        val pending = StringBuilder()          // 尚未确定要保留的 segment 标签
        var inMedia = false
        var maxDur = 0.0
        var isVod = false
        var skipNextDiscontinuity = false
        var kept = 0

        fun flushPending() {
            body.append(pending)
            pending.setLength(0)
        }

        for (raw in src) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTM3U", true)) continue      // 头部统一自己写，避免重复

            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-PLAYLIST-TYPE", true) &&
                    line.contains("VOD", true)
                ) {
                    isVod = true
                }
                // 头部标签：媒体段开始前遇到的一律原样保留
                if (line.startsWith("#EXT-X-TARGETDURATION", true)) {
                    // 值自己算，原值丢弃；出现在媒体段内就按分片标签随行处理
                    if (inMedia) pending.append(line).append('\n')
                    continue
                }
                if (line.startsWith("#EXT-X-ENDLIST", true)) {
                    continue
                }
                if (SEGMENT_TAGS.any { line.startsWith(it, true) }) {
                    inMedia = true
                    if (line.startsWith("#EXT-X-DISCONTINUITY", true) && skipNextDiscontinuity) {
                        skipNextDiscontinuity = false
                        continue              // 广告段被剔除后，这条不连续标记也失去意义
                    }
                    pending.append(line).append('\n')
                    continue
                }
                // 其它头部标签
                if (inMedia) pending.append(line).append('\n') else head.append(line).append('\n')
                continue
            }

            // ---------------- 分片行 ----------------
            inMedia = true
            val adSeg = AD_PATH.containsMatchIn(line)
            if (adSeg) {
                pending.setLength(0)          // 连同 EXTINF / DISCONTINUITY 一起丢弃
                skipNextDiscontinuity = true
                continue
            }

            EXTINF.find(pending.toString())?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let {
                maxDur = max(maxDur, it)
            }
            flushPending()
            skipNextDiscontinuity = false
            body.append(absolutize(line, dir, host)).append('\n')
            kept++
        }

        // 兜底：一个分片都没剩下时，保持原样交给播放器（不要产出空 playlist）
        if (kept == 0) return text

        val out = StringBuilder()
        out.append("#EXTM3U\n")
        // TARGETDURATION 必须 >= 实际最大分片时长，否则严格实现会判为非法；
        // 原文件没写也补上（VOD playlist 要求必有）
        val td = max(1, ceil(maxDur).toInt())
        out.append("#EXT-X-TARGETDURATION:").append(td).append('\n')
        out.append(head)
        out.append(body)
        // 只给 VOD 补 ENDLIST：直播流补上会被当成"已结束"直接截断
        if (isVod && !body.contains("#EXT-X-ENDLIST")) out.append("#EXT-X-ENDLIST\n")
        return out.toString()
    }

    private fun absolutize(seg: String, dir: String, host: String): String {
        if (seg.startsWith("http://") || seg.startsWith("https://")) return seg
        if (seg.startsWith("//")) return "https:$seg"
        if (seg.startsWith("/")) return if (host.isNotEmpty()) host + seg else seg
        return if (dir.isNotEmpty()) "$dir/$seg" else seg
    }
}

/**
 * 包装上游 [DataSource]：只拦 **playlist 响应**，读全书 -> [HlsPlaylistFixer.fix] -> 交给播放器；
 * 分片等大流量请求一律原样透传（不缓冲）。
 */
class HlsFixDataSource(private val upstream: DataSource) : DataSource {

    private var buffered: ByteArray? = null
    private var pos = 0
    private var closed = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        buffered = null
        pos = 0
        closed = false
        val len = upstream.open(dataSpec)
        // 只处理完整请求的 playlist
        if (dataSpec.position != 0L) return len
        if (!isPlaylist(dataSpec)) return len

        val bytes = readAll()
        val text = String(bytes, Charsets.UTF_8)
        buffered = if (text.trimStart().startsWith("#EXTM3U")) {
            HlsPlaylistFixer.fix(text, dataSpec.uri.toString()).toByteArray(Charsets.UTF_8)
        } else {
            bytes
        }
        runCatching { upstream.close() }
        closed = true
        return buffered!!.size.toLong()
    }

    private fun isPlaylist(dataSpec: DataSpec): Boolean {
        if (dataSpec.uri.toString().contains(".m3u8", true)) return true
        val ct = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.firstOrNull().orEmpty()
        return ct.contains("mpegurl", true) || ct.contains("m3u8", true)
    }

    private fun readAll(): ByteArray {
        val chunk = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream(64 * 1024)
        while (true) {
            val n = upstream.read(chunk, 0, chunk.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(chunk, 0, n)
            if (out.size() > 8 * 1024 * 1024) break   // 防御：playlist 不可能这么大
        }
        return out.toByteArray()
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val buf = buffered ?: return upstream.read(buffer, offset, length)
        if (pos >= buf.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, buf.size - pos)
        System.arraycopy(buf, pos, buffer, offset, n)
        pos += n
        return n
    }

    @Throws(IOException::class)
    override fun close() {
        if (!closed) runCatching { upstream.close() }
        closed = true
        buffered = null
        pos = 0
    }

    override fun getUri(): android.net.Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
}

/** 把 [HlsFixDataSource] 挂到任意 [DataSource.Factory] 上 */
class HlsFixDataSourceFactory(
    private val upstreamFactory: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = HlsFixDataSource(upstreamFactory.createDataSource())
}
