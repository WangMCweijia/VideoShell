package com.videoshell.data.model

/** 站点类型 */
data class Category(val id: String, val name: String, val pid: String = "0")

/** 列表项 */
data class VideoItem(
    val id: String = "",
    val name: String = "",
    val pic: String = "",
    val remarks: String = "",
    val typeName: String = "",
    val score: String = "",
    val year: String = "",
    val area: String = "",
    /**
     * 这条结果来自哪个站源（`SiteConfig.key`）。
     *
     * 单站浏览时留空（"就是当前站"）；只有**搜全站源**的聚合结果会填它。
     *
     * ⚠️ 为什么必须带上：影片 id 是**站点内**的编号，两个站完全可能都用 `3381`。
     * 聚合结果若不带来源，点进去就会拿着 A 站的 id 去 B 站查 —— 表现为"有一条点开是空的"，
     * 而且只在特定站点组合下复现（id 没撞上时又正常），是最难查的那类不一致。
     * 有它才能"从哪来、回哪去"。
     */
    val siteKey: String = ""
)

/** 一集（pic 默认空：老适配器取不到分集封面时不影响编译/运行，FN-1） */
data class Episode(
    val name: String,
    val url: String,
    val pic: String = ""
)

/** 一条播放线路 */
data class PlayGroup(val name: String, val episodes: List<Episode>)

/** 详情 */
data class VideoDetail(
    val id: String = "",
    val name: String = "",
    val pic: String = "",
    val remarks: String = "",
    val typeName: String = "",
    val year: String = "",
    val area: String = "",
    val actor: String = "",
    val director: String = "",
    val summary: String = "",
    val groups: List<PlayGroup> = emptyList()
)

/** 已保存站点的配置（自动适配的结果） */
data class SiteConfig(
    val key: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val apiUrl: String = "",
    val apiMode: String = MODE_MACCMS_JSON,
    val fixedParams: String = "",
    val note: String = "",
    val createdAt: Long = 0L
) {
    companion object {
        const val MODE_MACCMS_JSON = "maccms_json"
        const val MODE_MACCMS_XML = "maccms_xml"
        const val MODE_HTML = "html"
    }
}

/** 剧集地址解析结果 */
sealed class MediaSource {
    data class Direct(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val isHls: Boolean = false,
        /**
         * 内容类型（v1.0.65）。
         *
         * **为什么非要它**：media3 的 `DefaultMediaSourceFactory` 靠 URI 的**后缀**推断内容类型，
         * 而网盘直链经常不带扩展名（`…/file/download?fid=…`）。推断不出来时它会按
         * progressive 处理 —— 结果是一个 HLS 流被当成 mp4 去解，用户看到"能解析、一播就黑屏"。
         * 取流方（网盘 Provider）**知道**自己给的是什么，所以由它报出来，别让上层猜。
         */
        val mimeType: String? = null
    ) : MediaSource()

    data class Sniff(
        val pageUrl: String,
        val headers: Map<String, String> = emptyMap()
    ) : MediaSource()

    /**
     * 需要登录（v1.0.65，网盘）。
     *
     * 与 [Error] 分开，是因为它有一个**明确的下一步动作**：去「网盘账号」页登录。
     * 只丢一个 toast 的话，用户知道"要登录"却不知道去哪儿登 —— 而这条路径是网盘方案的
     * 第一道门（实测：不登录连一条直链都取不到），必须一次说清。
     *
     * [driveKey] 是 [com.videoshell.data.pan.PanType.key]，账号页靠它定位到对应那一行。
     */
    data class NeedLogin(val message: String, val driveKey: String = "") : MediaSource()

    data class Error(val message: String) : MediaSource()
}
