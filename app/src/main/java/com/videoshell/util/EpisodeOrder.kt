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
        Regex("^(\\d{1,4})$"),                       // 181 / 01（zqkhmy 的「蓝光2k」源就是纯数字）
        Regex("(?i)EP?\\s*(\\d{1,4})")               // EP12 / S01E02
    )

    /** 集号；取不到返回 null */
    fun noOf(ep: Episode): Int? = noFromName(ep.name) ?: lastNumber(ep.url.substringBefore('?'))

    private fun noFromName(name: String): Int? {
        noInTitle(name)?.let { return it }
        for (p in NAME_PATTERNS) {
            val v = p.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (v != null) return v
        }
        return lastNumber(name)
    }

    private val CN_DIGIT = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    /**
     * **集名里那个「第N集」的 N**；阿拉伯数字与中文数字都认，取不到返回 null。
     *
     * 这是全项目**唯一**一处「从内容里读集号」的判据。它以前散成了三份，
     * 三份的差别就是三类站点各错一格的来源：
     *
     * - `NAME_PATTERNS[0]`（本文件，只认阿拉伯数字）；
     * - `SsrPayload.TITLE_NO`（只认阿拉伯数字）；
     * - `YeguoMap.episodeNoIn`（只认… 谁也说不准，所以合并掉）。
     *
     * ## 为什么中文数字必须认
     *
     * 野果短剧的集名就是中文数字（`少妇白洁 第二十一集`）。只认阿拉伯数字时整列取不到号，
     * [order] 于是退到伪地址里的 id（`186514`）—— 与同一列里某个恰好带阿拉伯数字的
     * `第3集` 一比，第 3 集会被排到第 1 位。
     *
     * ## 为什么必须按「第N集」的**形状**取，而不是"最后一个数字"
     *
     * - `《庆余年》 第三季第一集` 里有「三」有「一」，只有形状能定出 1（"季"不在标记集里）；
     * - `剧名 第1集 1080P` 取最后一个数字会得到 1080（这条老坑已有断言守着）。
     *
     * ⚠️ 以后要加形状家族或数字写法（罗马数字 / `第N话` 之外的说法），**只改这一处** ——
     * `EpisodeOrder` / `SsrPayload` / `YeguoMap` 三个调用方会一起跟上。
     */
    fun noInTitle(title: String): Int? {
        val raw = EP_MARKER.find(title)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toIntOrNull()?.let { return it }
        // 中文数字：处理到百位（`二十一` / `十二` / `一百零八` 这一档够用 —— 集数不会更大）
        var total = 0
        var cur = 0
        var seen = false
        for (ch in raw) {
            when (ch) {
                '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0; seen = true }
                '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0; seen = true }
                else -> { cur = CN_DIGIT[ch] ?: return null; seen = true }
            }
        }
        return if (seen) total + cur else null
    }

    /** 「第{数字}{集话期回部}」的形状；数字部分是阿拉伯或中文，两种写法共用这一条 */
    private val EP_MARKER =
        Regex("第\\s*([0-9]+|[零一二两三四五六七八九十百]{1,4})\\s*[集话期回部]")

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
     *
     * ## ⚠️ 一列集号必须来自**同一个来源**（v1.0.36）
     *
     * [noOf] 是「集名取不到号就退到地址去取」的**逐条**回退。逐条回退放在排序里是错的：
     * 同一列里 A 集的号来自集名（`第3集`→3）、B 集的号来自地址（`…/186514`→186514），
     * 两个数量级根本不可比，排出来 A 会跑到 B 前面。
     *
     * 实测后果（野果短剧，集名是「{剧名} 第N集」且中文数字混阿拉伯数字）：
     * 「我能看到欲望值」三集叫 `我能看到欲望值 第一集` / `…第二集` / `…第3集` ——
     * 前两集名字里没有阿拉伯数字、退到伪地址取到 `186xxx`，第三集取到 `3`
     * ⇒ **第 3 集被排到第 1 位**。
     *
     * 所以这里改成**整列同源**：整列都能从集名取到号 ⇒ 用集名；否则整列试地址；
     * 还是取不齐 ⇒ 不猜（保持站点顺序 / 反过来）。这仍然是文档里那句
     * 「有任何一个取不出集号就不猜」，只是把"取不到"判在**同一个来源**上。
     *
     * ⚠️ 合并成"先算一个混合的 keys 再看有没有 null"是没用的 —— [noOf] 的地址兜底
     * 几乎总能给出一个数（伪地址里就有 id），于是「不猜」这条闸门**从来没关上过**。
     */
    fun order(list: List<Episode>, desc: Boolean): List<Int> {
        if (list.size < 2) return list.indices.toList()
        val byName = list.map { noFromName(it.name) }
        val keys = if (byName.all { it != null }) byName
        else list.map { lastNumber(it.url.substringBefore('?')) }
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
