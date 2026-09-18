package com.videoshell.util

import com.videoshell.data.model.Episode

/**
 * 剧集列表的「顺序 / 倒序」。
 *
 * 为什么要它：站点给分集的顺序各家不同 —— 有的 1→N 正着给（多数 maccms 主题），
 * 有的 N→1 倒着给（zqkhmy 的「蓝光2k」源是 181、180…1），同一个站不同线路的给法还能不一样。
 * 用户只能看站点脸色，选个集要滑到底。
 *
 * 取值规则分两种，**不猜**：
 * - 全部集都能取出集号 ⇒ 按集号排（正序 = 第 1 集在前，倒序 = 最后一集在前）；
 * - 有任何一个取不出集号（`HD中字`、`预告`、`正片`…）⇒ 不猜，正序 = 站点原顺序，倒序 = 反过来。
 *
 * 集号取自**集名里最后一段数字**（`第181集`→181、`181`→181、`EP12`→12），
 * 名字里没数字才退到地址里最后一段数字（`/play/20245-8-181.html`→181，问号后不算）。
 *
 * ⚠️ 这里排的只是**显示顺序**。进度记忆 / 上一下一集一律走**组内原始序号**（见 [order]），
 * 否则「看完第 5 集」的进度会被记到别的集上。
 */
object EpisodeOrder {

    private val NUM = Regex("\\d{1,4}")

    /**
     * 集名的取号顺序（**先按形状取、再退到"最后一个数字"**）。
     *
     * 为什么不直接取最后一个数字：集名里常夹着别的数字 —— 站点的 title 属性是
     * 「剧名 第1集 1080P」这种，取最后一个数字会得到 1080，整列顺序当场乱掉。
     * 按形状取就能稳稳拿到 1。
     */
    private val NAME_PATTERNS = listOf(
        Regex("第\\s*(\\d{1,4})\\s*[集话期回部]"),   // 第181集 / 第 1 话
        Regex("^(\\d{1,4})$"),                       // 181 / 01（zqkhmy 的「蓝光2k」源就是纯数字）
        Regex("(?i)EP?\\s*(\\d{1,4})")               // EP12 / S01E02
    )

    /** 集号；取不到返回 null */
    fun noOf(ep: Episode): Int? = noFromName(ep.name) ?: lastNumber(ep.url.substringBefore('?'))

    private fun noFromName(name: String): Int? {
        for (p in NAME_PATTERNS) {
            val v = p.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (v != null) return v
        }
        return lastNumber(name)
    }

    private fun lastNumber(s: String): Int? {
        var v: Int? = null
        for (m in NUM.findAll(s)) v = m.value.toIntOrNull()
        return v
    }

    /**
     * 按方向给出**显示顺序上的原始序号**（元素 = 组内原始序号）。
     *
     * 返回原始序号而不是排好序的列表，是为了让调用方无论如何都能把「第几个」映射回去 ——
     * 进度记忆、上一集/下一集、自动连播都只认原始序号，排序不该把它们带偏。
     */
    fun order(list: List<Episode>, desc: Boolean): List<Int> {
        if (list.size < 2) return list.indices.toList()
        val keys = list.map { noOf(it) }
        if (keys.any { it == null }) {
            // 取不到集号就不猜：正序 = 站点原顺序，倒序 = 反过来
            val idx = list.indices.toList()
            return if (desc) idx.reversed() else idx
        }
        val asc = list.indices.sortedBy { keys[it]!! }   // 稳定排序：集号相同保持站点顺序
        return if (desc) asc.reversed() else asc
    }

    /** 排好序的分集（[order] 的便捷包装） */
    fun arrange(list: List<Episode>, desc: Boolean): List<Episode> =
        order(list, desc).map { list[it] }
}
