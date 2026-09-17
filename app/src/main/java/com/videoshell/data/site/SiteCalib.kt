package com.videoshell.data.site

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 调试校准模式的**纯逻辑**部分：形状判据 + 容器选择器推导。
 *
 * 为什么要从 UI 里抽出来单独放一个 object：
 *
 * 1. **判据只能有一份**。若 JS 侧认一套、适配器认一套，就会出现「界面上说记下了、实际解析不用」
 *    这种最难查的不一致。JS 只负责报地址，判定一律走 [HtmlTemplates] / 这里。
 * 2. **推错了比不推更糟**。校准是「用户点出来的规则」，一旦把一个错模板固化进配方，
 *    下次会拿着错模板反复失败，而用户以为已经校准好了。所以这段逻辑必须有离线断言兜着
 *    （见 `videoshell_verify/Bs4.java`）。
 */
object SiteCalib {

    /**
     * 这一条链接像不像「分类」。
     *
     * 三类都要能分开：
     * - 分类：`/bspvt/dianying.html`（目录式别名）、`/riju`、`/vodshow/id/6.html`
     * - 详情：`/bspvd/548165.html`、`/detail/548165.html`、`/movie/23804.html`
     * - 分集：`/bspvp/548165-4-1.html`
     *
     * 顺序很重要：**先按形状认分类，再排除**。
     * 反过来（先看 [HtmlTemplates.videoIdOf] 有没有 id）会把 `/vodshow/id/6.html` 判成详情页
     * —— 那个通用详情正则 `/{目录}/{数字}.html` 也会命中它，可它明明是 maccms 的标准分类页。
     */
    fun isCategoryShape(href: String): Boolean {
        val h = href.trim()
        if (h.isEmpty()) return false
        if (HtmlTemplates.isPlayLink(h)) return false       // 分集页
        if (HtmlTemplates.isStrongDetail(h)) return false   // 明确的详情页
        return HtmlTemplates.isSlugCategory(h) ||
                HtmlTemplates.isSlugDirCategory(h) ||
                HtmlTemplates.isCategoryHref(h, false)
    }

    /**
     * 反推「用户点的这个分类链接，落在哪个导航容器里」。
     *
     * 做法：在首页 DOM 里找到那条链接，向上走，取**包含分类链接最多**的一层
     * （见到 5 个以上就停 —— 再往上就会把整页链接都圈进来）。
     *
     * 用解析出来的首页 DOM 而不是 WebView 渲染后的 DOM：适配器实际用的是前者，
     * 在这儿推出来的选择器必须在前者上有效，否则固化了也用不上。
     *
     * @return 形如 `ul.header-nav` 的选择器；推不出来返回 null
     */
    fun navSel(doc: Document, clickedAbs: String): String? {
        if (clickedAbs.isBlank()) return null
        val target = doc.select("a[href]").firstOrNull { abs(doc, it.attr("href")) == clickedAbs }
            ?: return null

        var best: Element? = null
        var p: Element? = target.parent()
        var depth = 0
        while (p != null && depth < 6) {
            val cats = p.select("a[href]").count { isCategoryShape(it.attr("href")) }
            if (cats >= 2) best = p
            if (cats >= 5) break
            p = p.parent()
            depth++
        }
        val el = best ?: return null
        val sel = selectorOf(el)
        if (sel.isBlank()) return null
        // 自校验：这个选择器在首页原文上必须真能选出东西，否则别写进配方
        return if (doc.select(sel).isNotEmpty()) sel else null
    }

    fun navSel(html: String, baseUri: String, clickedAbs: String): String? =
        runCatching { navSel(Jsoup.parse(html, baseUri), clickedAbs) }.getOrNull()

    /**
     * 给一个元素编一个「够用且稳定」的 CSS 选择器。
     *
     * 真实站点（尤其 maccms 主题）大量使用哈希类名：
     * `class="qanoq6m7wx bnyk3v83q3 fype59gc7q 46iaorb6t5 header-nav hidden-sm hidden-xs"`。
     * 取第一个类名会拿到 `qanoq6m7wx`，下次改版就失效。所以优先级：
     * 有语义的类名（含 nav/menu/head/sort/cate/tab） > 无数字的类名 > 不以数字开头的类名
     * （CSS 里数字开头的类名要转义，没必要自找麻烦）。
     */
    fun selectorOf(el: Element): String {
        val tag = el.tagName().lowercase()
        val id = el.id()
        if (!id.isNullOrBlank() && id.length <= 32) return "$tag#$id"
        val classes = el.classNames().filter { c ->
            c.length in 3..24 && !c[0].isDigit() &&
                    c.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
        val semantic = classes.firstOrNull { c ->
            val l = c.lowercase()
            SEMANTIC_WORDS.any { l.contains(it) }
        }
        return when {
            semantic != null -> "$tag.$semantic"
            classes.isNotEmpty() -> "$tag.${classes.first()}"
            else -> tag
        }
    }

    private val SEMANTIC_WORDS = listOf("nav", "menu", "head", "sort", "cate", "tab")

    private fun abs(doc: Document, href: String): String {
        val u = href.trim()
        if (u.isEmpty()) return ""
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:$u"
        return runCatching { java.net.URI(doc.baseUri()).resolve(u).toString() }.getOrElse { u }
    }
}
