package com.videoshell.player

import java.net.URI

/**
 * 嗅探候选的**智能筛选**。
 *
 * 为什么需要它：一个播放页在 WebView 里跑起来后，`shouldInterceptRequest` / JS 钩子
 * 会看到**一堆**媒体请求 —— 正片、预roll 广告、暂停广告、埋点心跳、
 * 页面预加载的其它线路……旧实现只按**文件类型**打分（HLS=100 / MP4=60），
 * 广告 m3u8 与正片 m3u8 分数完全相同，平局靠"命中次数"分胜负，
 * 而埋点/广告往往被反复请求、hits 更高 ⇒ **必然选错**。
 *
 * 本对象的排序依据分两层：
 *
 * 1. **URL 层**：只做"负面剔除"（广告/埋点），**不做正面排序**。
 *    实测两个站的正片 URL（`fengbao12.com/video/<标题拼音>/<hash>/index.m3u8`、
 *    `cdn.yzzy31-play.com/<日期>/<id>_<hash>/index.m3u8`）**不含任何可用正面特征**
 *    （不含视频 id、与播放页不同源），任何"正面关键词加分"都是猜测。
 *
 * 2. **内容层（关键）**：真的把 playlist 拉下来看它是什么。
 *    正片 = 几百上千个分片（几十分钟到两小时）；广告 = 十几个分片（几十秒）。
 *    `verdict()` 就是这个判据，它不需要认识任何站点。
 *
 * 纯逻辑，无网络、无 Android 依赖 —— 可以直接跑离线断言。
 */
object SniffRank {

    /** 没能判断出内容（未探测 / 探测失败 / 清单异常） */
    const val CONTENT_UNKNOWN = 0

    /** 内容太短，基本是广告 / 预告 / 埋点清单 */
    const val CONTENT_LIKELY_AD = 1

    /** 内容长度像正片 */
    const val CONTENT_REAL = 2

    /**
     * A 档广告词：**整段**等于该词才算（路径段 / 主机标签）。
     *
     * 必须按段匹配 —— 直接 `url.contains("ad")` 会把 `download`、`load`、
     * `head`、`radar`、`upload` 这些全部误杀，那种过滤比不过滤更糟。
     */
    private val SEG_BAD: Set<String> = setOf(
        "ad", "ads", "adv", "advert", "adverts", "advertise", "advertising", "adsense",
        "adserver", "adjump", "gg", "guanggao", "tuiguang", "promotion", "promo",
        "banner", "popup", "popunder", "pop", "preroll", "midroll", "postroll",
        "trailer", "preview", "sponsor", "stat", "stats", "track", "tracker",
        "beacon", "ping", "count", "thumb", "thumbs", "sprite", "notice", "tips"
    )

    /** 疑似广告/埋点：给出保守判定，只用于**排除自动播放**，不直接丢出列表 */
    fun isSuspect(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        // 主机标签：ads.example.com / ad.cdn.com
        for (label in u.host.orEmpty().lowercase().split('.')) {
            if (label in SEG_BAD) return true
        }
        // 路径段：/ad/123.m3u8、/adjump/xx.ts
        for (seg in u.path.orEmpty().lowercase().split('/')) {
            val s = seg.substringBeforeLast('.')     // 去掉扩展名再比
            if (s.isNotEmpty() && s in SEG_BAD) return true
        }
        // 查询串里出现 ad= / type=advert 这类
        for (kv in u.query.orEmpty().lowercase().split('&', ';')) {
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            if (k in SEG_BAD && k != "id") return true
            if (v.isNotEmpty() && v in setOf("ad", "ads", "preroll", "advert")) return true
        }
        return false
    }

    fun classify(url: String): String? {
        val u = url.lowercase()
        return when {
            u.contains("m3u8") -> "HLS"
            u.contains(".mpd") -> "DASH"
            u.contains(".mp4") -> "MP4"
            u.contains(".flv") -> "FLV"
            u.contains(".ts") -> "TS"
            else -> null
        }
    }

    /** 类型基础分：能直接播的流优先；`.ts` 是分片不是入口，压到最低 */
    fun baseScore(type: String): Int = when (type) {
        "HLS" -> 100
        "DASH" -> 70
        "MP4" -> 60
        "FLV" -> 50
        else -> 10
    }

    fun score(c: SniffCandidate, videoId: String, dirHits: Map<String, Int> = emptyMap()): Int {
        var s = baseScore(c.type)
        when (c.content) {
            CONTENT_REAL -> s += 120
            CONTENT_LIKELY_AD -> s -= 150
        }
        if (c.suspect) s -= 200
        // 播放页地址里带的视频 id 若出现在候选地址里，是很强的正面信号
        if (videoId.length >= 4 && c.url.contains(videoId)) s += 40
        // 同目录下观测到大量 .ts 分片请求 ⇒ 这个清单真的在被播放（正片几百个分片，广告十几个）
        s += minOf(dirHits[c.dir] ?: 0, 10) * 4
        // 命中次数只作为弱信号（埋点也会刷高它，不能当主判据）
        s += minOf(c.hits, 6) * 3
        return s
    }

    fun rank(
        list: Collection<SniffCandidate>,
        videoId: String = "",
        dirHits: Map<String, Int> = emptyMap()
    ): List<SniffCandidate> {
        for (c in list) c.score = score(c, videoId, dirHits)
        return list.sortedWith(
            compareByDescending<SniffCandidate> { it.score }.thenByDescending { it.hits }
        )
    }

    /** URL 所在目录（用于把同目录的分片请求归并到它的清单上） */
    fun dirOf(url: String): String = url.substringBefore('?').substringBeforeLast('/', "")

    /**
     * 从 playlist 正文判断内容属性。判据只有"时长/分片数"，不认识任何站点。
     *
     * - master（多码率主清单）：广告极少做多码率，直接算正片
     * - 媒体清单：分片时长求和 → >=3 分钟 或 >=40 个分片 ⇒ 正片
     * -                <90 秒 且 <=15 个分片 ⇒ 广告/预告
     * - 其余 ⇒ 未知（不武断）
     */
    fun verdict(text: String): Int {
        if (!text.contains("#EXTM3U")) return CONTENT_UNKNOWN
        if (text.contains("#EXT-X-STREAM-INF")) return CONTENT_REAL

        var n = 0
        var total = 0.0
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (!l.startsWith("#EXTINF:", true)) continue
            n++
            l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()?.let { total += it }
        }
        if (n == 0) return CONTENT_UNKNOWN
        if (total >= 180.0 || n >= 40) return CONTENT_REAL
        if (total < 90.0 && n <= 15) return CONTENT_LIKELY_AD
        return CONTENT_UNKNOWN
    }

    /** 给候选写一句人话注解，让用户一眼看出"这个多长" */
    fun describe(text: String): String {
        if (!text.contains("#EXTM3U")) return ""
        if (text.contains("#EXT-X-STREAM-INF")) {
            val n = text.lineSequence().count { it.trim().startsWith("#EXT-X-STREAM-INF", true) }
            return if (n > 1) "多码率主清单（${n} 档）" else "多码率主清单"
        }
        var n = 0
        var total = 0.0
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (!l.startsWith("#EXTINF:", true)) continue
            n++
            l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()?.let { total += it }
        }
        if (n == 0) return ""
        val mins = (total / 60.0).toInt()
        return "约 ${mins} 分钟 / ${n} 个分片"
    }

    /**
     * 是否自动播放，以及播哪个。
     *
     * 旧实现在"第一个候选出现后 3.5 秒"就无条件挑最高分并跳走 ——
     * 广告往往比正片先加载，于是**广告几乎必赢**，而且跳走后候选列表就没了。
     * 现在的规则：
     *
     * - 疑似广告 / 内容太短 ⇒ **绝不自动播**（宁可让用户点）
     * - 只有一个候选 ⇒ 直接播（这是绝大多数情况，保住原来的顺手体验）
     * - 多个候选 ⇒ 只在"首选是正片、且次选不是正片"时才自动播
     * - 都没探出内容（网络受限）⇒ 退回最高分，但只挑不疑似的
     */
    fun autoPick(ranked: List<SniffCandidate>, probed: Boolean): SniffCandidate? {
        if (ranked.isEmpty()) return null
        val top = ranked.first()
        if (top.suspect || top.content == CONTENT_LIKELY_AD) return null
        if (ranked.size == 1) return top
        if (!probed || top.content == CONTENT_UNKNOWN) return top
        return if (ranked[1].content != CONTENT_REAL) top else null
    }
}

/** 嗅探到的候选媒体地址（带排序依据，便于展示与诊断） */
data class SniffCandidate(
    val url: String,
    val type: String,
    var hits: Int = 1,
    /** URL 层疑似广告/埋点 */
    var suspect: Boolean = false,
    /** 内容探测结论：[SniffRank.CONTENT_UNKNOWN] / [CONTENT_LIKELY_AD] / [CONTENT_REAL] */
    var content: Int = SniffRank.CONTENT_UNKNOWN,
    /** 内容注解，如「约 103 分钟 / 2572 个分片」 */
    var note: String = "",
    /** 计算出的排序分 */
    var score: Int = 0
) {
    /** 所在目录 —— 用于把同目录下的分片请求归并成"这个清单正在被播放"的证据 */
    val dir: String get() = SniffRank.dirOf(url)

    /** 列表里显示的一行人话 */
    fun display(): String {
        val tag = when (content) {
            SniffRank.CONTENT_REAL -> "正片"
            SniffRank.CONTENT_LIKELY_AD -> "疑似广告"
            else -> "未识别"
        }
        val flags = buildList {
            add(type)
            add(tag)
            if (suspect) add("广告嫌疑")
            if (note.isNotBlank()) add(note)
            if (hits > 1) add("命中 ${hits}")
        }
        return flags.joinToString(" · ")
    }
}

/**
 * 候选清单跨 Activity 传递（Intent 塞不下，也避免重复嗅探）。
 *
 * 存下来的意义：**播放失败时能换下一个源**。旧实现 `startPlayer()` 之后立刻
 * `finish()`，候选列表随之销毁 —— 一旦排序选错，用户只能从头再嗅探一遍。
 */
object SniffQueue {
    var candidates: List<SniffCandidate> = emptyList()
        private set
    var index: Int = 0
        private set

    fun set(list: List<SniffCandidate>, at: Int) {
        candidates = list
        index = at.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    fun current(): SniffCandidate? = candidates.getOrNull(index)

    fun hasNext(): Boolean = index + 1 < candidates.size

    /** 前进到下一个候选；没有就返回 null */
    fun advance(): SniffCandidate? {
        if (!hasNext()) return null
        index++
        return candidates.getOrNull(index)
    }

    fun clear() {
        candidates = emptyList()
        index = 0
    }
}
