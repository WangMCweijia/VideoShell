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

    /** 从播放页 HTML 里挖出真实媒体地址 */
    fun extractFromHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null

        PLAYER_JSON.find(html)?.let { m ->
            val body = m.groupValues[1]
            KEY_URL.find(body)?.groupValues?.get(1)?.let {
                val u = unescape(it)
                if (looksLikeMedia(u)) return u
            }
            KEY_URL2.find(body)?.groupValues?.get(1)?.let {
                val u = unescape(it)
                if (looksLikeMedia(u)) return u
            }
        }
        KEY_URL.find(html)?.groupValues?.get(1)?.let {
            val u = unescape(it)
            if (looksLikeMedia(u)) return u
        }
        QUOTED_MEDIA.find(html)?.groupValues?.get(1)?.let {
            val u = unescape(it)
            if (looksLikeMedia(u)) return u
        }
        BARE_MEDIA.find(html)?.let {
            val u = unescape(it.value)
            if (looksLikeMedia(u)) return u
        }
        return null
    }
}
