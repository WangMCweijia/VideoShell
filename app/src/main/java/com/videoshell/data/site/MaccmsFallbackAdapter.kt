package com.videoshell.data.site

import com.videoshell.App
import com.videoshell.data.Store
import com.videoshell.data.model.Category
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem

/**
 * ## 采集接口死了要能退回网页解析（v1.0.68，E48）
 *
 * ### 死锁是怎么形成的（实测：快映/蜡笔/闪电/虎斑等一串网盘站）
 *
 * 1. 站点**早年被识别成有采集接口** ⇒ `apiUrl` 落盘（当年接口活着，识别没有错）；
 * 2. 站方后来**关掉了接口**（快映实测 `/api.php/provide/vod/at/json/` 返回 200 但只有 6 字节）；
 * 3. `MaccmsAdapter` 每次拿到 6 字节 ⇒ 解析不出 ⇒ **静默空列表**（分类/列表/详情全空）；
 * 4. 而 [AdapterFactory] 第 ④ 步看到 `apiUrl` 非空就直接返回接口适配器 ⇒
 *    **兜底链永远不走** ⇒ `PanShareFamily` 的自证（只在详情页解析里发生）**永远不发生**
 *    ⇒ 第 ②.5 步的「已自证命中网盘族」永远救不了它 —— 死锁闭环。
 *
 * 用户看到的正是这个形状：「个别的能取到首页，大部分取不到分类，全部播放不了」。
 *
 * ### 修法：装饰器回落 + 自愈
 *
 * - 接口链**空/异常** ⇒ 回落 [HtmlAdapter] 同名方法（网盘族的 HTML 形状实测完好：
 *   首页分类可提取、分类页 2000+ 卡片、详情页 downtab/clipboard 齐全）；
 * - 回落**真的拿到数据**才自愈（清 `apiUrl` 落盘）——接口死 + HTML 活 = 站方真关了接口；
 *   若是网络整体断，HTML 也空 ⇒ 不落盘，下次照旧先试接口。这样「暂时性网络抖动」
 *   永远不会把一份好配置误删。
 * - `htmlTrack`：分类一旦从 HTML 轨取到，后续 browse/detail 也必须走 HTML 轨 ——
 *   **两条轨的 typeId/id 形状不互通**（接口是数字 `vod_id`，网页是 `index.php/vod/detail/id/N`
 *   之类的路径形状），混着传必然错位。
 *
 * ⚠️ 判据是「空/异常」，不是「接口状态码」：6 字节的 200、`{"code":1,"list":[]}` 的
 * 「看起来正常」都算死。也**不写域名白名单** —— 死活是运行时事实，只有形状与数据说话。
 */
class MaccmsFallbackAdapter(site: SiteConfig) : SiteAdapter(site) {

    private val api = MaccmsAdapter(site)
    private val html = HtmlAdapter(site)

    /** 分类层已切换到 HTML 轨 ⇒ 后续 browse/detail 直接走 HTML（id 形状不互通，不能混轨） */
    @Volatile
    private var htmlTrack = false

    /** 本会话已自愈过（落盘一次就够，别反复写） */
    @Volatile
    private var healed = false

    private var diag = ""
    override val lastDiag: String get() = diag.ifBlank { api.lastDiag }

    // ---- 预渲染兜底与校准全部代理给 HTML 轨（接口轨本来就不支持） ----
    override val supportsWebRender: Boolean get() = htmlTrack
    override fun parseListFromHtml(html: String, page: Int) = this.html.parseListFromHtml(html, page)
    override fun parseDetailFromHtml(html: String): VideoDetail? = this.html.parseDetailFromHtml(html)
    override fun searchUrlFor(keyword: String, page: Int) = this.html.searchUrlFor(keyword, page)
    override fun browseUrlFor(typeId: String, page: Int) = this.html.browseUrlFor(typeId, page)
    override fun detailUrlFor(id: String): String? = this.html.detailUrlFor(id)
    override fun countCatTplHits(html: String, tpl: String): Int = this.html.countCatTplHits(html, tpl)

    override suspend fun categories(): List<Category> {
        diag = ""
        if (!htmlTrack) {
            val c = runCatching { api.categories() }.getOrElse {
                diag = "采集接口异常：${it.message.orEmpty().take(40)}"
                emptyList()
            }
            if (c.isNotEmpty()) return c
            if (diag.isBlank()) diag = "采集接口返回空（站点多半已关接口）"
        }
        val h = runCatching { html.categories() }.getOrElse { return emptyList() }
        if (h.isNotEmpty()) {
            htmlTrack = true
            diag = "采集接口死 ⇒ 已回落网页解析"
            heal()
        }
        return h
    }

    override suspend fun browse(typeId: String, page: Int): List<VideoItem> {
        if (!htmlTrack) {
            val items = runCatching { api.browse(typeId, page) }.getOrDefault(emptyList())
            if (items.isNotEmpty()) return items
            // typeId 可能已是 HTML 轨的形状（跨会话缓存/换轨瞬间）⇒ 直接试 HTML 轨
        }
        val h = runCatching { html.browse(typeId, page) }.getOrDefault(emptyList())
        if (h.isNotEmpty() && !htmlTrack) {
            htmlTrack = true
            heal()
        }
        return h
    }

    override suspend fun search(keyword: String, page: Int): List<VideoItem> {
        if (!htmlTrack) {
            val items = runCatching { api.search(keyword, page) }.getOrDefault(emptyList())
            if (items.isNotEmpty()) return items
        }
        // 搜索与 typeId 无关，轨切换与否都能安全回落
        return runCatching { html.search(keyword, page) }.getOrDefault(emptyList())
    }

    override suspend fun detail(id: String): VideoDetail {
        if (!htmlTrack) {
            val d = runCatching { api.detail(id) }.getOrNull()
            if (d != null && d.groups.isNotEmpty()) return d
        }
        val h = runCatching { html.detail(id) }.getOrNull()
        if (h != null && h.groups.isNotEmpty()) {
            if (!htmlTrack) {
                htmlTrack = true
                heal()
            }
            return h
        }
        // 两轨都不行：有接口报错就抛接口的错（调用方显示原因），否则抛 HTML 轨的错
        if (!htmlTrack) return api.detail(id)
        return html.detail(id)
    }

    /**
     * 自愈：把「接口已死」这个**长期事实**落盘 —— 清 `apiUrl`、apiMode 归 HTML。
     * 下次 [AdapterFactory.create] 就直接走 HTML 链，不再经过本装饰器。
     *
     * ⚠️ 只在「HTML 轨真的拿到数据」时调用（见类注释：别把网络抖动误判成接口死）。
     */
    private fun heal() {
        if (healed) return
        healed = true
        runCatching {
            val ctx = App.instance
            val list = Store.sites(ctx)
            val i = list.indexOfFirst { it.key == site.key }
            if (i >= 0) {
                list[i] = list[i].copy(apiUrl = "", apiMode = SiteConfig.MODE_HTML)
                Store.save(ctx, list)
            }
        }
    }
}
