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
     * 「静态整集」判据（v1.0.38）：站点**两样都不标**（既没有 `PLAYLIST-TYPE:VOD`
     * 也没有 `ENDLIST`）时，靠清单本身的长相判断它是不是一整集。
     *
     * 不补 ENDLIST 的话 ExoPlayer 会按**直播**处理：把起播点放在"直播边缘"（= 清单末尾），
     * 结果就是**时长读得到、画面一直转圈**（在等永远不来的"下一段"）。
     *
     * 阈值刻意保守 —— 直播 HLS 给的是**窗口**（几十秒、几个分片），
     * 30 个分片 + 15 分钟以上不可能是窗口。放宽到 12/5 分钟会开始碰到长窗口直播。
     */
    private const val STATIC_MIN_SEGMENTS = 30
    private const val STATIC_MIN_SECONDS = 900.0

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
        val uri = runCatching { java.net.URI(playlistUrl) }.getOrNull()
        val scheme = uri?.scheme.orEmpty().ifEmpty { "https" }
        val authority = uri?.rawAuthority.orEmpty()
        val host = if (authority.isEmpty()) "" else "$scheme://$authority"
        val dir = dirOf(playlistUrl, uri?.rawPath.orEmpty(), host)

        val head = StringBuilder()
        val body = StringBuilder()
        val pending = StringBuilder()          // 尚未确定要保留的 segment 标签
        var inMedia = false
        var maxDur = 0.0
        var totalDur = 0.0
        var isVod = false
        var hadEndList = false
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
                    // v1.0.22：原文自带的 ENDLIST 要**记账**（见末尾补写）—— 此前这里
                    // 剥掉后只有 PLAYLIST-TYPE:VOD 才补回，导致大量"有 ENDLIST 但没写
                    // PLAYLIST-TYPE"的普通 VOD 流被 ExoPlayer 当直播、从 live edge 起播。
                    hadEndList = true
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
                totalDur += it
            }
            flushPending()
            skipNextDiscontinuity = false
            body.append(absolutize(line, dir, host, scheme)).append('\n')
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
        // 补 ENDLIST 的三种情况：
        //  ① 声明过 VOD；② **原文本来就带 ENDLIST**（剥掉必须还回去）；
        //  ③ v1.0.38：两样都没标、但清单明显是一整集（静态长清单）——
        //     不补的话 ExoPlayer 按直播处理，表现为"时长读得到、一直转圈"。
        // 真直播流三者皆无，照旧不补 —— 补了会被当成"已结束"直接截断。
        val staticFull = !isVod && !hadEndList &&
            kept >= STATIC_MIN_SEGMENTS && totalDur >= STATIC_MIN_SECONDS
        if ((isVod || hadEndList || staticFull) && !body.contains("#EXT-X-ENDLIST")) {
            out.append("#EXT-X-ENDLIST\n")
        }
        return out.toString()
    }

    /**
     * 相对地址的**解析基准**（v1.0.40）。
     *
     * ⚠️ 必须优先用「**重定向后的最终地址**」，而不是我们发出去的那个地址。
     *
     * 实测（枫叶影院 `co` 线路，v1.0.39 用户真机日志 = 清单 200 / 分片全 402）：
     * ```
     * 请求清单 …/cloud/flv/<a>/<b>/x.m3u8?auth_key=…
     *   302 →  …/ufile/flv/qq/<长hash>/<name>_@lirose_tv.m3u8        ⇒ 200（openresty）
     * 分片是**相对地址**（`<sid>-N.ts?tg=@lirose_tv`），于是基准决定一切：
     *   以【请求地址】为基准 ⇒ …/cloud/flv/<a>/<b>/<sid>-N.ts
     *         ⇒ 又被 302 甩去 `https://www.aibox.eu.org/110`
     *         ⇒ **HTTP 402 `X-Vercel-Error: DEPLOYMENT_DISABLED`**（12/12 次，重试无用）
     *   以【最终地址】为基准 ⇒ …/ufile/flv/qq/<hash>/<sid>-N.ts
     *         ⇒ **200，1 690 120 B，`Content-Type: video/MP2T`，首字节 0x47**（真分片）
     * ```
     * 网页端（hls.js）用的是后者 —— 这就是「**网页能播、App 一直转圈**」的分界：
     * 不是网络、不是请求头（9 种组合测过，7 种 402，与 UA/Referer 无关）、
     * 也不是分片本身坏了，而是**我们把相对分片拼到了错的目录上**。
     *
     * 所以判据只有一句：**服务器最后把清单放在哪，分片就相对于哪里**。
     * 拿不到最终地址时（老路径/异常）退回请求地址 —— 维持 v1.0.39 以前的行为。
     */
    fun baseFor(requestUrl: String, finalUrl: String?): String =
        if (finalUrl.isNullOrBlank()) requestUrl else finalUrl

    /**
     * playlist 所在目录（含 scheme://authority，供相对分片拼接）。
     *
     * ⚠️ **必须先剥掉 query / fragment 再找斜杠**（v1.0.38 修的）。
     * 旧实现直接 `playlistUrl.substringBeforeLast('/')`：带鉴权参数的地址
     * （`…/hls/index.m3u8?auth=a/b`）里那个斜杠会让"最后一个斜杠"落在**参数里**，
     * 于是拼出来的分片地址整段错位 ⇒ 分片全 404 ⇒ 播放器一直转圈重试。
     * 症状与"流有问题"一模一样，而看代码怎么都不像有问题。
     *
     * 用 `rawPath`（未解码）而不是 `path`：解码会把 `%E7%AC%AC01%E9%9B%86` 变回中文，
     * 拼进分片地址后等于把"已编码"的东西重新变成非 ASCII。
     */
    private fun dirOf(playlistUrl: String, rawPath: String, host: String): String {
        if (rawPath.isNotEmpty() && host.isNotEmpty()) {
            val i = rawPath.lastIndexOf('/')
            return if (i <= 0) "$host/" else host + rawPath.substring(0, i)
        }
        return playlistUrl.substringBefore('?').substringBefore('#').substringBeforeLast('/', "")
    }

    /**
     * 相对路径 → 绝对地址。
     *
     * 协议相对地址（`//host/x.ts`）用 **playlist 自己的协议**，不能写死 https：
     * 纯 http 的站会因为被升级成 https 而整批分片失败。
     */
    private fun absolutize(seg: String, dir: String, host: String, scheme: String): String {
        if (seg.startsWith("http://") || seg.startsWith("https://")) return seg
        if (seg.startsWith("//")) return "$scheme:$seg"
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
        // ⚠️ 必须**紧接 open** 取，且取的是 `upstream.uri`（= 跟随重定向后的地址），
        //    不是 `dataSpec.uri`（我们发出去的那个）—— 两者在"清单被 302 到另一个目录"
        //    的站上完全不同，用错一个就是"清单 200、分片全 402、一直转圈"（v1.0.40 真踩）。
        val servedUrl = runCatching { upstream.uri?.toString() }.getOrNull()
        // 只处理完整请求的 playlist
        if (dataSpec.position != 0L) return len
        if (!isPlaylist(dataSpec)) return len

        val bytes = readAll()
        val text = String(bytes, Charsets.UTF_8)
        val requested = dataSpec.uri.toString()
        val base = HlsPlaylistFixer.baseFor(requested, servedUrl)
        if (base != requested) {
            // 把"换了基准"这件事写进播放记录：这类站一旦出问题，症状（分片 402/404）
            // 与"流坏了"一模一样，只有这行字能一眼定性。
            PlayLog.record(
                "⇄ 清单被重定向 ⇒ 分片基准改为 " + dirShort(base) + "（仍按请求地址拼会 402/404）"
            )
        }
        buffered = if (text.trimStart().startsWith("#EXTM3U")) {
            HlsPlaylistFixer.fix(text, base).toByteArray(Charsets.UTF_8)
        } else {
            bytes
        }
        runCatching { upstream.close() }
        closed = true
        return buffered!!.size.toLong()
    }

    private fun isPlaylist(dataSpec: DataSpec): Boolean {
        val u = dataSpec.uri.toString()
        // 后缀判据要连 `.m3u` 一起认：少数站用 `.m3u`（无反斜杠），
        // 漏掉它就等于那份 playlist 完全没被规范化过
        if (u.contains(".m3u8", true) || u.contains(".m3u?", true) ||
            u.endsWith(".m3u", true)
        ) {
            return true
        }
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

/**
 * 只留"能认出目录"的那一小段（host + path，去掉 query），供播放记录里一行放下。
 * query 里通常是一串 `auth_key=…`，留着只会把真正有用的目录信息挤出去。
 */
private fun dirShort(u: String): String {
    val s = u.substringBefore('?').substringBefore('#')
    val i = s.indexOf("://")
    if (i < 0) return if (s.length <= 60) s else s.take(30) + "…" + s.takeLast(26)
    val rest = s.substring(i + 3)
    val host = rest.substringBefore('/')
    val path = rest.substringAfter('/', "")
    val msg = if (path.isEmpty()) host else "$host/$path"
    return if (msg.length <= 60) msg else msg.take(30) + "…" + msg.takeLast(26)
}
