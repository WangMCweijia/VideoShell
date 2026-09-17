package com.videoshell.data.site

/** 媒体地址识别 + 播放页里直接抠出真实播放地址（大部分 maccms 播放页把地址写在 player_aaaa 里） */
object Media {

    private val DIRECT = Regex(
        "\\.(m3u8|mp4|flv|mkv|avi|m4v|mov|ts|mpd|wmv|rmvb)(\\?|#|$)",
        RegexOption.IGNORE_CASE
    )

    private val PLAYER_JSON = Regex(
        "(?:player_[A-Za-z0-9_]+)\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*;",
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

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=").replace("&amp;", "&")

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
