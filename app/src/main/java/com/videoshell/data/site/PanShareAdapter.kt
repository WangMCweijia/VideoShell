package com.videoshell.data.site

import com.videoshell.data.model.Category
import com.videoshell.data.model.Episode
import com.videoshell.data.model.MediaSource
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import com.videoshell.data.pan.PanLink
import com.videoshell.data.pan.PanResolver
import com.videoshell.util.resolveUrl
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/**
 * ## 网盘分享站适配器（快映4K / 玩偶4K 这一类）
 *
 * ### 为什么是「装饰器」而不是继承 [HtmlAdapter]
 *
 * 本项目的约定是：**所有适配器都是 final，彼此不继承**，复用一律走
 * 「扩展函数 + 委托」（`HtmlAdapter.buildDetail` 就是扩展函数，
 * [FamilyRouter] / [SeedRouter] 就是委托外壳）。刻意照做有实际收益：
 *
 *  - 列表/分类/搜索**一行都不用重写** —— 这些站的列表页就是标准 maccms 模板
 *    （`.module-item` 卡片、`/vodtype/{n}.html`），[HtmlExtractor] 已经覆盖；
 *  - 离线 harness 的 `Chains.worksAs` / `hops` 会沿 `underlying` 一路剥到底，
 *    本层**透明**：`SeedRouter → FamilyRouter → PanShareAdapter → HtmlAdapter`。
 *    若改成继承，`instanceof HtmlAdapter` 会变成真的，但那种"真的"没有意义 ——
 *    真正在解析的还是同一个对象，而 `hops >= 1` 那条"确实经过了延迟路由"的断言会失效。
 *
 * 唯一真正不同的地方是**详情页**：它没有 `<a>` 形式的播放锚点
 * （实测 `player_aaaa` 出现 **0 次**），只有"线路 tab + 复制网盘链接"。
 * 于是 [HtmlAdapter.detail] 的整条兜底链（学模板 → 穷举 → 播放页兜底）**必然全败**，
 * 最后抛「未能解析该影片详情（HTML 模板不匹配）」—— 这就是用户看到的症状。
 *
 * ### 触发方式：**先跑原链路，失败了才认领**
 *
 * 顺序见 [detail]：
 *
 * 1. 这个 host **已自证过**是本族（[PanShareFamily]）⇒ 直接走网盘路径，
 *    省掉那一串必然失败的兜底请求（实测 5~8 次）；
 * 2. 否则先跑 `html.detail(id)`。**普通站一定会成功返回** ⇒ 对它们
 *    「零额外请求、零行为变化」，网盘分支连一次都不会被触发；
 * 3. 只有原链路**抛异常**时才认领这个页面。命中就落盘，下一次走第 1 条。
 *
 * 这条顺序的好处是**判据不需要提前知道"这是不是网盘站"**。判据就是
 * 「原链路失败了 **且** 页面上真有可识别的网盘分享链接」，
 * 而这正好就是 [PanShareExtract.isPanSharePage] 的定义。
 *
 * ⚠️ 混合站（既有真分集、又挂网盘链接）会走到第 2 条并被记成否定 —— 那是对的：
 * 否定只关掉"抄近路"，**关不掉兜底**（第 3 条对所有站都在）。
 *
 * ### 详情页展开到什么程度：**直接展开成剧集**
 *
 * 方案文档最初打算"展开推迟到点播时"，实测后改掉了 —— 分享链接指向的是**文件夹**：
 *
 * ```
 * pan.quark.cn/s/9cb3db399337
 *   └─ F 非成勿扰/           ← 分享根就一层目录
 *        ├─ 166期.mp4  ├─ 第167期.mp4  …（实测 55 个文件）
 * ```
 *
 * 只列到"夸克网盘"一条线路的话，用户点进去只能播第一个文件 —— 那不是一个视频 App
 * 该有的样子。而**列目录匿名可用**（实测 `sharepage/token` + `sharepage/detail`
 * 都不需要登录），所以展开放在详情页是可行的，用户第一眼就能看到真实集数。
 *
 * 展开失败也**不会让整页变空**：[PanResolver.episodes] 在展开为空时会留一条
 * 「打开分享（未展开）」，点它时会在播放链路里再试一次并给出具体原因。
 */
class PanShareAdapter(
    site: SiteConfig,
    /** 被装饰的那一层。默认自建一个 —— 调用方也可以把自己那份传进来复用状态 */
    private val html: HtmlAdapter = HtmlAdapter(site)
) : SiteAdapter(site) {

    /** 网盘路径的现场结论（进 [calibDiag]，让"这次判定靠什么"可查） */
    @Volatile
    private var panDiag: String = ""

    /** 剥到底时能看见"谁在真正解析"（离线 harness 的 `Chains` 靠它穿透本层） */
    val underlying: SiteAdapter get() = html

    // ------------------------------------------------------------------ 详情（唯一被覆盖的契约）

    override suspend fun detail(id: String): VideoDetail {
        val known = PanShareFamily.cachedState(site.baseUrl) is PanShareFamily.State.Hit

        // ① 已自证 ⇒ 网盘路径优先，省掉那串必然失败的兜底请求
        if (known) {
            panDetail(id)?.let { return it }
            // 一条候选都没认出网盘形状（站点改版 / 换模板）时也要留下原因，
            // 否则下面抛的是原链路那句"HTML 模板不匹配"，等于把我们的判据又藏回去
            if (panDiag.isBlank()) {
                panDiag = "⚠️ 已自证是网盘分享站族，但这次没有任何一个候选详情页认出网盘形状" +
                        "（站点大概改版了；" + PanShareFamily.describe(site.baseUrl) + "）"
            }
            // 展开失败（分享失效 / 接口变了 / 站点真补了分集）：还是让它试一次原链路，
            // 但**不翻案** —— 已命中的结论不该被一次失败推翻，否则每失败一次就多一整轮兜底。
            try {
                return html.detail(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                throw IOException(panDiag.ifBlank { "网盘线路解析失败：${PanResolver.lastNote}" })
            }
        }

        // ② 原链路先跑（普通站在这里一定成功返回 ⇒ 零额外请求、零行为变化）
        var normalErr: Throwable? = null
        val normal: VideoDetail? = try {
            html.detail(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            normalErr = e
            null
        }
        if (normal != null) {
            // 它成功了 ⇒ 至少"详情页有可解析的分集锚点"，所以不是这一族。
            PanShareFamily.markAbsent(site.baseUrl)
            return normal
        }

        // ③ 原链路失败 ⇒ 认领这一页。
        //    先看它手上**现成的** DOM：`hitDetail` 会把每次抓到的页面写进 `lastDetailDoc`，
        //    所以这里**可能**正好是那张真正的详情页（命中就省一轮请求）。
        //    ⚠️ 但它也可能是**最后那条错误候选**返回的 404/首页 —— 那不算问题：
        //       `claim` 认不出形状就返回 null，我们照旧落到下面的 `panDetail`，
        //       只是白省不了请求而已（**不会**因此误判成网盘站）。
        //    `outerHtml()` 只为 D3 那条诊断数 `player_aaaa`，不参与任何判定。
        html.lastDetailDoc?.let { doc ->
            claim(id, doc, page = doc.outerHtml(), where = "原链路最后抓到的候选页")?.let { return it }
        }
        panDetail(id)?.let { return it }

        // ⚠️ 只在还没话说的时候才填这句笼统结论：上面 `claim` 可能已经写下更具体的
        //    「是网盘分享页但一条线路都没展开出来」—— 那条比这句有用得多，不能被覆盖
        if (panDiag.isBlank()) {
            panDiag = "✗ 详情页既没有可解析的分集锚点，也没有可识别的网盘分享链接"
        }
        throw (normalErr ?: IOException("未能解析该影片详情"))
    }

    // ------------------------------------------------------------------ 网盘详情

    /**
     * 抓详情页 → 判定是不是网盘分享页 → 展开成 `PlayGroup`。
     *
     * 返回 null 表示"没有哪一页认得出网盘分享形状"（调用方据此走回原链路/原异常）。
     */
    private suspend fun panDetail(id: String): VideoDetail? {
        for (url in detailCandidatesFor(id)) {
            val page = html.fetch(url) ?: continue
            val doc = runCatching { Jsoup.parse(page, site.baseUrl) }.getOrNull() ?: continue
            claim(id, doc, page = page, where = url)?.let { return it }
        }
        return null
    }

    /**
     * 认领一页：形状自证 → 按线路展开成剧集。
     *
     * 返回 null = 「这一页不是网盘分享页」**或**「是但一条线路都没展开出来」。
     * 后者会把原因写进 [panDiag] —— 详情页整页空白永远不该是"没有原因"的失败。
     *
     * @param page 原始 HTML，**只用来数 `player_aaaa`**（[PanShareExtract.evidence] 的 D3）。
     */
    private suspend fun claim(id: String, doc: Document, page: String, where: String): VideoDetail? {
        if (!PanShareExtract.isPanSharePage(doc)) return null

        val groups = buildPanGroups(doc)
        if (groups.isEmpty()) {
            panDiag = "⚠️ $where 是网盘分享页，但一条线路都没展开出来" +
                    "（${PanResolver.lastNote.takeIf { it.isNotBlank() } ?: "展开无结果"}）"
            return null
        }
        html.lastDetailDoc = doc
        runCatching { resolveUrl(site.baseUrl, HtmlExtractor.parsePic(doc)) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { html.detailPicHint = it }

        panDiag = "✓ 网盘分享页 $where → ${groups.size} 条线路 " +
                "（${groups.sumOf { it.episodes.size }} 集）｜判据：" +
                PanShareExtract.evidence(doc, page)
        PanShareFamily.markHit(site.baseUrl)
        return html.buildDetail(id, doc, groups)
    }

    /** 详情页候选：**配方/学到的模板优先**，再是通用形状兜底（与 [HtmlAdapter.detail] 同序） */
    private fun detailCandidatesFor(id: String): List<String> {
        val out = LinkedHashSet<String>()
        html.detailUrlFor(id)?.let { out.add(it) }
        for (tpl in HtmlTemplates.detailCandidates(html.root)) {
            runCatching { html.build(tpl, id = id) }.getOrNull()?.let { out.add(it) }
        }
        return out.toList()
    }

    /**
     * 线路 → `PlayGroup`。
     *
     * **配对方式是"按顺序"**：第 i 个线路名配第 i 条分享链接（实测两个站的 tab 顺序与
     * `.module-row-one` 顺序一致）。顺序对不上时才退回按网盘类型命名 —— 宁可名字朴素，
     * 也不要出现"线路名张冠李戴"（那会把用户引到一个完全不相干的分享里）。
     */
    private suspend fun buildPanGroups(doc: Document): List<PlayGroup> {
        val links = PanShareExtract.shareLinks(doc)
        if (links.isEmpty()) return emptyList()
        val names = PanShareExtract.lineNames(doc)
        val used = HashMap<String, Int>()
        val out = ArrayList<PlayGroup>()

        for ((i, u) in links.withIndex()) {
            val link = PanLink.parse(u) ?: continue
            var name = names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: link.type.label
            val n = (used[name] ?: 0) + 1
            used[name] = n
            // 同名线路（玩偶站实测两条都叫"夸克网盘"）加序号区分，否则界面上两个 tab 一模一样
            if (n > 1) name = "$name $n"

            val ex = PanResolver.episodes(link)
            if (ex.episodes.isEmpty()) continue
            val suffix = if (ex.truncated) "（已截断）" else "（${ex.episodes.size} 集）"
            out.add(PlayGroup(name + suffix, ex.episodes))
        }
        return out
    }

    // ------------------------------------------------------------------ 契约（其余全部委托）

    override suspend fun categories(): List<Category> = html.categories()

    override suspend fun browse(typeId: String, page: Int): List<VideoItem> =
        html.browse(typeId, page)

    override suspend fun search(keyword: String, page: Int): List<VideoItem> =
        html.search(keyword, page)

    override suspend fun resolve(episode: Episode): MediaSource = html.resolve(episode)

    override val lastDiag: String get() = html.lastDiag

    override val calibApplied: Boolean get() = html.calibApplied

    /** 预渲染兜底照旧（玩偶站实测被 Cloudflare 加了 `-text/javascript`，这条路要留着） */
    override val supportsWebRender: Boolean get() = html.supportsWebRender

    override fun countCatTplHits(html: String, tpl: String): Int = this.html.countCatTplHits(html, tpl)

    override fun parseListFromHtml(html: String, page: Int): List<VideoItem> =
        this.html.parseListFromHtml(html, page)

    override fun parseDetailFromHtml(html: String): VideoDetail? = this.html.parseDetailFromHtml(html)

    override fun searchUrlFor(keyword: String, page: Int): String? = html.searchUrlFor(keyword, page)

    override fun browseUrlFor(typeId: String, page: Int): String? = html.browseUrlFor(typeId, page)

    override fun detailUrlFor(id: String): String? = html.detailUrlFor(id)

    // ------------------------------------------------------------------ 诊断

    /** 把网盘那一段接进自检报告：判定靠什么依据、这一族现在是三态里的哪一种 */
    override val calibDiag: String
        get() {
            val tail = listOfNotNull(
                panDiag.takeIf { it.isNotBlank() }?.let { "网盘：$it" },
                "网盘分享站族：" + PanShareFamily.describe(site.baseUrl)
            ).joinToString("\n      ")
            val base = html.calibDiag
            return if (tail.isBlank()) base else "$base\n      $tail"
        }
}
