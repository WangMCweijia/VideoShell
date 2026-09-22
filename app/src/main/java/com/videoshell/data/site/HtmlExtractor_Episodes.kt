package com.videoshell.data.site

// HtmlExtractor 的**剧集与线路解析**（拆出来的第二块）：分集名、线路标签、
// 子块切分、按 URL 形状归类。
//
// 为什么这一族放一起：它们是**一条判据链** —— 容器选择 → 子块切分 → 线路标签 →
// 集名兜底。链上任何一环单独改动都会表现为「分集列表少了几条 / 线路名串了」，
// 而这是本工程最贵的一段用户可见逻辑（v1.0.22 的「第3集被排到第1位」就出在这条链上）。
// 同一屏里才看得出改的是哪一环。
//
// ⚠️ 区间里夹着四样**非函数**声明，它们跟着函数一起搬：
//   - `private const val MAX_SUB_BLOCK_DEPTH` / `private val PLAY_URL_PARTS` /
//     `private val SPACES`：只被这一段用到，所以留 `private`（同一文件内看得见）；
//   - `private class LineBlock` → **internal**：原文件里的 parseGroups 要在
//     `b.node` / `b.episodes` 上取值，跨文件就得放宽；
//   - `private val TAB_BAR_TEXT` → **internal**：它被 [nearestTitle]（Title 那一组）用着；
//   - 留在原 object 里、由 _fixvis 放宽成 internal 的是 GROUP_TITLE_SELECTORS ——
//     它描述的是「tab 栏长什么样」，与这一段同一族，但原文件的 setter 顺序不便搬。
//
// 所有 `internal` 都是**可见性**调整，没有一行逻辑改动；守卫读源码时会把这些声明
// 还原成拆分前的写法（见 tools/verify/_cp.py 的 kt() / _unwrap）。
//
// 拆法与约束同上。

import com.videoshell.data.model.Episode
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.VideoItem
import com.videoshell.util.resolveUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal fun HtmlExtractor.isLineLabel(t: String): Boolean {
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
internal val TAB_BAR_TEXT = Regex(
    "^(?:(?:线路|播放源|片源|来源|源|节点|云播|秒播|快播|极速|超清|高清|蓝光|原画|备用|移动|电信|联通)" +
        "\\s*[一二三四五六七八九十\\d]*\\s*){2,}$",
    RegexOption.IGNORE_CASE
)

/** 分组名清洗：去掉页面里带过来的分隔符尾巴（`肖申克的救赎|` → `肖申克的救赎`） */
internal fun HtmlExtractor.cleanGroupName(raw: String): String {
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
internal fun HtmlExtractor.asLineButtons(c: Element, base: String): List<Pair<String, Episode>>? {
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


internal fun HtmlExtractor.scanWholePage(doc: Document, base: String, strict: Boolean): List<Episode> {
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

internal fun HtmlExtractor.collectEpisodes(container: Element, base: String): List<Episode> {
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
internal class LineBlock(val episodes: List<Episode>, val node: Element?)

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
internal fun HtmlExtractor.splitSubBlocks(c: Element, base: String): List<LineBlock>? {
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
internal fun HtmlExtractor.ancestorAt(a: Element, c: Element, level: Int): Element? {
    var n: Element = a
    for (i in 1..level) {
        val p = n.parent() ?: return null
        if (p === c) return null
        n = p
    }
    return n
}

/** 容器里所有分集锚点（按 URL 去重、保持文档顺序），带原始 `<a>` 以便回溯祖先 */
internal fun HtmlExtractor.anchorEpisodes(c: Element, base: String): List<Pair<Element, Episode>> {
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
internal fun HtmlExtractor.splitByUrlShape(eps: List<Episode>): List<LineBlock>? {
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
internal fun HtmlExtractor.pileByUrl(
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
internal fun HtmlExtractor.fillBlankNames(list: List<Episode>): List<Episode> =
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
internal fun HtmlExtractor.tabLabelsIn(c: Element, n: Int): List<String>? {
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
internal fun HtmlExtractor.tabLabelText(a: Element): String {
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
internal fun HtmlExtractor.lineLabelsFor(c: Element, n: Int): List<String>? {
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

internal fun HtmlExtractor.optionName(op: Element): String {
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
internal fun HtmlExtractor.episodeName(a: Element): String {
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
