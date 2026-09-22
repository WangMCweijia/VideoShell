package com.videoshell.data.site

// HtmlAdapter 的**详情页与播放地址**（拆出来的第二块）：详情兜底、播放页回捞、
// 播放地址排序。
//
// 为什么这一族放一起：detail 走完模板没拿到东西时，会顺着同一条链回捞
// （hitDetail → detailFromPlayPage → domPlayUrls）。这条链的任何一环单独改动
// 都会让「详情页看着正常、但一播就空」—— 摆在同一屏里才看得出它是一条链。
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

/** 用一条模板真抓详情页并解析分集；成功就把模板固化进配方 */
internal suspend fun HtmlAdapter.hitDetail(tpl: String, id: String): VideoDetail? {
    val url = build(tpl, id = id)
    val html = fetch(url)
    if (html == null) {
        tr("✗ $url → " + lastFetchErr.ifBlank { "请求失败" })
        return null
    }
    val doc = Jsoup.parse(html, site.baseUrl)
    lastDetailDoc = doc
    rememberShape(doc)
    // 封面与「分集解析成功与否」**无关**：先把详情页这张真海报存下来，
    // 供后面「分集只能去播放页拿」的路径使用（播放页的 og:image 常是默认图）
    resolveUrl(site.baseUrl, HtmlExtractor.parsePic(doc))
        .takeIf { it.isNotBlank() }?.let { detailPicHint = it }
    // 播放页模板在详情页上就能学到（分集按钮的 href），**与分集解析成功与否无关** ——
    // 所以放在 groups 判断之前，失败路径上也能积累这次学习成果
    learnPlayTpl(doc)
    var groups = HtmlExtractor.parseGroups(doc, site.baseUrl)
    if (groups.isEmpty()) {
        // 自研 SSR 站（Nuxt/Vue）的分集不在 DOM 里 —— 锚点是前端路由，服务端只渲染出
        // 一个"当前集"的链接（甚至全是同一个 href）。真数据在页面内嵌 JSON 里，去那儿取。
        groups = SsrPayload.groups(html)
        if (groups.isNotEmpty()) {
            tr("· $url → DOM 无分集锚点，改从页面内嵌 JSON 取到 " +
                    "${groups.sumOf { it.episodes.size }} 集")
        }
    }
    if (groups.isEmpty()) {
        tr("✗ $url → 页面 ${html.length} 字，但一个分集锚点都没认出来")
        return null
    }
    tr("✓ $url → ${groups.sumOf { it.episodes.size }} 集")
    if (detailTpl != tpl) {
        detailTpl = tpl
        RecipeStore.update(site.baseUrl) { it.copy(detailTpl = tpl) }
    }
    return buildDetail(id, doc, groups)
}

/** 回首页抓一次，从卡片链接现学详情页模板（整个 Adapter 生命周期内只做一次） */
internal suspend fun HtmlAdapter.learnFromHome(): Boolean {
    if (homeLearned) return learnedDetailTpl != null
    homeLearned = true
    val html = Http.getOrNull(root, referer = root)
    if (html == null) {
        tr("✗ 回首页现学：首页抓不到（$root）")
        return false
    }
    val doc = Jsoup.parse(html, root)
    rememberShape(doc)
    learnDetailTpl(doc)
    val t = learnedDetailTpl
    if (t == null) tr("✗ 回首页现学：首页 ${html.length} 字，但没找到可学的详情链接")
    else tr("· 回首页现学：学到详情模板 $t")
    return t != null
}

internal fun HtmlAdapter.buildDetail(id: String, doc: Document, groups: List<PlayGroup>): VideoDetail {
    val title = HtmlExtractor.parseTitle(doc)
    return VideoDetail(
        id = id,
        name = title,
        // 播放页兜底时 og:image 常是站点默认图（已被 parsePic 挡掉）⇒ 回落到详情页那张
        pic = resolveUrl(site.baseUrl, HtmlExtractor.parsePic(doc)).ifBlank { detailPicHint },
        summary = HtmlExtractor.parseSummary(doc),
        // 分集名里若带着剧名前缀（`兰香如故第01集`），按剧名削掉 —— 一屏几十集都重复剧名
        // 既挤又难扫，用户看到的就是「兰香如故 第01集」重复十几遍
        groups = HtmlExtractor.stripTitlePrefix(groups, title)
    )
}

internal suspend fun HtmlAdapter.detailFromPlayPage(id: String): VideoDetail? {
    if (playTpl.isNullOrBlank()) tr("· 播放页兜底：没有播放模板，只能盲试通用候选")
    // 顺序有讲究：**先从详情页 DOM 里真的找到的播放页链接**，再退到猜模板。
    // 自研站（Nuxt）的播放页目录名是站点私有的（`/drama/video/{id}/`），
    // 穷举 `playCandidates` 一条也匹配不上；而详情页的分集按钮里就摆着真链接，
    // 直接抓它一次，比盲试 8 个候选地址靠谱得多 —— 也快得多。
    val urls = domPlayUrls() + orderPlay(HtmlTemplates.playCandidates(root)).map { build(it, id = id) }
    for (url in urls.distinct()) {
        val html = fetch(url)
        if (html == null) {
            tr("✗ $url → " + lastFetchErr.ifBlank { "请求失败" })
            continue
        }
        val doc = Jsoup.parse(html, site.baseUrl)
        var groups = HtmlExtractor.parseGroups(doc, site.baseUrl)
        if (groups.isEmpty()) {
            // 播放页才是真数据源：SSR 把整条 `episodeAll`（每集自带播放地址）塞在页内 JSON 里
            groups = SsrPayload.groups(html)
            if (groups.isNotEmpty()) {
                tr("· $url（播放页）→ DOM 无分集，改从页内 JSON 取到 " +
                        "${groups.sumOf { it.episodes.size }} 集")
            }
        }
        if (groups.isEmpty()) {
            tr("✗ $url → 页面 ${html.length} 字，但一个分集锚点都没认出来")
            continue
        }
        tr("✓ $url（播放页兜底）→ ${groups.sumOf { it.episodes.size }} 集")
        learnPlayTpl(doc)
        return buildDetail(id, doc, groups)
    }
    return null
}

/**
 * 详情页 DOM 里指向播放页的链接（分集按钮）。
 *
 * 只在**分集容器内部**取 —— 这是安全边界：容器名（episode / playlist）全球通用，
 * 而"在分集容器里"本身就说明它是分集链接，不需要再猜 URL 形状。
 * 拿到的链接直接可用，因此自研站也不必先学会播放页模板。
 */
internal fun HtmlAdapter.domPlayUrls(): List<String> {
    val doc = lastDetailDoc ?: return emptyList()
    val out = LinkedHashSet<String>()
    for (sel in episodeContainers) {
        for (a in doc.select("$sel a[href]")) {
            val href = a.attr("href").trim()
            if (href.isEmpty() || href.startsWith("javascript") || href.startsWith("#")) continue
            val abs = abs(href)
            if (!abs.startsWith("http")) continue
            if (hostOf(abs) != hostOf(site.baseUrl) && !hostOf(abs).endsWith("." + hostOf(site.baseUrl))) {
                continue
            }
            out.add(abs)
        }
        if (out.isNotEmpty()) break
    }
    return out.toList()
}

internal fun HtmlAdapter.orderPlay(all: List<String>): List<String> =
    if (playTpl.isNullOrBlank()) all else listOf(playTpl!!) + all.filter { it != playTpl }
