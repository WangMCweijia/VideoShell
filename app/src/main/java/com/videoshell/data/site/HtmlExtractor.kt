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

    internal val PIC_ATTRS = listOf("data-original", "data-src", "data-echo", "data-lazy", "src")
    internal val BG_ATTRS = listOf("data-bg", "data-background", "data-background-image")

    /** 轮播/slide 类主题会把海报写成 CSS background-image */
    internal val BG_STYLE = Regex(
        "background(?:-image)?\\s*:\\s*url\\(\\s*['\"]?([^)'\"]+?)\\s*['\"]?\\s*\\)",
        RegexOption.IGNORE_CASE
    )

    internal val NAME_SELECTORS = listOf(
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

    internal val NOTE_SELECTORS = listOf(
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
    internal val BAD_NAMES = setOf(
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
    internal val CTA_NAMES = setOf(
        "查看剧集", "查看详情", "查看更多", "查看", "点击查看", "详情",
        "立即播放", "马上播放", "开始观看", "立即观看", "点击播放", "去播放", "播放", "观看",
        "选集", "下载", "收藏", "追剧", "免费观看", "在线观看"
    )

    // ------------------------------------------------------------------ 列表

    /** 单张卡片 */
    internal data class Card(
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
    internal val GROUP_TITLE_SELECTORS =
        ".module-tab-item, .playlist-title, .play-source-tab, .stui-pannel__head h3, " +
            ".stui-pannel__head h4, .source-name, .line-name, .play-title, h3, .title"

    /**
     * 「线路/播放源」类标签：`线路1080P`、`播放源2`、`片源`、`来源1`…
     * 这类文字描述的是**播放源**，不是第几集。
     */
    internal val LINE_LABEL = Regex(
        "线路|播放源|片源|片\\s*源|来源|源\\s*\\d+|line\\s*\\d+" +
            "|云播|云\\s*[一二三四五六七八九十\\d]+|节点\\s*[一二三四五六七八九十\\d]*" +
            "|秒播|快播|极速源|超清源|高清源|蓝光源|原画源",
        RegexOption.IGNORE_CASE
    )

    /** 纯清晰度标签：`1080P`、`4K`、`HD中字`、`超清`、`蓝光`…（整串就是它本身） */
    internal val QUALITY_LABEL = Regex(
        "^\\s*(?:\\d{3,4}\\s*[pPiI]|4K|8K|HD|BD|TS|TC|超清|高清|蓝光|标清|原画|国语|粤语|HD中字)\\s*$",
        RegexOption.IGNORE_CASE
    )

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
