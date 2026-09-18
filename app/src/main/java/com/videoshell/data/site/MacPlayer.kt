package com.videoshell.data.site

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * maccms 播放器配置解析（v1.0.29）。
 *
 * ## 为什么需要它（「点某一集不能直接播、只能靠嗅探」的真身）
 *
 * maccms 播放页把播放信息写在 `player_aaaa` 里，但那个 `url` **不一定是媒体地址**：
 *
 * | 情况 | `url` 里是什么 | 真地址怎么来 |
 * |---|---|---|
 * | 自营线路（`ps:0`） | 直接就是 m3u8 / mp4 | 拿来即用 |
 * | 第三方解析（`ps:1`） | 一个令牌，如 `co_e5a2tzd6xi4age3dnjrq` | `parse 模板 + 令牌` |
 * | `encrypt:1` | 百分号编码过的地址 | 先 JS `unescape` |
 * | `encrypt:2` | base64 过的地址 | 先 base64，再 `unescape` |
 *
 * 站点自己的 `player.js`（原生 maccms 逻辑）正是这么做的：
 * ```js
 * if (player_data.encrypt == '1')      player_data.url = unescape(player_data.url)
 * else if (player_data.encrypt == '2') player_data.url = unescape(base64decode(player_data.url))
 * if (MacPlayerConfig.player_list[PlayFrom].ps == "1")
 *     this.Parse = player_list[PlayFrom].parse == '' ? MacPlayerConfig.parse : player_list[PlayFrom].parse
 * ```
 *
 * 我们原先只认「`url` 字段里直接写 m3u8」这一种形状，于是
 * **凡是 `ps:1` 或 `encrypt ≠ 0` 的线路一律抠不出地址，只能退回网页嗅探**。
 *
 * 实测 zqkhmy（2026-09-18，6 个真实播放页夹具，见 `_play/p1_line8.html` 等）：
 * 3 条 `ps:1`（co / vwnet / JD4K）**全部失败**，3 条 `ps:0`（dyttm3u8 / 1080zy / rym3u8）全部成功 ——
 * 成功率 6/12，与用户「很多站源点某一集不能直接播，只能网页嗅探」完全对应。
 *
 * 纯逻辑、不碰 Android API ⇒ 离线 harness 可直接断言。
 */
object MacPlayer {

    /** `player_list` 里的一条线路：`ps=1` 表示要走 [parse] 模板解析 */
    data class Line(val ps: Int, val parse: String, val show: String)

    /** `player_aaaa` 里我们用得上的字段；`url` / `urlNext` 已按 `encrypt` 解码 */
    data class Info(
        val from: String,
        val url: String,
        val urlNext: String,
        val link: String,
        val encrypt: Int
    )

    /** 最多跟随几层（正常一层就够；留一层给「解析页里又套一层」的站） */
    const val FOLLOW_MAX = 2

    // ⚠️ `(?!list)` 不能省：`MacPlayerConfig.player_list={` 里的 `player_list`
    // **同样满足** `player_[A-Za-z0-9_]+`。没有这个负向断言时，正则会先匹配到
    // `player_list={`，把线路表当成播放数据对象，于是 `from`/`url` 全空 ——
    // 离线断言里 D2 就是这么挂的（真实页面上表现同样是"抠不到地址"）。
    private val PLAYER_VAR = Regex(
        "player_(?!list)[A-Za-z0-9_]+\\s*=\\s*\\{",
        RegexOption.IGNORE_CASE
    )
    private val LIST_ASSIGN = Regex("player_list\\s*=\\s*\\{", RegexOption.IGNORE_CASE)
    private val CONFIG_ASSIGN = Regex("MacPlayerConfig\\s*=\\s*\\{", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ 抽取

    /**
     * 从 `{` 开始按**字符串/括号配平**取出完整 JSON 对象。
     *
     * 不能用非贪婪 `\{[\s\S]*?\}`：`player_aaaa` 里有嵌套对象（`vod_data`），
     * 非贪婪会在第一个 `}` 处截断，后面的 `url` 字段就丢了。
     */
    private fun jsonAt(s: String, start: Int): String? {
        if (start < 0 || start >= s.length || s[start] != '{') return null
        var depth = 0
        var i = start
        var inStr = false
        var esc = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    private fun objectAfter(html: String, re: Regex): String? {
        val m = re.find(html) ?: return null
        return jsonAt(html, m.range.last)
    }

    private fun JsonObject.str(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun objOf(json: String): JsonObject? =
        runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()

    // ------------------------------------------------------------------ 线路表

    /** 抽 `MacPlayerConfig.player_list`：`from` → 线路（`ps` / `parse`） */
    fun parseLines(html: String?): Map<String, Line> {
        if (html.isNullOrBlank()) return emptyMap()
        val json = objectAfter(html, LIST_ASSIGN) ?: return emptyMap()
        val o = objOf(json) ?: return emptyMap()
        val out = LinkedHashMap<String, Line>()
        for ((k, v) in o.entrySet()) {
            val e = v.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            out[k] = Line(
                ps = e.str("ps").trim().toIntOrNull() ?: 0,
                parse = e.str("parse").trim(),
                show = e.str("show").trim()
            )
        }
        return out
    }

    /** 抽全局 `MacPlayerConfig.parse`（线路自己的 `parse` 为空时的兜底，见 player.js） */
    fun parseGlobalParse(html: String?): String {
        if (html.isNullOrBlank()) return ""
        val json = objectAfter(html, CONFIG_ASSIGN) ?: return ""
        return objOf(json)?.str("parse")?.trim().orEmpty()
    }

    // ------------------------------------------------------------------ player_aaaa

    /** 抽 `player_xxxx` 并解码 `url` / `url_next`；抠不到返回 null */
    fun parseInfo(html: String?): Info? {
        if (html.isNullOrBlank()) return null
        val json = objectAfter(html, PLAYER_VAR) ?: return null
        return infoFromJson(json)
    }

    /** 同上，但入参已经是那段 JSON 本身（[Media] 抠出 body 后直接复用，不重复扫全页） */
    fun infoFromJson(json: String): Info? {
        val o = objOf(json) ?: return null
        val from = o.str("from")
        val rawUrl = o.str("url")
        val next = o.str("url_next")
        // 只认「播放数据对象」：三个关键字段一个都没有，就不是 player_aaaa。
        // 宁可返回 null 退回嗅探，也不要交出一个字段全空的假对象。
        if (from.isBlank() && rawUrl.isBlank() && next.isBlank()) return null
        val enc = o.str("encrypt").trim().toIntOrNull() ?: 0
        return Info(
            from = from,
            url = decodeUrl(rawUrl, enc),
            urlNext = decodeUrl(next, enc),
            link = o.str("link"),
            encrypt = enc
        )
    }

    /**
     * 按 `encrypt` 还原 `url`（对应 player.js 里那两行 unescape）。
     *
     * - `1`：JS `unescape` —— 把 `%XX` 还原成字节再按 UTF-8 解读。
     * - `2`：先 base64 解出字节串，再 `unescape`。
     * - 其它：原样返回。
     */
    fun decodeUrl(raw: String, encrypt: Int): String = when (encrypt) {
        1 -> pctDecode(raw)
        2 -> Base64Lite.decode(b64(raw))?.let { pctDecode(String(it, Charsets.UTF_8)) } ?: raw
        else -> raw
    }

    /**
     * 百分号解码。
     *
     * 与 `java.net.URLDecoder.decode(s, "UTF-8")` 等价**但不把 `+` 当成空格** ——
     * 媒体地址里 `+` 是合法字符，被吃掉会直接 404。
     */
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

    /** base64 可能是 URL-safe 字母表且缺 padding，先规范化再解 */
    private fun b64(raw: String): String {
        val t = raw.trim().replace('-', '+').replace('_', '/')
        return t + "=".repeat((4 - t.length % 4) % 4)
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun hex(c: Char) = Character.digit(c, 16)

    // ------------------------------------------------------------------ 推出真实播放页

    /**
     * 由 `player_aaaa` + `player_list` 推出「真正会去拉流的那一层地址」。
     *
     * 只有 `ps:1` 的线路才需要这一步：真地址 = **parse 模板原文 + 令牌**。
     * 站点自己的 `parse.js` 也是直接字符串相加（`MacPlayer.Parse + MacPlayer.PlayUrl`），
     * 所以这里同样不做 URL 编码 —— 编码反而会让站点认不出令牌。
     *
     * @return 推不出（自营线路、缺模板、缺令牌）返回 null
     */
    fun playPage(info: Info, lines: Map<String, Line>, globalParse: String): String? {
        val line = lines[info.from]
        if ((line?.ps ?: 0) != 1) return null
        val tpl = line?.parse?.takeIf { it.isNotBlank() }
            ?: globalParse.takeIf { it.isNotBlank() }
            ?: return null
        val token = info.url.trim()
        if (token.isBlank()) return null
        return tpl.trim() + token
    }

    // ------------------------------------------------------------------ 解析服务外壳（v1.0.30）

    /**
     * 第三方解析源的播放页外壳（`mui-player`）承载的取流凭据。
     *
     * `ps:1` 线路跟一层后拿到的是这种页面：正文只有一个 `#player-data`，真地址要
     * `POST {origin}{bt}mplayer.php`（表单 `url` + `token`）换回来。
     *
     * 实测（2026-09-19，zqkhmy 的 co / vwnet 两条线路）：
     * ```
     * 解析页 1293 B，4 个 script（jquery / mui-player / hls.js / md5.js）
     * <div id="player-data" data-u="co_yumkvzd6xi4agezyhm"
     *      data-te="4gKY…" data-v="8e35…" data-bt="/player/">
     * POST /player/mplayer.php  url=co_…&token=4gKY…
     * → {"code":200,"url":"https://cibn-edge-5g.1ljx.com/…/x.m3u8?auth_key=1789748995…"}
     * ```
     *
     * **`url` 与 `token` 都直接来自页面，不需要重算签名** —— 站点把
     * `data-*` 拼好后就摆在那儿，`md5.js`（jsjiami.cn.v7 混淆）只是干扰项。
     * 反过来，页面里那个 `auth_key` 有时效 ⇒ 只能**播放时现取**，绝不固化直链。
     */
    data class Shell(val u: String, val te: String, val bt: String)

    // `[^>]*` 会跨行 —— 真实页面的 data-* 每个一行，整个标签到 `style="display:none;">` 才结束。
    private val PLAYER_DATA_TAG =
        Regex("<div[^>]*id=[\"']player-data[\"'][^>]*>", RegexOption.IGNORE_CASE)
    private val SHELL_U = Regex("data-u\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val SHELL_TE = Regex("data-te\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val SHELL_BT = Regex("data-bt\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

    /**
     * 识别「解析服务外壳」。不是这种页面一律返回 null（调用方据此**不发任何请求**）。
     *
     * 只认带 `data-u` 的 `#player-data`：`data-u` 是视频号，缺了它接口必然要不到东西。
     */
    fun shellOf(html: String?): Shell? {
        if (html.isNullOrBlank()) return null
        val tag = PLAYER_DATA_TAG.find(html)?.value ?: return null
        val u = SHELL_U.find(tag)?.groupValues?.get(1)?.trim().orEmpty()
        if (u.isBlank()) return null
        val te = SHELL_TE.find(tag)?.groupValues?.get(1)?.trim().orEmpty()
        val bt = SHELL_BT.find(tag)?.groupValues?.get(1)?.trim().orEmpty()
        // `data-bt` 实测存在，但缺了也能按约定补 /player/
        return Shell(u = u, te = te, bt = bt.ifBlank { "/player/" })
    }

    /** 取流接口地址：`{origin}{bt}mplayer.php` */
    fun shellApiUrl(pageUrl: String, bt: String): String? {
        val origin = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE)
            .find(pageUrl.trim())?.groupValues?.get(1) ?: return null
        val base = bt.ifBlank { "/player/" }
        val dir = if (base.endsWith("/")) base else "$base/"
        return origin + dir + "mplayer.php"
    }

    /**
     * 从接口返回体里取真地址。
     *
     * 形状实测为 `{"code":200,"url":"…m3u8?auth_key=…","tradem":0,…}`；
     * `code` 不是 200（如 403 `{"code":403,"msg":"m3"}`）就不认，退回嗅探。
     *
     * ⚠️ 别信响应里的 `type` 字段（实测它的值是 `"mp4"`，给的却是 m3u8），
     * 是否 HLS 一律按地址判断。
     */
    fun jsonUrl(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val o = objOf(json.trim()) ?: return null
        val code = o.get("code")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (code.isNotBlank() && code != "200") return null
        val u = o.str("url").trim()
        return u.takeIf { it.startsWith("http") }
    }

    // ------------------------------------------------------------------ 站点级线路表

    /**
     * 站点级缓存：`host` → 线路表。
     *
     * 为什么需要：**大多数站的 `player_list` 不在播放页里**（实测 zqkhmy 的播放页
     * 120 KB，里面只有 `<script src="/static/js/playerconfig.js?t=…">`），
     * 真正的定义在外链的那个 JS 里。同一个站所有播放页共用一份配置 ⇒
     * 抓一次、按 host 记住，之后零成本。
     */
    private val listCache = HashMap<String, Map<String, Line>>()

    fun cachedLines(host: String): Map<String, Line>? = listCache[host]

    fun putLines(host: String, lines: Map<String, Line>) {
        if (host.isNotBlank() && lines.isNotEmpty()) listCache[host] = lines
    }

    /** 仅供离线 harness / 自检复位用 */
    fun clearCache() = listCache.clear()

    private val SCRIPT_SRC = Regex("<script[^>]+src=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

    /**
     * 从播放页 HTML 里找出 `playerconfig.js` 的**绝对**地址（找不到返回 null）。
     * 只认名字里带 `playerconfig` 的脚本 —— 别把站点另外几十个 JS 也抓一遍。
     */
    fun configScriptUrl(html: String?, pageUrl: String): String? {
        if (html.isNullOrBlank()) return null
        for (m in SCRIPT_SRC.findAll(html)) {
            val src = m.groupValues[1].trim()
            if (src.contains("playerconfig", true) || src.contains("player_config", true)) {
                return absUrl(src, pageUrl)
            }
        }
        return null
    }

    /** 把 `/x/y.js`、`//host/x.js`、`./x.js` 补成绝对地址（纯字符串处理，便于断言） */
    fun absUrl(src: String, page: String): String {
        val s = src.trim()
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        val origin = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE)
            .find(page.trim())?.groupValues?.get(1) ?: return s
        return when {
            s.startsWith("//") -> (if (origin.startsWith("https")) "https:" else "http:") + s
            s.startsWith("/") -> origin + s
            else -> origin + "/" + s.removePrefix("./")
        }
    }
}
