package com.videoshell.data.site

// HtmlExtractor 的**列表卡片解析**（拆出来的第一块）：封面取哪张、名字怎么定、
// 备注怎么认。
//
// ⚠️ 本类的**公开**方法（parseList / parseGroups / parseTitle / parseSummary /
// parsePic / detailTplHint / stripTitlePrefix / fillPics）一个都没搬 —— tools/verify 下
// 有几十处 Java 守卫在直接调 `HtmlExtractor.INSTANCE.parseList(...)`，
// 而 Java 里没有扩展函数，搬走公开方法等于一次性判掉十几个套件的编译。
// 这里住的只是它们用到的私有小工具。
//
// ⚠️ `Card` 是 `object HtmlExtractor` 的**嵌套** private data class。嵌套类型不会自动
// 进入别的文件的顶层作用域，所以这里显式 import 一次（同一包内 import 是合法的）。
// 其余被本文件用到的常量（PIC_ATTRS / BG_ATTRS / BG_STYLE / NAME_SELECTORS /
// NOTE_SELECTORS / BAD_NAMES / CTA_NAMES）留在原 object 里、放宽成 internal ——
// 它们同时也被留在原文件里的 parseList / fillPics 用着，搬走会两边都要 import。
//
// 拆法与约束同 PlayerActivity_Play.kt（纯搬运 + internal 扩展函数）：
// 守卫按「主文件 + 同主名拆分子文件」读源码，所以断言写的 HtmlExtractor.kt
// 仍然覆盖本文件。
import com.videoshell.data.site.HtmlExtractor.Card

import com.videoshell.data.model.Episode
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.VideoItem
import com.videoshell.util.resolveUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 判定一条 `<a>` 是不是影片卡片；不是返回 null。卡片必须自带图片（见类注释）。 */
internal fun HtmlExtractor.card(a: Element, vodIsCategory: Boolean): Card? {
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
internal fun HtmlExtractor.pickImage(a: Element): Element? {
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

internal fun HtmlExtractor.picOf(el: Element): String {
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
internal fun HtmlExtractor.pickName(a: Element, img: Element?): String? {
    cleanName(a.attr("title"))?.takeIf { !isCta(it) }?.let { return it }
    cleanName(a.attr("aria-label"))?.takeIf { !isCta(it) }?.let { return it }
    for (s in NAME_SELECTORS) {
        cleanName(a.selectFirst(s)?.text())?.takeIf { !isCta(it) }?.let { return it }
    }
    cleanName(img?.attr("alt"))?.takeIf { !isCta(it) }?.let { return it }
    return cleanName(a.ownText().ifBlank { a.text() })?.takeIf { !isCta(it) }
}

/** 按钮文案不是影片名（含"XX · 更多"这种带尾巴的形态） */
internal fun HtmlExtractor.isCta(s: String): Boolean =
    s in CTA_NAMES || s.substringBefore(" ·").substringBefore("·").trim() in CTA_NAMES

internal fun HtmlExtractor.cleanName(raw: String?): String? {
    val t = raw.orEmpty().replace(Regex("\\s+"), " ").trim()
    if (t.isBlank() || t.length > 40) return null
    if (t.endsWith(":") || t.endsWith("：") || t.endsWith("，") || t.endsWith(",")) return null
    return t
}

internal fun HtmlExtractor.pickRemarks(a: Element): String {
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
