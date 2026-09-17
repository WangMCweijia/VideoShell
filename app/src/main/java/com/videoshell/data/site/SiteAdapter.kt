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

    fun playHeaders(): Map<String, String> = mapOf(
        "User-Agent" to Http.UA,
        "Referer" to site.baseUrl
    )

    /**
     * 把剧集地址解析为可直接交给播放器的地址：
     * 1) 本身是 m3u8/mp4 -> 直接用
     * 2) 是播放页 -> 尝试直接从 HTML 里抠真实地址（快，不用 WebView）
     * 3) 抠不到 -> 交给网页嗅探
     */
    open suspend fun resolve(episode: Episode): MediaSource {
        val u = episode.url.trim()
        if (u.isEmpty()) return MediaSource.Error("播放地址为空")
        if (Media.isDirect(u)) return MediaSource.Direct(u, playHeaders(), Media.isHls(u))
        if (!u.startsWith("http")) return MediaSource.Error("无法识别的播放地址：$u")

        val html = Http.getOrNull(u, referer = site.baseUrl)
        val real = Media.extractFromHtml(html)
        if (!real.isNullOrBlank()) return MediaSource.Direct(real, playHeaders(), Media.isHls(real))
        return MediaSource.Sniff(u, playHeaders())
    }
}
