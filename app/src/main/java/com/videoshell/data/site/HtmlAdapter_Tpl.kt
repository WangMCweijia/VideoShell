package com.videoshell.data.site

// HtmlAdapter 的**模板学习与地址工具**（拆出来的第四块）。
//
// 左边一半是「从这一页学一条模板」（learnDetailTpl / learnPlayTpl / rememberShape /
// detectVodShape），右边一半是纯函数（abs / hostOf / enc / build / withQueryPage /
// ordered / sameHost）。
//
// 为什么合成一块：学到的模板**必须再经同一套地址工具**回填成可请求的 URL，
// 两边用的是同一个 host 判据 —— 拆成两个文件就会出现「学的时候按 A 判同域、
// 用的时候按 B 拼地址」（症状是学到了模板但请求全 404）。
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

/** 从列表页学一条详情页模板（只学一次），学到就写进配方 */
internal fun HtmlAdapter.learnDetailTpl(doc: Document) {
    if (learnedDetailTpl != null) return
    val tpl = HtmlExtractor.detailTplHint(doc, site.baseUrl, vodIsCategory) ?: return
    if (!sameHost(tpl)) return
    learnedDetailTpl = tpl
    RecipeStore.update(site.baseUrl) { it.copy(detailTpl = it.detailTpl ?: tpl) }
}

/**
 * 从详情页学一条**播放页**模板：`/bspvp/548165-1-1.html` -> `/bspvp/{id}-1-1.html`；
 * 自研站则学 `/drama/video/3381/` -> `/drama/video/{id}/`。
 *
 * 播放页的目录名同样是站点自己起的（金牌影视 `bspvp`、厂长 `/v_play/`、野果 `/drama/video/`），
 * 穷举 [HtmlTemplates.playCandidates] 举不全。用于「详情页不给分集、只有播放页有」的兜底。
 *
 * 学习顺序：
 * 1. **分集容器内**的锚点最可信 —— 容器名（episode / playlist）是通用语义，
 *    不需要猜 URL 形状，自研站也能学到；
 * 2. 整页 [HtmlTemplates.isPlayLink] 命中的锚点（覆盖 maccms 系）。
 */
internal fun HtmlAdapter.learnPlayTpl(doc: Document) {
    if (!playTpl.isNullOrBlank()) return

    // 1) 分集容器优先：里面的锚点必然是「去播放」的链接
    for (sel in episodeContainers) {
        for (a in doc.select("$sel a[href]")) {
            val href = a.attr("href").trim()
            if (href.isEmpty() || href.startsWith("javascript") || href.startsWith("#")) continue
            val t = HtmlTemplates.tplFromNumericSegment(abs(href)) ?: continue
            if (!sameHost(t)) continue
            // 学到的必须是**播放页**：详情页自己的模板不该从这条路上回来
            if (t == detailTpl || t == learnedDetailTpl) continue
            playTpl = t
            RecipeStore.update(site.baseUrl) { it.copy(playTpl = t) }
            return
        }
    }

    // 2) 整页找：maccms 系的播放页链接
    for (a in doc.select("a[href]")) {
        val href = a.attr("href").trim()
        if (!HtmlTemplates.isPlayLink(href)) continue
        val t = HtmlTemplates.playTplFrom(abs(href)) ?: continue
        if (!sameHost(t)) continue
        playTpl = t
        RecipeStore.update(site.baseUrl) { it.copy(playTpl = t) }
        return
    }
}

/** 学到的模板必须属于本站：列表页里混进广告 / 外链时会学到别家的模板 */
internal fun HtmlAdapter.sameHost(tpl: String): Boolean {
    val a = hostIn(tpl)
    val b = hostIn(root)
    if (a.isBlank() || b.isBlank()) return false
    return a == b || a.endsWith(".$b") || b.endsWith(".$a")
}

internal fun HtmlAdapter.hostIn(url: String): String =
    Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE)
        .find(url)?.groupValues?.get(1)?.lowercase().orEmpty()

/** 首页/列表页第一次解析时确定站点形态，之后沿用；学到就写进配方 */
internal fun HtmlAdapter.rememberShape(doc: Document) {
    if (vodIsCategory) return
    if (!detectVodShape(doc)) return
    vodIsCategory = true
    RecipeStore.update(site.baseUrl) { it.copy(vodIsCategory = true) }
}

internal fun HtmlAdapter.detectVodShape(doc: Document): Boolean {
    for (a in doc.select("a[href]")) {
        if (HtmlTemplates.isDetailSignal(a.attr("href"))) return true
    }
    return false
}

/** 值不大，够用：命中过就记住，避免每页重复试错 */
internal fun HtmlAdapter.guessTpl(url: String, page: Int): String? {
    val id = HtmlTemplates.typeIdOf(url, vodIsCategory) ?: return null
    for (tpl in HtmlTemplates.listCandidates(root)) {
        if (build(tpl, id = id, page = page) == url) return tpl
    }
    return null
}

internal fun HtmlAdapter.abs(url: String): String {
    val u = url.trim()
    if (u.isEmpty()) return root
    if (u.startsWith("http")) return u
    if (u.startsWith("//")) return "https:$u"
    return "$root/${u.trimStart('/')}"
}

internal fun HtmlAdapter.hostOf(url: String): String =
    Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1).orEmpty()

internal fun HtmlAdapter.withQueryPage(url: String, page: Int): String =
    url + (if (url.contains("?")) "&" else "?") + "page=$page"

internal fun HtmlAdapter.enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

internal fun HtmlAdapter.build(tpl: String, id: String = "", page: Int = 1, kw: String = ""): String {
    val hasPageToken = tpl.contains("{page}")
    var u = tpl
        .replace("{id}", id)
        .replace("{page}", "$page")
        .replace("{kw}", enc(kw))
    if (!u.startsWith("http")) u = "$root/${u.trimStart('/')}"
    if (page > 1 && !hasPageToken) u = withQueryPage(u, page)
    return u
}

internal fun HtmlAdapter.ordered(pref: String?, all: List<String>): List<String> =
    if (pref.isNullOrBlank()) all else listOf(pref) + all.filter { it != pref }
