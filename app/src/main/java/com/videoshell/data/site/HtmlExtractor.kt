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

    /**
     * 卡片上的**操作按钮文案**：它是链接的用途，不是影片名。
     *
     * 自研 SSR 站的 hero 轮播实测（野果短剧首页）：
     * ```html
     * <a href="/drama/detail/3379/" aria-label="查看剧集">
     *   <img src="data:image/gif;base64,R0lGOD…" alt="庆余年 第三季">
     * ```
     * `aria-label` 是 CTA、`img[alt]` 才是真标题；旧实现按「title → aria-label → 选择器 → alt」
     * 取值 ⇒ **整页 53 张卡片全叫「查看剧集」**。命中这个词表就往后顺延，别停在这里。
     */
    private val CTA_NAMES = setOf(
        "查看剧集", "查看详情", "查看更多", "查看", "点击查看", "详情",
        "立即播放", "马上播放", "开始观看", "立即观看", "点击播放", "去播放", "播放", "观看",
        "选集", "下载", "收藏", "追剧", "免费观看", "在线观看"
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

    /**
     * 解析列表卡片。
     *
     * [rawHtml] 传进来时，会给**没有封面的卡片**补封面 —— 见 [fillPics]。
     * 自研 SSR 站（Nuxt）的封面地址不在 DOM 里，只能从页面内嵌 JSON 取。
     */
    @JvmOverloads
    fun parseList(
        doc: Document,
        base: String,
        vodIsCategory: Boolean = false,
        rawHtml: String? = null
    ): List<VideoItem> {
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
        val items = out.values.toList()
        // 封面兜底：全都有封面就直接返回，**不白解一遍 payload**（它要遍历整棵 JSON 树）
        if (rawHtml == null || items.none { it.pic.isBlank() }) return items
        val cv = SsrPayload.covers(rawHtml)
        if (cv.isEmpty) return items
        return fillPics(items, cv)
    }

    /**
     * 用内嵌 JSON 的封面表补空封面。
     *
     * 三条规则：
     * 1. **已有封面的一律不动** —— 这些是站点真渲染出来的，比 payload 可信；
     * 2. 按 `id` 匹配优先（`href` 里的数字段 = payload 的 `video_id`）；
     * 3. 名称匹配只作后备，且要求**长度 ≥ 2** —— 单字标题（`"1"`、`"A"`）撞车概率太高。
     *
     * 纯函数，便于离线断言。
     */
    fun fillPics(items: List<VideoItem>, cv: SsrPayload.Covers): List<VideoItem> = items.map { v ->
        if (v.pic.isNotBlank()) return@map v
        val byId = cv.byId[v.id]
        val url = if (!byId.isNullOrBlank()) byId
        else if (v.name.trim().length >= 2) cv.byName[v.name.trim()].orEmpty()
        else ""
        if (url.isBlank()) v else v.copy(pic = url)
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

    /**
     * 取卡片名。
     *
     * 顺序：`a[title]` → `a[aria-label]` → 名称选择器 → `img[alt]` → 锚点文本。
     * **每一层都要过 [CTA_NAMES]**：命中的是"查看剧集 / 立即播放"这类按钮文案，
     * 不代表它不能用 —— 只代表**它不是名字**，继续往下一层找。
     * 野果首页的 `aria-label="查看剧集"` + `img[alt]="庆余年 第三季"` 就靠这一条纠正。
     */
    private fun pickName(a: Element, img: Element?): String? {
        cleanName(a.attr("title"))?.takeIf { !isCta(it) }?.let { return it }
        cleanName(a.attr("aria-label"))?.takeIf { !isCta(it) }?.let { return it }
        for (s in NAME_SELECTORS) {
            cleanName(a.selectFirst(s)?.text())?.takeIf { !isCta(it) }?.let { return it }
        }
        cleanName(img?.attr("alt"))?.takeIf { !isCta(it) }?.let { return it }
        return cleanName(a.ownText().ifBlank { a.text() })?.takeIf { !isCta(it) }
    }

    /** 按钮文案不是影片名（含"XX · 更多"这种带尾巴的形态） */
    private fun isCta(s: String): Boolean =
        s in CTA_NAMES || s.substringBefore(" ·").substringBefore("·").trim() in CTA_NAMES

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

    /**
     * **标签栏整段文本**：「线路1线路2」「播放源1播放源2」——
     * tab 栏是若干个相邻的行内标签，Jsoup 的 `.text()` 会把它们**不加空格地连在一起**。
     * 拿它当线路名就会得到一颗叫「线路1线路2」的 chip（两条线的名字缝在一起）。
     * [nearestTitle] 撞见这种就跳过，让 [parseGroups] 落回「线路 N」。
     */
    private val TAB_BAR_TEXT = Regex(
        "^(?:(?:线路|播放源|片源|来源|源|节点|云播|秒播|快播|极速|超清|高清|蓝光|原画|备用|移动|电信|联通)" +
            "\\s*[一二三四五六七八九十\\d]*\\s*){2,}$",
        RegexOption.IGNORE_CASE
    )

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

        // 3) 去掉被其它候选包住的（.lists-box 与 .tab-pane 常常是同一个元素或其子集）。
        //    ⚠️ 这里不能无脑「留外层」：有的站几条线路共用一个外层包装
        //    （外层里并排两块播放列表），留外层就把两条线并成一条 ——
        //    症状是「线路1线路2 融成一颗 chip，选集 52 = 26+26」。
        //    规则：外层的分集被内层候选的并集**全覆盖** ⇒ 外层只是包装，弃外层留内层；
        //          盖不全（内层只是外层的局部格式化）⇒ 仍留外层，别丢集。
        val epsCache = HashMap<Element, List<Episode>>()
        fun epsOf(e: Element): List<Episode> = epsCache.getOrPut(e) { collectEpisodes(e, base) }
        val dropped = HashSet<Element>()
        for (c in ordered) {
            if (c in dropped) continue
            val inners = ordered.filter { o -> o !== c && o.parents().contains(c) && o !in dropped }
            if (inners.isEmpty()) continue
            val outerEps = epsOf(c)
            val innerUrls = HashSet<String>()
            for (o in inners) epsOf(o).forEach { innerUrls.add(it.url) }
            if (outerEps.isNotEmpty() && outerEps.all { innerUrls.contains(it.url) }) {
                dropped.add(c)              // 内层盖得全 ⇒ 外层只是包装，弃外层
            } else {
                dropped.addAll(inners)      // 盖不全 ⇒ 留外层，弃内层（老行为，别丢集）
            }
        }
        val containers = ordered.filter { it !in dropped }

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
            // 「几条源共用一个外层列表」：骚火电影是
            //   <ul class="play_list"><li>源1 的 26 集</li><li>源2 的 26 集</li></ul>
            // 整个 ul 当一个容器 ⇒ 「1 条线路 52 集」，两条源缝在一起（52 = 26+26）。
            // 判据：分集锚点按「容器某一层的块」归堆后 ≥2 堆、每堆 ≥2 集 ⇒ 每堆一条线路。
            // 正常「每集一个 li」的列表每堆只有 1 个锚点，不会误拆（见 splitSubBlocks）。
            //
            // 两条路都试：先按 DOM 分层（能处理绝大多数主题），再按播放地址里的线路段兜底
            //（几条源**交错**摆在同一层时 DOM 分不开，只能看地址）。
            val blocks = splitSubBlocks(c, base) ?: splitByUrlShape(eps)
            if (blocks != null) {
                // 名字优先取页面自己的线路标签栏：容器外面的（骚火 div.play_from）或
                // 容器里面的 tab 栏（zqkhmy .anthology-tab），两者都认。
                val labels = lineLabelsFor(c, blocks.size) ?: tabLabelsIn(c, blocks.size)
                for ((i, b) in blocks.withIndex()) {
                    val bn = labels?.getOrNull(i)
                        ?: b.node?.let { cleanGroupName(nearestTitle(it)) }.orEmpty()
                            .ifBlank { "线路 ${out.size + 1}" }
                    out.add(PlayGroup(bn, b.episodes))
                }
                continue
            }
            var name = idNames[c.id()].orEmpty()
            if (name.isBlank()) name = nearestTitle(c)
            name = cleanGroupName(name)
            if (name.isBlank()) name = "线路 ${out.size + 1}"
            out.add(PlayGroup(name, eps))
        }
        if (out.isNotEmpty()) {
            // 组名去重：两条线拿到同一个名字时加序号，否则 UI 上两颗 chip 一模一样分不清。
            // （拆开外层包装后，两块列表都可能从同一段 tab 栏附近取名。）
            val used = HashMap<String, Int>()
            for (i in out.indices) {
                val g = out[i]
                val n = (used[g.name] ?: 0) + 1
                used[g.name] = n
                if (n > 1) out[i] = PlayGroup("${g.name} ($n)", g.episodes)
            }
            return out
        }

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

    /**
     * 拆出来的一条「线路」：分集 + 它所在的块节点（取名用，按地址拆时没有）。
     */
    private class LineBlock(val episodes: List<Episode>, val node: Element?)

    /**
     * 同一容器里几条源各占一块 ⇒ 拆成多条线路。
     *
     * 形状一（骚火电影）：`<ul class="play_list"><li>源1 的 26 个 <a></li><li>源2 的 26 个</li></ul>`
     * —— 锚点的顶层祖先（容器直接子元素）正好两块。
     *
     * 形状二（zqkhmy 实测，v1.0.28 修）：块外面还裹了一层
     * ```
     * <div class="anthology">
     *   <div class="anthology-tab">…6 个线路标签…</div>
     *   <div class="anthology-list">
     *     <div class="anthology-list-box none"><div><ul class="anthology-list-play">181 集</ul></div></div>
     *     <div class="anthology-list-box none">… 180 集 …</div>   ← 共 6 块
     * ```
     * 全部锚点的「直接子层祖先」都是同一个 `.anthology-list` ⇒ 只归到 1 堆，
     * 老实现直接放弃拆分，6 条源被缝成「1 条线路 924 集」（924 = 181+180+21+181+181+180）。
     * 所以这里**逐层下探**：第 1 层不行就看第 2 层，最多到 [MAX_SUB_BLOCK_DEPTH] 层。
     *
     * 判据（任一层命中即拆）：锚点按该层祖先归堆后 **≥2 堆、且每堆 ≥2 个锚点**。
     * 反面形状是正常主题的「每集一个 li」——每堆只有 1 个锚点，绝不拆；
     * 平铺列表（`<div class="playlist"><a/><a/>…`）的祖先就是容器本身，也不算一「层」。
     */
    private fun splitSubBlocks(c: Element, base: String): List<LineBlock>? {
        val items = anchorEpisodes(c, base)
        if (items.size < 4) return null
        for (level in 1..MAX_SUB_BLOCK_DEPTH) {
            val piles = LinkedHashMap<Element, MutableList<Episode>>()
            var aligned = true
            for ((a, ep) in items) {
                val top = ancestorAt(a, c, level)
                if (top == null) {
                    aligned = false
                    break
                }
                piles.getOrPut(top) { ArrayList() }.add(ep)
            }
            if (!aligned) continue
            if (piles.size < 2 || piles.values.any { it.size < 2 }) continue
            // 每堆内部的「无名分集」按堆内序号补名，与老行为一致
            return piles.map { (node, list) ->
                LineBlock(
                    list.mapIndexed { i, e -> if (e.name.isBlank()) e.copy(name = "第${i + 1}集") else e },
                    node
                )
            }
        }
        return null
    }

    /** 最多往下找几层「分块层」。2 层够 zqkhmy（`.anthology` → `.anthology-list`）；再多只是徒增误拆面。 */
    private const val MAX_SUB_BLOCK_DEPTH = 3

    /**
     * `a` 往上第 `level` 层的祖先。
     * 中途撞到容器 `c`（说明锚点本身就在这一层或更浅）或走到顶 ⇒ 返回 null，这一层不算数。
     */
    private fun ancestorAt(a: Element, c: Element, level: Int): Element? {
        var n: Element = a
        for (i in 1..level) {
            val p = n.parent() ?: return null
            if (p === c) return null
            n = p
        }
        return n
    }

    /** 容器里所有分集锚点（按 URL 去重、保持文档顺序），带原始 `<a>` 以便回溯祖先 */
    private fun anchorEpisodes(c: Element, base: String): List<Pair<Element, Episode>> {
        val out = ArrayList<Pair<Element, Episode>>()
        val seen = HashSet<String>()
        for (a in c.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!HtmlTemplates.isEpisodeLink(href)) continue
            val u = resolveUrl(base, href)
            if (u.isBlank() || !seen.add(u)) continue
            out.add(a to Episode(episodeName(a), u))
        }
        return out
    }

    /**
     * 按**播放地址里的线路段**拆线（DOM 分不开时的兜底）。
     *
     * maccms 的播放页地址是 `/{目录}/{影片id}-{线路id}-{集id}.html`，
     * **同一条分集列表里的线路段必然相同**。所以「一组里出现 ≥2 个线路段」= 至少两条源被缝在一起。
     * 这是与 DOM 结构完全无关的独立证据：页面把几条源平铺在同一层、整块由 JS 拼出来，
     * DOM 分层都会失手，地址不会。
     *
     * 保守到什么程度：
     * 1. **只要有一条地址不是这个形状就整体放弃**（返回 null）；
     * 2. 线路段在第 3 段还是第 4 段，由「**分出来的堆更少**」自己定 —— 线路数（个位数）
     *    天然远少于集数，段序被换过的站也能自动对齐；平局按 maccms 标准取第 3 段；
     * 3. 每堆必须 ≥2 集：只有一集的「线路」一定是判据看错了（或某站把线段含义反过来用），
     *    这种情况整体放弃，绝不拆出一堆单集线路。
     */
    private fun splitByUrlShape(eps: List<Episode>): List<LineBlock>? {
        if (eps.size < 4) return null
        val parts = eps.map { PLAY_URL_PARTS.find(it.url)?.groupValues ?: return null }
        val byThird = pileByUrl(eps, parts) { it[3] }
        val byFourth = pileByUrl(eps, parts) { it[4] }
        val pick = when {
            byThird == null -> byFourth
            byFourth == null -> byThird
            byFourth.size < byThird.size -> byFourth
            else -> byThird
        } ?: return null
        return pick.map { (_, list) -> LineBlock(fillBlankNames(list), null) }
    }

    /** 按 `key(分组)` 归堆；堆数 <2 或存在只有 1 集的堆 ⇒ 这不成一条判据，返回 null */
    private fun pileByUrl(
        eps: List<Episode>,
        parts: List<List<String>>,
        key: (List<String>) -> String
    ): LinkedHashMap<String, MutableList<Episode>>? {
        val piles = LinkedHashMap<String, MutableList<Episode>>()
        for (i in eps.indices) piles.getOrPut(key(parts[i])) { ArrayList() }.add(eps[i])
        if (piles.size < 2 || piles.values.any { it.size < 2 }) return null
        return piles
    }

    /** 没取到名字的分集按堆内序号补名（与 [collectEpisodes] 的兜底一致） */
    private fun fillBlankNames(list: List<Episode>): List<Episode> =
        list.mapIndexed { i, e -> if (e.name.isBlank()) e.copy(name = "第${i + 1}集") else e }

    /** `/{目录}/{影片id}-{线路id}-{集id}.html` → 分组 1=目录 2=影片 3=线路 4=集 */
    private val PLAY_URL_PARTS = Regex("/([A-Za-z][\\w_\\-]*)/(\\d+)-(\\d+)-(\\d+)(?:\\.html?)?")

    /**
     * 容器**内部**的线路标签栏（与 [lineLabelsFor] 互补：那个找容器前面的，这个找里面的）。
     *
     * zqkhmy 实测形状：
     * ```
     * <div class="anthology-tab"><div class="swiper-wrapper">
     *   <a class="swiper-slide"><i class="fa…"></i>&nbsp;蓝光2k<span class="badge">181</span></a>
     *   … 共 6 个，顺序与下面 6 个分集块一一对应
     * ```
     * 只认**没有真链接**的锚点（无 `href` / `#` / `javascript:`）—— 带真链接的是导航。
     * 名字取 `ownText()`：数量徽标在子 `<span>` 里，不会被混进名字（得到「蓝光2k」而不是「蓝光2k181」）。
     * 条数必须恰好等于分块数，多一个少一个都认输返回 null。
     */
    private fun tabLabelsIn(c: Element, n: Int): List<String>? {
        if (n < 2) return null
        val byParent = LinkedHashMap<Element, MutableList<Element>>()
        for (a in c.select("a")) {
            val href = a.attr("href").trim()
            if (href.startsWith("http") || href.startsWith("/") || href.startsWith("mailto")) continue
            val p = a.parent() ?: continue
            byParent.getOrPut(p) { ArrayList() }.add(a)
        }
        for ((_, kids) in byParent) {
            if (kids.size != n) continue
            val labels = kids.map { tabLabelText(it) }
            if (labels.all { it.isNotBlank() }) return labels
        }
        return null
    }

    /** tab 标签取文：优先 ownText（徽标在子节点里），退到整段文本；太长/太空当没取到 */
    private fun tabLabelText(a: Element): String {
        val own = a.ownText().replace(SPACES, " ").trim()
        val t = if (own.isNotBlank()) own else a.text().replace(SPACES, " ").trim()
        return if (t.length in 1..16) t else ""
    }

    /** 空白归一化。`&nbsp;`(\u00A0) 与全角空格 `\u3000` 不在 Java 正则的 `\s` 里，必须显式列出 —
     *  zqkhmy 的 tab 就是 `<i…></i>&nbsp;蓝光2k`，漏掉它名字会带一个前导空白。 */
    private val SPACES = Regex("[\\s\\u00A0\\u3000]+")

    /**
     * 找容器**前面**的「线路标签栏」，给按子块拆出的线路命名。
     * 骚火电影的 `div.play_from` 里摆着 `<li>线路1</li><li>线路2</li>`，
     * 条数正好等于子块数 ⇒ 按顺序对号入座，组名与站点自己的叫法一致。
     * 只认「全是线路/清晰度词汇、条数恰好等于块数、互不重复」的栏，认不出返回 null。
     */
    private fun lineLabelsFor(c: Element, n: Int): List<String>? {
        var p: Element? = c
        var depth = 0
        while (p != null && depth < 3) {
            var sib: Element? = p.previousElementSibling()
            var hops = 0
            while (sib != null && hops < 2) {
                val texts = sib.select("li, option, a").toList()
                    .map { it.text().replace(Regex("\\s+"), " ").trim() }
                    .filter { it.isNotEmpty() }
                val labels = texts.filter { isLineLabel(it) }
                if (labels.size == n && texts.size == labels.size &&
                    labels.toSet().size == labels.size
                ) return labels
                sib = sib.previousElementSibling()
                hops++
            }
            p = p.parent()
            depth++
        }
        return null
    }

    private fun optionName(op: Element): String {
        val t = op.text().replace(Regex("\\s+"), " ").trim()
        if (t.isBlank() || t.length > 20) return ""
        if (t.startsWith("选择") || t.startsWith("请选择")) return ""
        return t
    }

    /**
     * 分集名。
     *
     * 同一个分集链接在 HTML 里往往有**两个**名字：
     * - 可见文本 `a.text()`：通常就是集名本身（`第01集`）
     * - `title` 属性：常常是「剧名 + 集名」拼成的一整串（`兰香如故第01集`），
     *   但有时又比文本更精确（文本只有 `1`、`HD`，甚至是个图标）
     *
     * 旧实现无条件优先 `title`，于是详情页整列都成了「兰香如故第01集」这种带剧名的长名字
     * （金牌影视实测）。这里的判据：**title 比文本长且以文本结尾时，说明 title 只是给文本
     * 加了个前缀（多半就是剧名），此时取更短、更精确的文本**；其余情况仍以 title 为准。
     */
    private fun episodeName(a: Element): String {
        val txt = a.text().replace(Regex("\\s+"), " ").trim()
        val ttl = a.attr("title").replace(Regex("\\s+"), " ").trim()
        val c = when {
            txt.isBlank() -> ttl
            ttl.isBlank() -> txt
            ttl.length > txt.length && ttl.endsWith(txt) && txt.length >= 2 -> txt
            else -> ttl
        }
        return if (c.isBlank() || c.length > 20) "" else c
    }

    /**
     * 削掉分集名里重复的**剧名**前缀。
     *
     * [episodeName] 只能处理「title 里含有可见文本」的情况。有些主题的 `title` 只有
     * 剧名 + 集名、可见文本是空的（或就是个图标），那就只能拿详情页的剧名本身来削。
     *
     * 保守起见，只有**该线路下全部分集**都以剧名开头、且削完还剩内容时才动手；
     * 只要有任何一个不符合，就认为剧名不是前缀，原样返回。
     */
    fun stripTitlePrefix(groups: List<PlayGroup>, title: String): List<PlayGroup> {
        val t = title.replace(Regex("\\s+"), " ").trim()
        if (t.length < 2) return groups
        return groups.map { g ->
            val eps = g.episodes
            if (eps.size < 2) return@map g
            val all = eps.all { it.name.startsWith(t) && it.name.length > t.length }
            if (!all) return@map g
            g.copy(episodes = eps.map { it.copy(name = it.name.removePrefix(t).trim()) })
        }
    }

    private fun nearestTitle(node: Element): String {
        var p: Element? = node
        var depth = 0
        while (p != null && depth < 4) {
            for (sel in GROUP_TITLE_SELECTORS.split(", ")) {
                val el = p.selectFirst(sel) ?: continue
                val t = titleText(el)
                if (t.isNotBlank() && t.length <= 16 && !TAB_BAR_TEXT.matches(t)) return t
            }
            val prev = p.previousElementSibling()
            if (prev != null) {
                val el = prev.selectFirst("h3, .title") ?: prev
                val t = titleText(el)
                if (t.isNotBlank() && t.length <= 16 && !TAB_BAR_TEXT.matches(t)) return t
            }
            p = p.parent()
            depth++
        }
        return ""
    }

    /**
     * 标题节点的取文。推荐板块标题的常见形状是「剧名 + 尾注」拼合：
     * `<h3 class="title"><a href="/movie/…">交锋</a>同类型影片</h3>`
     * —— `.text()` 得到「交锋同类型影片」，当线路名又怪又容易撞车。
     * 判据：**锚点有字、锚点外面的直接文本也有字** ⇒ 取锚点文本（剧名）；
     * 其余（纯文本标题、锚点自己就是标题）照旧取整段。
     */
    private fun titleText(el: Element): String {
        val own = el.ownText().replace(Regex("\\s+"), " ").trim()
        if (own.isNotEmpty()) {
            val a = el.selectFirst("a")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (a.isNotEmpty()) return a
        }
        return el.text().trim()
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
        if (meta.isNotBlank() && !isShareDefault(meta)) return meta
        for (s in PIC_SELECTORS) {
            val el = doc.selectFirst(s) ?: continue
            val v = picOf(el)
            if (v.isNotBlank()) return v
        }
        return ""
    }

    /**
     * `og:image` 在很多站上是**站点级默认分享图**（`/images/social-default.png`），
     * 不是这部影片的海报。认了它，整站每部剧都会挂同一张图 —— 看起来像"封面错乱"。
     *
     * 实测：野果的详情页 `og:image` 是真海报（可用），而列表页/首页是
     * `social-default.png`（不可用）；茶杯狐等站也有同类命名的默认图。
     * 命中时**继续往下找**（找不到就返回空，让 UI 显示占位图，也好过张冠李戴）。
     */
    private val SHARE_DEFAULT = Regex(
        "social[-_]?default|default[-_]?share|share[-_]?default|placeholder|" +
            "/logo\\.|logo\\.(?:png|jpe?g|webp|gif|svg)",
        RegexOption.IGNORE_CASE
    )

    private fun isShareDefault(url: String): Boolean = SHARE_DEFAULT.containsMatchIn(url)
}
