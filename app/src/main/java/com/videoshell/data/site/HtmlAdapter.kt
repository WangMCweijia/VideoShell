package com.videoshell.data.site

import com.videoshell.data.model.Category
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.util.resolveUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import java.io.IOException
import java.net.URLEncoder

/**
 * 通用 HTML 适配器：没有标准采集接口的视频站兜底用。
 *
 * 与旧实现的区别：
 * 1) 分类项携带站点自己的真实 URL，浏览时**直接用该 URL**，不再靠猜模板 —— 命中率与首屏体验都稳得多；
 * 2) 卡片必须有图，导航链接（电影/电视剧…）不再被当成影片；
 * 3) 分页用「已见 id 集合」去重，站点忽略 page 参数时自动收尾，不会无限翻页。
 */
class HtmlAdapter(site: SiteConfig) : SiteAdapter(site) {

    private var listTpl: String? = null
    private var detailTpl: String? = null
    private var searchTpl: String? = null
    private var playTpl: String? = null

    /** 本站是否走「独立详情页 + 独立播放页」结构（决定 `/vod/{id}.html` 是分类还是详情） */
    @Volatile
    private var vodIsCategory = false

    /** 分类解析结果缓存 */
    private var cachedCats: List<Category> = emptyList()

    /**
     * 从列表页学到的详情页模板（`/movie/{id}.html` 之类）。
     * 比穷举 [HtmlTemplates.detailCandidates] 可靠得多，优先级也更高。
     */
    private var learnedDetailTpl: String? = null

    /** 分类为空时把原因带出去，让 UI 能显示出来（不然只能靠猜） */
    private var diag: String = ""
    override val lastDiag: String get() = diag

    /** 当前浏览目标的已见影片 id，用于分页去重 */
    private val seenIds = HashSet<String>()
    private var seenKey = ""

    private val root: String get() = site.baseUrl.trimEnd('/')

    // ------------------------------------------------------------------ 分类

    /** 导航语义容器（越靠前越像"分类标签"） */
    private val navSelectors = listOf(
        ".main_nav a", ".tab_head a", ".nav-list a", "header nav a", "nav a",
        ".navbar a", ".nav_bar a", ".menu a", "#nav a", ".header-nav a", "header .nav a", "header a"
    )

    private val catBlacklist = setOf(
        "首页", "全部", "更多", "排行", "排行榜", "登录", "注册", "求片", "留言",
        "历史", "专题", "关于", "反馈", "APP", "手机版", "换一换", "最近更新"
    )

    /** 名称里带这些字的一律不算分类（公告/求片/备用站点之类的功能页） */
    private val catBadWords = listOf(
        "公告", "须知", "关于", "求片", "留言", "备用", "加入", "Telegram",
        "客服", "声明", "版权", "筛选"
    )

    /**
     * 目录式分类（`/meijutt`、`/riju`…）**只在这些容器里认**。
     * 放到全文档扫会把 `/gbook`、`/label`、`/user` 这类功能页也收进来。
     *
     * 注意：一个站往往有**好几套**导航（顶部主菜单 / 二级"分类"面板 / 底部导航），
     * 分类会分散在里面 —— 厂长资源的「日剧」就只存在于 `ul.submenu_mi` 这套里，
     * 所以下面这些容器要**全部**扫一遍，不能命中一个就收工。
     */
    private val slugNavContainers = listOf(
        ".navlist", ".navtop", ".nav-list", ".navlist-content", ".nav_menu", ".nav-menu",
        ".navbar", ".nav_bar", ".nav-bar", ".top-nav", ".head-nav", ".header-nav",
        ".submenu_mi", ".v-sort-nav", ".sort-nav", ".cate-nav", ".category-nav",
        ".footnav", ".footer_nav",
        "#nav", "nav", ".nav", ".menu", "header"
    )

    override suspend fun categories(): List<Category> {
        if (cachedCats.isNotEmpty()) return cachedCats
        diag = ""

        // 1) 首页导航 —— 最理想，直接就是站点自己的分类标签
        //    多地址重试：http/https、www/裸域 都试一遍（有的站在某些网络上只认其中一种）
        var lastErr = ""
        var sawHome = 0
        for (base in baseCandidates()) {
            val home = fetch(base)
            if (home == null) continue
            sawHome = home.length
            val list = try {
                categoriesFrom(Jsoup.parse(home, base))
            } catch (e: Exception) {
                lastErr = e.javaClass.simpleName + ": " + e.message
                emptyList()
            }
            if (list.size >= 2) {
                cachedCats = list
                return list
            }
            diag = "首页 ${home.length} 字，分类 ${list.size} 个"
        }
        if (sawHome == 0) diag = "首页请求失败" + if (lastErr.isBlank()) "" else "：$lastErr"

        // 2) 兜底：有的站首页是纯 JS 渲染（导航藏在脚本里），但列表页有静态导航。
        //    用列表模板探一遍，能拿到就用，拿不到也不影响"最新"浏览。
        for (tpl in HtmlTemplates.listCandidates(root).take(5)) {
            val url = build(tpl, id = "1", page = 1)
            val html = fetch(url) ?: continue
            val doc = Jsoup.parse(html, site.baseUrl)
            val list = categoriesFrom(doc)
            if (list.size >= 2) {
                cachedCats = list
                return list
            }
        }
        if (diag.isBlank()) diag = "首页与候选列表页都没解析到分类"
        return emptyList()
    }

    /**
     * 首页地址候选：原地址 → 换 www/裸域 → 换协议。
     * 有些站在特定网络下只认其中一种，多试一次成本很低，却能救回整个站。
     */
    private fun baseCandidates(): List<String> {
        val b = site.baseUrl.trimEnd('/')
        val out = LinkedHashSet<String>()
        out.add(b)
        val host = hostOf(b)
        if (host.isNotBlank()) {
            val alt = when {
                host.startsWith("www.") -> host.removePrefix("www.")
                else -> "www.$host"
            }
            out.add(b.replaceFirst(host, alt))
        }
        val swapped = if (b.startsWith("https://")) b.replaceFirst("https://", "http://")
        else b.replaceFirst("http://", "https://")
        out.add(swapped)
        return out.filter { it.length > 8 }.toList()
    }

    /** 带一次重试的抓取：首屏分类偶发超时会直接让分类栏消失，这里补一次 */
    private suspend fun fetch(url: String): String? {
        Http.getOrNull(url, referer = site.baseUrl)?.let { return it }
        return Http.getOrNull(url, referer = site.baseUrl)
    }

    /** 从首页 DOM 里解析分类标签（独立成函数便于离线校验） */
    fun categoriesFrom(doc: Document): List<Category> {
        vodIsCategory = detectVodShape(doc)

        val out = LinkedHashMap<String, Category>()

        // 1) 优先在导航容器里找 —— 最贴近站点自身的分类标签
        for (sel in navSelectors) {
            val anchors = doc.select(sel)
            if (anchors.isEmpty()) continue
            collectCategories(anchors, out)
            if (out.size >= 2) return out.values.take(40).toList()
        }

        // 2) 目录式分类：WordPress 系（厂长资源那类）用自定义分类别名做分类页，
        //    URL 既没有 vodshow 也没有数字，第 1 步一条都认不出来。
        collectSlugCategories(doc, out)
        if (out.size >= 2) return out.values.take(40).toList()

        // 3) 兜底：全文档扫（仍要求是无图纯文字链接）
        out.clear()
        collectCategories(doc.select("a[href]"), out)
        if (out.size < 2) collectSlugCategories(doc, out)
        if (out.size >= 2) return out.values.take(40).toList()

        // 4) 最后兜底：强行把 `/vod/{id}.html` 当分类再扫一遍。
        //    `/vod/{id}.html` 是分类还是详情，全靠"站点有没有独立详情页"来消歧；
        //    详情 URL 五花八门，判据有认不出的时候 —— 那时分类栏就会空着。
        //    这里加「名字短且不含数字」的限制，避免把一屏影片名当成分类标签。
        val alt = LinkedHashMap<String, Category>()
        collectCategories(doc.select("a[href]"), alt, forceCategory = true)
        val clean = alt.filterValues { it.name.length <= 6 && it.name.none { ch -> ch.isDigit() } }
        return clean.values.take(40).toList()
    }

    /** 目录式分类（`/meijutt`、`/riju`…）：只在导航容器内认，避免收进 `/gbook` 这类功能页 */
    private fun collectSlugCategories(doc: Document, out: LinkedHashMap<String, Category>) {
        val host = hostOf(site.baseUrl)
        // 遍历**全部**导航容器：同一个站的分类会分散在主导航/顶部栏/底部导航里，只取第一个必然漏
        for (sel in slugNavContainers) {
            for (box in doc.select(sel)) {
                for (a in box.select("a[href]")) {
                    if (a.selectFirst("img") != null) continue
                    val href = a.attr("href").trim()
                    if (!HtmlTemplates.isSlugCategory(href)) continue

                    val name = a.text().replace(Regex("\\s+"), " ").trim()
                    if (name.isBlank() || name.length > 10) continue
                    if (name in catBlacklist) continue
                    if (catBadWords.any { name.contains(it) }) continue

                    val url = abs(href)
                    if (host.isNotBlank() && !hostOf(url).equals(host, true)) continue
                    if (out.containsKey(url)) continue
                    out[url] = Category(url, name, "0")
                }
            }
        }
    }

    private fun collectCategories(
        anchors: Elements,
        out: LinkedHashMap<String, Category>,
        forceCategory: Boolean = false
    ) {
        for (a in anchors) {
            if (a.selectFirst("img") != null) continue
            val href = a.attr("href").trim()
            if (href.isEmpty()) continue
            if (href.startsWith("javascript") || href.startsWith("#") || href.startsWith("mailto")) continue
            if (!HtmlTemplates.isCategoryHref(href, vodIsCategory || forceCategory)) continue

            val name = a.text().replace(Regex("\\s+"), " ").trim()
            if (name.isBlank() || name.length > 10) continue
            if (name in catBlacklist) continue
            if (name.endsWith(":") || name.endsWith("：")) continue

            val url = abs(href)
            if (out.containsKey(url)) continue
            out[url] = Category(url, name, "0")
        }
    }

    // ------------------------------------------------------------------ 浏览

    override suspend fun browse(typeId: String, page: Int): List<VideoItem> {
        val ref = typeId.trim()
        val urls = browseUrls(ref, page)
        if (urls.isEmpty()) return emptyList()

        val k = "browse|$ref"
        if (k != seenKey) {
            seenKey = k
            seenIds.clear()
        }
        return fetchList(urls, page)
    }

    private fun browseUrls(ref: String, page: Int): List<String> {
        if (ref.startsWith("http") || ref.startsWith("/")) {
            val u = abs(ref)
            if (page <= 1) return listOf(u)
            // 分页：先试模板（能从 URL 里抠出分类 id 时），再试 ?page=N，最后回落原页（靠去重收尾）
            val id = HtmlTemplates.typeIdOf(u, vodIsCategory)
            val tpls = if (id == null) emptyList()
            else ordered(listTpl, HtmlTemplates.listCandidates(root)).map { build(it, id = id, page = page) }
            // 目录式分类（/meijutt）没有数字 id，WordPress 系的分页是 /page/N
            val slugPage = if (id == null) listOf("$u/page/$page", "$u/page/$page/") else emptyList()
            return tpls + slugPage + listOf(withQueryPage(u, page), u)
        }
        if (ref.isBlank()) {
            // 空 id = 首页"最新"，站点首页没有分页
            return if (page <= 1) listOf(site.baseUrl) else emptyList()
        }
        return ordered(listTpl, HtmlTemplates.listCandidates(root))
            .map { build(it, id = ref, page = page) }
    }

    // ------------------------------------------------------------------ 搜索

    override suspend fun search(keyword: String, page: Int): List<VideoItem> {
        val k = "search|$keyword"
        if (k != seenKey) {
            seenKey = k
            seenIds.clear()
        }
        for (tpl in ordered(searchTpl, HtmlTemplates.searchCandidates(root))) {
            val html = Http.getOrNull(build(tpl, kw = keyword, page = page), referer = site.baseUrl) ?: continue
            val doc = Jsoup.parse(html, site.baseUrl)
            rememberShape(doc)
            val fresh = accept(HtmlExtractor.parseList(doc, site.baseUrl, vodIsCategory), page)
            if (fresh == null) continue
            learnDetailTpl(doc)
            searchTpl = tpl
            return fresh
        }
        return emptyList()
    }

    // ------------------------------------------------------------------ 详情

    override suspend fun detail(id: String): VideoDetail {
        // 列表页学到的模板优先：它一定对得上本站的路径约定
        val pref = learnedDetailTpl ?: detailTpl
        for (tpl in ordered(pref, HtmlTemplates.detailCandidates(root))) {
            val html = fetch(build(tpl, id = id)) ?: continue
            val doc = Jsoup.parse(html, site.baseUrl)
            rememberShape(doc)
            val groups = HtmlExtractor.parseGroups(doc, site.baseUrl)
            if (groups.isEmpty()) continue

            detailTpl = tpl
            return buildDetail(id, doc, groups)
        }
        // 兜底：不少主题的**详情页只有海报和简介，分集列表只在播放页**。
        // 用 play 模板探一次，从中把分集捞出来。
        detailFromPlayPage(id)?.let { return it }

        throw IOException("未能解析该影片详情（HTML 模板不匹配，可尝试网页嗅探播放）")
    }

    private fun buildDetail(id: String, doc: Document, groups: List<PlayGroup>): VideoDetail =
        VideoDetail(
            id = id,
            name = HtmlExtractor.parseTitle(doc),
            pic = resolveUrl(site.baseUrl, HtmlExtractor.parsePic(doc)),
            summary = HtmlExtractor.parseSummary(doc),
            groups = groups
        )

    private suspend fun detailFromPlayPage(id: String): VideoDetail? {
        for (tpl in orderPlay(HtmlTemplates.playCandidates(root))) {
            val html = fetch(build(tpl, id = id)) ?: continue
            val doc = Jsoup.parse(html, site.baseUrl)
            val groups = HtmlExtractor.parseGroups(doc, site.baseUrl)
            if (groups.isEmpty()) continue
            playTpl = tpl
            return buildDetail(id, doc, groups)
        }
        return null
    }

    private fun orderPlay(all: List<String>): List<String> =
        if (playTpl.isNullOrBlank()) all else listOf(playTpl!!) + all.filter { it != playTpl }

    // ------------------------------------------------------------------ 内部

    /**
     * 过滤分页结果：
     * - 返回 null 表示"这个 URL 不适合当前页"，去试下一个候选；
     * - 返回空列表表示"没有新内容了"，前端据此收尾。
     */
    private fun accept(items: List<VideoItem>, page: Int): List<VideoItem>? {
        if (items.size < 2) return null
        if (page <= 1) {
            seenIds.clear()
            seenIds.addAll(items.map { it.id })
            return items
        }
        val fresh = items.filter { it.id !in seenIds }
        if (fresh.isEmpty()) return emptyList()
        seenIds.addAll(fresh.map { it.id })
        return fresh
    }

    private suspend fun fetchList(urls: List<String>, page: Int): List<VideoItem> {
        for (u in urls) {
            val html = Http.getOrNull(u, referer = site.baseUrl) ?: continue
            val doc = Jsoup.parse(html, site.baseUrl)
            rememberShape(doc)
            val fresh = accept(HtmlExtractor.parseList(doc, site.baseUrl, vodIsCategory), page)
            if (fresh == null) continue
            learnDetailTpl(doc)
            if (fresh.isEmpty()) return emptyList()
            if (urls.size > 1 && u == urls.first()) listTpl = guessTpl(u, page)
            return fresh
        }
        return emptyList()
    }

    /** 从列表页学一条详情页模板（只学一次） */
    private fun learnDetailTpl(doc: Document) {
        if (learnedDetailTpl != null) return
        learnedDetailTpl = HtmlExtractor.detailTplHint(doc, site.baseUrl, vodIsCategory)
    }

    /** 首页/列表页第一次解析时确定站点形态，之后沿用 */
    private fun rememberShape(doc: Document) {
        if (!vodIsCategory) vodIsCategory = detectVodShape(doc)
    }

    private fun detectVodShape(doc: Document): Boolean {
        for (a in doc.select("a[href]")) {
            if (HtmlTemplates.isDetailSignal(a.attr("href"))) return true
        }
        return false
    }

    /** 值不大，够用：命中过就记住，避免每页重复试错 */
    private fun guessTpl(url: String, page: Int): String? {
        val id = HtmlTemplates.typeIdOf(url, vodIsCategory) ?: return null
        for (tpl in HtmlTemplates.listCandidates(root)) {
            if (build(tpl, id = id, page = page) == url) return tpl
        }
        return null
    }

    private fun abs(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return root
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:$u"
        return "$root/${u.trimStart('/')}"
    }

    private fun hostOf(url: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1).orEmpty()

    private fun withQueryPage(url: String, page: Int): String =
        url + (if (url.contains("?")) "&" else "?") + "page=$page"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun build(tpl: String, id: String = "", page: Int = 1, kw: String = ""): String {
        val hasPageToken = tpl.contains("{page}")
        var u = tpl
            .replace("{id}", id)
            .replace("{page}", "$page")
            .replace("{kw}", enc(kw))
        if (!u.startsWith("http")) u = "$root/${u.trimStart('/')}"
        if (page > 1 && !hasPageToken) u = withQueryPage(u, page)
        return u
    }

    private fun ordered(pref: String?, all: List<String>): List<String> =
        if (pref.isNullOrBlank()) all else listOf(pref) + all.filter { it != pref }
}
