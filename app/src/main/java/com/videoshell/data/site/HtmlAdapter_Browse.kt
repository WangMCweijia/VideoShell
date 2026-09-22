package com.videoshell.data.site

// HtmlAdapter 的**浏览路径**（拆出来的第一块）：分类页翻页、首页不可用时的替代、
// 候选地址拼装、分页去重。
//
// 为什么这一族放一起：它们共同回答一个问题 ——「分类页这一趟到底该请求哪些地址、
// 拿到几页算够」。分开就会出现「改翻页规则只改了拼地址那半、去重那半还是旧的」，
// 而症状是**第 2 页开始全是第 1 页的重复**（不报错，只是白翻）。
//
// 拆法与约束同 PlayerActivity_Play.kt（纯搬运 + internal 扩展函数）：
// 扩展函数访问不了 private，所以被它用到的成员在原类里是 internal；
// 守卫按「主文件 + 同主名拆分子文件」读源码，所以断言写的 HtmlAdapter.kt 覆盖本文件。
//
// ⚠️ categoriesFrom(doc) **没有**被搬走，它留在 HtmlAdapter 里：
// tools/verify 下有一批 Java 守卫在**直接调用**它（ad.categoriesFrom(home)），
// 而 Java 里没有扩展函数 —— 搬走它等于一次性判掉 7 个套件的编译。

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

internal suspend fun HtmlAdapter.browseCat(cat: String, page: Int): List<VideoItem> {
    val k = "browse|$cat"
    if (k != seenKey) {
        seenKey = k
        seenIds.clear()
    }
    return fetchList(browseUrls(cat, page), page)
}

/**
 * 「最新」tab 的兜底：**首页列表一条封面都没有**时，改用探到的真分类页。
 *
 * 为什么要做这件事（v1.0.18，野果短剧线上实测）：
 *
 * | | App 默认入口 `browse("")`（首页） | 真分类页 |
 * |---|---|---|
 * | 条数 | 53 | 30 |
 * | 有封面 | **0** | **30** |
 * | 名字 | 全是「查看剧集」 | 正常剧名 |
 * | HTML 里的图床地址 | **0 个** | 31 个 |
 *
 * 原因不是"解析没写好"，而是**首页的数据根本不在 SSR 里**：`__NUXT_DATA__` 只有 SEO 配置
 * （`cover` 值为空、页面上 56 张图全是 `data:` 占位 GIF），剧集列表由客户端 JS 再拉一次接口
 * 才填上。**这类页面无论怎么改选择器都抠不出封面**，只能换数据源。
 *
 * 保守起见必须**探到"分类页确实有封面"才替换**：只试 1~3 个分类，都没有就原样返回首页结果，
 * 不做任何"猜"的替换。探到之后固化进配方（[SiteRecipe.homeCat]），此后只探一次。
 */
internal suspend fun HtmlAdapter.substituteHome(home: List<VideoItem>): List<VideoItem>? {
    if (home.isEmpty() || home.any { it.pic.isNotBlank() }) return null
    val cat = probeCoveredCategory()
    if (cat == null) {
        // v1.0.20：替换失败要留痕。用户反馈「仍然无封面」时，自检报告 / 诊断
        // 必须能区分「探到了但没替换」与「分类都探了但全没封面」。
        val n = runCatching { categories() }.getOrDefault(emptyList()).size
        diag = "首页无封面（客户端渲染），已试探 $n 个分类页均未探到封面 —— " +
                "若分类 tab 里有封面，请把站点自检报告发出来"
        return null
    }
    homeCat = cat
    RecipeStore.update(site.baseUrl) { it.copy(homeCat = cat) }
    diag = "首页无封面数据（客户端渲染），已改用分类页 $cat"
    return browseCat(cat, 1)
}

/** 探前 6 个真分类页，返回**确实带封面**的那一个；都没有则 null（不替换） */
internal suspend fun HtmlAdapter.probeCoveredCategory(): String? {
    val cands = runCatching { categories() }.getOrDefault(emptyList())
        .map { abs(it.id) }
        .filter { it.startsWith("http") }
        .distinct()
        .take(6)
    for (c in cands) {
        if (fetchList(listOf(c), 1).any { it.pic.isNotBlank() }) return c
    }
    return null
}

internal fun HtmlAdapter.browseUrls(ref: String, page: Int): List<String> {
    if (ref.startsWith("http") || ref.startsWith("/")) {
        val u = abs(ref)
        if (page <= 1) return listOf(u)
        // 分页：先试模板（能从 URL 里抠出分类 id 时），再试 ?page=N，最后回落原页（靠去重收尾）
        val id = HtmlTemplates.typeIdOf(u, vodIsCategory)
        val tpls = if (id == null) emptyList()
        else ordered(listTpl, HtmlTemplates.listCandidates(root)).map { build(it, id = id, page = page) }
        // 目录式分类（/meijutt）没有数字 id，WordPress 系的分页是 /page/N
        val slugPage = if (id == null) listOf("$u/page/$page", "$u/page/$page/") else emptyList()
        return tpls + slugPage + listOf(withQueryPage(u, page), u)
    }
    if (ref.isBlank()) {
        // 空 id = 首页"最新"，站点首页没有分页
        return if (page <= 1) listOf(site.baseUrl) else emptyList()
    }
    return ordered(listTpl, HtmlTemplates.listCandidates(root))
        .map { build(it, id = ref, page = page) }
}
// ------------------------------------------------------------------ 内部

/**
 * 过滤分页结果：
 * - 返回 null 表示"这个 URL 不适合当前页"，去试下一个候选；
 * - 返回空列表表示"没有新内容了"，前端据此收尾。
 */
internal fun HtmlAdapter.accept(items: List<VideoItem>, page: Int): List<VideoItem>? {
    if (items.size < 2) return null
    if (page <= 1) {
        seenIds.clear()
        seenIds.addAll(items.map { it.id })
        return items
    }
    val fresh = items.filter { it.id !in seenIds }
    if (fresh.isEmpty()) return emptyList()
    seenIds.addAll(fresh.map { it.id })
    return fresh
}

internal suspend fun HtmlAdapter.fetchList(urls: List<String>, page: Int): List<VideoItem> {
    for (u in urls) {
        val html = Http.getOrNull(u, referer = site.baseUrl) ?: continue
        val doc = Jsoup.parse(html, site.baseUrl)
        rememberShape(doc)
        val fresh = accept(HtmlExtractor.parseList(doc, site.baseUrl, vodIsCategory, html), page)
        if (fresh == null) continue
        learnDetailTpl(doc)
        if (fresh.isEmpty()) return emptyList()
        if (urls.size > 1 && u == urls.first()) {
            val g = guessTpl(u, page)
            if (g != null && g != listTpl) {
                listTpl = g
                RecipeStore.update(site.baseUrl) { it.copy(listTpl = g) }
            }
        }
        return fresh
    }
    return emptyList()
}
