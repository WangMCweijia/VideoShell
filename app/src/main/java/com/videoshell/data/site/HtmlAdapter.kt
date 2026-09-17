package com.videoshell.data.site

import com.videoshell.data.model.Category
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.util.resolveUrl
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder

/**
 * 通用 HTML 适配器：没有标准接口的视频站兜底用。
 * 运行时逐个试 maccms 系常见 URL 模板，命中的记住复用；剧集地址多为播放页，交给 resolve() 抠地址或走嗅探。
 */
class HtmlAdapter(site: SiteConfig) : SiteAdapter(site) {

    private var listTpl: String? = null
    private var detailTpl: String? = null
    private var searchTpl: String? = null

    private val root: String get() = site.baseUrl.trimEnd('/')

    private fun build(tpl: String, id: String = "", page: Int = 1, kw: String = ""): String {
        var u = tpl
            .replace("{id}", id)
            .replace("{page}", "$page")
            .replace("{kw}", URLEncoder.encode(kw, "UTF-8"))
        if (!u.startsWith("http")) u = "$root/${u.trimStart('/')}"
        return u
    }

    private fun ordered(pref: String?, all: List<String>): List<String> =
        if (pref.isNullOrBlank()) all else listOf(pref) + all.filter { it != pref }

    override suspend fun categories(): List<Category> {
        val html = Http.getOrNull(site.baseUrl, referer = site.baseUrl) ?: return emptyList()
        val doc = Jsoup.parse(html, site.baseUrl)
        val out = LinkedHashMap<String, Category>()
        for (a in doc.select("a[href]")) {
            val id = HtmlTemplates.typeIdOf(a.attr("href")) ?: continue
            val name = a.text().trim()
            if (name.isBlank() || name.length > 12) continue
            if (!out.containsKey(id)) out[id] = Category(id, name, "0")
        }
        return out.values.take(80).toList()
    }

    override suspend fun browse(typeId: String, page: Int): List<VideoItem> {
        if (typeId.isBlank()) {
            val html = Http.getOrNull(site.baseUrl, referer = site.baseUrl) ?: return emptyList()
            return HtmlExtractor.parseList(Jsoup.parse(html, site.baseUrl), site.baseUrl)
        }
        for (tpl in ordered(listTpl, HtmlTemplates.listCandidates(root))) {
            val html = Http.getOrNull(build(tpl, id = typeId, page = page), referer = site.baseUrl) ?: continue
            val items = HtmlExtractor.parseList(Jsoup.parse(html, site.baseUrl), site.baseUrl)
            if (items.size >= 2) {
                listTpl = tpl
                return items
            }
        }
        return emptyList()
    }

    override suspend fun search(keyword: String, page: Int): List<VideoItem> {
        for (tpl in ordered(searchTpl, HtmlTemplates.searchCandidates(root))) {
            val html = Http.getOrNull(build(tpl, kw = keyword, page = page), referer = site.baseUrl) ?: continue
            val items = HtmlExtractor.parseList(Jsoup.parse(html, site.baseUrl), site.baseUrl)
            if (items.isNotEmpty()) {
                searchTpl = tpl
                return items
            }
        }
        return emptyList()
    }

    override suspend fun detail(id: String): VideoDetail {
        for (tpl in ordered(detailTpl, HtmlTemplates.detailCandidates(root))) {
            val url = build(tpl, id = id)
            val html = Http.getOrNull(url, referer = site.baseUrl) ?: continue
            val doc = Jsoup.parse(html, site.baseUrl)
            val groups = HtmlExtractor.parseGroups(doc, site.baseUrl)
            if (groups.isEmpty()) continue

            detailTpl = tpl
            val name = doc.title().split("_", "-", "|", " ").firstOrNull()?.trim().orEmpty()
            val pic = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            val summary = doc.selectFirst(
                ".module-info-introduction-content, .vod_content, .detail-desc, #desc, .content, .stui-content__desc"
            )?.text()?.trim().orEmpty()

            return VideoDetail(
                id = id,
                name = name,
                pic = resolveUrl(site.baseUrl, pic),
                summary = summary,
                groups = groups
            )
        }
        throw IOException("未能解析该影片详情（HTML 模板不匹配，可尝试网页嗅探播放）")
    }
}
