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
import com.videoshell.data.net.NetLog
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.RecipeStore
import com.videoshell.data.site.SiteAdapter
import com.videoshell.data.site.SiteDoctor
import com.videoshell.databinding.ViewSiteBrowserBinding
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
    private val videoAdapter = VideoAdapter { item ->
        site?.let { onOpenDetail(it.key, item) }
    }

    private var cats: List<Category> = emptyList()
    private var page = 1
    private var loading = false
    private var mode = MODE_CATEGORY
    private var currentType = ""
    private var keyword = ""

    /** 首页那次「预渲染兜底」只试一次，别把每次翻页都拖成 WebView 加载 */
    private var webRendered = false

    init {
        b.btnSearchToggle.setOnClickListener {
            val show = b.searchRow.visibility != View.VISIBLE
            b.searchRow.visibility = if (show) View.VISIBLE else View.GONE
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
        b.btnCatRetry.setOnClickListener { loadCategories() }
        b.tvCatHint.setOnClickListener { showCategoryPicker() }
        b.btnDoctor.setOnClickListener { runDoctor() }
        b.btnCalib.setOnClickListener { site?.let { launchCalib(it.key) } }
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
        reload()
    }

    fun reload() {
        page = 1
        videoAdapter.clear()
        showState(null)
        b.pb.visibility = View.VISIBLE
        load(1, false)
    }

    private fun load(p: Int, append: Boolean) {
        val a = adapter ?: return
        if (loading) return
        loading = true

        act.lifecycleScope.launch {
            val res = runCatching {
                if (mode == MODE_SEARCH) a.search(keyword, p) else a.browse(currentType, p)
            }
            var items = res.getOrElse { emptyList() }

            // ⑥ 预渲染兜底：首屏一条都解析不出来时，用 WebView 把页面真正跑一遍再取 DOM 解析。
            // 纯客户端渲染的站（模板注入、JS 拼卡片）在这里被救回来。
            // 接口型适配器不参与（supportsWebRender=false）—— 它的数据不在 DOM 里，
            // 白开一次 WebView 只是让用户多等几秒，然后必然还是空。
            if (items.isEmpty() && !append && !webRendered && a.supportsWebRender) {
                webRendered = true
                val url = currentTargetUrl(a)
                if (url != null) {
                    b.pb.visibility = View.VISIBLE
                    val html = WebRender.html(act, url)
                    if (!html.isNullOrBlank()) items = a.parseListFromHtml(html, p)
                }
            }

            loading = false
            b.pb.visibility = View.GONE

            if (items.isEmpty()) {
                if (!append) {
                    val why = res.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message }
                    val net = NetLog.lastFailure()
                    showState(
                        when {
                            !why.isNullOrBlank() -> act.getString(R.string.err_prefix, why)
                            net.isNotBlank() -> "暂无数据（$net）"
                            mode == MODE_SEARCH -> act.getString(R.string.no_result)
                            else -> "暂无数据，可换个分类试试"
                        }
                    )
                } else {
                    act.toast(act.getString(R.string.no_more))
                }
                return@launch
            }
            // 搜索模式：片名里标出关键词（切回分类/换站时自动清掉）。
            // 就放在 submit 前 —— `submit(…, false)` 走 notifyDataSetChanged，会立刻用新值重绑所有卡片；
            // 分散到 doSearch / onCategory / bindSite 各写一遍反而容易漏（本项目踩过"漏传回调"的坑）。
            videoAdapter.highlight = if (mode == MODE_SEARCH) keyword else ""
            videoAdapter.submit(items, append)
            page = p
            showState(null)
        }
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
        adapter = AdapterFactory.create(s)
        webRendered = false
        cats = emptyList()
        act.toast(act.getString(R.string.site_recipe_reset_done))
        loadCategories()
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()
}
