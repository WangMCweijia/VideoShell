package com.videoshell.data.site

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 播放器「外壳」的通用识别与换链（v1.0.31）。
 *
 * 越来越多的播放页**本身不带地址**，而是一层壳：真地址要么在 iframe 里，要么藏在
 * `window.__XXX__={...}` 这样的引导对象里，靠页面 JS 去换。只靠 `Media.extractFromHtml`
 * 抠，这类站**全部**只能退嗅探 —— 枫叶影视（v1.0.30 的 mui-player 外壳）、
 * 骚火（v1.0.31 的 hhplayer 引导壳）都是这么冒出来的。
 *
 * 把所有壳收在同一个地方，是为了「后续新站别再一个一个爆」：
 * 1. **每个分支都自门控** —— 页面里没有对应标记就返回 null，调用方据此**一个请求都不发**
 *    （否则会对所有站白发请求，比播不出来更糟）；
 * 2. [describe] 给出「认出了哪种壳 / 哪种都没认出」，写进自检报告；新站一眼看出该补哪一种，
 *    不用再抓包猜。
 *
 * 共同规律（两次都是）：**令牌就在页面里，不用重算签名**；页面里的混淆 JS 是诱饵。
 * 换回来的地址带 `auth_key` / `expires` / `sign` 一类时效参数 ⇒ **只当次取用，绝不固化**。
 */
object PlayerShell {

    private val ORIGIN = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE)

    private fun originOf(url: String): String? =
        ORIGIN.find(url.trim())?.groupValues?.get(1)

    // ------------------------------------------------------------ iframe 播放器

    private val IFRAME_SRC =
        Regex("<iframe[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** 地址里带这些字样的 iframe 才像"播放器"（广告/统计/评论区一律不算） */
    private val PLAYER_HINT =
        Regex("(player|hhplayer|play|video|m3u8|tv|jx|parse|ckplayer|dplayer|artplayer)", RegexOption.IGNORE_CASE)

    /**
     * 从播放页里挑出**播放器 iframe** 的绝对地址。
     *
     * 骚火实测：播放页 10 KB 里既没有 `player_aaaa` 也没有 m3u8，只有一个
     * `<iframe src="https://hhjx.hhplayer.com/?url=<96 位十六进制>">` —— 真地址全在那一层。
     * 以前这类站只能退嗅探。
     *
     * 门控：`about:` / `data:` 跳过；相对地址按 [pageUrl] 的 origin 补全；
     * 地址里没有播放器字样就不认（避免跟着广告 iframe 乱跳）。
     */
    fun iframeOf(html: String?, pageUrl: String): String? {
        if (html.isNullOrBlank()) return null
        val base = originOf(pageUrl) ?: return null
        for (m in IFRAME_SRC.findAll(html)) {
            val raw = m.groupValues[1].trim()
            if (raw.isBlank()) continue
            if (raw.startsWith("about:", true) || raw.startsWith("data:", true)) continue
            val abs = when {
                raw.startsWith("http", true) -> raw
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> base + raw
                else -> continue
            }
            if (PLAYER_HINT.containsMatchIn(abs)) return abs
        }
        return null
    }

    // ------------------------------------------------------------ 引导对象 + JSON 接口

    /**
     * `window.__HHJX_BOOTSTRAP__={"url":"…","t":1789…,"key":"…","ts_key":"…"};</script>`
     *
     * 名字取通用的 `__XXX__`：不同家的壳换个名字而已，形状一样。
     */
    private val BOOTSTRAP = Regex(
        "window\\.__([A-Za-z0-9_]+)__\\s*=\\s*(\\{[^<>]*\\})\\s*;?\\s*</script>",
        RegexOption.IGNORE_CASE
    )

    /** 引导对象里的换链令牌。**令牌是页面现成给的，不要自己去算**。 */
    data class Boot(
        val name: String = "",
        val url: String = "",
        val t: String = "",
        val key: String = ""
    )

    /**
     * 认「引导壳」。必须同时有 `url` 与 `key` 才认 —— 缺一个接口必然换不到东西，
     * 早返回早省一个请求。
     */
    fun bootOf(html: String?): Boot? {
        if (html.isNullOrBlank()) return null
        val m = BOOTSTRAP.find(html) ?: return null
        val o = objOf(m.groupValues[2]) ?: return null
        val url = o.str("url").trim()
        val key = o.str("key").trim()
        if (url.isBlank() || key.isBlank()) return null
        return Boot(name = m.groupValues[1], url = url, t = o.str("t").trim(), key = key)
    }

    /**
     * 换链接口地址：`{origin}{path}`。
     *
     * 骚火实测 `POST https://hhjx.hhplayer.com/api/parse`；接口与播放页**同源**，
     * 所以跟着 iframe 页的 origin 走即可，不用记域名。
     */
    @JvmOverloads   // 默认参数对 Java harness 不可见；不加它所有 harness 当天编译失败
    fun bootApiUrl(pageUrl: String, path: String = "/api/parse"): String? {
        val origin = originOf(pageUrl) ?: return null
        val p = if (path.startsWith("/")) path else "/$path"
        return origin + p
    }

    /**
     * 接口要的那段 JSON。字段与页面 JS 实测一致：
     * `{url, t, key, client_fallback}`（`client_fallback` 默认 false = 不要浏览器兜底线路）。
     *
     * `t` 在页面里是数字，就按数字发（有的接口对字符串/数字敏感）。
     */
    fun bootJson(b: Boot): String {
        val o = JsonObject()
        o.addProperty("url", b.url)
        val ti = b.t.toLongOrNull()
        if (ti != null) o.addProperty("t", ti)
        else if (b.t.isNotBlank()) o.addProperty("t", b.t)
        o.addProperty("key", b.key)
        o.addProperty("client_fallback", false)
        return o.toString()
    }

    /**
     * 从换链接口的响应里取真地址。
     *
     * 实测成功：`{"code":200,"msg":"成功","url":"http://…/playlist/….m3u8?expires=…&sign=…"}`；
     * `code` 不是 200 就不认（跟 maccms 外壳一个规矩：别把错当对）。
     */
    fun jsonUrl(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val o = objOf(json.trim()) ?: return null
        val code = o.get("code")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (code.isNotBlank() && code != "200") return null
        val u = o.str("url").trim()
        return u.takeIf { it.startsWith("http", true) }
    }

    // ------------------------------------------------------------ 诊断

    /**
     * 自检/排查用：这一页到底认出了哪种壳。
     *
     * 新站"又要嗅探"时，先看这一行：写「哪种都没认出」⇒ 是新壳，抓一页补一个分支；
     * 写了具体某种 ⇒ 是分支里的判据没生效，不是新壳。
     */
    fun describe(html: String?, pageUrl: String): String {
        if (html.isNullOrBlank()) return "页面为空"
        val tags = ArrayList<String>()
        if (MacPlayer.shellOf(html) != null) tags.add("mui-player外壳(#player-data)")
        bootOf(html)?.let { tags.add("引导壳(window.__${it.name}__)") }
        iframeOf(html, pageUrl)?.let { tags.add("iframe播放器(${short(it)})") }
        if (html.contains(".m3u8", true) || html.contains("player_aaaa", true)) tags.add("页面内含地址线索")
        return if (tags.isEmpty()) "哪种壳都没认出（新壳 ⇒ 抓一页补分支）" else tags.joinToString(" + ")
    }

    private fun short(u: String): String = runCatching {
        val h = java.net.URI(u).host.orEmpty()
        if (h.isBlank()) u.take(40) else h
    }.getOrDefault(u.take(40))

    // ------------------------------------------------------------ JSON 小工具

    private fun objOf(json: String): JsonObject? =
        runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()

    private fun JsonObject.str(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
