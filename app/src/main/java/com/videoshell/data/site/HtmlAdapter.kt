package com.videoshell.data.site

import com.videoshell.data.model.Category
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

    /** 本站是否走「独立详情页 + 独立播放页」结构（决定 `/vod/{id}.html` 是分类还是详情） */
    @Volatile
    private var vodIsCategory = false

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

    override suspend fun categories(): List<Category> {
        val html = Http.getOrNull(site.baseUrl, referer = site.baseUrl) ?: return emptyList()
        return categoriesFrom(Jsoup.parse(html, site.baseUrl))
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

        // 2) 兜底：全文档扫（仍要求是无图纯文字链接）
        if (out.size < 2) {
            out.clear()
            collectCategories(doc.select("a[href]"), out)
        }
        return out.values.take(40).toList()
    }

    private fun collectCategories(anchors: Elements, out: LinkedHashMap<String, Category>) {
        for (a in anchors) {
            if (a.selectFirst("img") != null) continue
            val href = a.attr("href").trim()
            if (href.isEmpty()) continue
            if (href.startsWith("javascript") || href.startsWith("#") || href.startsWith("mailto")) continue
            if (!HtmlTemplates.isCategoryHref(href, vodIsCategory)) continue

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
            return tpls + listOf(withQueryPage(u, page), u)
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
            searchTpl = tpl
            return fresh
        }
        return emptyList()
    }

    // ------------------------------------------------------------------ 详情

    override suspend fun detail(id: String): VideoDetail {
        for (tpl in ordered(detailTpl, HtmlTemplates.detailCandidates(root))) {
            val html = Http.getOrNull(build(tpl, id = id), referer = site.baseUrl) ?: continue
            val doc = Jsoup.parse(html, site.baseUrl)
            val groups = HtmlExtractor.parseGroups(doc, site.baseUrl)
            if (groups.isEmpty()) continue

            detailTpl = tpl
            return VideoDetail(
                id = id,
                name = HtmlExtractor.parseTitle(doc),
                pic = resolveUrl(site.baseUrl, HtmlExtractor.parsePic(doc)),
                summary = HtmlExtractor.parseSummary(doc),
                groups = groups
            )
        }
        throw IOException("未能解析该影片详情（HTML 模板不匹配，可尝试网页嗅探播放）")
    }

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
            if (fresh.isEmpty()) return emptyList()
            if (urls.size > 1 && u == urls.first()) listTpl = guessTpl(u, page)
            return fresh
        }
        return emptyList()
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
        return "$root/${u.trimStart('/')}"
    }

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
