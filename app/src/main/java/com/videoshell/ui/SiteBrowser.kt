package com.videoshell.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.Store
import com.videoshell.data.model.Category
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.AggSearch
import com.videoshell.data.site.CryptFamily
import com.videoshell.data.site.RecipeStore
import com.videoshell.data.site.SearchScope
import com.videoshell.data.site.SiteAdapter
import com.videoshell.data.site.SiteDoctor
import com.videoshell.databinding.ViewSiteBrowserBinding
import com.videoshell.player.SniffActivity
import com.videoshell.ui.adapter.CategoryAdapter
import com.videoshell.ui.adapter.VideoAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「站源浏览」控制器：分类浏览 + 搜索 + 加载更多 + 自检 + 校准。
 *
 * 首页与二级站源页**共用同一个视图**（`view_site_browser.xml`），逻辑也只有这一份 ——
 * v1.0.25 之前首页只显示 12 条静态内容、分类/搜索还得点进二级页，两处逻辑各写一遍。
 * 现在首页就是这个浏览器本身，二级页只是它的另一个宿主。
 *
 * @param launchCalib 宿主注册的校准入口（registerForActivityResult 必须在宿主创建期注册）
 * @param onOpenDetail 点影片封面的动作（宿主决定跳哪个 Activity）
 */
class SiteBrowser(
    private val act: AppCompatActivity,
    private val b: ViewSiteBrowserBinding,
    private val launchCalib: (String) -> Unit,
    private val onOpenDetail: (String, VideoItem) -> Unit
) {

    companion object {
        private const val MODE_CATEGORY = 0
        private const val MODE_SEARCH = 1
    }

    var site: SiteConfig? = null
        private set
    private var adapter: SiteAdapter? = null

    private val catAdapter = CategoryAdapter { index, c -> onCategory(index, c) }

    /**
     * `siteKey` → 站名 的查询表，「搜全站源」时给卡片标出来源站。
     *
     * 为什么要先存成一张表：`siteNameOf` 会在**每一张卡片绑定时**被调用，
     * 里面若去 `Store.sites()` 就是每次绑定读一次 SharedPreferences ⇒
     * 滚动时每帧一次磁盘读，网格会明显发涩。存表只读内存。
     */
    private val siteNames = HashMap<String, String>()

    private val videoAdapter = VideoAdapter(
        onClick = { item ->
            // 聚合结果自带来源站；单站浏览时它为空 ⇒ 回落到当前站
            val k = item.siteKey.ifBlank { site?.key.orEmpty() }
            if (k.isNotBlank()) onOpenDetail(k, item)
        },
        siteNameOf = { key -> siteNames[key].orEmpty() }
    )

    private var cats: List<Category> = emptyList()
    private var page = 1
    private var loading = false
    private var mode = MODE_CATEGORY
    private var currentType = ""
    private var keyword = ""

    /** 搜索范围：本站 / 全站 / 全网（持久化 —— 选过一次，下次进来还得是它） */
    private var scope = SearchScope.SITE

    /** 首页那次「预渲染兜底」只试一次，别把每次翻页都拖成 WebView 加载 */
    private var webRendered = false

    init {
        scope = SearchScope.of(Store.searchScope(act))
        b.btnSearchToggle.setOnClickListener {
            val show = b.searchRow.visibility != View.VISIBLE
            b.searchRow.visibility = if (show) View.VISIBLE else View.GONE
            // 范围选择与搜索框同生共死：单看"全站"两个字，没有任何意义
            b.scopeRow.visibility = if (show) View.VISIBLE else View.GONE
            renderScope()
            if (!show && mode == MODE_SEARCH) {
                mode = MODE_CATEGORY
                catAdapter.select(0)
                currentType = ""
                reload()
            }
        }
        b.btnSearch.setOnClickListener { doSearch() }
        b.inputSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        b.scopeSite.setOnClickListener { setScope(SearchScope.SITE) }
        b.scopeAll.setOnClickListener { setScope(SearchScope.ALL) }
        b.scopeWeb.setOnClickListener { setScope(SearchScope.WEB) }
        b.btnCatRetry.setOnClickListener { loadCategories() }
        b.tvCatHint.setOnClickListener { showCategoryPicker() }
        b.btnDoctor.setOnClickListener { runDoctor() }
        b.btnCalib.setOnClickListener { site?.let { launchCalib(it.key) } }
    }

    // ------------------------------------------------------------------ 搜索范围

    private fun setScope(s: SearchScope) {
        if (scope == s) {
            renderScope()
            return
        }
        val before = scope
        scope = s
        Store.setSearchScope(act, s.name)
        renderScope()
        if (mode != MODE_SEARCH || keyword.isBlank()) return
        // 已经在搜了 ⇒ 按新范围重来一遍。唯独 WEB 不重来网格（结果不在网格里，在网页里）
        when (s) {
            SearchScope.WEB -> openWebSearch(keyword)
            SearchScope.ALL -> { refreshSiteNames(); reload() }
            SearchScope.SITE -> if (before == SearchScope.ALL) reload()
        }
    }

    private fun renderScope() {
        b.scopeSite.isSelected = scope == SearchScope.SITE
        b.scopeAll.isSelected = scope == SearchScope.ALL
        b.scopeWeb.isSelected = scope == SearchScope.WEB
        b.tvScopeHint.text = when (scope) {
            SearchScope.SITE -> act.getString(R.string.scope_hint_site)
            SearchScope.ALL -> {
                val n = Store.sites(act).size
                if (n == 0) act.getString(R.string.scope_hint_all_empty)
                else act.getString(R.string.scope_hint_all, n)
            }
            SearchScope.WEB -> act.getString(R.string.scope_hint_web)
        }
    }

    /**
     * 全网搜索：**不做抓取解析**，直接把 Bing 结果页当网页打开。
     *
     * 理由见 [SearchScope] 的注释 —— 抓搜索引擎结果再解析，等于把"站点的适配难题"
     * 换成"搜索引擎的适配难题"，而且对方改版我们必挂。打开网页则一次也不用修。
     */
    private fun openWebSearch(kw: String) {
        b.tvScopeHint.text = act.getString(R.string.scope_web_searching, kw)
        act.startActivity(
            SniffActivity.intent(
                act,
                SearchScope.webSearchUrl(kw),
                kw,
                mapOf("User-Agent" to Http.UA),
                browse = true
            )
        )
    }

    /** 站名表只在「要用到聚合搜索」时刷一次（新增/改名/删除站点后都够新） */
    private fun refreshSiteNames() {
        siteNames.clear()
        Store.sites(act).forEach {
            siteNames[it.key] = it.name.ifBlank { Store.hostOf(it.baseUrl) }
        }
    }

    /** 首次接线：拿不到视图尺寸的东西都在这里定 */
    fun setup(showBack: Boolean, onBack: () -> Unit) {
        b.btnBack.visibility = if (showBack) View.VISIBLE else View.GONE
        b.btnBack.setOnClickListener { onBack() }
        val span =
            if (act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 5 else 3
        b.rvCats.layoutManager = LinearLayoutManager(act, RecyclerView.HORIZONTAL, false)
        b.rvCats.adapter = catAdapter
        b.rvVideos.layoutManager = GridLayoutManager(act, span)
        b.rvVideos.adapter = videoAdapter
        b.rvVideos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= videoAdapter.itemCount - 4) {
                    load(page + 1, true)
                }
            }
        })
        renderScope()
        refreshSiteNames()
    }

    /** 一个站点都没有：给首页用的空态 */
    fun showNoSite() {
        site = null
        adapter = null
        webRendered = false
        b.tvTitle.text = act.getString(R.string.app_name)
        catAdapter.submit(emptyList())
        b.catHintRow.visibility = View.GONE
        videoAdapter.clear()
        b.pb.visibility = View.GONE
        showState(act.getString(R.string.home_empty_default))
    }

    /** 绑定一个站点并开始拉分类 */
    fun bind(siteKey: String) {
        val s = Store.find(act, siteKey)
        if (s == null) {
            act.toast("站点不存在")
            return
        }
        bindSite(s)
    }

    fun bindSite(s: SiteConfig, force: Boolean = false) {
        if (!force && site?.key == s.key && adapter != null) return
        site = s
        adapter = AdapterFactory.create(s)
        webRendered = false
        b.tvTitle.text = s.name.ifBlank { s.baseUrl }
        cats = emptyList()
        loadCategories()
    }

    /** 校准页回来后：配方/站点模式都可能变了，必须换新适配器重来 */
    fun onCalibReturned() {
        // 首页在「还没绑定站点」的空态时 site 为 null —— 旧实现直接 return，
        // 于是从首页进校准、返回后什么都不刷新（用户以为"校准没生效"）。
        // 兜底：没有当前站点就回到默认站源重绑一次。
        val cur = site
        val fresh = if (cur != null) Store.find(act, cur.key) else Store.defaultSite(act)
        if (fresh == null) return
        site = fresh
        adapter = AdapterFactory.create(fresh)
        webRendered = false
        b.tvTitle.text = fresh.name.ifBlank { fresh.baseUrl }
        cats = emptyList()
        act.toast(act.getString(R.string.calib_resumed))
        loadCategories()
    }

    // ------------------------------------------------------------------ 分类 / 列表

    private fun loadCategories() {
        val a = adapter ?: return
        b.pb.visibility = View.VISIBLE
        showState(null)
        b.tvCatHint.text = act.getString(R.string.cat_loading)
        b.catHintRow.visibility = View.VISIBLE
        act.lifecycleScope.launch {
            var list: List<Category> = emptyList()
            var why = ""
            // 手机网络下"每个新域名的第一次请求"很容易抖一下（DNS 慢 / 首次握手超时）。
            // 旧实现一抖整条分类栏就消失，只能靠用户手动点"重新加载"。这里自动补两轮
            // （退避 1.5s / 3s），抖动就自愈了 —— 用户不该为这种事点第二次。
            for (round in 0 until 3) {
                if (round > 0) {
                    b.tvCatHint.text = act.getString(R.string.cat_retrying, round)
                    delay(if (round == 1) 1_500L else 3_000L)
                }
                val res = runCatching { a.categories() }
                list = res.getOrElse { emptyList() }
                if (list.isNotEmpty()) break
                why = res.exceptionOrNull()
                    ?.let { it.javaClass.simpleName + ": " + it.message }
                    ?.takeIf { it.isNotBlank() }
                    ?: a.lastDiag.ifBlank { NetLog.lastFailure() }
            }
            b.pb.visibility = View.GONE
            cats = list
            val all = listOf(Category("", act.getString(R.string.cat_latest))) + list
            catAdapter.submit(all)
            // 这一行**常驻显示**：成功时报数量、失败时报原因。
            // 目的：让"分类栏是空的"自己说清是数据问题还是渲染问题。
            b.tvCatHint.text = if (list.isEmpty()) {
                if (why.isBlank()) act.getString(R.string.cat_only_home)
                else act.getString(R.string.cat_only_home) + "（" + why + "）"
            } else {
                act.getString(R.string.cat_count, list.size)
            }
            b.btnCatRetry.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            b.catHintRow.visibility = View.VISIBLE
            onCategory(0, all[0])
        }
    }

    private fun showCategoryPicker() {
        val all = listOf(Category("", act.getString(R.string.cat_latest))) + cats
        if (all.size <= 1) {
            act.toast(act.getString(R.string.cat_count, 0))
            return
        }
        AlertDialog.Builder(act)
            .setTitle(R.string.cat_picker_title)
            .setItems(all.map { it.name }.toTypedArray()) { _, which ->
                onCategory(which, all[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onCategory(index: Int, c: Category) {
        catAdapter.select(index)
        b.rvCats.smoothScrollToPosition(index)
        mode = MODE_CATEGORY
        currentType = c.id
        reload()
    }

    private fun doSearch() {
        val kw = b.inputSearch.text.toString().trim()
        if (kw.isEmpty()) {
            act.toast("请输入关键词")
            return
        }
        keyword = kw
        mode = MODE_SEARCH
        if (scope == SearchScope.WEB) {
            // 全网：结果在网页里看。**刻意不清空网格** —— 清空会让人以为"没搜到"，
            // 而真相是结果换了地方显示；留着旧内容，视线自然跟着新开的网页走。
            openWebSearch(kw)
            return
        }
        if (scope == SearchScope.ALL) refreshSiteNames()
        reload()
    }

    fun reload() {
        page = 1
        videoAdapter.clear()
        showState(null)
        b.pb.visibility = View.VISIBLE
        load(1, false)
    }

    /**
     * 拉一页。
     *
     * 两条路：**单站**（`adapter` 干活，原有逻辑）与**全站聚合**（每个站都搜一遍再拼起来）。
     * 聚合那条路故意不走 [webRendered] 预渲染兜底：兜底要"开一个 WebView 把页面跑一遍"，
     * 而聚合是多站 —— 给每个站都开一次就等于批量拉网页，代价与收益完全不成比例。
     */
    private fun load(p: Int, append: Boolean) {
        if (loading) return
        val agg = mode == MODE_SEARCH && scope == SearchScope.ALL
        val a = adapter
        // 聚合不需要"当前站"的适配器；单站搜索/分类浏览没有它则无从加载
        if (!agg && a == null) return
        loading = true

        act.lifecycleScope.launch {
            var items: List<VideoItem>
            var why = ""
            var summary: String? = null
            var failures: List<AggSearch.SiteHits> = emptyList()

            if (agg) {
                val hits = AggSearch.run(Store.sites(act), keyword, p)
                items = AggSearch.merge(hits)
                summary = AggSearch.summary(hits)
                failures = AggSearch.failures(hits)
            } else {
                val ad = a!!
                val res = runCatching {
                    if (mode == MODE_SEARCH) ad.search(keyword, p) else ad.browse(currentType, p)
                }
                items = res.getOrElse { emptyList() }

                // ⑥ 预渲染兜底：首屏一条都解析不出来时，用 WebView 把页面真正跑一遍再取 DOM 解析。
                // 纯客户端渲染的站（模板注入、JS 拼卡片）在这里被救回来。
                // 接口型适配器不参与（supportsWebRender=false）—— 它的数据不在 DOM 里，
                // 白开一次 WebView 只是让用户多等几秒，然后必然还是空。
                if (items.isEmpty() && !append && !webRendered && ad.supportsWebRender) {
                    webRendered = true
                    val url = currentTargetUrl(ad)
                    if (url != null) {
                        b.pb.visibility = View.VISIBLE
                        val html = WebRender.html(act, url)
                        if (!html.isNullOrBlank()) items = ad.parseListFromHtml(html, p)
                    }
                }
                if (items.isEmpty()) {
                    why = res.exceptionOrNull()
                        ?.let { it.javaClass.simpleName + ": " + it.message }.orEmpty()
                }
            }

            loading = false
            b.pb.visibility = View.GONE

            if (items.isEmpty()) {
                if (append) {
                    act.toast(act.getString(R.string.no_more))
                    return@launch
                }
                if (agg) {
                    // 汇总行常驻：**"几个站有结果/几个站失败"必须说出来**，
                    // 否则用户只看到"没有结果"，会把我们的问题（某站挂了）当成"这片全网都没有"。
                    b.tvScopeHint.text = summary.orEmpty()
                    val txt = buildString {
                        append(act.getString(R.string.scope_agg_empty, keyword))
                        if (failures.isNotEmpty()) {
                            append("（")
                            append(act.getString(R.string.scope_fail_detail, failures.size))
                            append("）")
                        }
                    }
                    showState(txt)
                    // 失败详情要能点开看，"几个站失败"这种没有主语的句子等于没说
                    if (failures.isNotEmpty()) {
                        b.tvState.setOnClickListener { showFailures(failures) }
                    }
                } else {
                    val net = NetLog.lastFailure()
                    showState(
                        when {
                            why.isNotBlank() -> act.getString(R.string.err_prefix, why)
                            net.isNotBlank() -> "暂无数据（$net）"
                            mode == MODE_SEARCH -> act.getString(R.string.no_result)
                            else -> "暂无数据，可换个分类试试"
                        }
                    )
                }
                return@launch
            }
            if (summary != null) b.tvScopeHint.text = summary
            // 搜索模式：片名里标出关键词（切回分类/换站时自动清掉）。
            // 就放在 submit 前 —— `submit(…, false)` 走 notifyDataSetChanged，会立刻用新值重绑所有卡片；
            // 分散到 doSearch / onCategory / bindSite 各写一遍反而容易漏（本项目踩过"漏传回调"的坑）。
            videoAdapter.highlight = if (mode == MODE_SEARCH) keyword else ""
            videoAdapter.submit(items, append)
            page = p
            showState(null)
        }
    }

    /** 聚合搜索里失败站点的原因清单（用户点一下状态行就能看到） */
    private fun showFailures(fs: List<AggSearch.SiteHits>) {
        val msg = fs.joinToString("\n\n") {
            it.name.ifBlank { it.key } + "\n" + it.error.orEmpty()
        }
        AlertDialog.Builder(act)
            .setTitle(R.string.scope_fail_title)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** 预渲染兜底要打开的地址：分类/首页的第一个候选 */
    private fun currentTargetUrl(a: SiteAdapter): String? {
        val s = site ?: return null
        if (mode == MODE_SEARCH) {
            return a.searchUrlFor(keyword, 1) ?: s.baseUrl
        }
        if (currentType.isBlank()) return s.baseUrl
        return a.browseUrlFor(currentType, 1) ?: s.baseUrl
    }

    private fun showState(msg: String?) {
        b.tvState.text = msg.orEmpty()
        b.tvState.visibility = if (msg == null) View.GONE else View.VISIBLE
        // 聚合搜索会在状态行上挂"查看失败的 N 个站"；状态位一旦清空，
        // 那个入口必须一起退场 —— 留着一个"看不见但能点"的区域，是最难复现的那种 bug。
        if (msg == null) b.tvState.setOnClickListener(null)
    }

    // ------------------------------------------------------------------ 站点自检 / 重学

    private fun runDoctor() {
        val s = site ?: return
        act.toast(act.getString(R.string.site_doctor_running))
        b.pb.visibility = View.VISIBLE
        act.lifecycleScope.launch {
            val report = runCatching { SiteDoctor.run(s) }
                .getOrElse { "自检本身出错：${it.javaClass.simpleName}: ${it.message}" }
            b.pb.visibility = View.GONE
            showReport(report)
        }
    }

    private fun showReport(text: String) {
        val tv = TextView(act).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(ContextCompat.getColor(act, R.color.text_primary))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            this.text = text
        }
        val sv = ScrollView(act).apply { addView(tv) }
        AlertDialog.Builder(act)
            .setTitle(R.string.site_doctor_title)
            .setView(sv)
            .setPositiveButton(R.string.site_doctor_copy) { _, _ ->
                runCatching {
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("videoshell-doctor", text))
                    act.toast(act.getString(R.string.site_doctor_copied))
                }
            }
            .setNegativeButton(R.string.site_doctor_close, null)
            .setNeutralButton(R.string.site_recipe_reset) { _, _ -> resetRecipe() }
            .show()
    }

    /**
     * 站点配方的手动校准入口：清掉配方 + 丢开旧适配器实例，下一次解析等同首次访问。
     */
    private fun resetRecipe() {
        val s = site ?: return
        RecipeStore.clear(s.baseUrl)
        // 「血缘判定」也要一起忘掉：否则一个被自证成加密接口族的域名
        // reset 之后仍会立刻被路由回接口，用户会觉得"重置了没反应"（v1.0.35）
        CryptFamily.forget(s.baseUrl)
        adapter = AdapterFactory.create(s)
        webRendered = false
        cats = emptyList()
        act.toast(act.getString(R.string.site_recipe_reset_done))
        loadCategories()
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()
}
