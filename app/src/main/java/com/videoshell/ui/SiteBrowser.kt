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
import com.videoshell.data.ListCache
import com.videoshell.data.Store
import com.videoshell.data.model.Category
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.AggSearch
import com.videoshell.data.model.VideoRow
import com.videoshell.data.site.CryptFamily
import com.videoshell.data.site.RecipeStore
import com.videoshell.data.site.RecipeTransfer
import com.videoshell.data.site.SearchEngine
import com.videoshell.data.site.SearchScope
import com.videoshell.data.site.SiteAdapter
import com.videoshell.data.site.SiteDoctor
import com.videoshell.databinding.ViewSiteBrowserBinding
import com.videoshell.player.SniffActivity
import com.videoshell.ui.adapter.CategoryAdapter
import com.videoshell.ui.adapter.SearchSuggestAdapter
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
    internal val act: AppCompatActivity,
    internal val b: ViewSiteBrowserBinding,
    private val launchCalib: (String) -> Unit,
    private val onOpenDetail: (String, VideoItem) -> Unit,
    /**
     * 搜索结果交给谁（v1.0.50）。
     *
     * 搜索**不再**在本页网格里显示：结果挪到了二级页（[SearchActivity]），
     * 那一页左侧能直接切换站源 —— "这个站没有，换一个站看看"从
     * "退出去、换站、再搜一遍"变成"点一下左边"。
     *
     * 参数是「关键词」+「是不是聚合（全站源）」。默认 null ⇒ 维持旧行为
     * （在本页网格里搜），这样离线自检与将来别的宿主都不会被这次改动打断。
     */
    internal val openSearch: ((keyword: String, aggregate: Boolean) -> Unit)? = null
) {

    companion object {
        internal const val MODE_CATEGORY = 0
        internal const val MODE_SEARCH = 1
    }

    var site: SiteConfig? = null
        private set
    internal var adapter: SiteAdapter? = null

    private val catAdapter = CategoryAdapter { index, c -> onCategory(index, c) }

    /**
     * `siteKey` → 站名 的查询表，「搜全站源」时给卡片标出来源站。
     *
     * 为什么要先存成一张表：`siteNameOf` 会在**每一张卡片绑定时**被调用，
     * 里面若去 `Store.sites()` 就是每次绑定读一次 SharedPreferences ⇒
     * 滚动时每帧一次磁盘读，网格会明显发涩。存表只读内存。
     */
    internal val siteNames = HashMap<String, String>()

    internal val videoAdapter = VideoAdapter(
        onClick = { item ->
            // 聚合结果自带来源站；单站浏览时它为空 ⇒ 回落到当前站
            val k = item.siteKey.ifBlank { site?.key.orEmpty() }
            if (k.isNotBlank()) onOpenDetail(k, item)
        },
        siteNameOf = { key -> siteNames[key].orEmpty() }
    )

    /** 搜索历史下拉适配器（FN-2） */
    internal val suggestAdapter = SearchSuggestAdapter(
        onClick = { kw ->
            b.inputSearch.setText(kw)
            b.inputSearch.setSelection(kw.length)
            hideSuggestions()
            doSearch()
        },
        onLongClick = { kw ->
            Store.removeSearchHistory(act, kw)
            showSuggestions()
        }
    )

    internal var cats: List<Category> = emptyList()
    internal var page = 1
    internal var loading = false
    internal var mode = MODE_CATEGORY
    internal var currentType = ""
    internal var keyword = ""

    /** 搜索范围：本站 / 全站 / 全网（持久化 —— 选过一次，下次进来还得是它） */
    internal var scope = SearchScope.SITE

    /**
     * 全网搜索用哪个引擎（v1.0.38，持久化）。
     *
     * 默认百度：这个入口的用途是"找一个能播的站"，而中文在线影视站在百度的收录
     * 远好于 Bing（Bing 会因合规策略把这类站压掉）。引擎可选而不是换死，
     * 因为"哪家收录好"是会变的东西 —— 可变的东西只能当选项，不能写进判据。
     */
    internal var engine = SearchEngine.DEFAULT

    /** 关键词是否追加「在线观看」（v1.0.38，持久化） */
    internal var enhance = false

    /** 首页那次「预渲染兜底」只试一次，别把每次翻页都拖成 WebView 加载 */
    internal var webRendered = false

    init {
        scope = SearchScope.of(Store.searchScope(act))
        engine = SearchEngine.of(Store.searchEngine(act))
        enhance = Store.searchEnhance(act)
        b.btnSearchToggle.setOnClickListener {
            if (b.searchRow.visibility == View.VISIBLE) {
                // 再点一次 = **退出搜索**，走与 ✕ / 返回键**同一个**入口（v1.0.50）。
                // 旧实现是在这里自己重排一遍状态（只改可见性、不动关键词），
                // 于是"放大镜点一次收起了、分类条却没回来"这类不一致就从这个分支冒出来 ——
                // 同一件事有三个入口各写一遍，迟早长得不一样。
                exitSearch()
            } else {
                b.searchRow.visibility = View.VISIBLE
                // 范围选择与搜索框同生共死：单看"全站"两个字，没有任何意义
                b.scopeRow.visibility = View.VISIBLE
                renderScope()
            }
        }
        b.btnSearch.setOnClickListener { doSearch() }
        // 退出搜索（v1.0.50）：唯一的显式出口。返回键走的是同一个方法，
        // 所以"按 ✕"和"按返回"不会出现两种结果。
        b.btnExitSearch.setOnClickListener { exitSearch() }
        b.inputSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        b.scopeSite.setOnClickListener { setScope(SearchScope.SITE) }
        b.scopeAll.setOnClickListener { setScope(SearchScope.ALL) }
        b.scopeWeb.setOnClickListener { setScope(SearchScope.WEB) }
        b.engBaidu.setOnClickListener { setEngine(SearchEngine.BAIDU) }
        b.engSogou.setOnClickListener { setEngine(SearchEngine.SOGOU) }
        b.eng360.setOnClickListener { setEngine(SearchEngine.SO360) }
        b.engBing.setOnClickListener { setEngine(SearchEngine.BING) }
        b.engDdg.setOnClickListener { setEngine(SearchEngine.DDG) }
        b.engSuffix.setOnClickListener { setEnhance(!enhance) }
        b.btnCatRetry.setOnClickListener { loadCategories() }
        b.tvCatHint.setOnClickListener { showCategoryPicker() }
        b.btnDoctor.setOnClickListener { runDoctor() }
        b.btnCalib.setOnClickListener { site?.let { launchCalib(it.key) } }
    }

    /**
     * 底部让位（v1.0.49）。
     *
     * 首页的底部导航是**浮**在内容之上的一层，列表必须自己让出它的高度，否则最后
     * 一行会被压在导航栏底下。
     *
     * ⚠️ 让位只能加在 `rvVideos` 自己的 paddingBottom 上（它已经是
     * `clipToPadding="false"`）—— 加在外层容器上只会把整个列表上推，内容依旧不会
     * 从导航栏底下穿过，那"半透明"就白做了。
     * 二级站源页（SiteActivity）没有底部导航，不调这个方法即保持默认 20dp。
     */
    fun setBottomInset(px: Int) {
        b.rvVideos.setPadding(
            b.rvVideos.paddingStart, b.rvVideos.paddingTop, b.rvVideos.paddingEnd, px
        )
    }

    /** 首次接线：拿不到视图尺寸的东西都在这里定 */
    fun setup(showBack: Boolean, onBack: () -> Unit) {
        b.btnBack.visibility = if (showBack) View.VISIBLE else View.GONE
        b.btnBack.setOnClickListener { onBack() }
        val span =
            if (act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 5 else 3
        b.rvCats.layoutManager = LinearLayoutManager(act, RecyclerView.HORIZONTAL, false)
        b.rvCats.adapter = catAdapter
        // 分组标题必须**独占整行**（v1.0.39）：在 3/5 列的网格里，标题若只占一格、
        // 和卡片并排，分组边界反而比不加标题更糊 —— 那正是这次要解决的问题。
        b.rvVideos.layoutManager = GridLayoutManager(act, span).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (videoAdapter.isHeader(position)) span else 1
            }
        }
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

    /**
     * 分类加载的代次（v1.0.50）。
     *
     * 分类是**异步且带重试**的（最多 3 轮，退避 1.5s / 3s，最坏约 4.5s 才收尾），
     * 而用户完全可能在这段时间里就去搜索了。晚到的那一轮若照旧执行收尾，会做两件坏事：
     * ① 把 `catHintRow` / `rvCats` 重新拉成 VISIBLE —— 搜索结果上方冒出一排站点分类标签；
     * ② 调 `onCategory(0)`，把 mode 打回分类并 reload —— 刚搜出来的结果整屏被换掉。
     * 这正是「搜索结果带当前站源的分类行」的真因（v1.0.41 只在 reload() 里收口，
     * 没管住这条异步尾巴）。
     *
     * 判据用**代次**而不是布尔标记：连续两次 loadCategories（切站、校准回来）时，
     * 只看"有没有在搜"分不出哪一轮是旧的，旧的那轮会把新的那轮结果覆盖掉。
     */
    private var catSeq = 0

    /**
     * 现在是不是"搜索真的发起过"。
     *
     * ⚠️ 不能用 `mode == MODE_SEARCH`：v1.0.50 起搜索结果在二级页里，本页网格始终是
     * 分类内容 ⇒ mode 恒为 MODE_CATEGORY。也不能只看"搜索区开着"：
     * 用户点了放大镜、还没输关键词时分类条不该消失。两个条件合起来才是准确判据。
     */
    private fun searchingNow(): Boolean = mode == MODE_SEARCH || keyword.isNotBlank()

    /** 分类条可见性的**唯一**出口：三处调用点（reload / loadCategories 首尾）共用一份判据 */
    private fun applyCatVisibility() {
        val searching = searchingNow()
        b.rvCats.visibility = if (searching) View.GONE else View.VISIBLE
        b.catHintRow.visibility = if (searching) View.GONE else View.VISIBLE
    }

    internal fun loadCategories() {
        val a = adapter ?: return
        val seq = ++catSeq
        b.pb.visibility = View.VISIBLE
        showState(null)
        b.tvCatHint.text = act.getString(R.string.cat_loading)
        applyCatVisibility()
        act.lifecycleScope.launch {
            var list: List<Category> = emptyList()
            var why = ""
            // 手机网络下"每个新域名的第一次请求"很容易抖一下（DNS 慢 / 首次握手超时）。
            // 旧实现一抖整条分类栏就消失，只能靠用户手动点"重新加载"。这里自动补两轮
            // （退避 1.5s / 3s），抖动就自愈了 —— 用户不该为这种事点第二次。
            for (round in 0 until 3) {
                // 已经有更新的一轮在跑 ⇒ 立刻收工，别把旧结果写回去
                if (seq != catSeq) return@launch
                if (round > 0) {
                    b.tvCatHint.text = act.getString(R.string.cat_retrying, round)
                    delay(if (round == 1) 1_500L else 3_000L)
                    // ⚠️ delay 之后必须**再查一次**：这 1.5s / 3s 正是用户最可能切站
                    // 或去搜索的窗口，退避睡醒才检查等于把窗口白留
                    if (seq != catSeq) return@launch
                }
                val res = runCatching { a.categories() }
                list = res.getOrElse { emptyList() }
                if (list.isNotEmpty()) break
                why = res.exceptionOrNull()
                    ?.let { it.javaClass.simpleName + ": " + it.message }
                    ?.takeIf { it.isNotBlank() }
                    ?: a.lastDiag.ifBlank { NetLog.lastFailure() }
            }
            if (seq != catSeq) return@launch
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
            // ⚠️ 搜索发起过时**不许**把分类条拉回来、也不许 onCategory(0) 换掉网格（v1.0.50）。
            // 分类数据本身照常写进 cats / catAdapter —— 退出搜索后立刻可用、不用重拉；
            // 被压住的只有"可见性"与"切网格"这两个动作。
            applyCatVisibility()
            if (searchingNow()) return@launch
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

    /**
     * 退出搜索（v1.0.50）。
     *
     * 判据是**搜索区开着**，而不是"网格里是不是搜过"：搜索结果已经在二级页里了，
     * 本页要退的只是这个操作区。收敛到一个方法、✕ 与返回键共用，避免出现
     * "按 ✕ 收起了、按返回却没反应"这种两套判据各说各话的情况。
     *
     * @return true = 确实退出了搜索（调用方据此决定要不要把返回键继续往下传）
     */
    fun exitSearch(): Boolean {
        if (b.searchRow.visibility != View.VISIBLE) return false
        hideSuggestions()
        keyword = ""
        b.inputSearch.setText("")
        b.searchRow.visibility = View.GONE
        b.scopeRow.visibility = View.GONE
        b.engineRow.visibility = View.GONE
        // 网格里可能留着搜索结果（openSearch 为 null 的那条旧路径），也可能因为
        // **分类是异步的、首屏那次加载被搜索打断**而还是空的（v1.0.50 新增的路径）
        // ⇒ 一律退回「最新」分类重拉一次。反过来，网格里已经有分类内容时不重拉，
        // 免得用户退出搜索的瞬间看见列表闪一下。
        if (mode == MODE_SEARCH || videoAdapter.itemCount == 0) {
            mode = MODE_CATEGORY
            catAdapter.select(0)
            currentType = ""
            reload()
        }
        // 没走重拉那条路时，分类条的可见性还得拨回来（reload 里已经拨过一次）
        applyCatVisibility()
        return true
    }

    fun reload() {
        page = 1
        videoAdapter.clear()
        showState(null)
        // 搜索态把站点自己的分类条（chips 行 + 「分类 N 个」提示）一起收掉（v1.0.40）。
        // 搜索结果跟站点分类无关，留着会让人以为这排标签还在参与过滤。
        // ⚠️ v1.0.50 起判据收敛进 applyCatVisibility()：原来 reload() 与
        // loadCategories 的异步尾巴**各判一次**，尾巴那一次判漏了 ——
        // 于是"搜索结果上方重新冒出分类条"。判据只能有一份。
        applyCatVisibility()
        b.pb.visibility = View.VISIBLE
        load(1, false)
    }

    /** 缓存时间戳 →「MM-dd HH:mm」，只给「离线缓存」横幅用 */
    internal fun stampOf(ts: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ts))

    internal fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()
}
