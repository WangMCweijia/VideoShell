package com.videoshell.data.site

/** 媒体地址识别 + 播放页里直接抠出真实播放地址（大部分 maccms 播放页把地址写在 player_aaaa 里） */
object Media {

    private val DIRECT = Regex(
        "\\.(m3u8|mp4|flv|mkv|avi|m4v|mov|ts|mpd|wmv|rmvb)(\\?|#|$)",
        RegexOption.IGNORE_CASE
    )

    /** 播放页里的 player_aaaa = {...}：结尾可能接 `;`、也可能直接跟 </script>，两种都要吃 */
    private val PLAYER_JSON = Regex(
        "player_[A-Za-z0-9_]+\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*(?:;|</script)",
        RegexOption.IGNORE_CASE
    )

    private val KEY_URL = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val KEY_URL2 = Regex("\"url_next\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    /**
     * 自研播放器（DPlayer / ArtPlayer / Plyr / video.js）的地址字段（v1.0.25）。
     *
     * 这类播放器的初始化参数长这样：
     * ```js
     * new DPlayer({ video: { url: '...m3u8' } })
     * dplayer = { "file": "...m3u8", "type": "hls" }
     * ```
     * 老规则只认 `"url"`，遇到 `file` / `playUrl` 就漏。
     */
    private val KEY_FILE = Regex(
        "\"(?:file|source|src|playUrl|play_url|videoUrl|video_url|m3u8)\"\\s*:\\s*\"([^\"]+)\"",
        RegexOption.IGNORE_CASE
    )

    /** `sources: [{file: "..."}]` 形式：先抠出数组再取里面的地址 */
    private val SOURCES_ARRAY = Regex("\"sources\"\\s*:\\s*\\[([\\s\\S]{0,3000}?)]", RegexOption.IGNORE_CASE)

    private val QUOTED_MEDIA = Regex(
        "[\"']([^\"']+?\\.(?:m3u8|mp4|flv|ts|mpd)[^\"']*)[\"']",
        RegexOption.IGNORE_CASE
    )

    private val BARE_MEDIA = Regex(
        "https?://[^\\s\"'<>\\\\]+?\\.(?:m3u8|mp4|flv|mpd)[^\\s\"'<>\\\\]*",
        RegexOption.IGNORE_CASE
    )

    fun isDirect(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        if (u.startsWith("rtmp://") || u.startsWith("rtsp://")) return true
        if (u.contains(".m3u8", true)) return true
        return DIRECT.containsMatchIn(u)
    }

    fun isHls(url: String) = url.contains("m3u8", true)

    private const val HEX = "0123456789ABCDEF"

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /**
     * 把 URL 里「不该出现在请求行中」的字符做 UTF-8 百分号编码。
     *
     * ## 为什么必须做（这是「自检 200、播放 404」的真正原因）
     *
     * 站点的媒体路径常常含中文，例如
     * `https://c1.ddbbffcdn.com/video/bianshuiwangshi/第01集/index.m3u8`。
     * 这个地址有两套栈会去请求：
     *
     * | 谁 | 底层 | 对非 ASCII 路径的处理 |
     * |---|---|---|
     * | 自检 / 抓页面 | OkHttp | **自动百分号编码** → CDN 200 |
     * | ExoPlayer 播放 | `DefaultHttpDataSource` → `HttpURLConnection` | **不编码**，原样塞字节 → CDN 404 |
     *
     * 本机回环实测抓到的原始请求行：
     * ```
     * OkHttp            → GET /video/%E7%AC%AC01%E9%9B%86/index.m3u8 HTTP/1.1
     * HttpURLConnection → GET /video/ç¬¬01é/index.m3u8            HTTP/1.1
     * ```
     * 两者不同 —— 所以「自检全绿」永远无法证明「播放能成」，它们根本不是同一个请求。
     *
     * 修法：在交给播放器之前先把 URL 规范成纯 ASCII，让两套栈发出的字节完全一致。
     * 已经存在的 `%XX` 会原样保留，避免被二次编码成 `%25XX`。
     */
    fun encodeUrl(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return u

        // 快路径：整串都没有需要转义的字符，原样返回（对占绝大多数的纯 ASCII 地址零改动）
        var need = false
        for (c in u) {
            if (isUnsafe(c)) { need = true; break }
        }
        if (!need) return u

        val sb = StringBuilder(u.length + 24)
        var i = 0
        while (i < u.length) {
            val c = u[i]
            when {
                // 已有的 %XX 原样保留（防二次编码）
                c == '%' && i + 2 < u.length && isHex(u[i + 1]) && isHex(u[i + 2]) -> {
                    sb.append(u, i, i + 3)
                    i += 3
                }
                // 非 ASCII 或必须转义的 ASCII
                isUnsafe(c) -> {
                    val cp = u.codePointAt(i)
                    val cc = Character.charCount(cp)
                    val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        val v = b.toInt() and 0xFF
                        sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
                    }
                    i += cc
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /**
     * 稳定摘要（SHA-1 取前 8 字节 = 16 个十六进制字符）。
     *
     * **进度记忆的 key 必须用它，不要用 `String.hashCode()`。** 两个原因：
     * 1. hashCode 只有 32 位，不同内容撞到同一个 key 就会"串台"（A 的进度续到 B 上）；
     * 2. 它换不来"稳定" —— 带时效签名的媒体直链每次都不同，hash 值自然每次都变。
     */
    fun digest(s: String): String = try {
        val b = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            val v = b[i].toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        sb.toString()
    } catch (t: Throwable) {
        // 摘要算法不可用（理论不会发生）时退化，仍然确定、只是短一些
        Integer.toHexString(s.hashCode()).padStart(8, '0')
    }

    private fun isUnsafe(c: Char): Boolean =
        c.code > 127 || c == ' ' || c.code < 0x20 ||
            c == '"' || c == '<' || c == '>' || c == '\\' ||
            c == '^' || c == '`' || c == '{' || c == '|' || c == '}'

    /**
     * 续播落点是否已落进流末尾（v1.0.21）。
     *
     * 存进度时"看到尾"的会清掉（savePosition 的 dur-15s 规则），所以一个**正常**的续播点
     * 不可能落在最后 15 秒里。若续播 seek 的实际落点在这里 ⇒ 要么存的点超出了本条流
     * （站点截断 / 换源后时长变短），要么流本身的时长和存的时候不一样 —— 都该从头播，
     * 而不是停在离片尾十几秒的地方让用户以为"进度错乱"。
     * 纯函数放这里是为了离线 harness（ResumeKey）能直接断言。
     */
    fun resumeAtEnd(posMs: Long, durMs: Long): Boolean =
        durMs > 0L && posMs > durMs - 15_000L

    fun looksLikeMedia(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http")) return false
        return u.contains(".m3u8", true) || u.contains(".mp4", true) ||
            u.contains(".flv", true) || u.contains(".mpd", true) ||
            u.contains(".ts", true)
    }

    /**
     * 还原 JSON / JS 字符串里的转义。
     *
     * 关键：**必须支持通用 `\uXXXX`**。
     * 很多站的 `player_aaaa` 是 JSON 序列化出来的，路径里的中文会被写成 `\uXXXX`
     * （例如 `/video/bianshuiwangshi/\u7b2c01\u96c6/index.m3u8` 实际是 `…/第01集/index.m3u8`）。
     * 旧实现只解 `\/ \u0026 \u003d`，于是把 `\u7b2c01\u96c6` 原样丢给播放器 → CDN 返回 404。
     */
    fun unescape(s: String): String {
        if (s.indexOf('\\') < 0 && !s.contains("&amp;")) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) {
                sb.append(c)
                i++
                continue
            }
            when (s[i + 1]) {
                'u', 'U' -> {
                    val cp = hex4(s, i + 2)
                    if (cp < 0) {
                        sb.append(c)
                        i++
                    } else {
                        // 逐 UTF-16 单元追加：代理对（\uD83D\uDE00）也能拼回完整字符
                        sb.append(cp.toChar())
                        i += 6
                    }
                }
                '/' -> { sb.append('/'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '"' -> { sb.append('"'); i += 2 }
                '\'' -> { sb.append('\''); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString().replace("&amp;", "&")
    }

    /** 读 4 位十六进制，失败返回 -1 */
    private fun hex4(s: String, from: Int): Int {
        if (from + 4 > s.length) return -1
        var v = 0
        for (k in 0 until 4) {
            val d = Character.digit(s[from + k], 16)
            if (d < 0) return -1
            v = v * 16 + d
        }
        return v
    }

    /**
     * 取「最内层」的媒体地址。
     *
     * ## 为什么必须有这一步（厂长资源播放失败的原因）
     *
     * 很多站的播放页不直接把 m3u8 写出来，而是嵌一层**代理播放器**，把真地址当参数塞进去：
     *
     * ```html
     * <iframe src="https://plaa.py1080p.com:8181/player/py.php?code=cs&if=1&url=https://m3hlsm3.py1080p.com:907/hls3/hls/峡谷.m3u8">
     * ```
     *
     * 旧实现的正则 `https?://[^"'<>\s]+?\.(?:m3u8|mp4…)` 从**第一个** `https://` 开始匹配，
     * 而这一整串里没有空白和引号，于是它贪婪地一路吃到结尾的 `.m3u8`，
     * 把**外层代理页**当成了媒体地址。结果：
     *  - `isHls()` 因为串里有 "m3u8" 而返回 true → 按 HLS 播；
     *  - 播放器去请求 `py.php?...`，拿回来的是 `text/html`；
     *  - 解析失败，用户看到"播放失败"，而自检 [5] 打印的地址看着"完全正常"。
     *
     * 修法：若候选串里还嵌着 `?url=` / `&url=`（或 URL 编码后的 `%3Dhttp`），
     * 就递归取最后那一层；只有拆出来的东西**仍然像媒体地址**才采用，避免误伤正常 URL。
     */
    fun innermost(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return u

        // 明文嵌套：...&url=https://real/x.m3u8
        val i = u.lastIndexOf("=http", ignoreCase = true)
        if (i >= 0) {
            val inner = u.substring(i + 1)
            if (inner != u && looksLikeMedia(inner)) return innermost(inner)
        }

        // URL 编码嵌套：url=https%3A%2F%2Freal%2Fx.m3u8
        if (u.contains("%3a%2f%2f", true)) {
            val dec = runCatching { java.net.URLDecoder.decode(u, "UTF-8") }.getOrDefault("")
            if (dec.isNotBlank() && dec != u) {
                val j = dec.lastIndexOf("=http", ignoreCase = true)
                val inner = if (j >= 0) dec.substring(j + 1) else dec
                if (inner != u && looksLikeMedia(inner)) return innermost(inner)
            }
        }
        return u
    }

    /** 抽出一个候选地址并做"最内层 + 反转义"处理；不是媒体地址就返回 null */
    private fun pick(re: Regex, src: String): String? {
        val g = re.find(src)?.groupValues?.getOrNull(1) ?: return null
        val u = innermost(unescape(g))
        return if (looksLikeMedia(u)) u else null
    }

    /** 从播放页 HTML 里挖出真实媒体地址 */
    fun extractFromHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null

        PLAYER_JSON.find(html)?.let { m ->
            val body = m.groupValues[1]
            // v1.0.29：先按 `encrypt` 解码 url（maccms 标准：1=百分号编码，2=base64）。
            // 不解码时 `"url":"aHR0cHM6Ly8…"` / `"url":"https%3A%2F%2F…"` 这类站
            // 永远抠不到地址 —— 用户看到的就是「点某一集播不了，只能靠嗅探」。
            MacPlayer.infoFromJson(body)?.url?.let { u ->
                val dec = innermost(unescape(u))
                if (looksLikeMedia(dec)) return dec
            }
            pick(KEY_URL, body)?.let { return it }
            pick(KEY_URL2, body)?.let { return it }
            pick(KEY_FILE, body)?.let { return it }
        }
        pick(KEY_URL, html)?.let { return it }
        // 自研播放器（DPlayer / ArtPlayer / video.js）
        SOURCES_ARRAY.find(html)?.let { m ->
            pick(KEY_FILE, m.groupValues[1])?.let { return it }
        }
        pick(KEY_FILE, html)?.let { return it }
        pick(QUOTED_MEDIA, html)?.let { return it }
        BARE_MEDIA.find(html)?.let {
            val u = innermost(unescape(it.value))
            if (looksLikeMedia(u)) return u
        }
        return null
    }
}
