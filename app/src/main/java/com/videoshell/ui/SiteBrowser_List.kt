package com.videoshell.ui

// SiteBrowser 的**拉列表与聚合状态位**（拆出来的第二块）。
//
// 前半是 load()：单站与全站聚合两条路，含翻页去重与迟到的作废。
// 后半是聚合失败清单、汇总行、当前目标地址、状态文案。
//
// 为什么合成一块：聚合那趟**必须把失败说出来**（"几个站有结果 / 几个站失败"），
// 否则用户只看到「没有结果」，会把某站挂掉当成「这片全网都没有」。
// 失败清单是 load 的产物、也是这几个展示函数的输入 —— 分开就会出现
// 「load 记了失败、界面不显示」这类只在特定站点组合下才暴露的问题。
// 拆法与约束同上（纯搬运 + internal 扩展函数）。

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.ListCache
import com.videoshell.data.Store
import com.videoshell.data.model.Category
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.AggSearch
import com.videoshell.data.model.VideoRow
import com.videoshell.data.site.CryptFamily
import com.videoshell.data.site.RecipeStore
import com.videoshell.data.site.RecipeTransfer
import com.videoshell.data.site.SearchEngine
import com.videoshell.data.site.SearchScope
import com.videoshell.data.site.SiteAdapter
import com.videoshell.data.site.SiteDoctor
import com.videoshell.databinding.ViewSiteBrowserBinding
import com.videoshell.player.SniffActivity
import com.videoshell.ui.adapter.CategoryAdapter
import com.videoshell.ui.adapter.SearchSuggestAdapter
import com.videoshell.ui.adapter.VideoAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 拉一页。
 *
 * 两条路：**单站**（`adapter` 干活，原有逻辑）与**全站聚合**（每个站都搜一遍再拼起来）。
 * 聚合那条路故意不走 [webRendered] 预渲染兜底：兜底要"开一个 WebView 把页面跑一遍"，
 * 而聚合是多站 —— 给每个站都开一次就等于批量拉网页，代价与收益完全不成比例。
 */
internal fun SiteBrowser.load(p: Int, append: Boolean) {
    if (loading) return
    val agg = mode == SiteBrowser.MODE_SEARCH && scope == SearchScope.ALL
    val a = adapter
    // 聚合不需要"当前站"的适配器；单站搜索/分类浏览没有它则无从加载
    if (!agg && a == null) return
    loading = true

    act.lifecycleScope.launch {
        var items: List<VideoItem>
        // >0 表示这一屏是拿缓存顶的：提交时要挂「离线缓存」横幅而不是清掉状态位
        var cachedAt = 0L
        // 聚合那条路提交的单位是**行**（每块前面多一行分组标题，v1.0.39）；
        // 单站那条路是纯卡片。两条路在这里汇合到同一个提交点，
        // 所以用一个可空变量区分，而不是把提交代码写两遍。
        var aggRows: List<VideoRow>? = null
        var why = ""
        var summary: String? = null
        var failures: List<AggSearch.SiteHits> = emptyList()

        if (agg) {
            val sites = Store.sites(act)
            // ---- 首屏：先铺已到达的（v1.0.38）----
            // 旧实现等**所有**站返回才铺。十个站里有一个 12s 超时的，用户就得对着
            // 空网格干等十几秒 —— 而那时前面几个站的结果早就到手了。
            if (!append && sites.isNotEmpty()) {
                b.tvScopeHint.setOnClickListener(null)   // 清掉上一轮搜索挂的失败入口
                val slots = arrayOfNulls<AggSearch.SiteHits>(sites.size)
                var arrived = 0
                videoAdapter.setSearchKeyword(keyword)
                AggSearch.runStreaming(sites, keyword, p) { i, h ->
                    slots[i] = h
                    arrived++
                    // 插入位置由 AggSearch 算（= 排在它前面、且已到达的那些站占的**行数**），
                    // 与最终 mergeRows 同源 ⇒ 不会出现"铺出来的"和"收尾算出来的"对不上。
                    // ⚠️ 单位是**行**不是卡片：每块前面还有一行分组标题（v1.0.39）。
                    videoAdapter.insertBlock(
                        AggSearch.insertAt(slots.toList(), i),
                        AggSearch.rows(h)
                    )
                    val shown = AggSearch.mergeArrived(slots.toList()).size
                    if (shown > 0) showState(null)
                    b.tvScopeHint.text = if (arrived < sites.size) {
                        act.getString(R.string.scope_agg_streaming, arrived, sites.size, shown)
                    } else {
                        AggSearch.summary(slots.filterNotNull())
                    }
                }
                items = AggSearch.mergeArrived(slots.toList())
                summary = AggSearch.summary(slots.filterNotNull())
                failures = AggSearch.failures(slots.filterNotNull())
                loading = false
                b.pb.visibility = View.GONE
                b.tvScopeHint.text = summary
                if (items.isEmpty()) {
                    showAggEmpty(summary, failures)
                } else {
                    // ⚠️ 这里**不能再整表提交**：网格在回调里已经逐块插好了，
                    // 再提交一次会把所有卡片重绑，用户盯着看时会闪一下
                    page = p
                    attachFailures(failures)
                }
                return@launch
            }
            // ---- 翻页：等齐即可（用户那时已经在看内容了，不必再流式）----
            val hits = AggSearch.run(sites, keyword, p)
            items = AggSearch.merge(hits)
            aggRows = AggSearch.mergeRows(hits)
            summary = AggSearch.summary(hits)
            failures = AggSearch.failures(hits)
        } else {
            val ad = a!!
            val res = runCatching {
                if (mode == SiteBrowser.MODE_SEARCH) ad.search(keyword, p) else ad.browse(currentType, p)
            }
            items = res.getOrElse { emptyList() }

            // ⑥ 预渲染兜底：首屏一条都解析不出来时，用 WebView 把页面真正跑一遍再取 DOM 解析。
            // 纯客户端渲染的站（模板注入、JS 拼卡片）在这里被救回来。
            // 接口型适配器不参与（supportsWebRender=false）—— 它的数据不在 DOM 里，
            // 白开一次 WebView 只是让用户多等几秒，然后必然还是空。
            if (items.isEmpty() && !append && !webRendered && ad.supportsWebRender) {
                webRendered = true
                val url = currentTargetUrl(ad)
                if (url != null) {
                    b.pb.visibility = View.VISIBLE
                    val html = WebRender.html(act, url)
                    if (!html.isNullOrBlank()) items = ad.parseListFromHtml(html, p)
                }
            }
            if (items.isEmpty()) {
                why = res.exceptionOrNull()
                    ?.let { it.javaClass.simpleName + ": " + it.message }.orEmpty()
            }

            // FN-8：只对**分类浏览的首屏**做内容缓存。
            //   成功 ⇒ 落盘（下次断网有得用）；
            //   失败 ⇒ 拿上次的顶上（带时效），提交时挂「离线缓存」横幅，绝不假装是刚拉的。
            // 聚合搜索与翻页都不进这条路 —— 见 ListCache 的注释。
            if (!append && mode == SiteBrowser.MODE_CATEGORY) {
                val k = site?.key.orEmpty()
                if (items.isNotEmpty()) {
                    ListCache.save(act, k, currentType, items)
                } else {
                    val cached = ListCache.load(act, k, currentType)
                    if (cached != null) {
                        items = cached.items
                        cachedAt = cached.ts
                    }
                }
            }
        }

        loading = false
        b.pb.visibility = View.GONE

        if (items.isEmpty()) {
            if (append) {
                act.toast(act.getString(R.string.no_more))
                return@launch
            }
            if (agg) {
                showAggEmpty(summary, failures)
            } else {
                val net = NetLog.lastFailure()
                showState(
                    when {
                        why.isNotBlank() -> act.getString(R.string.err_prefix, why)
                        net.isNotBlank() -> "暂无数据（$net）"
                        mode == SiteBrowser.MODE_SEARCH -> act.getString(R.string.no_result)
                        else -> "暂无数据，可换个分类试试"
                    }
                )
            }
            return@launch
        }
        if (summary != null) b.tvScopeHint.text = summary
        // 搜索模式：片名里标出关键词、并隐掉分类标签（v1.0.39）。
        // 两样由一个入口一起设，切回分类/换站时空串自动复原。
        // 就放在提交前 —— `submit(…, false)` 走 notifyDataSetChanged，会立刻用新值重绑所有行；
        // 分散到 doSearch / onCategory / bindSite 各写一遍反而容易漏（本项目踩过"漏传回调"的坑）。
        videoAdapter.setSearchKeyword(if (mode == SiteBrowser.MODE_SEARCH) keyword else "")
        val rows = aggRows
        if (rows != null) videoAdapter.submitRows(rows, append)
        else videoAdapter.submit(items, append)
        page = p
        if (cachedAt > 0L) {
            // 横幅明确写出"这是缓存 + 何时抓的"，并给一个点了就重拉的入口
            showState(act.getString(R.string.cache_banner_at, stampOf(cachedAt)))
            b.tvState.setOnClickListener { reload() }
        } else {
            showState(null)
        }
        attachFailures(failures)
    }
}

/**
 * 聚合搜索"一条都没铺出来"时的状态位。
 *
 * 汇总行常驻：**"几个站有结果 / 几个站失败"必须说出来**，
 * 否则用户只看到"没有结果"，会把我们的问题（某站挂了）当成"这片全网都没有"。
 */
internal fun SiteBrowser.showAggEmpty(summary: String?, failures: List<AggSearch.SiteHits>) {
    b.tvScopeHint.text = summary.orEmpty()
    val txt = buildString {
        append(act.getString(R.string.scope_agg_empty, keyword))
        if (failures.isNotEmpty()) {
            append("（")
            append(act.getString(R.string.scope_fail_detail, failures.size))
            append("）")
        }
    }
    showState(txt)
    attachFailures(failures)
}

/**
 * 把"查看失败的 N 个站"挂到汇总行上（v1.0.38）。
 *
 * 旧实现只在"一条结果都没有"时才给这个入口 —— 于是"9 个站有结果、1 个站挂了"
 * 这种情况永远看不到那一个站为什么挂了，而这种"少了一部分"的结果恰恰最容易被
 * 读成"这部片只有这几个站有"。
 *
 * ⚠️ [failures] 为空时**必须显式摘掉监听**：留着的话指向的是上一次的失败清单，
 * 于是"这次明明没失败，点开却写着上个站报的错" —— 本项目最忌讳的"看不见但能点"。
 */
internal fun SiteBrowser.attachFailures(failures: List<AggSearch.SiteHits>) {
    if (failures.isEmpty()) {
        b.tvScopeHint.setOnClickListener(null)
        return
    }
    b.tvScopeHint.setOnClickListener { showFailures(failures) }
    if (b.tvState.visibility == View.VISIBLE) {
        b.tvState.setOnClickListener { showFailures(failures) }
    }
}

/** 聚合搜索里失败站点的原因清单（用户点一下状态行就能看到） */
internal fun SiteBrowser.showFailures(fs: List<AggSearch.SiteHits>) {
    val msg = fs.joinToString("\n\n") {
        it.name.ifBlank { it.key } + "\n" + it.error.orEmpty()
    }
    AlertDialog.Builder(act)
        .setTitle(R.string.scope_fail_title)
        .setMessage(msg)
        .setPositiveButton(R.string.ok, null)
        .show()
}

/** 预渲染兜底要打开的地址：分类/首页的第一个候选 */
internal fun SiteBrowser.currentTargetUrl(a: SiteAdapter): String? {
    val s = site ?: return null
    if (mode == SiteBrowser.MODE_SEARCH) {
        return a.searchUrlFor(keyword, 1) ?: s.baseUrl
    }
    if (currentType.isBlank()) return s.baseUrl
    return a.browseUrlFor(currentType, 1) ?: s.baseUrl
}

internal fun SiteBrowser.showState(msg: String?) {
    b.tvState.text = msg.orEmpty()
    b.tvState.visibility = if (msg == null) View.GONE else View.VISIBLE
    // 聚合搜索会在状态行上挂"查看失败的 N 个站"；状态位一旦清空，
    // 那个入口必须一起退场 —— 留着一个"看不见但能点"的区域，是最难复现的那种 bug。
    if (msg == null) {
        b.tvState.setOnClickListener(null)
        // 汇总行上也挂着同一个入口（它更显眼），一起摘掉：两处入口只留一处活着，
        // 就会出现"状态位没了、但汇总行还能点开上一次的失败清单"
        if (scope != SearchScope.ALL) b.tvScopeHint.setOnClickListener(null)
    }
}
