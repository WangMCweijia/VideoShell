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
        ".jidi",
        ".time-title"
    )

    /** 明显不是影片名的导航词 */
    private val BAD_NAMES = setOf(
        "首页", "全部", "更多", "排行", "排行榜", "登录", "注册", "求片", "留言",
        "历史", "专题", "关于", "反馈", "APP", "手机版", "换一换"
    )

    // ------------------------------------------------------------------ 列表

    /** 单张卡片 */
    private data class Card(
        val id: String,
        val href: String,
        val name: String,
        val pic: String,
        val remarks: String
    )

    fun parseList(doc: Document, base: String, vodIsCategory: Boolean = false): List<VideoItem> {
        val out = LinkedHashMap<String, VideoItem>()
        for (a in doc.select("a[href]")) {
            val c = card(a, vodIsCategory) ?: continue
            if (out.containsKey(c.id)) continue
            out[c.id] = VideoItem(
                id = c.id,
                name = c.name,
                pic = resolveUrl(base, c.pic),
                remarks = c.remarks
            )
        }
        return out.values.toList()
    }

    /**
     * 列表页学一条详情页模板：拿第一张真实卡片的链接，把其中的影片 id 换成 `{id}`。
     *
     * 「站点用哪个路径前缀放详情页」只有它自己的列表页知道（`/detail/`、`/movie/`、`/watch/`…），
     * 穷举模板永远会漏，学一条最稳。
     */
    fun detailTplHint(doc: Document, base: String, vodIsCategory: Boolean): String? {
        for (a in doc.select("a[href]")) {
            val c = card(a, vodIsCategory) ?: continue
            val tpl = HtmlTemplates.detailTplFrom(resolveUrl(base, c.href), c.id)
            if (tpl != null) return tpl
        }
        return null
    }

    /** 判定一条 `<a>` 是不是影片卡片；不是返回 null。卡片必须自带图片（见类注释）。 */
    private fun card(a: Element, vodIsCategory: Boolean): Card? {
        val href = a.attr("href").trim()
        if (href.isEmpty()) return null
        if (href.startsWith("javascript") || href.startsWith("#") || href.startsWith("mailto")) return null

        val id = HtmlTemplates.videoIdOf(href, vodIsCategory) ?: return null

        val img = pickImage(a)
        var pic = img?.let { picOf(it) }.orEmpty()
        // 有些主题把懒加载图放在 <a> 自身（stui: a[data-original]），也认
        if (pic.isBlank()) pic = picOf(a)
        // 没图的必须是「强详情链接」才认，否则一律视为导航/功能链接
        if (pic.isBlank() && !HtmlTemplates.isStrongDetail(href)) return null

        val name = pickName(a, img) ?: return null
        if (name in BAD_NAMES) return null

        return Card(id, href, name, pic, pickRemarks(a))
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

    /** 播放列表容器（优先精确的、再有兜底的；尽量覆盖常见 maccms 主题） */
    private val CONTAINER_SELECTORS = listOf(
        "[id^=playlist]",
        ".tab-pane",
        ".module-play-list",
        ".module-play-list-content",
        ".stui-content__playlist",
        ".play-list",
        ".play-list-content",
        ".playlist",
        ".playlist-content",
        ".ff-playurl",
        ".content-playlist",
        ".lists-box",
        ".eplist",
        ".anthology",
        ".episode-list",
        ".detail-play-list",
        ".play-source-list",
        ".video-playlist",
        ".num-list",
        ".ep-list",
        // WordPress 系（厂长资源）：<div class="mi_paly_box"><div class="paly_list_btn"><a…>
        ".paly_list_btn",
        ".mi_paly_box",
        "[class*=paly_list]",
        "[class*=play_list]",
        "#playlist",
        "dl"
    )

    /** 线路名兜底：向上找最近的标题节点 */
    private val GROUP_TITLE_SELECTORS =
        ".module-tab-item, .playlist-title, .play-source-tab, .stui-pannel__head h3, " +
            ".stui-pannel__head h4, .source-name, .line-name, .play-title, h3, .title"

    /**
     * 「线路/播放源」类标签：`线路1080P`、`播放源2`、`片源`、`来源1`…
     * 这类文字描述的是**播放源**，不是第几集。
     */
    private val LINE_LABEL = Regex(
        "线路|播放源|片源|片\\s*源|来源|源\\s*\\d+|line\\s*\\d+" +
            "|云播|云\\s*[一二三四五六七八九十\\d]+|节点\\s*[一二三四五六七八九十\\d]*" +
            "|秒播|快播|极速源|超清源|高清源|蓝光源|原画源",
        RegexOption.IGNORE_CASE
    )

    /** 纯清晰度标签：`1080P`、`4K`、`HD中字`、`超清`、`蓝光`…（整串就是它本身） */
    private val QUALITY_LABEL = Regex(
        "^\\s*(?:\\d{3,4}\\s*[pPiI]|4K|8K|HD|BD|TS|TC|超清|高清|蓝光|标清|原画|国语|粤语|HD中字)\\s*$",
        RegexOption.IGNORE_CASE
    )

    private fun isLineLabel(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty()) return false
        return LINE_LABEL.containsMatchIn(s) || QUALITY_LABEL.matches(s)
    }

    /** 分组名清洗：去掉页面里带过来的分隔符尾巴（`肖申克的救赎|` → `肖申克的救赎`） */
    private fun cleanGroupName(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
            .trim('|', '｜', '-', '—', '_', '·', ':', '：', ',', '，', '/', '\\', ' ')
        if (t.isBlank()) return ""
        // 相邻重复词压掉：`云播四 云播四 云播四` -> `云播四`。
        // 来源是 nearestTitle() 往祖先链上取文本时，同一段 tab 文本被重复取了几次。
        val parts = t.split(' ').filter { it.isNotBlank() }
        if (parts.size > 1) {
            val uniq = ArrayList<String>(parts.size)
            for (p in parts) if (uniq.isEmpty() || uniq.last() != p) uniq.add(p)
            t = uniq.joinToString(" ")
        }
        return if (t.length > 16) t.take(16) else t
    }

    /**
     * 容器里的 `<a>` 是否**全是「线路按钮」**（是则返回它们，否则 null）。
     *
     * 厂长资源这类 WordPress 影视站的详情页**根本没有分集列表**：`.paly_list_btn` 里每个
     * `<a>` 是一条独立线路（`/v_play/{base64}.html`，解码后 `mv_849-nm_1` / `mv_849-nm_2`），
     * 标签还都叫「线路1080P」。按分集去理解，一部电影会变成「2 集」，
     * 点「第2集」实际跳到另一条线路 —— 线路数与集数全错。
     *
     * 判据（命中其一即认为是线路按钮）：
     * 1) 每个标签都是线路/清晰度词汇；
     * 2) 两个以上锚点且标签**完全相同** —— 分集不可能同名。
     */
    private fun asLineButtons(c: Element, base: String): List<Pair<String, Episode>>? {
        // 注意：Elements 自带 filter(NodeFilter) 成员方法，会挡住 Kotlin 的 Iterable.filter，
        // 所以必须先 toList() 转成普通 List 再用扩展函数。
        val anchors = c.select("a[href]").toList().filter {
            HtmlTemplates.isEpisodeLink(it.attr("href").trim())
        }
        if (anchors.size < 2) return null
        val labels = anchors.map { episodeName(it) }
        val allLine = labels.all { isLineLabel(it) }
        val sameLabel = labels.all { it.isNotBlank() } && labels.distinct().size == 1
        if (!allLine && !sameLabel) return null

        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, Episode>>()
        for (i in anchors.indices) {
            val u = resolveUrl(base, anchors[i].attr("href").trim())
            if (u.isBlank() || !seen.add(u)) continue
            val lb = labels[i]
            out.add(lb to Episode(lb.ifBlank { "第${out.size + 1}集" }, u))
        }
        return if (out.size >= 2) out else null
    }

    fun parseGroups(doc: Document, base: String): List<PlayGroup> {
        // 1) tab 标题映射：href="#playlist2" -> "极速播放"（顺序也按 tab 来）
        val idNames = LinkedHashMap<String, String>()
        for (a in doc.select("a[href^=#], a[id^=#], [data-toggle=tab]")) {
            val href = a.attr("href").trim()
            // tab 目标有两种写法：写在 `href`（标准），或写在**元素自己的 `id` 属性**里。
            // 后者看着怪但真实存在 —— 金牌影视：<a href="javascript:void(0);" id="#con_playlist_2">云播四</a>
            // 只认 href 的话，线路名会整片落回 nearestTitle 去拼，拼出「云播四 云播四 云播四」这种名字。
            val target = (if (href.startsWith("#")) href else a.attr("id").trim()).removePrefix("#")
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
            // 先判「线路按钮式播放区」：这类容器的每个 <a> 是一条线路，不是一个分集
            val lines = asLineButtons(c, base)
            if (lines != null) {
                val used = HashMap<String, Int>()
                for ((label, ep) in lines) {
                    val lb = label.ifBlank { "线路" }
                    val n = (used[lb] ?: 0) + 1
                    used[lb] = n
                    out.add(PlayGroup(if (n > 1) "$lb ($n)" else lb, listOf(ep)))
                }
                continue
            }
            val eps = collectEpisodes(c, base)
            if (eps.isEmpty()) continue
            var name = idNames[c.id()].orEmpty()
            if (name.isBlank()) name = nearestTitle(c)
            name = cleanGroupName(name)
            if (name.isBlank()) name = "线路 ${out.size + 1}"
            out.add(PlayGroup(name, eps))
        }
        if (out.isNotEmpty()) return out

        // 4) 兜底：认不出容器结构时，整页链接按文档顺序当成一条线路
        //    先用严格判据，一个都没命中再放宽 —— 放宽只是为了别漏掉奇怪主题，不是在放宽噪声
        var all = scanWholePage(doc, base, strict = true)
        if (all.isEmpty()) all = scanWholePage(doc, base, strict = false)
        return if (all.isEmpty()) emptyList() else listOf(PlayGroup("默认线路", all))
    }

    private fun scanWholePage(doc: Document, base: String, strict: Boolean): List<Episode> {
        val all = ArrayList<Episode>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            val ok = if (strict) HtmlTemplates.isPlayLink(href) else HtmlTemplates.isEpisodeLink(href)
            if (!ok) continue
            val u = resolveUrl(base, href)
            if (u.isBlank() || !seen.add(u)) continue
            all.add(Episode(episodeName(a).ifBlank { "第${all.size + 1}集" }, u))
        }
        for (op in doc.select("option[value]")) {
            val v = op.attr("value").trim()
            val ok = if (strict) HtmlTemplates.isPlayLink(v) else HtmlTemplates.isEpisodeLink(v)
            if (!ok) continue
            val u = resolveUrl(base, v)
            if (u.isBlank() || !seen.add(u)) continue
            all.add(Episode(optionName(op).ifBlank { "第${all.size + 1}集" }, u))
        }
        return all
    }

    private fun collectEpisodes(container: Element, base: String): List<Episode> {
        val eps = ArrayList<Episode>()
        val seen = HashSet<String>()
        for (a in container.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!HtmlTemplates.isEpisodeLink(href)) continue
            val u = resolveUrl(base, href)
            if (u.isBlank() || !seen.add(u)) continue
            eps.add(Episode(episodeName(a).ifBlank { "第${eps.size + 1}集" }, u))
        }
        // 下拉式选集：<select><option value="/play/x-1-1.html">第1集</option>
        for (op in container.select("option[value]")) {
            val v = op.attr("value").trim()
            if (!HtmlTemplates.isEpisodeLink(v)) continue
            val u = resolveUrl(base, v)
            if (u.isBlank() || !seen.add(u)) continue
            eps.add(Episode(optionName(op).ifBlank { "第${eps.size + 1}集" }, u))
        }
        return eps
    }

    private fun optionName(op: Element): String {
        val t = op.text().replace(Regex("\\s+"), " ").trim()
        if (t.isBlank() || t.length > 20) return ""
        if (t.startsWith("选择") || t.startsWith("请选择")) return ""
        return t
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
