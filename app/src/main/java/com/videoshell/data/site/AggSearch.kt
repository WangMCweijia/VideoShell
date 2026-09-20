package com.videoshell.data.site

import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「搜全站源」：把同一个关键词同时发给**所有**已保存站点，再把结果拼成一份网格。
 *
 * ## 为什么拆成一个独立对象
 *
 * 扇出（并发发请求）与拼接（纯函数）是两件事，**只有后者需要被断言**。
 * [merge] / [summary] 不碰网络、不碰 Android，可以在离线 harness 里逐条验证；
 * 把它们塞在 Activity 里就永远只能靠肉眼。
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
     * 纯函数：各站结果 → 一份网格列表。
     *
     * 只做两件事：① 逐条打上 `siteKey`（点它才知道去哪个站开详情）
     * ② 每站最多 [perSite] 条。
     *
     * 站点顺序**按传入顺序保留**（= Store 的顺序，最近添加的在前），
     * 不做"哪个站结果多就排前面"的花活：顺序会被用户当成"哪个站更好"的暗示，
     * 而结果条数与片源质量毫无关系。
     */
    fun merge(hits: List<SiteHits>, perSite: Int = PER_SITE): List<VideoItem> {
        if (hits.isEmpty()) return emptyList()
        val cap = if (perSite <= 0) Int.MAX_VALUE else perSite
        val out = ArrayList<VideoItem>()
        for (h in hits) {
            if (h.key.isBlank()) continue
            var n = 0
            for (it in h.items) {
                if (n >= cap) break
                if (it.name.isBlank()) continue     // 无名的条目点开必然是空页，不如不显示
                out += it.copy(siteKey = h.key)
                n++
            }
        }
        return out
    }

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

    /** 失败的站名，供界面点开看原因 */
    fun failures(hits: List<SiteHits>): List<SiteHits> = hits.filter { !it.ok }

    /**
     * 扇出执行。并发、单站限时、任何一站炸了都不影响其它站。
     *
     * 用 [Dispatchers.IO]：这里的每个 `search` 都是一次（或几次）网络请求，
     * 全部串行会让 5 个站耗时相加 —— 用户会以为"卡住了"。
     */
    suspend fun run(
        sites: List<SiteConfig>,
        keyword: String,
        page: Int,
        perSiteTimeoutMs: Long = PER_SITE_TIMEOUT_MS
    ): List<SiteHits> = coroutineScope {
        sites.map { s ->
            async(Dispatchers.IO) {
                val name = s.name.ifBlank { RecipeStore.hostOf(s.baseUrl) }
                runCatching {
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
        }.map { it.await() }
    }
}
