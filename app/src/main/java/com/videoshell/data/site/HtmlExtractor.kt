package com.videoshell.data.site

import com.videoshell.data.model.Episode
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.VideoItem
import com.videoshell.util.resolveUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * HTML 站点的通用抽取器（尽力而为：按常见 maccms 主题的 DOM 约定抓取）。
 *
 * 两条铁律：
 * 1) **影片卡片必须自带图片**。导航「电影/电视剧/综艺/动漫」这类纯文字链接没有 `<img>`，
 *    也因此必须禁止从祖先容器里"借"图片 —— 否则会把站点 logo 当成海报，把导航当成影片。
 * 2) **线路名按 tab 的 `href="#playlistN"` 映射**，不要靠 DOM 顺序猜（本站 tab 顺序与容器顺序并不一致）。
 */
object HtmlExtractor {

    private val PIC_ATTRS = listOf("data-original", "data-src", "data-echo", "data-lazy", "src")
    private val BG_ATTRS = listOf("data-bg", "data-background", "data-background-image")

    /** 轮播/slide 类主题会把海报写成 CSS background-image */
    private val BG_STYLE = Regex(
        "background(?:-image)?\\s*:\\s*url\\(\\s*['\"]?([^)'\"]+?)\\s*['\"]?\\s*\\)",
        RegexOption.IGNORE_CASE
    )

    private val NAME_SELECTORS = listOf(
        ".module-card-item-title",
        ".module-poster-item-title",
        ".info-title-box .title",
        ".card-info .title",
        ".vodlist_title",
        ".video-title",
        ".vtitle",
        ".title",
        "h3",
        "h4",
        "h5",
        ".name"
    )

    private val NOTE_SELECTORS = listOf(
        ".module-item-note",
        ".module-item-text",
        ".pic-text",
        ".public-list-prb",
        ".tag-box .tag",
        ".vodtag",
        ".v-note",
        ".note",
        ".time-title"
    )

    /** 明显不是影片名的导航词 */
    private val BAD_NAMES = setOf(
        "首页", "全部", "更多", "排行", "排行榜", "登录", "注册", "求片", "留言",
        "历史", "专题", "关于", "反馈", "APP", "手机版", "换一换"
    )

    // ------------------------------------------------------------------ 列表

    fun parseList(doc: Document, base: String, vodIsCategory: Boolean = false): List<VideoItem> {
        val out = LinkedHashMap<String, VideoItem>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            if (href.isEmpty()) continue
            if (href.startsWith("javascript") || href.startsWith("#") || href.startsWith("mailto")) continue

            val id = HtmlTemplates.videoIdOf(href, vodIsCategory) ?: continue
            if (out.containsKey(id)) continue

            val img = pickImage(a)
            var pic = img?.let { picOf(it) }.orEmpty()
            // 有些主题把懒加载图放在 <a> 自身（stui: a[data-original]），也认
            if (pic.isBlank()) pic = picOf(a)
            // 没图的必须是「强详情链接」才认，否则一律视为导航/功能链接
            if (pic.isBlank() && !HtmlTemplates.isStrongDetail(href)) continue

            val name = pickName(a, img) ?: continue
            if (name in BAD_NAMES) continue

            out[id] = VideoItem(
                id = id,
                name = name,
                pic = resolveUrl(base, pic),
                remarks = pickRemarks(a)
            )
        }
        return out.values.toList()
    }

    /**
     * 取卡片图片：只认锚点自己的后代，或往上走最多两层、且该层不含其它锚点
     * （不含其它锚点 = 它是这张卡片自己的包裹层，不是导航容器）。
     */
    private fun pickImage(a: Element): Element? {
        a.selectFirst("img")?.let { return it }
        var p: Element? = a.parent()
        var depth = 0
        while (p != null && depth < 2) {
            if (p.select("a[href]").size > 1) return null
            p.selectFirst("img")?.let { return it }
            p = p.parent()
            depth++
        }
        return null
    }

    private fun picOf(el: Element): String {
        for (at in PIC_ATTRS) {
            val v = el.attr(at).trim()
            if (v.isNotBlank() && !v.startsWith("data:")) return v
        }
        for (at in BG_ATTRS) {
            val v = el.attr(at).trim()
            if (v.isNotBlank() && !v.startsWith("data:")) return v
        }
        val style = el.attr("style")
        if (style.isNotBlank()) {
            val v = BG_STYLE.find(style)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (v.isNotBlank() && !v.startsWith("data:")) return v
        }
        return ""
    }

    private fun pickName(a: Element, img: Element?): String? {
        cleanName(a.attr("title"))?.let { return it }
        cleanName(a.attr("aria-label"))?.let { return it }
        for (s in NAME_SELECTORS) {
            cleanName(a.selectFirst(s)?.text())?.let { return it }
        }
        cleanName(img?.attr("alt"))?.let { return it }
        return cleanName(a.ownText().ifBlank { a.text() })
    }

    private fun cleanName(raw: String?): String? {
        val t = raw.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (t.isBlank() || t.length > 40) return null
        if (t.endsWith(":") || t.endsWith("：") || t.endsWith("，") || t.endsWith(",")) return null
        return t
    }

    private fun pickRemarks(a: Element): String {
        val box = a.parent() ?: return ""
        var r = ""
        for (s in NOTE_SELECTORS) {
            r = a.selectFirst(s)?.text()?.trim().orEmpty()
            if (r.isNotBlank()) break
            r = box.selectFirst(s)?.text()?.trim().orEmpty()
            if (r.isNotBlank()) break
        }
        r = r.replace(Regex("\\s+"), " ").trim()
        if (r.contains("：") || r.contains(":") || r.contains("主演")) r = ""
        if (r.length > 16) r = ""
        return r
    }

    // ------------------------------------------------------------------ 选集

    /** 播放列表容器（优先精确的、再有兜底的） */
    private val CONTAINER_SELECTORS = listOf(
        "[id^=playlist]",
        ".tab-pane",
        ".module-play-list",
        ".stui-content__playlist",
        ".play-list",
        ".playlist",
        ".ff-playurl",
        ".content-playlist",
        ".playlist-content",
        ".lists-box",
        ".eplist",
        "#playlist"
    )

    /** 线路名兜底：向上找最近的标题节点 */
    private val GROUP_TITLE_SELECTORS =
        ".module-tab-item, .playlist-title, .play-source-tab, .stui-pannel__head h3, .stui-pannel__head h4, h3, .title"

    fun parseGroups(doc: Document, base: String): List<PlayGroup> {
        // 1) tab 标题映射：href="#playlist2" -> "极速播放"（顺序也按 tab 来）
        val idNames = LinkedHashMap<String, String>()
        for (a in doc.select("a[href^=#], [data-toggle=tab]")) {
            val target = a.attr("href").trim().removePrefix("#")
            if (target.isBlank()) continue
            val t = a.text().trim().ifBlank { a.attr("data-title").trim() }
            if (t.isNotBlank() && t.length <= 16) idNames.putIfAbsent(target, t)
        }

        // 2) 候选容器：先按 tab 顺序，再按 DOM 顺序
        val ordered = LinkedHashSet<Element>()
        for (k in idNames.keys) doc.getElementById(k)?.let { ordered.add(it) }
        for (sel in CONTAINER_SELECTORS) ordered.addAll(doc.select(sel))

        // 3) 去掉被其它候选包住的（.lists-box 与 .tab-pane 常常是同一个元素或其子集）
        val containers = ordered.filter { c -> ordered.none { o -> o !== c && c.parents().contains(o) } }

        val out = ArrayList<PlayGroup>()
        for (c in containers) {
            val eps = collectEpisodes(c, base)
            if (eps.isEmpty()) continue
            var name = idNames[c.id()].orEmpty()
            if (name.isBlank()) name = nearestTitle(c)
            if (name.isBlank()) name = "线路 ${out.size + 1}"
            out.add(PlayGroup(name, eps))
        }
        if (out.isNotEmpty()) return out

        // 4) 兜底：认不出容器结构时，整页链接按文档顺序当成一条线路
        val all = ArrayList<Episode>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!HtmlTemplates.isPlayLink(href)) continue
            val u = resolveUrl(base, href)
            if (u.isBlank() || !seen.add(u)) continue
            all.add(Episode(episodeName(a).ifBlank { "第${all.size + 1}集" }, u))
        }
        return if (all.isEmpty()) emptyList() else listOf(PlayGroup("默认线路", all))
    }

    private fun collectEpisodes(container: Element, base: String): List<Episode> {
        val eps = ArrayList<Episode>()
        val seen = HashSet<String>()
        for (a in container.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!HtmlTemplates.isPlayLink(href)) continue
            val u = resolveUrl(base, href)
            if (u.isBlank() || !seen.add(u)) continue
            eps.add(Episode(episodeName(a).ifBlank { "第${eps.size + 1}集" }, u))
        }
        return eps
    }

    private fun episodeName(a: Element): String {
        val t = a.attr("title").trim().ifBlank { a.text().trim() }
        val c = t.replace(Regex("\\s+"), " ").trim()
        return if (c.isBlank() || c.length > 20) "" else c
    }

    private fun nearestTitle(node: Element): String {
        var p: Element? = node
        var depth = 0
        while (p != null && depth < 4) {
            for (sel in GROUP_TITLE_SELECTORS.split(", ")) {
                val t = p.selectFirst(sel)?.text()?.trim().orEmpty()
                if (t.isNotBlank() && t.length <= 16) return t
            }
            val prev = p.previousElementSibling()
            if (prev != null) {
                val t = prev.text().trim()
                if (t.isNotBlank() && t.length <= 16) return t
            }
            p = p.parent()
            depth++
        }
        return ""
    }

    // ------------------------------------------------------------------ 详情元信息

    private val TITLE_SELECTORS = listOf(
        ".detail-box .title-box h1",
        ".title-box h1",
        ".myui-content__detail h1",
        ".module-info-heading h1",
        ".stui-content__detail h1",
        "h1.title",
        "h1"
    )

    private val SUMMARY_SELECTORS = listOf(
        ".vod-content .intro .wrapper_more_text",
        ".vod-content .intro",
        ".module-info-introduction-content",
        ".vod_content",
        ".myui-content__desc",
        ".stui-content__desc",
        ".detail-desc",
        "#desc",
        ".content"
    )

    private val PIC_SELECTORS = listOf(
        ".img-box img",
        ".detail-pic img",
        ".module-item-pic img",
        ".stui-content__thumb img",
        ".myui-content__thumb img"
    )

    fun parseTitle(doc: Document): String {
        for (s in TITLE_SELECTORS) {
            val t = doc.selectFirst(s)?.text()?.trim().orEmpty()
            if (t.isNotBlank() && t.length <= 60) return t
        }
        val raw = doc.title().trim()
        return raw.split("_", "-", "|", "，", ",").firstOrNull()?.replace("《", "")?.replace("》", "")?.trim().orEmpty()
    }

    fun parseSummary(doc: Document): String {
        for (s in SUMMARY_SELECTORS) {
            val t = doc.selectFirst(s)?.text()?.trim().orEmpty()
            if (t.isNotBlank() && t.length >= 6) return t.take(1200)
        }
        return ""
    }

    fun parsePic(doc: Document): String {
        val meta = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
        if (meta.isNotBlank()) return meta
        for (s in PIC_SELECTORS) {
            val el = doc.selectFirst(s) ?: continue
            val v = picOf(el)
            if (v.isNotBlank()) return v
        }
        return ""
    }
}
