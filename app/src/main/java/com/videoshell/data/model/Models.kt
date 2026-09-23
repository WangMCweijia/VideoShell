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

/**
 * 已保存站点的配置（自动适配的结果）。
 *
 * ⚠️ **必须带 `@JvmOverloads`**（v1.0.67 补）：`tools/verify/` 下几十个 Java harness 用
 * **位置参数**构造它（`new SiteConfig(key, name, base, "", SiteConfig.MODE_HTML, "", "", 0L)`），
 * 而 Kotlin 的**默认参数对 Java 不可见** —— 加一个字段（`mirrors`）就会让全部调用点一起
 * 编译不过（实测 7 个套件当场 `javac 失败`）。有这个注解，Kotlin 会按"从尾部逐个省略
 * 有默认值的参数"生成重载 ⇒ 加字段不再是一次跨语言的破坏性改动。
 * （同一条纪律见 PITFALLS 总表 E1：加字段两端同步 + 显式重载式构造。）
 *
 * 注：`@JvmOverloads` 要挂在**构造器**上（`class X @JvmOverloads constructor(...)`），
 * 挂在类上会直接编译不过（`This annotation is not applicable to target 'class'`）。
 */
data class SiteConfig @JvmOverloads constructor(

    val key: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val apiUrl: String = "",
    val apiMode: String = MODE_MACCMS_JSON,
    val fixedParams: String = "",
    val note: String = "",
    val createdAt: Long = 0L,
    /**
     * **备用地址**（同一站的其它域名 / 入口，v1.0.67）。
     *
     * ## 为什么要有它
     *
     * 影视站的域名会轮换 —— 这一族（快映 / 玩偶这类网盘分享站，以及一波 maccms 换皮站）
     * 尤其明显：同一个站同时挂着好几个域名，主域名随时可能被墙或过期。旧做法是"一个站
     * 一个地址"，主地址一死整条记录就废了，用户只能删掉重加，而新记录又是一个地址。
     *
     * 潇洒 TVBox 本地包的做法就是在源的配置里放一串网址、运行时挑能用的那个
     * （见 `docs/网盘站源清单.md`：玩偶 4 个域名、快映 2 个…）。这里照同样的思路，
     * 但地址由**用户添加站点时自己给**（多个网址一次粘进来），不做域名白名单 ——
     * 判据仍然只看站点自己的形状。
     *
     * 挑选逻辑在 [com.videoshell.data.site.MirrorRace]：并发探活、**谁先响应谁赢**，
     * 结果在会话内缓存；请求失败会自动作废重挑（自愈）。单个地址的站点**零额外请求**。
     *
     * ⚠️ **声明成可空不是偷懒**：Gson 用 Unsafe 分配对象，Kotlin 的字段默认值对它不生效
     * （本文件 `apiMode` 那一栏踩过同一个坑）——老配置里没有这个字段 ⇒ 反序列化后它是
     * **null**，按非空读就是一次 NPE。所以一律走 [mirrorList]。
     */
    val mirrors: List<String>? = null
) {
    /**
     * 备用地址（**永远非空**，老配置是 null ⇒ 空列表）。
     *
     * 取用一律经过它：`site.mirrors` 直读是 Gson 之后唯一的 NPE 入口。
     */
    fun mirrorList(): List<String> = mirrors ?: emptyList()

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
