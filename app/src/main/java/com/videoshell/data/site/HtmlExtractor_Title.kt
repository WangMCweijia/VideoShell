package com.videoshell.data.site

// HtmlExtractor 的**标题清洗**（拆出来的第三块）：从 DOM 往上找最近的标题、
// 再把站点自己加的前后缀剥掉。
//
// 与 Card 里的 pickName 分家的理由：pickName 处理的是**列表卡片**上的短名字，
// 这里处理的是**详情页**上带季集/清晰度后缀的长标题，两者的清洗规则相反
// （前者要保留「第2季」这类信息，后者要剥掉）。混在一起必然互相污染。
//
// 依赖两个选择器常量，两者都**没搬过来**（放宽成 internal 用）：
//   - GROUP_TITLE_SELECTORS：留在原 object 里；
//   - TAB_BAR_TEXT：住在 HtmlExtractor_Episodes.kt（它属于「tab 栏长什么样」的知识）。
// 刻意不跟着搬：nearestTitle 只是它的**使用者**，把它搬到这里会让选集那一组反过来
// 依赖本文件，方向就反了。
// 拆法与约束同上。

import com.videoshell.data.model.Episode
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.VideoItem
import com.videoshell.util.resolveUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal fun HtmlExtractor.nearestTitle(node: Element): String {
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
internal fun HtmlExtractor.titleText(el: Element): String {
    val own = el.ownText().replace(Regex("\\s+"), " ").trim()
    if (own.isNotEmpty()) {
        val a = el.selectFirst("a")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (a.isNotEmpty()) return a
    }
    return el.text().trim()
}
