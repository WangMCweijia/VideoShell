package com.videoshell.data.site

import com.videoshell.data.model.Category
import com.videoshell.data.model.Episode
import com.videoshell.data.model.MediaSource
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ## 搜索/解析的「降级链」路由器（v1.0.35，第二笔债）
 *
 * ### 它补的是链条中间缺失的那一环
 *
 * 「这个站能不能搜」这件事，以前的链条是：
 *
 * ```
 * URL 模板（严格遍 → 宽松遍）  →  [空] →  UI 层 WebRender 预渲染 →  [空] →  用户看到"没有结果"
 * ```
 *
 * 中间漏了一环，而它恰恰是**最可靠**的一环：**站点自己的 JSON 接口**。
 * 网页端能搜、App 搜不到，绝大多数就是这个原因 —— 浏览器把 JS 跑完了、调的是接口；
 * 我们抓的是 SSR 出来的 HTML，而结果节点根本不在里面（野果就是这样：
 * `/?s=` 是软 404 回首页，`/search/drama/{kw}/` 是 5.4 KB 的 JS 空壳）。
 *
 * 这一层的做法是：**不确定就用网页解析，一旦自证是加密接口族，整程换成接口**。
 *
 * ### 为什么用「包一层」而不是改 AdapterFactory 的返回值
 *
 * 家族自证必须发一次请求（[CryptApi.probe]），而 [AdapterFactory.create] 是**同步的**、
 * 且在 UI 线程被多处调用（`SiteBrowser` / `DetailActivity` / `PlayerActivity`）——
 * 让它在主线程上等网络是绝对不能做的。所以这里把判定**推迟到第一次真正解析时**
 * （那时已经在 IO 协程里），并且每个域名只判定一次（[CryptFamily] 三态缓存）。
 *
 * ### 覆盖范围（刻意收窄）
 *
 * 只包两种结果：
 * - `apiUrl` 为空的 HTML 兜底站 —— 没有接口，正好是"隐藏着一个加密接口"的典型；
 * - 已人工校准的站 —— 用户报的就是这一类（校准救不了搜索，因为搜索不靠 HTML）。
 *
 * **不包**采集接口站（`apiUrl` 非空）：它们有自己的原生接口，多探一次纯属浪费。
 *
 * ### 命中后的优先级说明（这是设计，不是"校准没生效"）
 *
 * 一个站一旦自证是加密接口族，**接口路径全面取代网页解析**（含人工校准的模板）。
 * 理由：这一族在 HTML 里根本没有可解析的内容，接口是唯一能搜、能取全集的路径。
 * 这个决定**会写进自检报告**（[calibDiag]），绝不会让用户以为"校准白做了"。
 */
class FamilyRouter(site: SiteConfig) : SiteAdapter(site) {

    /** 没自证之前先用它。`lazy` 是因为很多站会立刻命中缓存、根本用不着它 */
    private val html: HtmlAdapter by lazy { HtmlAdapter(site) }

    /** 判定结果（命中 → [YeguoAdapter]；不命中 → [html]）。判定一次，全程复用 */
    @Volatile
    private var resolved: SiteAdapter? = null

    /** 判定过程的说明，进自检。空串 = 还没判定 */
    @Volatile
    private var gateNote: String = ""

    @Volatile
    private var hitRecipe: CryptRecipe? = null

    private val lock = Mutex()

    /**
     * 拿到真正的目标适配器。**这是唯一会发探测请求的地方**，且只发一次。
     *
     * 拿不到首页也照样能判定：`CryptApi.probe` 只需要能 POST 通 `{origin}/api.php`。
     */
    private suspend fun target(): SiteAdapter {
        resolved?.let { return it }
        return lock.withLock {
            resolved?.let { return@withLock it }
            val hit = CryptFamily.resolve(site.baseUrl, referer = site.baseUrl)
            hitRecipe = hit
            val a: SiteAdapter = if (hit != null) YeguoAdapter(site, hit) else html
            gateNote = if (hit != null) {
                "本站自证为**加密接口族**（${hit.apiBase}）⇒ 分类/搜索/详情全程走接口" +
                        "（HTML 里没有可解析内容，这是设计如此，不是校准没生效）"
            } else {
                "自动探测：本站**不是**加密接口族 ⇒ 沿用网页解析" +
                        CryptDiscovery.lastNote.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            }
            resolved = a
            a
        }
    }

    // ------------------------------------------------------------------ 解析契约（全部委托）

    /**
     * **正在干活**的适配器：判定完就是目标适配器，判定前是网页解析。
     *
     * 为什么需要它：本类是一层**延迟路由**外壳，`instanceof HtmlAdapter` 之类的判定会因为
     * "外面多包了一层"而全部失效 —— 可那句断言的**本意**（"`apiUrl` 为空的站不会被
     * 采集接口分支吞掉"）依然成立。自检与离线断言要看**谁在干活**，而不是外面包了几层。
     */
    val underlying: SiteAdapter get() = resolved ?: html

    override suspend fun categories(): List<Category> = target().categories()

    override suspend fun browse(typeId: String, page: Int): List<VideoItem> =
        target().browse(typeId, page)

    override suspend fun search(keyword: String, page: Int): List<VideoItem> =
        target().search(keyword, page)

    override suspend fun detail(id: String): VideoDetail = target().detail(id)

    override suspend fun resolve(episode: Episode): MediaSource = target().resolve(episode)

    // ------------------------------------------------------------------ 诊断

    override val lastDiag: String
        get() = listOfNotNull(
            gateNote.takeIf { it.isNotBlank() },
            resolved?.lastDiag?.takeIf { it.isNotBlank() }
        ).joinToString("；")

    /**
     * 自检里「校准到底生效没有」那一段。
     *
     * 判定命中时要**明说**校准规则被接口路径取代了 —— 否则用户会看到
     * 「配方明明写着分类形状，怎么没用」而再次走进"校准没生效"的悬案。
     */
    override val calibDiag: String
        get() {
            val head = when {
                hitRecipe != null -> "★ $gateNote"
                gateNote.isNotBlank() -> gateNote
                else -> "（还没解析过 ⇒ 尚未判定本站是不是加密接口族）"
            }
            val tail = resolved?.calibDiag.orEmpty()
            return if (tail.isBlank()) head else "$head\n      $tail"
        }

    override val calibApplied: Boolean
        get() = hitRecipe != null || (resolved?.calibApplied ?: html.calibApplied)

    /** 预渲染兜底只在**没命中接口**时才有意义（接口站的数据不在 DOM 里） */
    override val supportsWebRender: Boolean
        get() = resolved?.supportsWebRender ?: true

    override fun countCatTplHits(html2: String, tpl: String): Int = html.countCatTplHits(html2, tpl)

    override fun parseListFromHtml(html2: String, page: Int): List<VideoItem> =
        html.parseListFromHtml(html2, page)

    override fun parseDetailFromHtml(html2: String): VideoDetail? = html.parseDetailFromHtml(html2)

    override fun searchUrlFor(keyword: String, page: Int): String? = html.searchUrlFor(keyword, page)

    override fun browseUrlFor(typeId: String, page: Int): String? = html.browseUrlFor(typeId, page)

    override fun detailUrlFor(id: String): String? = html.detailUrlFor(id)
}
