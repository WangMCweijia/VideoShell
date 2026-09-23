package com.videoshell.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.Store
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import com.videoshell.data.model.VideoRow
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.AggSearch
import com.videoshell.data.site.MirrorRace
import com.videoshell.data.site.RailGrowth
import com.videoshell.data.site.SiteAdapter
import com.videoshell.databinding.ActivitySearchBinding
import com.videoshell.ui.adapter.SearchRailAdapter
import com.videoshell.ui.adapter.VideoAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.launch

/**
 * 搜索结果的**二级页**（v1.0.50）。
 *
 * ## 为什么要把搜索结果搬出首页
 *
 * 原来结果直接铺在首页网格里，"这个站没有、换个站看看"必须：退出去 → 改默认站 →
 * 再搜一遍（三步），而且首页网格从此处于"分类内容 / 搜索结果"两种状态之间，
 * 谁都能把它改掉（v1.0.41 与 v1.0.50 两次"搜索结果里冒出站点分类条"都出在这里）。
 *
 * 现在：结果独立成一页，**左边一列就是站源**，点一下即换。首页网格回到只做分类浏览，
 * 两边的状态不再互相污染。
 *
 * ## 左栏的约定
 *
 * `railSites[0] == null` 表示「聚合」（= 全部站源，[AggSearch]），之后依次是已保存站点。
 * 索引与 [SearchRailAdapter] 的位置一一对应 —— 计数回填也是按这个索引找位置的。
 *
 * ## v1.0.67：聚合模式下左栏**只显示有结果的站**，而且是随到达长出来的
 *
 * 用户的原话是「**没有结果的站源直接不在左侧显示，哪个站源出来了就展示哪一个站源，
 * 后续出来的就增量展示**」。
 *
 * 于是左栏分成两种形态，由 [aggMode] 决定，**进入时就定死不再变**：
 *
 *  - **聚合模式**（从"全站"范围搜进来）：只铺「聚合」一格，其余站由 [revealRail]
 *    在它返回时按需长出 —— 十个站里八个没货时，左栏就只剩两格，不再是一片空格子。
 *    补救入口是末尾那格「显示全部」（[expandRail]）。
 *  - **单站模式**：照旧铺满全部站点。那一栏的用途就是"这个站没有、换个站看看"
 *    （v1.0.50 建这一页的全部理由），藏起来等于把功能删掉。
 *
 * 两件跟着来的事，缺一个就会出"看起来像坏了"的现象：
 *
 *  - **插入位置按站点顺序算**（[RailGrowth.insertAt]），不是按到达顺序 ——
 *    否则同一个站在左栏与网格里的相对位置不一致，来回对照时会以为点错了；
 *  - **[SearchRailAdapter.insert] 要同步右移选中下标** —— 新格插在选中格之前时，
 *    "位置"变了而"身份"没变；不同步的症状是高亮跳到隔壁那个站上。
 *
 * ## 列数为什么是 2（横屏 4）而不是首页的 3 / 5
 *
 * 左栏吃掉了 76dp + 两处间距，结果区只剩约 256dp。还按 3 列排的话每张卡片只有 ~70dp，
 * 封面会明显比首页小一圈。2 列正好让卡片回到 ~115dp，与首页 3 列同档
 * （反算过程写在 dimens/search_rail_w 的注释里）。横屏同理取 4。
 *
 * ## v1.0.51：**每一栏的结果在会话内留着，切回去不再重搜**
 *
 * v1.0.50 的左栏是"点一下 = 发一次请求"：从 A 站切到 B 站、再切回 A 站，
 * 又把 A 站搜了一遍 —— 而 A 的结果刚才就摊在屏幕上，一个字都没变。
 * 更浪费的是「聚合」这一栏：它**已经把每个站的 `search(q, 1)` 发出去过了**，
 * 用户接着点开其中某个站，等于把同一个请求当场重发一次。
 *
 * 所以有了 [cache]（`railKey → RailState`）：
 *
 *  - **命中就整份还原**（[render]），零请求、零闪烁 —— 这一条覆盖"来回对比几个站"的主用法；
 *  - **聚合跑完顺手预填**（[prefill]）：把这一趟已经拿全了的站直接存成单站缓存。
 *    只有"没被截断也没被过滤"的站才填（[AggSearch.complete]）—— 见那个函数的注释；
 *  - **切走了也照样写缓存**：迟到的结果只失去改界面的权利（[loadSeq]），不会白跑。
 *
 * 状态是**整栏一份**（[RailState]）而不是"往网格里补差量"：换栏 = 整份换掉、回栏 = 整份还原，
 * 于是"切回去"与"第一次搜出来"在外观上逐字节相同，不需要任何部分刷新逻辑。
 *
 * ⚠️ 本页在 manifest 里声明了 `configChanges=orientation|screenSize|...` ⇒ **旋转不重建**。
 * 列数只在 onCreate 排一次的话，转一次屏就错位 —— 所以必须补 [onConfigurationChanged]。
 * （这是 v1.0.45 在播放页底栏上踩过的同一个坑。）
 *
 * 顺带一提，旋转不重建在这里还多一个好处：`cache` 是同一个 Activity 实例的字段，
 * 转屏后左栏各站的条数、点开的速度都还在。
 *
 * ## v1.0.52：聚合那一栏**先出结果先显示**
 *
 * 用户的原话是「可以将已经有结果的站源的结果先展示，而不是等所有站源全部搜索结束再统一展示」。
 * 旧实现（[AggSearch.run]）等**所有**站返回才铺网格，而单站超时是 12s —— 十个站里有一个
 * 慢站，用户就得对着空网格干等，可前九个站的结果早就到手了。
 *
 * 现在聚合第 1 页走 [streamAggregate]（[AggSearch.runStreaming]）：每有一个站返回就
 * 回填它的条数、顺手预填它的单站缓存、把它的那一块插进网格。收尾**不整表重铺**，
 * 只补周边文字（[commitChrome]）—— 重铺会把所有封面重绑、集体闪一下。
 *
 * 由此多出两样东西，缺一个都会出"看起来像坏了"的毛病：
 *
 *  - [paintSeq]（界面归谁）与 [loadSeq]（发出去了几轮）分开：流式的多次到达属于同一轮，
 *    可每一次都得有权改界面。只用一个编号就表达不了这件事。
 *  - [inflight]（这一栏还在跑）：用户从聚合切到某个站、再切回来时，缓存里只有"已经到达的
 *    那些站"，铺上是对的，但**不能再发一轮** —— 认领（[selectRail]）而不是重新 [load]。
 */
class SearchActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_KW = "keyword"
        private const val EXTRA_AGG = "aggregate"
        private const val EXTRA_SITE = "site_key"

        /**
         * 「聚合」那一栏在 [cache] 里的键。
         *
         * 站点键取自站点地址的 host，不会是 `@` 开头的形状 —— 所以这个键不会和真站点撞上。
         * 用字符串键而不是左栏下标：下标是"当前这一份站点列表"的位置，站点增减一次就整体错位。
         */
        private const val KEY_AGG = "@agg"

        fun intent(
            ctx: Context,
            keyword: String,
            aggregate: Boolean,
            siteKey: String = ""
        ): Intent = Intent(ctx, SearchActivity::class.java)
            .putExtra(EXTRA_KW, keyword)
            .putExtra(EXTRA_AGG, aggregate)
            .putExtra(EXTRA_SITE, siteKey)
    }

    private lateinit var binding: ActivitySearchBinding
    private lateinit var keyword: String

    /** 左栏每一项对应的站点；**第 0 项固定为 null = 聚合** */
    private val railSites = ArrayList<SiteConfig?>()
    private val railNames = ArrayList<String>()
    private val railCounts = ArrayList<String?>()

    /**
     * 左栏是不是「聚合模式」（= 只显示有结果的站，随到达长出来）。
     *
     * **进入页面时定死**（`EXTRA_AGG`），之后不再变：它决定左栏是"按需长"还是"一次铺满"，
     * 中途切换的话 [railSites] 里那些格子算谁的都说不清。
     */
    private var aggMode = false

    /**
     * 已保存站点的**快照**（顺序 = 站点顺序）。[railSites] 是它按需长出来的子集。
     *
     * 为什么要留一份全量：聚合模式下站没上栏不代表它不存在 —— [expandRail] 要靠它铺回全部，
     * [revealRail] 也要靠它把 `SiteHits.key` 还原成可点的 [SiteConfig]。
     * 每次都回读 `Store.sites()` 也行，但那样"搜索期间站点列表变了"会让同一趟搜索里的
     * 顺序前后不一致。
     */
    private val allSites = ArrayList<SiteConfig>()

    /** 左栏是否已经被「显示全部」展开过（展开是单向的，不收回） */
    private var railExpanded = false

    private var selected = 0

    /** 当前这一栏翻到第几页（滚动翻页的基准，跟随 [shown]） */
    private var page = 1

    /** 有没有一次翻页请求在飞（只用来防滚动重复触发，**不拦换栏**） */
    private var loading = false

    /** 界面此刻显示的那一栏的完整状态 */
    private var shown: RailState? = null

    /**
     * 每一栏搜过的结果，会话内保留 —— 这就是"来回点不用重搜"的全部实现。
     *
     * 键是 [railKey]（`@agg` 或站点 key）。只增不减：一页内存换来的是左栏变成**瞬时**的操作，
     * 而它撑破内存这件事在"一次搜索会话"的尺度上不存在。
     */
    private val cache = HashMap<String, RailState>()

    /**
     * 轮次编号：**每发起一轮加一**，只增不减。
     *
     * 它的用处是给每一轮一个**唯一编号**（[paintSeq] 认的就是这个号），顺带保证
     * "先发的那一轮"与"后发的那一轮"永远不会撞号。谁有资格改界面由 [paintSeq] 决定。
     *
     * 源头是 v1.0.51 的教训：用户点得比请求快是常态（聚合一栏最慢要等齐所有站），
     * 迟到的结果如果把用户**后来**选的那一栏盖掉，看起来就是"我点的站没生效"。
     *
     * 为什么不复用 [loading]：那是个布尔量，分不出"哪一轮是旧的"，只能粗鲁地拒绝新请求
     * （v1.0.50 就是 `if (loading) return`，换栏的那次点击被**静默丢弃**，
     * 而左栏高亮已经移过去了 —— 界面在撒谎）。编号是计数，能同时容纳"旧的作废、新的照跑"。
     */
    private var loadSeq = 0

    /**
     * 界面此刻**归谁**：只有这一代的结果有资格改界面（v1.0.52）。
     *
     * 与 [loadSeq] 的分工是这一版新增的东西，也是流式能成立的前提：
     *
     *  - [loadSeq] 是"发出去了几轮"，只增不减，给每一轮一个**唯一编号**；
     *  - [paintSeq] 是"界面现在认哪一轮"。两者平时一模一样（发起时就认领），
     *    分开只为一种情况：**聚合那一栏要分很多次到达**，每一次都得有权改界面，
     *    可它们属于同一轮 —— 一个编号不够用。
     *
     * ⚠️ 不能靠"把 loadSeq 调回旧值"来认领：那样两轮会撞上同一个编号
     * （先发的那一轮还在飞，后发的又拿到重复号），迟到的结果就能盖住新的一轮。
     */
    private var paintSeq = 0

    /**
     * `railKey → 还在飞的那一轮编号`。
     *
     * 作用是**"这一栏正在跑"**：用户从聚合切到某个站、再切回聚合时，
     * 缓存里只有"已经到达的那些站"，直接铺上去是对的，但**不能再发一轮** ——
     * 那一轮还在飞，而且它下一批结果马上就到。所以这里要认领（见 [selectRail]），
     * 而不是重新 `load`。缺了它，症状是"切回来看到半份结果，然后永远停在那儿"。
     */
    private val inflight = HashMap<String, Int>()

    /** 聚合搜索里失败的站（点状态行可看原因）；单站模式下恒为空。**跟着栏走**（见 [RailState]） */
    private var failures: List<AggSearch.SiteHits> = emptyList()

    /** siteKey → 适配器：同一站在一次会话里来回切时不必重建 */
    private val adapters = HashMap<String, SiteAdapter>()

    /** siteKey → 站名，给卡片副标题用（避免每次绑定都读一次 SharedPreferences） */
    private val siteNames = HashMap<String, String>()

    private val railAdapter = SearchRailAdapter { index -> selectRail(index) }

    private val videoAdapter = VideoAdapter(
        onClick = { item -> openDetail(item) },
        siteNameOf = { key -> siteNames[key].orEmpty() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyword = intent.getStringExtra(EXTRA_KW).orEmpty().trim()
        if (keyword.isEmpty()) {
            finish()
            return
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.tvQuery.text = getString(R.string.search_result_title, keyword)
        binding.tvTip.text = getString(R.string.search_rail_tip)

        binding.rvRail.layoutManager = LinearLayoutManager(this)
        binding.rvRail.adapter = railAdapter
        setupGrid()
        binding.rvVideos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading) return
                // 翻页只对**单站**开放：聚合一栏每个站已经截到 24 条，再翻一页会把同一批站的
                // 分组标题重复铺一遍 —— 列表反而更难读，而"全站"这个功能并不缺内容。
                if (selected == 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= videoAdapter.itemCount - 4) {
                    load(page + 1, true)
                }
            }
        })

        // ⚠️ **先说清这是哪种模式，再铺左栏**：聚合模式不铺站点格（见 [aggMode]）。
        //    顺序写反的症状正是本版要改掉的那个 —— "聚合搜索的左栏仍然一次铺满"。
        aggMode = intent.getBooleanExtra(EXTRA_AGG, false)
        buildRail()
        railAdapter.onFooterClick = { expandRail() }

        // 起始选中哪一项：全站范围进来 ⇒ 聚合；否则 ⇒ 来时的那个站（找不到就取第一个站）
        val wantKey = intent.getStringExtra(EXTRA_SITE).orEmpty()
        val found = railSites.indexOfFirst { it?.key == wantKey }
        selected = when {
            aggMode -> 0
            found > 0 -> found
            railSites.size > 1 -> 1
            else -> 0
        }
        railAdapter.submit(railNames, railCounts, selected)
        // 左栏末尾那格「显示全部」不在这里收（[syncFooter] 只在"确实搜过之后"调）：
        // 一进页面就摆一格"显示全部"，会让人以为"还没搜就已经被藏了东西"。
        load(1, false)
    }

    /** 旋转不重建（见类注释），所以列数要在这里重排一次 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupGrid()
    }

    // ------------------------------------------------------------------ 左栏

    private fun buildRail() {
        railSites.clear()
        railNames.clear()
        railCounts.clear()
        siteNames.clear()
        allSites.clear()
        val sites = Store.sites(this)
        allSites.addAll(sites)
        sites.forEach { siteNames[it.key] = it.name.ifBlank { Store.hostOf(it.baseUrl) } }

        railSites.add(null)                     // ← 「聚合」必须占 0 号位
        railNames.add(getString(R.string.search_rail_agg))
        railCounts.add(getString(R.string.search_rail_sites, sites.size))
        // ★ v1.0.67：**聚合模式不铺站点格** —— 它们由 [revealRail] 在返回时按需长出来
        //   （理由见类注释）。单站模式必须铺满：那一栏的用途就是"这个站没有、换个站看看"。
        if (aggMode) return
        sites.forEach {
            railSites.add(it)
            railNames.add(it.name.ifBlank { Store.hostOf(it.baseUrl) })
            railCounts.add(null)                // 计数等搜出来再填
        }
    }

    /**
     * 聚合流式：一个站返回时，左栏该不该为它长出一格（v1.0.67）。
     *
     * 三道门，缺一道都会出"看起来像坏了"的现象：
     *  - **[aggMode]**：单站模式的左栏是一份固定名单，不该增删（见类注释）；
     *  - **[RailGrowth.shouldShow]**：没内容的站（含失败站）不占格 —— 这是本版的核心诉求；
     *  - **已在栏里就跳过**：用户点过「显示全部」之后，藏起来的站本来就都在栏里了。
     *
     * ⚠️ 与 [commitArrived] 不同，这里**不看 `paintSeq`**：左栏说的是"聚合这一趟哪些站有货"，
     * 属于整趟搜索的公共信息，用户切到别的栏去看了也一样成立（[fillRailCount] 同理）。
     */
    private fun revealRail(h: AggSearch.SiteHits) {
        if (!aggMode) return
        if (!RailGrowth.shouldShow(h)) return
        if (railSites.any { it?.key == h.key }) return
        val s = allSites.firstOrNull { it.key == h.key } ?: return
        val at = RailGrowth.insertAt(
            allSites.map { it.key }, railSites.mapNotNull { it?.key }, h.key
        )
        insertRail(
            at, s, s.name.ifBlank { Store.hostOf(s.baseUrl) },
            getString(R.string.search_count, AggSearch.block(h).size)
        )
        syncFooter()
    }

    /**
     * 运行期往左栏插一格 —— **数据与适配器一起动，这是唯一入口**。
     *
     * 分两处写的话，[railSites] 的下标与界面会在某次到达顺序下错开一格，
     * 那是"点左边的站、右边显示的是另一个站"这类最难复现的 bug。
     */
    private fun insertRail(at: Int, s: SiteConfig, name: String, count: String?) {
        val pos = at.coerceIn(0, railSites.size)
        railSites.add(pos, s)
        railNames.add(pos, name)
        railCounts.add(pos, count)
        railAdapter.insert(pos, name, count)
    }

    /** 收一下末尾那格「显示全部」：**有站被藏着才在**（判据在 [RailGrowth.needFooter]） */
    private fun syncFooter() {
        val need = RailGrowth.needFooter(
            railSites.mapNotNull { it?.key }, allSites.map { it.key }
        )
        railAdapter.setFooter(if (need) getString(R.string.search_rail_show_all) else null)
    }

    /**
     * 尾巴被点了：把藏起来的站全部铺回左栏（v1.0.67）。
     *
     * 这是**聚合模式下点回"没结果的站"的唯一入口** —— 少了它，那些站从此在这一页里
     * 够不着，只能退出去重走"换默认站 → 再搜一遍"，也就是 v1.0.50 建这一页时要消灭的那几步。
     *
     * 走整表 [SearchRailAdapter.submit] 而不是逐个 insert：这是一次显式的展开动作，
     * 一口气铺完最直观（逐个插会有 N 段动画），顺序也天然回到站点顺序。
     * 已经搜到过的站把条数带上（[cache] 里有），没搜过的**留空** ——
     * 那不是"0 条"，是"还没搜过"，写 0 就成了假结论。
     */
    private fun expandRail() {
        if (railExpanded) return
        railExpanded = true
        val curKey = railSites.getOrNull(selected)?.key
        railSites.clear()
        railNames.clear()
        railCounts.clear()
        railSites.add(null)
        railNames.add(getString(R.string.search_rail_agg))
        railCounts.add(getString(R.string.search_rail_sites, allSites.size))
        for (s in allSites) {
            railSites.add(s)
            railNames.add(s.name.ifBlank { Store.hostOf(s.baseUrl) })
            railCounts.add(cache[s.key]?.count)
        }
        // ⚠️ 下标整体挪位了（原来在第 2 格的那个站现在可能在第 5 格）⇒ 按 **key** 重新定位，
        //    绝不能沿用旧下标。聚合那格 key 为 null，0 号位永远是它。
        selected = if (curKey == null) 0
        else railSites.indexOfFirst { it?.key == curKey }.coerceAtLeast(0)
        railAdapter.submit(railNames, railCounts, selected)
        syncFooter()
    }

    /** 左栏下标 → 缓存键。**第 0 项是聚合**（[railSites] 里那一项为 null） */
    private fun railKey(index: Int): String =
        railSites.getOrNull(index)?.key?.takeIf { it.isNotBlank() } ?: KEY_AGG

    /**
     * 记下某一栏此刻的样子 —— **缓存只有这一个写入口**（[prefill] 那个内联构造是唯一例外，
     * 它写的是另一个站、而且是"顺手产物"）。
     *
     * 收口成一处不是洁癖：流式那一趟每到达一个站都要更新一次快照，如果写在回调里，
     * 就成了"网格一处、缓存一处"各说各话，而两者的差异只在特定到达顺序下才显形。
     */
    private fun remember(key: String, st: RailState) {
        cache[key] = st
    }

    private fun selectRail(index: Int) {
        if (index == selected || index !in railSites.indices) return
        selected = index
        railAdapter.select(index)
        val key = railKey(index)
        val run = inflight[key]
        if (run != null) {
            // 这一栏**还在跑**（流式聚合最典型）：缓存里是"已经到达的那些站"，铺上去就是
            // 此刻该看到的样子，然后把归属认领回那一轮，让它继续往下铺。
            // 只认领、不重发 —— 这里不能走 render()，它会把归属推新，那一轮从此改不了界面。
            paintSeq = run
            val partial = cache[key]
            if (partial != null) {
                paintAll(index, partial)
            } else {
                // 一个站都还没返回：没有任何东西可铺，如实显示"在搜"
                videoAdapter.clear()
                binding.tvCount.text = ""
                showState(null)
                binding.pb.visibility = View.VISIBLE
            }
            return
        }
        val hit = cache[key]
        if (hit != null) {
            // 这一栏刚才看过、而且已经跑完 ⇒ 整份还原。**不发请求**。
            render(index, hit)
        } else {
            load(1, false)
        }
    }

    // ------------------------------------------------------------------ 结果

    private fun setupGrid() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val cols = if (land) 4 else 2
        binding.rvVideos.layoutManager = GridLayoutManager(this, cols).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (videoAdapter.isHeader(position)) cols else 1
            }
        }
        binding.rvVideos.adapter = videoAdapter
    }

    /**
     * 取本站的适配器（同一站在一次会话里来回切时复用）。
     *
     * v1.0.67：先 [MirrorRace.of] 把地址定下来 —— 站点可以带一串备用地址，哪个快用哪个。
     * 没有备用地址的站**零开销**（直接返回原地址，一个包都不多发）。
     *
     * ⚠️ 必须**先查缓存再走赛马**：`getOrPut` 的 lambda 不是 suspend 的，
     * 所以这里展开成三步写；顺带避免"每次绑定都赛一次马"。
     */
    private suspend fun adapterFor(s: SiteConfig): SiteAdapter {
        adapters[s.key]?.let { return it }
        val a = AdapterFactory.create(MirrorRace.of(s))
        adapters[s.key] = a
        return a
    }

    /**
     * 铺一整栏：网格 + 周边文字。**不动归属**，调用方决定要不要顺手推（见 [render]）。
     */
    private fun paintAll(index: Int, st: RailState) {
        videoAdapter.setSearchKeyword(keyword)
        videoAdapter.submitRows(st.rows, false)
        binding.rvVideos.scrollToPosition(0)
        commitChrome(index, st)
    }

    /**
     * 收尾：**只补网格之外的字**，以及"这一栏此刻到底是哪一份"的定义。
     *
     * 流式那条路必须走它而不是 [render]：网格在回调里已经逐块 `insertBlock` 插好了，
     * 内容和一次性 `mergeRows` 逐行相同（`AggSearch.insertAt` 的不变式保证），
     * **再整表提交一次只会让所有卡片重绑、封面集体闪一下**（v1.0.38 在首页为这件事
     * 专门写过理由，这里是同一条）。
     *
     * 条数在这里**一次写两处**：顶栏与左栏徽标共用 [RailState.count] 这一个来源。
     */
    private fun commitChrome(index: Int, st: RailState) {
        shown = st
        page = st.page
        failures = st.failures
        binding.tvCount.text = if (st.cards == 0) "" else st.count
        if (index > 0) railAdapter.setCount(index, st.count)
        binding.tvTip.text = st.tip
        showState(st.state)
    }

    /**
     * 缓存命中：整份还原，**并推一次归属**。
     *
     * 推归属是为了让**别的栏**还在飞的那一轮再也盖不回来（见 [paintSeq]）。
     * 所以认领已有归属的那条路（[selectRail] 里 `inflight` 命中）不能用它。
     */
    private fun render(index: Int, st: RailState) {
        paintSeq = ++loadSeq
        paintAll(index, st)
        finishLoad()
    }

    /**
     * 拉一页。
     *
     * [append] 只可能来自单站的翻页（见 onCreate 里的滚动监听）。**前几行就把这一栏的
     * 身份与关键词快照下来** —— 协程跑起来之后 [selected] 可能已经被用户改掉了，
     * 那时再用它去取站点，结果就会归到别的栏名下（而且会写进别的栏的缓存）。
     *
     * 聚合的第 1 页不走这里的收尾：它交给 [streamAggregate]（自己铺、自己提交），
     * 因为它**不能整表重铺**（见 [commitChrome]）。
     */
    private fun load(p: Int, append: Boolean) {
        val index = selected
        val site = railSites.getOrNull(index)
        val key = railKey(index)
        val q = keyword
        val railName = railNames.getOrNull(index).orEmpty()
        // 翻页要接在这一栏**已经显示出来的**那批之后；必须在发起前取，理由同上。
        val base = if (append) shown?.rows ?: emptyList() else emptyList()
        val seq = ++loadSeq
        paintSeq = seq
        inflight[key] = seq

        if (!append) {
            // 换栏即刻清场：宁可看着空白转圈，也不能"点过去了还显示上一个站的片单"
            videoAdapter.clear()
            binding.tvCount.text = ""
            showState(null)
            binding.rvVideos.scrollToPosition(0)
        }
        loading = true
        binding.pb.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val st: RailState = if (site == null) {
                    val sites = Store.sites(this@SearchActivity)
                    if (sites.isEmpty()) {
                        RailState(
                            rows = emptyList(), page = p,
                            tip = getString(R.string.search_rail_tip),
                            count = getString(R.string.search_count, 0),
                            state = getString(R.string.search_no_site), failures = emptyList()
                        )
                    } else if (p == 1) {
                        // ★ 聚合的第 1 页走**流式**（v1.0.52）：先出结果的站先显示，
                        //   不必等最慢的那个（单站超时 12s，等齐就是干等十几秒）。
                        //   它自己铺、自己收尾后直接返回 —— 见 streamAggregate。
                        streamAggregate(index, key, q, sites, seq)
                        return@launch
                    } else {
                        // 第 2 页起不流式：用户那时已经在看内容了，而且聚合本来就翻不了页
                        val hits = AggSearch.run(sites, q, p)
                        val rows = AggSearch.mergeRows(hits)
                        val f = AggSearch.failures(hits)
                        val n = rows.count { it is VideoRow.Card }
                        val msg = if (n == 0) {
                            if (hits.isNotEmpty() && f.size == hits.size) {
                                getString(R.string.search_agg_failed, f.size)
                            } else {
                                getString(R.string.no_result)
                            }
                        } else null
                        RailState(
                            rows = rows, page = p, tip = AggSearch.summary(hits),
                            count = getString(R.string.search_count, n),
                            state = msg, failures = f
                        )
                    }
                } else {
                    val res = runCatching { adapterFor(site).search(q, p) }
                    val items = res.getOrElse { emptyList() }
                    // 失败 ⇒ 这次"哪个地址能用"的判断作废，下回重新赛马（域名轮换的自愈路径）
                    if (res.isFailure) MirrorRace.invalidate(site)
                    if (append && items.isEmpty()) {
                        // 翻到底了：这一栏的状态不动，只提示一句
                        if (seq == paintSeq) {
                            toast(getString(R.string.no_more))
                            finishLoad()
                        }
                        return@launch
                    }
                    val rows = base + items.map { VideoRow.Card(it) }
                    val n = rows.count { it is VideoRow.Card }
                    val why = res.exceptionOrNull()
                        ?.let { it.javaClass.simpleName + ": " + it.message }.orEmpty()
                    val msg = when {
                        n > 0 -> null
                        why.isNotBlank() -> getString(R.string.search_site_failed, railName, why)
                        else -> getString(R.string.search_site_empty, q, railName)
                    }
                    RailState(
                        rows = rows, page = p, tip = getString(R.string.search_rail_tip),
                        count = getString(R.string.search_count, n),
                        state = msg, failures = emptyList()
                    )
                }

                // 不管这一轮还有没有资格改界面，结果都存下来 —— 用户切走了不等于这次请求白跑，
                // 他多半还会切回来（"这个站没有、回去看看全站"就是这个来回）。
                remember(key, st)
                if (seq == paintSeq) render(index, st)
            } finally {
                // 这一轮跑完了，摘掉"还在飞"的标记。比编号而不是无条件 remove：
                // 同一栏被重新发起过的话，先结束的旧轮不能把新那一轮摘掉。
                if (inflight[key] == seq) inflight.remove(key)
            }
        }
    }

    /**
     * 聚合的第 1 页：**流式铺**（v1.0.52）。
     *
     * 机制与 v1.0.38 首页那一趟完全相同（[AggSearch.runStreaming] + `insertBlock`，
     * 插入位置由 [AggSearch.insertAt] 按**行**算），差别只在收尾 —— 这里只补字，
     * 不整表重铺（重铺会让所有封面重绑、集体闪一下）。
     *
     * 每有一个站返回就做四件事，**顺序不能变**：
     *
     *  ⓪ [revealRail]：有内容的站才在左栏占一格（v1.0.67，本版新增）——
     *     必须在 ① 之前，因为 ① 是"往已有的格子里填数字"，格子得先在；
     *  ① [fillRailCount]：把它的条数回填给左栏徽标；
     *  ② [prefill]：拿全了的站顺手存成单站缓存 ⇒ 之后点它就是零请求；
     *  ③ [commitArrived]：把它的那一块插进网格。
     *
     * ①②③与③刻意分开：①②③**必须做**（用户切走了也做，回来就是现成的），
     * ③只在"界面此刻还归这一轮"时做。①②反过来的话，预填刚写下的那些站会被回填
     * 当成"已有结果"跳过，徽标要等到点进去才有数字 —— 正是 v1.0.51 要消灭的等待。
     */
    private suspend fun streamAggregate(
        index: Int,
        key: String,
        q: String,
        sites: List<SiteConfig>,
        seq: Int
    ) {
        val slots = arrayOfNulls<AggSearch.SiteHits>(sites.size)
        var arrived = 0
        val hits = AggSearch.runStreaming(sites, q, 1) { i, h ->
            slots[i] = h
            arrived++
            revealRail(h)
            fillRailCount(h)
            prefill(h, q)
            commitArrived(index, key, slots, i, h, arrived, sites.size, seq)
        }
        val rows = AggSearch.mergeRows(hits)
        val f = AggSearch.failures(hits)
        val n = rows.count { it is VideoRow.Card }
        val msg = if (n == 0) {
            if (hits.isNotEmpty() && f.size == hits.size) {
                getString(R.string.search_agg_failed, f.size)
            } else {
                getString(R.string.no_result)
            }
        } else null
        val st = RailState(
            rows = rows, page = 1, tip = AggSearch.summary(hits),
            count = getString(R.string.search_count, n), state = msg, failures = f
        )
        remember(key, st)
        // 左栏尾巴在这里**无条件**收一次：一个站都没出结果时它尤其重要 ——
        // 那种情况下左栏只剩「聚合」，用户连站源名单都看不到，而那正是他最需要它的时候。
        syncFooter()
        if (seq == paintSeq) {
            // 网格此刻装的就是 rows（逐块插出来的，与它逐行相同）⇒ 只补周边文字
            commitChrome(index, st)
            finishLoad()
        }
    }

    /**
     * 一个站到达时的界面动作：插块、刷新周边文字、把这一份快照留给"中途切走再回来"。
     *
     * 缓存写在**这一处**（不是各个调用点各写一遍）：快照只有一个来源，
     * 界面与缓存才不会各说各话。
     */
    private fun commitArrived(
        index: Int,
        key: String,
        slots: Array<AggSearch.SiteHits?>,
        i: Int,
        h: AggSearch.SiteHits,
        arrived: Int,
        total: Int,
        seq: Int
    ) {
        val st = partialRail(slots, arrived, total)
        remember(key, st)
        // 界面此刻不归这一轮（用户切去别的栏了）⇒ 只落缓存，一个字都不许改动界面
        if (seq != paintSeq) return
        // ★ 流式这条路**不能指望 paintAll**（它只在缓存命中的 render 里跑）：进来就流式时
        //   一次 paintAll 都没有，适配器就一直是默认态 ⇒ 不藏标签、不高亮关键词。
        //   这属于"编译绿、自检绿、只是第一屏少了个行为"的静默失效，必须在**每个铺块的入口**
        //   自己把搜索态设上。纯字段赋值（无 notify），重复设无代价。
        videoAdapter.setSearchKeyword(keyword)
        videoAdapter.insertBlock(AggSearch.insertAt(slots.toList(), i), AggSearch.rows(h))
        commitChrome(index, st)
        binding.pb.visibility = if (st.cards == 0) View.VISIBLE else View.GONE
    }

    /**
     * 聚合**中途**的那一份整栏状态：已经到达的那些站 + 一句话进度。
     *
     * 进度句必须带**分母** —— 只写"已有 N 条"会让用户以为搜索结束了、剩下的站吞了，
     * 而"全站"正是这一栏的承诺。这句话与首页那条共用同一个字符串：
     * 同一句话留两份就一定会有一份被改歪。
     */
    private fun partialRail(
        slots: Array<AggSearch.SiteHits?>,
        arrived: Int,
        total: Int
    ): RailState {
        val rows = AggSearch.mergeRowsArrived(slots.toList())
        val n = rows.count { it is VideoRow.Card }
        return RailState(
            rows = rows,
            page = 1,
            tip = if (arrived >= total) AggSearch.summary(slots.filterNotNull())
            else getString(R.string.scope_agg_streaming, arrived, total, n),
            count = getString(R.string.search_count, n),
            state = null,
            failures = emptyList()
        )
    }

    /**
     * 把聚合这一趟拿到的条数回填进左栏徽标。
     *
     * **跳过已经有自己结果的站**：聚合那份可能被 [AggSearch.PER_SITE] 截到 24 条，
     * 而那个站真搜回来可能是 40 条 —— 拿截断数去盖真数，徽标就永远比网格少。
     * index 0 也要跳过：那是聚合自己，徽标写的是"共 N 个站"，比条数有用。
     */
    private fun fillRailCount(h: AggSearch.SiteHits) {
        val i = railSites.indexOfFirst { it?.key == h.key }
        if (i > 0 && !cache.containsKey(h.key)) {
            railAdapter.setCount(i, getString(R.string.search_count, AggSearch.block(h).size))
        }
    }

    /**
     * 把聚合那一趟的**顺手产物**存成单站缓存（v1.0.51）。
     *
     * 三条不收的规矩，缺一条都会把"省一次请求"变成"显示错的东西"：
     *
     *  1. **失败的站不收**（`!ok`）：它那 [AggSearch.block] 是空的，若当成"没有结果"存下来，
     *     用户点进去看到的是"本站没有这部片"，而真相是"这个站没连上 —— 可以重试"。
     *     （[AggSearch.complete] 对失败站恒为 true，所以这条必须在这里判，那里判不了。）
     *  2. **已经被真搜过的站不覆盖**：真搜回来的是整页，聚合那份可能是截断的。
     *  3. **被 [AggSearch.PER_SITE] 截断的块不收**（[AggSearch.complete] 为 false）：
     *     否则下拉翻页会从第 2 页接着走，把第 1 页剩下的那几条永远跳过去。
     */
    private fun prefill(h: AggSearch.SiteHits, q: String) {
        if (h.key.isBlank() || !h.ok) return
        if (cache.containsKey(h.key)) return
        if (!AggSearch.complete(h)) return
        val cards = AggSearch.block(h)
        cache[h.key] = RailState(
            rows = cards.map { VideoRow.Card(it) },
            page = 1,
            tip = getString(R.string.search_rail_tip),
            count = getString(R.string.search_count, cards.size),
            // 空块是**有效结论**（这个站确实没有），要连状态文案一起存 —— 否则点进去
            // 只有一个空网格，看起来像还在加载。
            state = if (cards.isEmpty()) {
                getString(R.string.search_site_empty, q, h.name.ifBlank { h.key })
            } else null,
            failures = emptyList()
        )
    }

    private fun finishLoad() {
        loading = false
        binding.pb.visibility = View.GONE
    }

    private fun showState(msg: String?) {
        binding.tvState.text = msg.orEmpty()
        binding.tvState.visibility = if (msg == null) View.GONE else View.VISIBLE
        // ⚠️ 状态位是**复用**的：清空时必须显式摘掉监听，否则会留着一个"看不见但能点"的
        // 区域，点开还是上一次的失败清单（本项目最忌讳的那种 bug）。
        binding.tvState.setOnClickListener(null)
        if (msg != null && failures.isNotEmpty()) {
            binding.tvState.setOnClickListener { showFailures() }
        }
    }

    private fun showFailures() {
        val msg = failures.joinToString("\n\n") {
            it.name.ifBlank { it.key } + "\n" + it.error.orEmpty()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.search_fail_title)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun openDetail(item: VideoItem) {
        // 聚合结果自带来源站；单站结果它是空的 ⇒ 用当前选中的那个站
        val key = item.siteKey.ifBlank { railSites.getOrNull(selected)?.key.orEmpty() }
        if (key.isBlank()) {
            toast(getString(R.string.site_empty_action))
            return
        }
        startActivity(DetailActivity.intent(this, key, item))
    }
}

/**
 * 一栏结果的**完整显示状态**（v1.0.51）。
 *
 * 抽成一份不可变快照，是为了让"换栏"这件事只有一个动作：整份换掉。
 * 如果改成"往网格里补差量"，就得区分"这一栏是空的/被截断的/还没拉到过"，
 * 而每一种组合都是一个能出错的分支 —— 这里不给自己出这种题。
 *
 * [state] 是空态/失败态的那句提示（null = 正常有内容）；它跟着栏走，
 * 所以切回一栏时"这个站没连上"那句话还在，点它仍然能看到失败原因。
 */
private class RailState(
    val rows: List<VideoRow>,
    val page: Int,
    val tip: String,
    // 顶栏与左栏徽标**共用**的这份条数文案（"N 条"）—— 数字只有这一处来源，两处永远一致
    val count: String,
    val state: String?,
    val failures: List<AggSearch.SiteHits>
) {
    /** 卡片数（不含分组标题）。顶栏在 0 条时留白，靠它判 */
    val cards: Int get() = rows.count { it is VideoRow.Card }
}
