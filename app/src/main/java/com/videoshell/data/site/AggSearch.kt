package com.videoshell.data.site

import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「搜全站源」：把同一个关键词同时发给**所有**已保存站点，再把结果拼成一份网格。
 *
 * ## 为什么拆成一个独立对象
 *
 * 扇出（并发发请求）与拼接（纯函数）是两件事，**只有后者需要被断言**。
 * [block] / [merge] / [growPlan] / [summary] 不碰网络、不碰 Android，可以在离线 harness
 * 里逐条验证；把它们塞在 Activity 里就永远只能靠肉眼。
 *
 * ## 三个刻意的设计
 *
 * 1. **不做跨站去重**。同一部剧在三个站上就是三个可用的源 —— 那正是用户开这个功能的
 *    理由（这个站的是抢先版、那个站的是完整版）。去重只会把可选项藏起来。
 * 2. **每站限量**（[PER_SITE]）。一个站的搜索结果动辄上百条，不限量的话列表会被
 *    第一个站整个占满，"全站"就退化成"第一站"。
 * 3. **失败也留痕**。[SiteHits.error] 不是给人看的日志，而是界面要显示的东西 ——
 *    "5 个站里有 2 个没出结果"和"全网都没这部片"是完全不同的结论，
 *    只说后者等于把我们的问题说成用户的问题。
 *
 * ## v1.0.38：**先铺已到达的**
 *
 * 旧实现用 [run] 等**所有**站返回再一次性铺网格。十个站里有一个慢站（或一个 12s 超时站），
 * 用户就得对着空网格干等十几秒 —— 而那时前九个站的结果早就到手了。
 *
 * 流式版本（[runStreaming]）每有一个站返回就把它那一**块**插进去。为了让"插到哪里"
 * 不靠 diff 猜，这里把单站结果切块这件事抽成纯函数 [block]：
 * 一格一格的块拼起来 == [merge] 的结果，所以界面只要数"比我靠前的站各有多少条"就能
 * 算出插入位置，**永远不会和最终结果对不上**。
 */
object AggSearch {

    /** 每个站最多收多少条。太多会被一个站刷屏，"全站"名不副实 */
    const val PER_SITE = 24

    /** 单站超时：一个站卡住不能把整次搜索拖死 */
    const val PER_SITE_TIMEOUT_MS = 12_000L

    data class SiteHits(
        val key: String,
        val name: String,
        val items: List<VideoItem>,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * 纯函数：**一个站**贡献的那一块（已打上 `siteKey`、已限量、已滤掉无名条目）。
     *
     * [merge] 就是"把各站的块按顺序拼起来"，[growPlan] 则靠它算出插入位置。
     * 两处共用这一个函数 ⇒ 界面增量铺出来的列表与一次性 [merge] 的结果**逐条相等**。
     */
    fun block(h: SiteHits, perSite: Int = PER_SITE): List<VideoItem> {
        if (h.key.isBlank()) return emptyList()
        val cap = if (perSite <= 0) Int.MAX_VALUE else perSite
        val out = ArrayList<VideoItem>(minOf(cap, h.items.size))
        for (it in h.items) {
            if (out.size >= cap) break
            if (it.name.isBlank()) continue     // 无名的条目点开必然是空页，不如不显示
            out += it.copy(siteKey = h.key)
        }
        return out
    }

    /**
     * 纯函数：各站结果 → 一份网格列表。
     *
     * 站点顺序**按传入顺序保留**（= Store 的顺序，最近添加的在前），
     * 不做"哪个站结果多就排前面"的花活：顺序会被用户当成"哪个站更好"的暗示，
     * 而结果条数与片源质量毫无关系。
     */
    fun merge(hits: List<SiteHits>, perSite: Int = PER_SITE): List<VideoItem> =
        hits.flatMap { block(it, perSite) }

    /**
     * 流式铺网格时的"还没到达"占位：[slots] 与站点列表等长，未返回的位置是 null。
     *
     * 等价于"先丢掉没到的，再 [merge]" —— 所以**站点顺序与最终结果完全一致**，
     * 早到的站不会插到别人前面去。
     */
    fun mergeArrived(slots: List<SiteHits?>, perSite: Int = PER_SITE): List<VideoItem> =
        merge(slots.filterNotNull(), perSite)

    /**
     * 纯函数：第 [index] 个站的块应该插到哪个下标。
     *
     * = 排在它前面、**且已经到达**的那些站的条数之和。用 [block] 数，所以与 [merge] 同源。
     */
    fun insertAt(slots: List<SiteHits?>, index: Int, perSite: Int = PER_SITE): Int {
        var at = 0
        for (i in 0 until index.coerceAtMost(slots.size)) {
            slots.getOrNull(i)?.let { at += block(it, perSite).size }
        }
        return at
    }

    /**
     * 纯函数：检查"把第 [index] 个站的块插到 [at] 上"是否与全量 [merge] 一致。
     *
     * 存在的意义是**把界面那点算术钉死**：判定式不是"插入看起来对不对"，
     * 而是"插入之后逐条等于一次性 merge"。断言里用的就是它。
     */
    fun growPlan(
        slots: List<SiteHits?>,
        index: Int,
        perSite: Int = PER_SITE
    ): Pair<Int, List<VideoItem>> = insertAt(slots, index, perSite) to
        (slots.getOrNull(index)?.let { block(it, perSite) } ?: emptyList())

    /**
     * 一行汇总，直接给界面显示。
     *
     * 措辞刻意区分三件事：**几个站出了结果** / **几个站失败** / **是不是所有站都失败**。
     * 后者意味着"这次聚合基本无效"，界面该提示用户退回单站搜索，而不是展示一个空网格。
     */
    fun summary(hits: List<SiteHits>): String {
        if (hits.isEmpty()) return "还没有站点"
        val hit = hits.count { it.items.isNotEmpty() }
        val bad = hits.count { !it.ok }
        val sb = StringBuilder("全站源：$hit/${hits.size} 个站有结果")
        if (bad > 0) {
            sb.append("（$bad 个失败：")
            sb.append(hits.filter { !it.ok }.take(3).joinToString("、") { it.name.ifBlank { it.key } })
            if (bad > 3) sb.append("…")
            sb.append("）")
        }
        return sb.toString()
    }

    /**
     * 还在等的时候的汇总行：**必须写出分母**（还没到的站有几个）。
     *
     * 只写"已有 N 条"会让用户以为搜索已经结束、剩下的站吞了 —— 而这个功能的核心承诺
     * 恰恰是"全站"。分母就是那个承诺的进度条。
     */
    fun progress(arrived: Int, total: Int, shown: Int): String =
        "全站源：$arrived/$total 个站已返回，先显示 $shown 条…"

    /** 失败的站名，供界面点开看原因 */
    fun failures(hits: List<SiteHits>): List<SiteHits> = hits.filter { !it.ok }

    /** 单站执行：并发扇出与流式都用它 ⇒ 超时/异常的判据只有一份 */
    private suspend fun oneSite(
        s: SiteConfig,
        keyword: String,
        page: Int,
        perSiteTimeoutMs: Long
    ): SiteHits {
        val name = s.name.ifBlank { RecipeStore.hostOf(s.baseUrl) }
        return runCatching {
            withTimeoutOrNull(perSiteTimeoutMs) {
                AdapterFactory.create(s).search(keyword, page)
            } ?: throw IllegalStateException("超时未返回")
        }.fold(
            onSuccess = { SiteHits(s.key, name, it) },
            onFailure = {
                SiteHits(s.key, name, emptyList(), it.javaClass.simpleName + ": " + it.message)
            }
        )
    }

    /**
     * 扇出执行（等齐版）。并发、单站限时、任何一站炸了都不影响其它站。
     *
     * 用 [Dispatchers.IO]：这里的每个 `search` 都是一次（或几次）网络请求，
     * 全部串行会让 5 个站耗时相加 —— 用户会以为"卡住了"。
     *
     * 翻页（第二页起）仍走这里：用户那时已经在看内容了，不必再流式。
     */
    suspend fun run(
        sites: List<SiteConfig>,
        keyword: String,
        page: Int,
        perSiteTimeoutMs: Long = PER_SITE_TIMEOUT_MS
    ): List<SiteHits> = coroutineScope {
        sites.map { s -> async(Dispatchers.IO) { oneSite(s, keyword, page, perSiteTimeoutMs) } }
            .awaitAll()
    }

    /**
     * 扇出执行（流式版）：**每有一个站返回就回调一次**，不等其它站。
     *
     * [onSite] 收两个参数：该站在 [sites] 里的下标（决定插到哪）与它的结果。
     * 回调在**主线程**上执行 —— 界面直接改 UI 即可，不用自己 post。
     * 返回值仍是按站点顺序排好的全量结果，供收尾时算汇总行。
     */
    suspend fun runStreaming(
        sites: List<SiteConfig>,
        keyword: String,
        page: Int,
        perSiteTimeoutMs: Long = PER_SITE_TIMEOUT_MS,
        onSite: suspend (index: Int, hits: SiteHits) -> Unit
    ): List<SiteHits> = coroutineScope {
        sites.mapIndexed { i, s ->
            async(Dispatchers.IO) {
                val h = oneSite(s, keyword, page, perSiteTimeoutMs)
                withContext(Dispatchers.Main) { onSite(i, h) }
                h
            }
        }.awaitAll()
    }
}
