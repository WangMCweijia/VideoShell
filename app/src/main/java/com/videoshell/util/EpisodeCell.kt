package com.videoshell.util

import com.videoshell.data.model.Episode

/**
 * 分集格子的显示规划（v1.0.49 · 方案 3）。
 *
 * ## 要解决的问题（先把物理量算出来）
 *
 * 综艺的分集名是「20240921 第10期 嘉宾：周深」这种 15～25 字符的长串。竖屏 5 列时
 * 每格内容宽 62dp，扣掉内边距后**文字区只剩 54dp**；12sp 汉字约 12dp 宽、数字约
 * 6.6dp ⇒ 只放得下 **4 个汉字 / 8 位半角**。截断点因此永远落在日期里，结果是
 * **整屏格子都长成「202409…」，彼此无法区分** —— 这比"只看到名字的一半"严重得多，
 * 因为用户连"点哪一个是第 10 期"都判断不出来。
 *
 * ## 两条正交的改法，叠起来用
 *
 * 1. **拆名**（[of]）：把「第N期」提到主行、日期与其余文字退到副行。
 *    解决"**分得清**"——不改列数就能生效。
 * 2. **降列**（[plan]）：整列名字普遍偏长时把列数降下来（竖 5→3、横 8→5），
 *    每格文字区从 54dp 涨到 97dp，副行才真的放得下「09-21 嘉宾：周深」。
 *    解决"**看得见**"。
 *
 * 单用哪一个都不够：只拆名 ⇒ 副行窄到只能塞一个日期；只降列 ⇒ 单行 97dp 也只
 * 刚够 `20240921 第10期`，再长一点就又把期号截掉了。
 *
 * ## 降列是**按整列统计**决定的，不是逐个格子
 *
 * 逐个格子决定会让同一列里出现「有副行 / 无副行」两种高度，网格参差不齐 ——
 * 那比字被截更难看成"设计过的界面"。所以 [plan] 一次性给出列数 + 是否两行，
 * 整列统一，[EpisodeAdapter] 照做即可。
 *
 * ## ⚠️ 只做显示规划，一个字都不改数据
 *
 * [Episode.name] 全程不动。进度身份是「站点|剧名|集名#组内序号」，上一集/下一集、
 * 自动连播也都走它 —— 改了名字这些全部错位（进度会被记到别的集上）。
 * 拆名只发生在 `EpisodeAdapter.bind()` 里，出了那一行就没人知道拆过。
 */
object EpisodeCell {

    /** 一格的显示文本 */
    data class Cell(
        /** 主行大字：期号（`10`），或退化时的日期（`09-21`）/ 原名 */
        val main: String,
        /** 紧跟主行、小一号的单位字（`期`/`集`/`话`/`回`/`部`）；无则空串 */
        val unit: String,
        /** 副行小字：日期 + 剩余文字；无则空串（格子仍占位，保证整列等高） */
        val sub: String
    )

    /** 网格规划：列数 + 是否走主副两行 */
    data class Plan(val cols: Int, val twoLine: Boolean)

    // ------------------------------------------------------------------ 列数决策

    /** 竖屏 / 横屏的基础列数（与 v1.0.48 之前的写死值一致，短名站点完全不受影响） */
    private const val BASE_PORT = 5
    private const val BASE_LAND = 8

    /** 长名站点降到的列数 */
    private const val REDUCED_PORT = 3
    private const val REDUCED_LAND = 5

    /**
     * 一格的文字区能放几个**汉字宽**（竖屏 5 列 ≈ 54dp / 12sp ≈ 4.5）。
     * 超过它就必然出现省略号 —— 这是"要不要降列"的物理门槛。
     */
    private const val NARROW_W = 4.5

    /**
     * 名字的"物理宽度"：ASCII 记 0.5，其余（汉字、全角标点）记 1。
     *
     * 为什么不用 `String.length`：`20240921` 是 8 个字符但只占 4 个汉字宽，
     * 按长度算会把它误判成超长；而 `第10期` 是 4 个字符、占 3 个汉字宽。
     * 判据必须跟"占多宽"对齐，不能跟"几个字符"对齐。
     */
    private fun width(s: String): Double =
        s.sumOf { if (it.code in 0x20..0x7E) 0.5 else 1.0 }

    /**
     * 整列名字是否"普遍偏长"。
     *
     * 取**中位数**而不是平均值：一列里若只有个别超长（`20240921 特别加更版`），
     * 不该让整列跟着降列；中位数天然抗离群。
     * 样本少于 4 条时改用最大值 —— 3 集以内的列表没有"普遍"可言，此时宁可按
     * 最长的那个保守处理（降列只是格子变少，代价远小于看不全）。
     */
    private fun longNames(names: List<String>): Boolean {
        if (names.isEmpty()) return false
        val ws = names.map { width(it) }.sorted()
        val med = if (ws.size < 4) ws.last() else ws[ws.size / 2]
        return med > NARROW_W
    }

    /**
     * 给一整列分集算网格规划。
     *
     * @param landscape 当前是否横屏（横屏基础 8 列、降列后 5 列）
     */
    fun plan(list: List<Episode>, landscape: Boolean): Plan {
        val base = if (landscape) BASE_LAND else BASE_PORT
        if (!longNames(list.map { it.name })) return Plan(base, false)
        return Plan(if (landscape) REDUCED_LAND else REDUCED_PORT, true)
    }

    // ------------------------------------------------------------------ 拆名

    /**
     * 8 位连写日期：`20240921`。
     *
     * 两侧的 `(?<!\d)` / `(?!\d)` 是必需的 —— 没有它们，`202409211` 这种 9 位串会被
     * 截成"前 8 位"当日期；有了它们就必须正好 8 位。
     */
    private val DATE_COMPACT = Regex("(?<!\\d)(\\d{4})(\\d{2})(\\d{2})(?!\\d)")

    /**
     * 带分隔符的日期：`2024-09-21` / `2024.9.21` / `2024年9月21日`。
     *
     * ⚠️ 分隔符是**必需**的（不在 `?` 里），否则 `20245-8-181` 这种站点的伪 ID 串
     * 会被解成「2024 年 5 月 8 日」—— 年份后面直接跟 `5`，那一段本来就不是日期。
     */
    private val DATE_SPLIT = Regex(
        "(?<!\\d)(\\d{4})\\s*[-./年月]\\s*(\\d{1,2})\\s*[-./月]?\\s*(\\d{1,2})\\s*[日号]?(?!\\d)"
    )

    /**
     * 把一个集名拆成 主行 / 单位字 / 副行。
     *
     * 优先级是 **期号 > 日期**：用户认综艺的默认单位就是"第几期"，日期只在没有期号时
     * 顶上（`20240921` 这种）。两者都拆不出 ⇒ 主行直接放原名、副行留空 ——
     * 格子高度由整列统一决定（见 [Plan.twoLine]），不会因为副行空而变矮。
     */
    fun of(name: String): Cell {
        val t = name.trim()
        val date = findDate(t)
        val mark = EpisodeOrder.markerInTitle(t)

        // 需要从原文里剪掉的两段：日期、以及「第N期」本身
        val cuts = ArrayList<IntRange>(2)
        date?.let { cuts += it.range }
        mark?.let { cuts += it.range }
        val left = cutOut(t, cuts)

        if (mark != null) {
            // 主行 = 期号 + 单位字；副行 = 日期与剩余文字（哪个有放哪个）
            val sub = listOfNotNull(date?.let { fmt(it) }, left.ifBlank { null })
                .joinToString(" ")
            return Cell(mark.no.toString(), mark.unit, sub)
        }
        if (date != null) {
            // 没有期号：日期当主行，剩余文字全部留给副行
            return Cell(fmt(date), "", left)
        }
        return Cell(t, "", "")
    }

    /** 取第一个**月份/日数合法**的日期（防 `1080` 这类数字串被当成日期） */
    private fun findDate(t: String): MatchResult? =
        (DATE_COMPACT.find(t) ?: DATE_SPLIT.find(t))?.takeIf { it.plausible() }

    private fun MatchResult.plausible(): Boolean {
        val mo = groupValues[2].toIntOrNull() ?: return false
        val d = groupValues[3].toIntOrNull() ?: return false
        return mo in 1..12 && d in 1..31
    }

    /** 日期 → `MM-dd`（主副行都窄，年份在这个场景里没有用） */
    private fun fmt(m: MatchResult): String {
        val mo = m.groupValues[2].toIntOrNull() ?: return m.value
        val d = m.groupValues[3].toIntOrNull() ?: return m.value
        return "%02d-%02d".format(mo, d)
    }

    /**
     * 从 [s] 里剪掉 [ranges] 这些区间，再把首尾残留的分隔符/空白清掉。
     *
     * 清理这一步不是装饰：`第10期 · 嘉宾：周深` 剪掉「第10期」后会剩
     * ` · 嘉宾：周深`，不 trim 的话副行会以一个孤零零的分隔符开头。
     * 区间按起点排序后再走一遍，是为了容忍"日期在期号后面"这种顺序。
     */
    private fun cutOut(s: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        for (r in ranges.sortedBy { it.first }) {
            if (r.first > i) sb.append(s, i, r.first)
            i = maxOf(i, r.last + 1)
        }
        if (i < s.length) sb.append(s, i, s.length)
        return sb.toString().trim().trim(*SEP)
    }

    private val SEP = charArrayOf(
        '·', '・', '-', '—', '~', '|', '、', '，', ',', '：', ':',
        '（', '(', '）', ')', '【', '】', '[', ']', '《', '》', '　', ' '
    )
}
