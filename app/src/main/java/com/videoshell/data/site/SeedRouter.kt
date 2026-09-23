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
 * 「签名种子配置族」的**延迟路由**（v1.0.53）。
 *
 * 结构与 [FamilyRouter] 完全同构，理由也一样：家族自证要发一次 `{origin}/config.json`，
 * 而 [AdapterFactory.create] 是**同步的**、且在 UI 线程被多处调用
 * （`SiteBrowser` / `DetailActivity` / `PlayerActivity` / `AggSearch`），
 * 让它在主线程等网络绝对不能做 ⇒ 判定推迟到**第一次真正解析时**（那时已在 IO 协程里），
 * 每个 host 只判定一次（[SeedFamily] 进程内缓存）。
 *
 * ## 命中之后会发生什么（这是设计，不是"校准又没生效"）
 *
 * 一个站一旦自证是这一族，**接口路径全面取代网页解析**（也取代人工校准的模板）。
 * 这一族在 HTML 里**根本没有可解析的内容**：分类只有 3 个排序 tab、卡片是
 * `/?drama=…&ep=1` 这种同页 query、播放地址由 JS 调接口拿（`<video>` 连 `src` 都没有）。
 * 所以用户"无论怎么校准都失败"是必然的 —— 校准的是**形状**，而这站没有形状可学。
 *
 * 为了让用户不再走进这个悬案，[calibDiag] 会把这件事**明说**出来（同 [FamilyRouter] 的口径）。
 *
 * ## 与 [FamilyRouter] 的关系
 *
 * 不命中种子族时**原样交给 [FamilyRouter]**（它再去判加密接口族、再退回网页解析）；
 * 若 [CryptFamily] 早已把本站判过否定（`familyAbsent`），则跳过 [FamilyRouter] 直接落到网页解析。
 * 两层各只发一次探测，且都在 IO 协程里；对普通 HTML 站是"多一个几百字节的 GET"。
 */
class SeedRouter(site: SiteConfig, private val familyAbsent: Boolean = false) : SiteAdapter(site) {

    /**
     * 没自证之前先用它。
     *
     * `familyAbsent = true` 表示 [CryptFamily] 已经把本站**判过否定**并落了盘（[AdapterFactory] 第 ⑤ 步
     * 从缓存里读到的）—— 那就没必要再包一层 [FamilyRouter]（它只会去读同一个缓存），
     * 落点直接从 [HtmlAdapter] 起。**这是 v1.0.35 的「零成本」规矩，v1.0.53 加种子族时差点丢掉**。
     */
    private val fallback: SiteAdapter by lazy {
        if (familyAbsent) PanShareAdapter(site) else FamilyRouter(site)
    }

    @Volatile
    private var resolved: SiteAdapter? = null

    @Volatile
    private var gateNote: String = ""

    @Volatile
    private var hitApi: String? = null

    private val lock = Mutex()

    /** 唯一会发种子探测的地方，且只发一次 */
    private suspend fun target(): SiteAdapter {
        resolved?.let { return it }
        return lock.withLock {
            resolved?.let { return@withLock it }
            val api = SeedFamily.resolve(site.baseUrl, referer = site.baseUrl)
            hitApi = api
            val a: SiteAdapter = if (api != null) SeedApiAdapter(site, api) else fallback
            gateNote = if (api != null) {
                "本站识别为**签名种子配置族**（依据：解开 /config.json 的信封 ⇒ api=$api）⇒ 分类/列表/搜索/详情/播放全部走接口。" +
                        "该站的 HTML 里没有分类、也没有播放地址（`<video>` 的 src 由 JS 调接口设），" +
                        "所以**校准学不到东西不是校准的问题** —— 这是设计如此。"
            } else {
                "自动探测：本站**不是**签名种子配置族 ⇒ 交给后续判定与网页解析" +
                        SeedFamily.lastNote.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            }
            resolved = a
            a
        }
    }

    /**
     * **正在干活**的适配器（同 [FamilyRouter.underlying] 的理由）：
     * 本类是延迟路由外壳，`instanceof` 一类判定会因"多包了一层"失效，
     * 但断言的本意（谁在真正解析）依然成立。
     */
    val underlying: SiteAdapter get() = resolved ?: fallback

    // ------------------------------------------------------------------ 契约（全部委托）

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

    override val calibDiag: String
        get() {
            val head = when {
                hitApi != null -> "★ $gateNote"
                gateNote.isNotBlank() -> gateNote
                else -> "（还没解析过 ⇒ 尚未判定本站是不是签名种子配置族）"
            }
            val tail = resolved?.calibDiag.orEmpty()
            return if (tail.isBlank()) head else "$head\n      $tail"
        }

    override val calibApplied: Boolean
        get() = hitApi != null || (resolved?.calibApplied ?: fallback.calibApplied)

    /** 预渲染兜底只在**没命中接口**时才有意义（接口站的数据不在 DOM 里） */
    override val supportsWebRender: Boolean
        get() = resolved?.supportsWebRender ?: fallback.supportsWebRender

    override fun countCatTplHits(html: String, tpl: String): Int = fallback.countCatTplHits(html, tpl)

    override fun parseListFromHtml(html: String, page: Int): List<VideoItem> =
        fallback.parseListFromHtml(html, page)

    override fun parseDetailFromHtml(html: String): VideoDetail? = fallback.parseDetailFromHtml(html)

    override fun searchUrlFor(keyword: String, page: Int): String? = fallback.searchUrlFor(keyword, page)

    override fun browseUrlFor(typeId: String, page: Int): String? = fallback.browseUrlFor(typeId, page)

    override fun detailUrlFor(id: String): String? = fallback.detailUrlFor(id)
}
