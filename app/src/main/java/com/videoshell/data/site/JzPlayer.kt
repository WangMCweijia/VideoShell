package com.videoshell.data.site

import com.google.gson.JsonParser

/**
 * 「jz 播放器栈」取流规则（v1.0.86，juzong01 等站）。
 *
 * ## 为什么需要它（「网页能播、App 里点某一集播不出」的真身）
 *
 * 这一族的 maccms 站把播放器整个**换成了自研栈**，`player_aaaa` 里
 * `ps:0`（没有解析模板）、`url` 是一个令牌而不是媒体地址：
 *
 * ```
 * player_data={"from":"dujiarbx","url":"juzong1-045bInmV…","encrypt":0}   // 无 http 前缀
 * ```
 *
 * 于是现有解析链走到 maccms 跟随那一步就断了：[MacPlayer.playPage] 因 `ps != 1`
 * 返回 null ⇒ 只能退回「网页嗅探」。**而嗅探出来的地址在 App 里照样播不了** ——
 * 真流是抖音 CDN（`*.douyinvod.com`），它按 Referer 做防盗链：
 * 只接受**空 Referer** 或 `https://www.douyin.com/`，App 默认挂的
 * `Referer: <站点根>` 一律 **403**。网页端之所以正常，是因为站点自己给 iframe 打了
 * `<meta name="referrer" content="no-referrer">`（见 [JzPlayer] 注释与实测）。
 *
 * ## 完整链路（逐步实测，2026-10-12）
 *
 * ```
 * 播放页 /vodplay/{id}-{sid}-{nid}/
 *   → /static/player/{from}.js
 *        s.src = "https://jzpic.ok1333.cn/wgart/cms-player.js?base=https%3A%2F%2Fjzpic.ok1333.cn%2F&…"
 *   → iframe = base + "?url=" + encode(令牌)                     （cms-player.js 里就是 base + '?url=…'）
 *        <script>window.WGART_PARSE_TOKEN="1791781625.7bee…";</script>
 *   → GET {origin}/wgart/api.php?action=try_json_api&video_url=令牌&parse_token=WGART_PARSE_TOKEN
 *        {"success":true,"data":{"url":"https://v6-default.douyinvod.com/…/juzong.mp4","type":"auto"}}
 * ```
 *
 * 三个**必须照抄**的细节：
 *  1. `video_url` 就是页面里的令牌原文（`juzong1-…`），**不要**再解码/编码成别的形状；
 *  2. `parse_token` 是**每个 iframe 会话现签**的（带时间戳），必须**先抓 iframe 页**拿到它，
 *     再拿去换流 —— 不能跨会话复用，也不能固化；
 *  3. 返回体里的 `type` 实测恒为 `"auto"`（连 m3u8 也是），**是否 HLS 一律按地址判断**，
 *     别信 `type`（与 [MacPlayer.jsonUrl] 同一个教训）。
 *
 * 纯逻辑、不碰 Android / 网络 ⇒ 离线 harness 可直接断言。
 */
object JzPlayer {

    /**
     * 该线路的播放器脚本地址：maccms 约定 `/static/player/{from}.js`。
     *
     * 只在调用方已经确认「`url` 是令牌型」时才会被调用 ⇒ 普通 maccms 站**零额外请求**
     * （普通自营线路的 `url` 是 http 直链，自门控直接挡掉）。
     */
    fun playerScriptUrl(pageUrl: String, from: String): String? {
        val origin = originOf(pageUrl) ?: return null
        // `from` 直接进 URL，先按 maccms 的字符集收敛，杜绝路径穿越 / 注入
        val f = from.trim().lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (f.isEmpty()) return null
        return "$origin/static/player/$f.js"
    }

    private val CMS_PLAYER_SRC = Regex(
        "[\"']([^\"']*cms-player\\.js[^\"']*)[\"']",
        RegexOption.IGNORE_CASE
    )

    /**
     * 从每线路播放器脚本里推出 iframe 地址；认不出返回 null（调用方据此**不发后续请求**）。
     *
     * 只认这一族真实存在的形状：脚本里出现 `…/wgart/cms-player.js?base=…`，
     * iframe 就是 `base + "?" + "url=" + encode(令牌)`（照抄 cms-player.js 的 `playerBase + '?' + …`）。
     * `name` / `ep` 等参数实测**可省**（接口只认 `video_url`），少拼几个反而少一处出错。
     */
    fun iframeFromPlayerScript(js: String?, playUrl: String): String? {
        if (js.isNullOrBlank()) return null
        val src = CMS_PLAYER_SRC.find(js)?.groupValues?.get(1)?.trim() ?: return null
        val base = queryParam(src, "base")
        if (base.isBlank()) return null
        val origin = originOf(src) ?: return null
        val abs = MacPlayer.absUrl(base, "$origin/")
        if (!abs.startsWith("http")) return null
        val token = playUrl.trim()
        if (token.isEmpty()) return null
        return abs + "?" + "url=" + enc(token)
    }

    private val PARSE_TOKEN = Regex(
        "WGART_PARSE_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']"
    )

    /** iframe 页面里的会话令牌；没有就是不能换流，返回 null。 */
    fun parseToken(html: String?): String? =
        html?.let { PARSE_TOKEN.find(it)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotBlank() }

    /**
     * 取流接口地址。
     *
     * 路径与页面里 `getApiPath()` 同源：iframe 在 `/`（根）⇒ 走 `/wgart/api.php`。
     * 本族 iframe 实测都是根路径（cms-player 拼的是 `base + '?…'`，base 无子路径），
     * 故固定 `/wgart/api.php`，不猜。
     */
    fun apiUrl(iframeUrl: String, videoUrl: String, token: String): String? {
        val origin = originOf(iframeUrl) ?: return null
        if (videoUrl.isBlank() || token.isBlank()) return null
        return "$origin/wgart/api.php?action=try_json_api" +
            "&video_url=" + enc(videoUrl) +
            "&parse_token=" + enc(token)
    }

    /**
     * 接口返回体 → 真地址。
     *
     * 形状实测 `{"success":true,"data":{"url":"…","type":"auto"},"api_id":1,…}`。
     * `success` 为假、或取不到 http 地址 ⇒ null（退回嗅探，等价修复前行为）。
     */
    fun streamUrl(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val o = runCatching { JsonParser.parseString(json.trim()).asJsonObject }.getOrNull() ?: return null
        o.get("success")?.takeIf { it.isJsonPrimitive }?.let {
            val s = it.asString.trim()
            if (s.equals("false", true) || s == "0") return null
        }
        val data = o.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        val u = (data?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            ?: o.get("url")?.takeIf { it.isJsonPrimitive }?.asString).orEmpty().trim()
        return u.takeIf { it.startsWith("http") }
    }

    // ------------------------------------------------------------------ 内部

    /** `https://a.b/c?d` → `https://a.b`（拿不到返回 null） */
    private fun originOf(url: String): String? =
        Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE)
            .find(url.trim())?.groupValues?.get(1)

    /** 取查询参数并百分号解码（`+` 不当空格：URL 里可能是合法字符） */
    private fun queryParam(url: String, name: String): String {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return ""
        for (kv in q.split('&')) {
            val i = kv.indexOf('=')
            if (i <= 0) continue
            if (kv.substring(0, i) == name) return pctDecode(kv.substring(i + 1))
        }
        return ""
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun pctDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                out.write((hex(s[i + 1]) shl 4) or hex(s[i + 2]))
                i += 3
            } else {
                val b = c.toString().toByteArray(Charsets.UTF_8)
                out.write(b, 0, b.size)
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun hex(c: Char) = Character.digit(c, 16)
}
