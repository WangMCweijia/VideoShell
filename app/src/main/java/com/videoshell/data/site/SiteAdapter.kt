package com.videoshell.data.site

import com.videoshell.data.model.Category
import com.videoshell.data.model.Episode
import com.videoshell.data.model.MediaSource
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http

/** 站点适配器基类：一个视频站 = 一个适配器 */
abstract class SiteAdapter(val site: SiteConfig) {

    abstract suspend fun categories(): List<Category>

    abstract suspend fun browse(typeId: String, page: Int): List<VideoItem>

    abstract suspend fun search(keyword: String, page: Int): List<VideoItem>

    abstract suspend fun detail(id: String): VideoDetail

    /**
     * 上一次「分类解析失败」的原因（空串 = 无法给出原因或解析成功）。
     * 分类栏空着却没头绪时，把它显示出来，用户一眼就知道是网络还是解析问题。
     */
    open val lastDiag: String get() = ""

    // ------------------------------------------------------------------ v1.0.25 新增能力

    /**
     * 用**已经拿到的 HTML** 解析列表（不发请求）。
     * 供 UI 层的"预渲染兜底"使用：HTML 适配器解析不到卡片时，UI 用 WebView 把页面
     * 真正跑一遍（JS 渲染完的 DOM），再把 outerHTML 送回这里解析。
     */
    open fun parseListFromHtml(html: String, page: Int): List<VideoItem> = emptyList()

    /** 同上，解析详情（预渲染兜底用） */
    open fun parseDetailFromHtml(html: String): VideoDetail? = null

    /** 该站「搜索」会把请求打到哪个地址（预渲染兜底要知道开哪个页面） */
    open fun searchUrlFor(keyword: String, page: Int): String? = null

    /** 该站「分类浏览」的目标地址（预渲染兜底用） */
    open fun browseUrlFor(typeId: String, page: Int): String? = null

    /** 该站「详情页」的地址（预渲染兜底用）；拿不到返回 null */
    open fun detailUrlFor(id: String): String? = null

    /**
     * 这个适配器**认不认**「预渲染兜底」（v1.0.25）。
     *
     * 接口型适配器（采集接口 / 加密接口）的数据根本不来自 HTML —— 让 UI 再开一次 WebView
     * 把首页跑一遍，只会白等几秒并且必然解析不出东西。默认 false，只有 [HtmlAdapter] 打开。
     */
    open val supportsWebRender: Boolean get() = false

    fun playHeaders(): Map<String, String> = mapOf(
        "User-Agent" to Http.UA,
        "Referer" to site.baseUrl
    )

    /**
     * 把剧集地址解析为可直接交给播放器的地址：
     * 1) 本身是 m3u8/mp4 -> 直接用
     * 2) 是播放页 -> 尝试直接从 HTML 里抠真实地址（快，不用 WebView）
     * 3) 页面把地址交给第三方 **jx 解析接口** -> 跟过去取流（新增）
     * 4) 抠不到 -> 交给网页嗅探
     */
    open suspend fun resolve(episode: Episode): MediaSource {
        val u = episode.url.trim()
        if (u.isEmpty()) return MediaSource.Error("播放地址为空")
        // ⚠️ 必须过 encodeUrl —— 这是「自检 200、播放 404」的全部原因：
        // 媒体路径经常含中文（如 /video/bianshuiwangshi/第01集/index.m3u8），
        // 自检走 OkHttp（自动百分号编码），播放走 ExoPlayer 的 DefaultHttpDataSource
        // → HttpURLConnection（**不编码**，把中文原样塞进请求行）→ CDN 404。
        if (Media.isDirect(u)) return MediaSource.Direct(Media.encodeUrl(u), playHeaders(), Media.isHls(u))
        if (!u.startsWith("http")) return MediaSource.Error("无法识别的播放地址：$u")

        val html = Http.getOrNull(u, referer = site.baseUrl)
        val real = Media.extractFromHtml(html)
        if (!real.isNullOrBlank()) {
            return MediaSource.Direct(Media.encodeUrl(real), playHeaders(), Media.isHls(real))
        }
        // jx 解析接口跟随：地址本身是解析接口，或页面里引用了它
        val jx = JxParser.findJxUrl(html).orEmpty().ifBlank {
            if (JxParser.isJxUrl(u)) u else ""
        }
        if (jx.isNotBlank()) {
            val stream = JxParser.follow(jx, site.baseUrl)
            if (!stream.isNullOrBlank()) {
                return MediaSource.Direct(Media.encodeUrl(stream), playHeaders(), Media.isHls(stream))
            }
        }
        return MediaSource.Sniff(u, playHeaders())
    }
}
