package com.videoshell.data.site

import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import com.videoshell.data.model.VideoRow
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
 * [rows] / [mergeRows] / [growPlan] / [summary] 不碰网络、不碰 Android，可以在离线 harness
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
 * 不靠 diff 猜，这里把单站结果切块这件事抽成纯函数 [rows]：
 * 一格一格的块拼起来 == [mergeRows] 的结果，所以界面只要数"比我靠前的站各占多少行"就能
 * 算出插入位置，**永远不会和最终结果对不上**。
 *
 * ## v1.0.39：**块与块之间要有标题行**
 *
 * v1.0.38 及以前，一个站的一整块是**紧挨着**铺下去的，界面上靠卡片副标题里那点灰色小字
 * 区分来源。实际看起来就是一整片没有边界的大网格 —— 而"这条来自哪个站"恰恰是聚合搜索
 * 唯一要说的事。所以每块前面加一行 [VideoRow.Header]（站名 + 条数）。
 *
 * 由此[行]成了网格的基本单位：界面按行插入、[insertAt] 按**行**计数。
 * 卡片视图（[block] / [merge]）仍然保留，它是行视图的投影，供"进度行里先显示 N 条"
 * 与那些以卡片为单位的断言使用 —— **两份判据同源，不是两套实现**。
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
     * 纯函数：**一个站**贡献的那一块**卡片**（已打上 `siteKey`、已限量、已滤掉无名条目）。
     *
     * 这是"卡片视图"：与 [rows] 同源（[rows] = 本函数的结果前面加一行分组标题）。
     * 进度行的条数、以及那些"以卡片为单位"的断言用它。
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
     * 纯函数：**一个站**贡献的那一块**网格行** = 1 行分组标题 + 它自己的卡片（v1.0.39）。
     *
     * 空块（没搜到 / 整批被丢 / key 为空）**不产出标题** —— 否则会出现一个下面什么都
     * 没有的站名，看起来像"这个站的结果没显示出来"。
     *
     * 注意 [VideoRow.Header.count] 是**卡片数**（不含标题行自己），标题上写的"N 条"就是它。
     */
    fun rows(h: SiteHits, perSite: Int = PER_SITE): List<VideoRow> {
        val cards = block(h, perSite)
        if (cards.isEmpty()) return emptyList()
        val out = ArrayList<VideoRow>(cards.size + 1)
        out += VideoRow.Header(h.key, h.name.ifBlank { h.key }, cards.size)
        cards.forEach { out += VideoRow.Card(it) }
        return out
    }

    /**
     * 纯函数：这一趟聚合拿到的，是不是这个站**整页第 1 页**的完整答案（v1.0.51）。
     *
     * ## 为什么需要这个判据
     *
     * 聚合搜索已经把每个站的 `search(q, 1)` 发出去过了 —— 也就是说，用户随后在左栏点开**某一个站**
     * 时想要的那份数据，**聚合那一趟手里就有**（这就是"每点一个站源都要重新搜"的根治点）。
     * 但"手里的这份"能不能直接当该站第 1 页用，取决于它有没有被丢过东西：
     *
     *  - **被 [PER_SITE] 截断**：站点第 1 页有 40 条，聚合只留了 24 条。若当成整页缓存下来，
     *    用户下拉翻页会从第 2 页接着走，第 1 页剩下的 16 条**永远看不到**；
     *  - **被滤掉无名条目**：[block] 会丢掉名字为空的条目（点开必然是空页），
     *    但那属于"我们的取舍"，不是"站点的第 1 页"，同样不能当整页。
     *
     * 判据因此只有一条：**[block] 出来的条数 == 原始条数** —— 一个都没丢，才算整页。
     *
     * ⚠️ 用 [block] 判而不是另写一遍"有没有超 24 / 有没有空的"：判据必须与产出**同源**，
     * 否则 [block] 将来改了过滤规则，这里会静默跟随不上。
     *
     * 另外注意本函数**不看 `error`** —— 失败的站 `items` 是空的，`block` 也是空的，
     * 于是恒为 true。调用方必须自己先判 `ok`，否则会把"这个站没连上"缓存成"这个站没有结果"。
     */
    fun complete(h: SiteHits, perSite: Int = PER_SITE): Boolean =
        block(h, perSite).size == h.items.size

    /**
     * 纯函数：各站结果 → 一份网格**行**列表（含每个站的分组标题）。
     *
     * 站点顺序**按传入顺序保留**（= Store 的顺序，最近添加的在前），
     * 不做"哪个站结果多就排前面"的花活：顺序会被用户当成"哪个站更好"的暗示，
     * 而结果条数与片源质量毫无关系。
     */
    fun mergeRows(hits: List<SiteHits>, perSite: Int = PER_SITE): List<VideoRow> =
        hits.flatMap { rows(it, perSite) }

    /**
     * 流式铺网格时的"还没到达"占位：[slots] 与站点列表等长，未返回的位置是 null。
     *
     * 等价于"先丢掉没到的，再 [mergeRows]" —— 所以**站点顺序与最终结果完全一致**，
     * 早到的站不会插到别人前面去。
     */
    fun mergeRowsArrived(slots: List<SiteHits?>, perSite: Int = PER_SITE): List<VideoRow> =
        mergeRows(slots.filterNotNull(), perSite)

    /**
     * 纯函数：各站结果 → 一份**卡片**列表（不含分组标题）= [mergeRows] 的卡片投影。
     *
     * 只用来数条数（进度行的"先显示 N 条"数的是片子不是行）。
     */
    fun merge(hits: List<SiteHits>, perSite: Int = PER_SITE): List<VideoItem> =
        cards(mergeRows(hits, perSite))

    /** [mergeRowsArrived] 的卡片投影。**顺序按站点顺序，不是到达顺序** */
    fun mergeArrived(slots: List<SiteHits?>, perSite: Int = PER_SITE): List<VideoItem> =
        cards(mergeRowsArrived(slots, perSite))

    /** 行列表 → 卡片列表（丢掉标题行）。投影只有这一份实现 */
    private fun cards(rows: List<VideoRow>): List<VideoItem> =
        rows.mapNotNull { (it as? VideoRow.Card)?.item }

    /**
     * 纯函数：第 [index] 个站的**行块**应该插到哪个下标。
     *
     * = 排在它前面、**且已经到达**的那些站的**行数**之和。用 [rows] 数，所以与 [mergeRows] 同源。
     *
     * ⚠️ 单位是**行**，不是卡片（v1.0.39 改）。界面上那个列表装的就是行，
     * 插入下标必须同单位 —— 差一行就会把标题插进上一站的卡片中间。
     */
    fun insertAt(slots: List<SiteHits?>, index: Int, perSite: Int = PER_SITE): Int {
        var at = 0
        for (i in 0 until index.coerceAtMost(slots.size)) {
            slots.getOrNull(i)?.let { at += rows(it, perSite).size }
        }
        return at
    }

    /**
     * 纯函数：检查"把第 [index] 个站的行块插到 [at] 上"是否与全量 [mergeRows] 一致。
     *
     * 存在的意义是**把界面那点算术钉死**：判定式不是"插入看起来对不对"，
     * 而是"插入之后逐行等于一次性 mergeRows"。断言里用的就是它。
     */
    fun growPlan(
        slots: List<SiteHits?>,
        index: Int,
        perSite: Int = PER_SITE
    ): Pair<Int, List<VideoRow>> = insertAt(slots, index, perSite) to
        (slots.getOrNull(index)?.let { rows(it, perSite) } ?: emptyList())

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
