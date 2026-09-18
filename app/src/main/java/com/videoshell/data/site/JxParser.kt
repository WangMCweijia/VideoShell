package com.videoshell.data.site

import com.videoshell.data.net.Http

/**
 * jx 解析接口跟随（v1.0.25）。
 *
 * 一批站点自己不下发流地址，而是把播放地址交给第三方「解析接口」：
 *   `.../player/?url=https://v.qq.com/x/cover/xxx.html`
 *   `.../jx.php?url=...`、`.../parse/api.php?url=...`
 * 壳子以前遇到这类站只能嗅探（网页里只有 iframe，要跑完 JS 才发流），
 * 而解析接口其实**一个 GET 就能拿到 m3u8**。
 *
 * 这里做两件事：
 * 1. [findJxUrl]：从播放页 HTML 里找出解析接口地址（`url=` 参数后跟 http 地址）；
 * 2. [follow]：请求解析接口，把响应（JSON / 纯文本 / JS 变量）里的真实流地址抠出来。
 */
object JxParser {

    /** 解析接口的典型形状：某个参数后面直接跟着一个 http(s) 地址 */
    private val JX_PARAM = Regex(
        "[?&](?:url|vurl|vid|link|u|play_url)=(https?%3A%2F%2F|https?://)",
        RegexOption.IGNORE_CASE
    )

    /** 从 HTML 里找绝对形式的解析接口地址 */
    private val JX_IN_HTML = Regex(
        "https?://[^\"'<>\\s\\\\)]+[?&](?:url|vurl|link|u)=https?[^\"'<>\\s\\\\)]*",
        RegexOption.IGNORE_CASE
    )

    /** 响应里的流地址（含被编码的） */
    private val STREAM = Regex(
        "https?://[^\"'<>\\s\\\\]+?\\.(?:m3u8|mp4|flv)[^\"'<>\\s\\\\]*",
        RegexOption.IGNORE_CASE
    )

    /** JSON 字段里的地址：`"url":"http..."` / `"data":{"url":...}` */
    private val JSON_URL_FIELD = Regex(
        "\"(?:url|play_url|data|src|video|link|m3u8)\"\\s*:\\s*\"(https?:[^\"]+)\"",
        RegexOption.IGNORE_CASE
    )

    /** 地址本身就是解析接口 */
    fun isJxUrl(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http")) return false
        val head = u.substringBefore('?').lowercase()
        return JX_PARAM.containsMatchIn(u) ||
            head.contains("/jx") || head.contains("/parse") ||
            head.contains("/jiexi") || head.contains("/player/") ||
            head.contains("/api.php") && u.contains("url=")
    }

    /** 从播放页 HTML 里找解析接口地址（找不到返回 null） */
    fun findJxUrl(html: String?): String? {
        if (html.isNullOrBlank()) return null
        // iframe/script 里的相对形式先补全再判断：这里只认绝对地址，相对形式交给调用方
        val m = JX_IN_HTML.find(html) ?: return null
        val raw = unescape(m.value)
        return raw.takeIf { isJxUrl(it) }
    }

    /**
     * 请求解析接口并取出流地址。
     * @return m3u8/mp4/flv 直链；取不到返回 null
     */
    suspend fun follow(jxUrl: String, referer: String): String? {
        val body = runCatching { Http.getOrNull(jxUrl, referer = referer) }.getOrNull() ?: return null
        return extractStream(body)
    }

    /** 从解析接口的响应体里抠流地址（JSON 字段 > 裸地址 > HTML 内嵌） */
    fun extractStream(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val text = unescape(body)
        JSON_URL_FIELD.find(text)?.groupValues?.getOrNull(1)?.let { v ->
            if (isStream(v)) return v
        }
        STREAM.find(text)?.value?.let { return it }
        // 有的接口把地址塞在 HTML 里（<video src>、player_aaaa 变量）
        Media.extractFromHtml(text)?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun isStream(u: String): Boolean {
        val low = u.lowercase()
        return low.startsWith("http") &&
            (low.contains(".m3u8") || low.contains(".mp4") || low.contains(".flv"))
    }

    /** `\u002F` / `\/` / `&amp;` 这类转义还原，否则正则匹配不上 */
    private fun unescape(s: String): String = s
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&")
}
