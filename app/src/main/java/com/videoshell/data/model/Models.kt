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

/** 一集 */
data class Episode(val name: String, val url: String)

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
        val isHls: Boolean = false
    ) : MediaSource()

    data class Sniff(
        val pageUrl: String,
        val headers: Map<String, String> = emptyMap()
    ) : MediaSource()

    data class Error(val message: String) : MediaSource()
}
