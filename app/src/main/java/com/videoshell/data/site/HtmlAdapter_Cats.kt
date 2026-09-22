package com.videoshell.data.site

// HtmlAdapter 的**分类采集器**（拆出来的第三块）。
//
// categoriesFrom（本族的总入口）**留在 HtmlAdapter 里** —— Java 守卫直接调它；
// 这里住的是它用的四个收集器：目录式 / 斜杠目录 / 模板命中 / 汇总。
//
// 为什么要一起看：它们共用一套黑名单与功能页判据（funcSlug / catBadWords /
// slugNavContainers），顺序也有语义 —— 目录式排在前面且**命中即返回**
// （v1.0.35 那次「该进的没进、不该进的进了」就是这条）。
// 拆法与约束同上（纯搬运 + internal 扩展函数）。

import com.videoshell.data.model.Category
import com.videoshell.data.model.PlayGroup
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
 * 目录式分类（`/meijutt`、`/riju`…）：只在导航容器内认，避免收进 `/gbook` 这类功能页。
 *
 * [only] 非空时只扫这些容器 —— 「调试校准模式」固化的选择器就走这条路。
 */
internal fun HtmlAdapter.collectSlugCategories(
    doc: Document,
    out: LinkedHashMap<String, Category>,
    only: List<String>? = null
) {
    val host = hostOf(site.baseUrl)
    // 遍历**全部**导航容器：同一个站的分类会分散在主导航/顶部栏/底部导航里，只取第一个必然漏
    for (sel in (only ?: slugNavContainers)) {
        for (box in doc.select(sel)) {
            for (a in box.select("a[href]")) {
                if (a.selectFirst("img") != null) continue
                val href = a.attr("href").trim()
                // 两种写法都认：`/riju`（无后缀）与 `/bspvt/dianying.html`（带 .html）。
                // maccms 后台的「分类别名」两种写法都常见，只认一种就会整站没有分类。
                if (!HtmlTemplates.isSlugCategory(href) && !HtmlTemplates.isSlugDirCategory(href)) continue
                if (isFuncSlug(href)) continue

                val name = a.text().replace(Regex("\\s+"), " ").trim()
                if (name.isBlank() || name.length > 10) continue
                if (name in catBlacklist) continue
                if (catBadWords.any { name.contains(it) }) continue
                // 同名去重：同一个分类常同时出现在主菜单、二级面板与底部导航里，
                // URL 可能各不相同（`/bspvt/dianshiju.html` vs `/bspvs/dianshiju-----.html`），
                // 只按 URL 去重会在分类栏里出现一串重复标签。
                if (out.values.any { it.name == name }) continue

                val url = abs(href)
                if (host.isNotBlank() && !hostOf(url).equals(host, true)) continue
                if (out.containsKey(url)) continue
                out[url] = Category(url, name, "0")
            }
        }
    }
}

/**
 * URL 的最后一段是不是「站点功能页」词汇（`/contact/`、`/search.html`、`/privacy`…）。
 * 见 [funcSlug] 的说明：功能页词汇是封闭集合，写词表是可靠的。
 */
internal fun HtmlAdapter.isFuncSlug(href: String): Boolean {
    val p = href.trim().substringBefore('?').substringBefore('#').trimEnd('/')
    if (p.isBlank()) return false
    val seg = p.substringAfterLast('/').substringBeforeLast('.').lowercase()
    return seg.isNotBlank() && seg in funcSlug
}

/**
 * 「尾斜杠目录式分类」：`/tag/AI%E7%9F%AD%E5%89%A7/`、`/drama/rec-hot-drama/`。
 *
 * ## 为什么必须全文档扫
 *
 * 实测野果短剧（Nuxt3 SSR 站）首页 `<nav>` 里只有 4 条链接
 * （推荐 / 探索分类 / 排行榜 / 回家的路），而真分类是 `/tag/` 下的 30+ 个标签 ——
 * 它们散落在页面的各个推荐区块里。靠容器收集只能拿到 4 条功能页，
 * 这正是 v1.0.12 那条教训的又一次重演（认形状 > 认容器）。
 *
 * ## 为什么必须聚类
 *
 * 全文档扫会顺带收进 `/rank/drama/`、`/explore/drama/` 这类**功能页** ——
 * 它们的 URL 形状和分类一模一样（都是 `/{目录}/{别名}/`），
 * 靠白名单（`rank`/`explore`/`search`…）永远追不上站点改版。
 *
 * 真正的分界线是**数量**：分类目录下面会有很多个**不同的别名**
 * （实测 `/tag/` 有 30+ 个不同 slug），功能页只有一个（`/rank/drama/`、`/explore/drama/`
 * 的别名都是 `drama`）。所以：**同一目录下不同别名 ≥ 2 且不同名称 ≥ 2** 才算分类目录。
 *
 * 名称那一半同样必要：`/drama/rec-hot-drama/` 这类栏目页链接在首页上文字全是
 * 「查看更多」，名字去重后只剩 1 个 —— 按名字判它就不是分类，自然被排除，
 * 不会在分类栏里摆出 6 个一模一样的「查看更多」。
 *
 * [expectDir] 非空 = 用户在校准模式亲手点过这个目录，此时只认它、且不再要求聚类
 * （用户的主权高于启发式，这是 v1.0.14 定下的分工）。
 */
internal fun HtmlAdapter.collectSlashDirCategories(
    doc: Document,
    out: LinkedHashMap<String, Category>,
    expectDir: String? = null
) {
    val host = hostOf(site.baseUrl)

    // dir -> (slug -> (name, url))，LinkedHashMap 保证分类栏顺序与页面出现顺序一致
    val byDir = LinkedHashMap<String, LinkedHashMap<String, Pair<String, String>>>()

    for (a in doc.select("a[href]")) {
        val href = a.attr("href").trim()
        val ds = HtmlTemplates.slashDirOf(href) ?: continue
        // 分集/播放按钮同样长这样（`/drama/video/3381/`），别名是纯数字已被判据挡掉，
        // 但保险起见：带图片的锚点一律不是分类
        if (a.selectFirst("img") != null) continue
        if (isFuncSlug(href)) continue
        // 目录名本身是功能页词汇的（`/search/xxx/`）也排除
        if (ds.first.lowercase() in funcSlug) continue
        // 分页段（`/tag/x/page/2/` 是三段，不会走到这里；防 `/tag/page/` 这类）
        if (ds.second.equals("page", true)) continue

        val name = a.text().replace(Regex("\\s+"), " ").trim()
        if (name.isBlank() || name.length > 10) continue
        if (name in catBlacklist) continue
        if (catBadWords.any { name.contains(it) }) continue

        val url = abs(href)
        if (host.isNotBlank() && !hostOf(url).equals(host, true)) continue

        val dir = ds.first
        if (expectDir != null && !dir.equals(expectDir, true)) continue
        byDir.getOrPut(dir) { LinkedHashMap() }.putIfAbsent(ds.second, name to url)
    }

    for (slugs in byDir.values) {
        if (expectDir == null) {
            // 聚类判据：同一目录下至少 2 个不同别名、且至少 2 个不同名字
            if (slugs.size < 2) continue
            if (slugs.values.map { it.first }.distinct().size < 2) continue
        }
        for (nv in slugs.values) {
            val (name, url) = nv
            if (out.values.any { it.name == name }) continue   // 同名去重（同一分类常多处出现）
            if (out.containsKey(url)) continue
            out[url] = Category(url, name, "0")
        }
    }
}

/**
 * 按「校准固化的分类形状」扫全文档。
 *
 * 这是**唯一**允许全文档认分类的地方 —— 因为目录名是站点自己配的（金牌影视 `/bspvt/`），
 * 比"任意目录"精确得多，不会像 [collectSlugCategories] 那样必须靠容器白名单兜着，
 * 也就不会把 `/gbook`、`/label` 这类功能页收进来。
 */
internal fun HtmlAdapter.collectByCatTpl(
    doc: Document,
    out: LinkedHashMap<String, Category>,
    tpl: String
) {
    val host = hostOf(site.baseUrl)
    for (a in doc.select("a[href]")) {
        if (a.selectFirst("img") != null) continue
        val href = a.attr("href").trim()
        if (!HtmlTemplates.matchesCatTpl(href, tpl)) continue

        val name = a.text().replace(Regex("\\s+"), " ").trim()
        if (name.isBlank() || name.length > 10) continue
        if (name in catBlacklist) continue
        if (catBadWords.any { name.contains(it) }) continue
        // 同一分类常在多处出现且 URL 各不相同，按名字去重（同 collectSlugCategories）
        if (out.values.any { it.name == name }) continue

        val url = abs(href)
        if (host.isNotBlank() && !hostOf(url).equals(host, true)) continue
        if (out.containsKey(url)) continue
        out[url] = Category(url, name, "0")
    }
}

internal fun HtmlAdapter.collectCategories(
    anchors: Elements,
    out: LinkedHashMap<String, Category>,
    forceCategory: Boolean = false
) {
    for (a in anchors) {
        if (a.selectFirst("img") != null) continue
        val href = a.attr("href").trim()
        if (href.isEmpty()) continue
        if (href.startsWith("javascript") || href.startsWith("#") || href.startsWith("mailto")) continue
        if (!HtmlTemplates.isCategoryHref(href, vodIsCategory || forceCategory)) continue

        val name = a.text().replace(Regex("\\s+"), " ").trim()
        if (name.isBlank() || name.length > 10) continue
        if (name in catBlacklist) continue
        if (name.endsWith(":") || name.endsWith("：")) continue

        val url = abs(href)
        if (out.containsKey(url)) continue
        out[url] = Category(url, name, "0")
    }
}
