package com.videoshell.data.site

import com.videoshell.data.model.Category
import com.videoshell.data.model.Episode
import com.videoshell.data.model.MediaSource
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http

/** 站点适配器基类：一个视频站 = 一个适配器 */
abstract class SiteAdapter(val site: SiteConfig) {

    abstract suspend fun categories(): List<Category>

    abstract suspend fun browse(typeId: String, page: Int): List<VideoItem>

    abstract suspend fun search(keyword: String, page: Int): List<VideoItem>

    abstract suspend fun detail(id: String): VideoDetail

    /**
     * 上一次「分类解析失败」的原因（空串 = 无法给出原因或解析成功）。
     * 分类栏空着却没头绪时，把它显示出来，用户一眼就知道是网络还是解析问题。
     */
    open val lastDiag: String get() = ""

    // ------------------------------------------------------------------ v1.0.25 新增能力

    /**
     * 用**已经拿到的 HTML** 解析列表（不发请求）。
     * 供 UI 层的"预渲染兜底"使用：HTML 适配器解析不到卡片时，UI 用 WebView 把页面
     * 真正跑一遍（JS 渲染完的 DOM），再把 outerHTML 送回这里解析。
     */
    open fun parseListFromHtml(html: String, page: Int): List<VideoItem> = emptyList()

    /** 同上，解析详情（预渲染兜底用） */
    open fun parseDetailFromHtml(html: String): VideoDetail? = null

    /** 该站「搜索」会把请求打到哪个地址（预渲染兜底要知道开哪个页面） */
    open fun searchUrlFor(keyword: String, page: Int): String? = null

    /** 该站「分类浏览」的目标地址（预渲染兜底用） */
    open fun browseUrlFor(typeId: String, page: Int): String? = null

    /** 该站「详情页」的地址（预渲染兜底用）；拿不到返回 null */
    open fun detailUrlFor(id: String): String? = null

    /**
     * 这个适配器**认不认**「预渲染兜底」（v1.0.25）。
     *
     * 接口型适配器（采集接口 / 加密接口）的数据根本不来自 HTML —— 让 UI 再开一次 WebView
     * 把首页跑一遍，只会白等几秒并且必然解析不出东西。默认 false，只有 [HtmlAdapter] 打开。
     */
    open val supportsWebRender: Boolean get() = false

    fun playHeaders(): Map<String, String> = mapOf(
        "User-Agent" to Http.UA,
        "Referer" to site.baseUrl
    )

    /**
     * 把剧集地址解析为可直接交给播放器的地址：
     * 1) 本身是 m3u8/mp4 -> 直接用
     * 2) 是播放页 -> 尝试直接从 HTML 里抠真实地址（快，不用 WebView）
     * 3) 页面把地址交给第三方 **jx 解析接口** -> 跟过去取流
     * 4) 页面用 maccms 的 **player_list 解析模板**（`ps:1`）-> 跟到解析页再抠一次（v1.0.29）
     * 5) 都抠不到 -> 交给网页嗅探
     */
    open suspend fun resolve(episode: Episode): MediaSource {
        val u = episode.url.trim()
        if (u.isEmpty()) return MediaSource.Error("播放地址为空")
        // ⚠️ 必须过 encodeUrl —— 这是「自检 200、播放 404」的全部原因：
        // 媒体路径经常含中文（如 /video/bianshuiwangshi/第01集/index.m3u8），
        // 自检走 OkHttp（自动百分号编码），播放走 ExoPlayer 的 DefaultHttpDataSource
        // → HttpURLConnection（**不编码**，把中文原样塞进请求行）→ CDN 404。
        if (Media.isDirect(u)) return MediaSource.Direct(Media.encodeUrl(u), playHeaders(), Media.isHls(u))
        if (!u.startsWith("http")) return MediaSource.Error("无法识别的播放地址：$u")

        var pageUrl = u
        var html = Http.getOrNull(pageUrl, referer = site.baseUrl)
        Media.extractFromHtml(html)?.takeIf { it.isNotBlank() }?.let {
            return MediaSource.Direct(Media.encodeUrl(it), playHeaders(), Media.isHls(it))
        }
        // 解析服务「mui-player 外壳」（v1.0.30）：页面本身就是它时直接换地址。
        // 不是这种页面 shellOf() 返回 null ⇒ 一个多余请求都不会发（自门控）。
        shellDirect(html, pageUrl)?.let {
            return MediaSource.Direct(Media.encodeUrl(it), playHeaders(), Media.isHls(it))
        }
        // jx 解析接口跟随：地址本身是解析接口，或页面里引用了它
        val jx = JxParser.findJxUrl(html).orEmpty().ifBlank {
            if (JxParser.isJxUrl(u)) u else ""
        }
        if (jx.isNotBlank()) {
            val stream = JxParser.follow(jx, site.baseUrl)
            if (!stream.isNullOrBlank()) {
                return MediaSource.Direct(Media.encodeUrl(stream), playHeaders(), Media.isHls(stream))
            }
        }
        // maccms 第三方解析源跟随（v1.0.29）。
        // 这类线路的 `player_aaaa.url` 只是一个**令牌**（`co_e5a2tzd6xi4age3dnjrq`），
        // 真地址在 `parse 模板 + 令牌` 指向的那一层。实测 zqkhmy 6 条线路里 3 条如此，
        // 以前全数落到嗅探；跟一层就能让其中"解析服务是明文"的站直接播。
        val host = RecipeStore.hostOf(site.baseUrl)
        for (i in 0 until MacPlayer.FOLLOW_MAX) {
            val info = MacPlayer.parseInfo(html) ?: break
            val next = MacPlayer.playPage(
                info,
                linesForPlay(html, pageUrl, host),
                MacPlayer.parseGlobalParse(html)
            ) ?: break
            if (next == pageUrl) break
            val nextHtml = Http.getOrNull(next, referer = pageUrl) ?: break
            Media.extractFromHtml(nextHtml)?.takeIf { it.isNotBlank() }?.let {
                return MediaSource.Direct(Media.encodeUrl(it), playHeaders(), Media.isHls(it))
            }
            // 解析服务的播放页外壳：POST 它的 mplayer.php 换真地址（v1.0.30）
            shellDirect(nextHtml, next)?.let {
                return MediaSource.Direct(Media.encodeUrl(it), playHeaders(), Media.isHls(it))
            }
            pageUrl = next
            html = nextHtml
        }
        // v1.0.31：壳并不都是 maccms 线路 —— 还有两种真地址藏在壳里的形状：
        //   ① 页面本身就是「引导对象 + JSON 接口」壳（骚火 hhplayer）；
        //   ② 播放器被塞进 iframe，真地址全在那一层（也是骚火这一站的形态）。
        // 两类都自门控（[PlayerShell] 认不出就返回 null），非此类站零额外请求。
        bootDirect(html, pageUrl)?.let {
            return MediaSource.Direct(Media.encodeUrl(it), playHeaders(), Media.isHls(it))
        }
        val frame = PlayerShell.iframeOf(html, pageUrl)
        if (!frame.isNullOrBlank() && frame != pageUrl) {
            val frameHtml = Http.getOrNull(frame, referer = pageUrl)
            if (frameHtml != null) {
                // iframe 那一层可能是明文地址，也可能是另一种壳 —— 三种判据都试一遍
                Media.extractFromHtml(frameHtml)?.takeIf { it.isNotBlank() }?.let {
                    return MediaSource.Direct(Media.encodeUrl(it), playHeaders(), Media.isHls(it))
                }
                shellDirect(frameHtml, frame)?.let {
                    return MediaSource.Direct(Media.encodeUrl(it), playHeaders(), Media.isHls(it))
                }
                bootDirect(frameHtml, frame)?.let {
                    return MediaSource.Direct(Media.encodeUrl(it), playHeaders(), Media.isHls(it))
                }
            }
        }
        // 嗅探目标仍是**原始播放页**：WebView 打开它会自然带上正确的 Referer 并完成跳转，
        // 换成解析页虽然少一跳，但解析页可能校验 Referer —— 不为未验证的收益引入回归。
        return MediaSource.Sniff(u, playHeaders())
    }

    /**
     * 取本站的 `player_list`（`from` → `ps` / `parse`）。
     *
     * 顺序：播放页内联 → 站点缓存 → 跟着 `playerconfig.js` 抓一次。
     *
     * **大多数站的配置不在播放页里**，而在外链的 `/static/js/playerconfig.js`：
     * 实测 zqkhmy 的播放页 120 KB，里面只有 `<script src="/static/js/playerconfig.js?t=…">`。
     * 同一站所有播放页共用一份配置，所以抓一次按 host 记下来，之后不再发请求。
     *
     * 只有「页面里确实出现了 `player_aaaa`」时调用方才会走到这里，
     * 因此对非 maccms 站**不会**多出任何请求。
     */
    private suspend fun linesForPlay(
        html: String?,
        pageUrl: String,
        host: String
    ): Map<String, MacPlayer.Line> {
        val inline = MacPlayer.parseLines(html)
        if (inline.isNotEmpty()) return inline
        MacPlayer.cachedLines(host)?.let { return it }
        val js = MacPlayer.configScriptUrl(html, pageUrl) ?: return emptyMap()
        val text = Http.getOrNull(js, referer = pageUrl) ?: return emptyMap()
        val lines = MacPlayer.parseLines(text)
        MacPlayer.putLines(host, lines)
        return lines
    }

    /**
     * 解析服务「mui-player 外壳」→ 真地址（v1.0.30）。
     *
     * 这是「点某一集不能直接播、只能靠嗅探」里**最后剩的那一类**：站点把第三方解析源
     * （`ps:1`）的播放页整个嵌进来，而那个播放页自己也不含地址 —— 它只摆一个
     * `#player-data`，真地址得 `POST {origin}{bt}mplayer.php` 换。
     *
     * 两道门保证「不该发就不发」：
     * 1. [MacPlayer.shellOf] 认不出外壳 ⇒ 直接 null，**零请求**；
     * 2. 请求失败 / `code != 200` / 返回体里没有 http 地址 ⇒ null，退回嗅探（等价修复前行为）。
     */
    private suspend fun shellDirect(html: String?, pageUrl: String): String? {
        val sh = MacPlayer.shellOf(html) ?: return null
        val api = MacPlayer.shellApiUrl(pageUrl, sh.bt) ?: return null
        val body = LinkedHashMap<String, String>()
        body["url"] = sh.u
        if (sh.te.isNotBlank()) body["token"] = sh.te
        val json = Http.postFormOrNull(api, body, referer = pageUrl) ?: return null
        return MacPlayer.jsonUrl(json)
    }

    /**
     * 「引导对象 + JSON 接口」壳 → 真地址（v1.0.31，骚火 hhplayer）。
     *
     * 页面把 `url` / `t` / `key` 明文摆进 `window.__XXX__={...}`，混淆 JS 再
     * `POST {origin}/api/parse` 换真地址。**令牌是页面现成给的，不用重算签名**
     * （页面里那些混淆 JS 是诱饵，两次踩的经验都是"别去反混淆，看页面就行"）。
     *
     * 门控同 [shellDirect]：[PlayerShell.bootOf] 认不出（缺 `url` 或 `key`）⇒ 零请求。
     * ⚠️ 换回来的地址带 `expires` / `sign`，有 TTL ⇒ 只当次取用，绝不写进配方。
     */
    private suspend fun bootDirect(html: String?, pageUrl: String): String? {
        val boot = PlayerShell.bootOf(html) ?: return null
        val api = PlayerShell.bootApiUrl(pageUrl) ?: return null
        val json = Http.postJsonOrNull(api, PlayerShell.bootJson(boot), referer = pageUrl) ?: return null
        return PlayerShell.jsonUrl(json)
    }
}
