package com.videoshell.data.site

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.videoshell.App

/**
 * 站点配方（Recipe）：把「从真实页面学到的东西」固化下来，跨 Activity / 跨启动复用。
 *
 * ## 为什么必须持久化（v1.0.11 修的根因）
 *
 * 详情页是独立的 `DetailActivity`，进来时会 `AdapterFactory.create(site)` **新建一个 Adapter 实例**。
 * 而模板学习结果原本只活在实例内存里（`HtmlAdapter.learnedDetailTpl`）——
 * 一到详情页就全丢，只能退回穷举模板（`/voddetail/`、`/detail/`、`/movie/`…）。
 * maccms 站只要改过目录名（金牌影视用 `bspvd`），穷举必然全 404 →
 * 症状就是「分类能出、列表能出，一点进详情就失败」。
 *
 * ## 原则
 *
 * **能从页面学到的东西，学一次就写盘；下次任何 Activity（甚至下次启动）都能直接用。**
 *
 * ## 存储
 *
 * SharedPreferences（key = `r_<host>`）。内存里再放一层 front cache，
 * 因此磁盘不可用的场景（离线 JVM harness）退化成「进程内跨实例共享」，行为一致、可测。
 *
 * 注意 Gson + Kotlin 非空的老坑：所有字段都可空 + 给默认值，且带 [ver] 版本号，
 * 模板语义变更时旧配方自动作废，避免拿一个错模板反复失败。
 */
data class SiteRecipe(
    /** schema 版本：模板语义变了就 +1，旧配方自动作废 */
    val ver: Int = VER,
    /** 本站是否走「独立详情页 + 独立播放页」结构 */
    val vodIsCategory: Boolean = false,
    /** 验证过 / 学到的详情页模板，如 `https://x.com/bspvd/{id}.html` */
    val detailTpl: String? = null,
    /** 播放页模板，如 `https://x.com/bspvp/{id}-1-1.html` */
    val playTpl: String? = null,
    val listTpl: String? = null,
    val searchTpl: String? = null,
    /** 学到的分类容器选择器（调试校准模式下由用户点击推出来） */
    val navSel: String? = null,
    /**
     * 校准学到的**分类页 URL 形状**，形如 `/bspvt/{slug}.html`。
     *
     * 比 [navSel] 更本质：同一个站的分类常散落在多个导航容器里，
     * 认形状才能一次全收（金牌影视实测：认容器只 5 个，认形状 40 个）。
     */
    val catTpl: String? = null,
    /**
     * 「最新」tab（`browse("")`）实际使用的分类页 URL。
     *
     * 有些站的**首页是客户端渲染的 hero 轮播**：SSR 里没有剧集数据、卡片图全是
     * `data:` 占位、名字是「查看剧集」这类按钮文案 ⇒ 整屏无封面、整屏假名字。
     * 一旦探到某个真分类页**确实带封面**，就记下来长期替代首页 —— 只探一次。
     */
    val homeCat: String? = null,
    /**
     * ## 形状普查**固化**下来的分类形状（v1.0.35，第三笔债）
     *
     * [HtmlTemplates.shapeCensus] 提议的形状，v1.0.34 时刻意只活在 `HtmlAdapter` 实例内存里
     * —— "先测量，再固化"（当时的教训：没验证的规则一旦写盘，就变成下一轮排查的谜题）。
     *
     * 现在它已经是**被证明可靠的判据**了（两道阈值：不同别名 ≥2 且不同文字 ≥2，
     * 且归纳与接收复用运行时同一套收集器），于是按这三条规则固化：
     *
     * - **只有真的收出 ≥2 个分类才写盘** —— 写盘的形状一定是"用过且有效"的，不是提议；
     * - **活证据优先** —— 每次解析仍会现场普查一遍，新形状排在固化值**前面**，
     *   站点改版后新形状立刻顶掉旧的；旧值只在现场全部不成立时当退路；
     * - **人工校准仍然最优先** —— 校准形状（[catTpl]）在第 1 步就被试，普查排在第 2.6 步。
     */
    val learnedCatTpl: String? = null,
    /** 自动普查形状的固化时间（0 = 没有）。自检里显示，一眼看出"这条是哪来的" */
    val learnedCatAt: Long = 0L,
    /**
     * 人工校准完成时间；> 0 表示这份配方是**校准模式**固化下来的。
     */
    val calibAt: Long = 0L,
    /** 校准摘要（三步各自学到了什么），自检报告里展示，便于判断校准到了哪一步 */
    val calibNote: String? = null,
    val updatedAt: Long = 0L
) {
    companion object {
        const val VER = 1
    }

    val isEmpty: Boolean
        get() = detailTpl == null && playTpl == null && listTpl == null &&
                searchTpl == null && navSel == null && catTpl == null && homeCat == null &&
                learnedCatTpl == null
}

object RecipeStore {

    private const val SP = "videoshell_recipe"
    private const val PREFIX = "r_"
    private val gson = Gson()

    /** 进程内 front cache；磁盘不可用时它就是唯一存储（离线 harness 靠它验证跨实例） */
    private val mem = HashMap<String, SiteRecipe>()

    private fun spOrNull(): SharedPreferences? =
        runCatching { App.instance.getSharedPreferences(SP, Context.MODE_PRIVATE) }.getOrNull()

    /** 配方按 host 归类：同一站换域名（http/https、www）仍能命中 */
    fun hostOf(baseUrl: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE)
            .find(baseUrl.trim())?.groupValues?.get(1)?.lowercase().orEmpty()

    fun load(baseUrl: String): SiteRecipe? {
        val h = hostOf(baseUrl)
        if (h.isBlank()) return null
        mem[h]?.let { return it }
        val s = runCatching { spOrNull()?.getString(PREFIX + h, null) }.getOrNull() ?: return null
        val r = runCatching { gson.fromJson(s, SiteRecipe::class.java) }.getOrNull() ?: return null
        if (r.ver != SiteRecipe.VER) return null
        mem[h] = r
        return r
    }

    fun save(baseUrl: String, r: SiteRecipe) {
        val h = hostOf(baseUrl)
        if (h.isBlank()) return
        val v = r.copy(ver = SiteRecipe.VER, updatedAt = System.currentTimeMillis())
        mem[h] = v
        runCatching {
            spOrNull()?.edit()?.putString(PREFIX + h, gson.toJson(v))?.apply()
        }
    }

    /** 读-改-写。只在真的学到新东西时调用，避免无谓写盘 */
    fun update(baseUrl: String, f: (SiteRecipe) -> SiteRecipe): SiteRecipe {
        val cur = load(baseUrl) ?: SiteRecipe()
        val next = f(cur)
        val changed = next != cur.copy(updatedAt = cur.updatedAt)
        if (changed) save(baseUrl, next) else mem[hostOf(baseUrl)] = cur
        return if (changed) load(baseUrl) ?: next else cur
    }

    fun clear(baseUrl: String) {
        val h = hostOf(baseUrl)
        if (h.isBlank()) return
        mem.remove(h)
        runCatching { spOrNull()?.edit()?.remove(PREFIX + h)?.apply() }
    }

    /** 自检报告用：把当前配方摊开成人话 */
    fun describe(baseUrl: String): String {
        val r = load(baseUrl) ?: return "（尚未学到任何模板）"
        val lines = ArrayList<String>()
        lines += "来源：      " + if (r.calibAt > 0) "调试校准模式（人工）" else "自动学习"
        lines += "分类形状：  " + (r.catTpl ?: "—")
        lines += "分类容器：  " + (r.navSel ?: "—")
        lines += "首页替代：  " + (r.homeCat ?: "—")
        lines += "详情页模板：" + (r.detailTpl ?: "—")
        lines += "播放页模板：" + (r.playTpl ?: "—")
        lines += "列表页模板：" + (r.listTpl ?: "—")
        lines += "搜索页模板：" + (r.searchTpl ?: "—")
        // 自动普查固化值单独一行：它和"人工校准"是两个来源，混在一行会让人以为是校准
        if (!r.learnedCatTpl.isNullOrBlank()) {
            lines += "自动普查：  " + r.learnedCatTpl +
                    if (r.learnedCatAt > 0) "（固化了，活证据仍优先）" else ""
        }
        lines += "站点结构：  " + if (r.vodIsCategory) "详情页 + 播放页分离" else "单页/未知"
        r.calibNote?.takeIf { it.isNotBlank() }?.let { lines += "校准记录：" + it }
        if (r.updatedAt > 0) {
            lines += "更新时间：  " +
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(r.updatedAt))
        }
        return lines.joinToString("\n")
    }
}
