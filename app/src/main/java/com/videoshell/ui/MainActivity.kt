package com.videoshell.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.videoshell.App
import android.graphics.drawable.LayerDrawable
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.videoshell.R
import com.videoshell.data.Store
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.data.site.Media
import com.videoshell.data.site.SearchEngine
import com.videoshell.data.site.SearchScope
import com.videoshell.data.site.SiteDetector
import com.videoshell.databinding.ActivityMainBinding
import com.videoshell.player.PlayerActivity
import com.videoshell.player.SniffActivity
import com.videoshell.ui.adapter.SiteListAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 主界面：底部三 Tab。
 * - 首页 = **默认站源的浏览器**（分类 + 搜索 + 网格，与二级站源页同一套视图与逻辑）
 * - 站源 = 添加入口 + 站点管理（点击进入 / 长按改名 / ★ 设默认 / 删除）
 * - 我的 = 播放历史、收藏、播放设置、外观（自动暗色）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var browserHome: SiteBrowser
    private var pendingPageUrl: String = ""

    /** 首页当前绑定的站点 key（避免每次 onResume 都重新拉一遍分类） */
    private var homeKeyBound: String = ""

    private val siteAdapter = SiteListAdapter(
        onClick = { openSite(it) },
        onDelete = { confirmDelete(it) },
        onLongClick = { renameSite(it) },
        onSetDefault = { setDefaultSite(it) }
    )

    /** 首页里的「校准」入口：回来后配方变了，浏览器得重来一遍 */
    private val calibLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        browserHome.onCalibReturned()
    }

    // ------------------------------------------------------------------ 站点导入 / 导出（FN-6）

    /** 导出：让用户选一个 .json 存哪，再把站点列表写进去 */
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val list = Store.sites(this)
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(Gson().toJson(list).toByteArray(Charsets.UTF_8))
            } ?: error("openOutputStream 返回 null")
        }.isSuccess
        if (ok) toast(getString(R.string.site_export_done, list.size, uri.lastPathSegment ?: "文件"))
        else toast("导出失败：无法写入所选文件")
    }

    /** 导入：读一个 .json，按 key / host 去重后并入现有站点 */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            toast("导入失败：文件读不出内容")
            return@registerForActivityResult
        }
        val incoming = runCatching {
            val t = object : TypeToken<MutableList<SiteConfig>>() {}.type
            Gson().fromJson<MutableList<SiteConfig>>(text, t) ?: mutableListOf()
        }.getOrNull().orEmpty()
        if (incoming.isEmpty()) {
            toast("该文件里没有可识别的站点")
            return@registerForActivityResult
        }
        val added = Store.importSites(this, incoming)
        refresh()
        bindHome(force = true)
        toast(getString(R.string.site_import_done, added))
    }

    /** 拖拽排序（FN-6）：只有从手柄起拖，整行长按仍然是「改名」 */
    private var dragHappened = false
    private val touchHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                siteAdapter.moveItem(from, to)
                dragHappened = true
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                // 松手（回到 IDLE）才落盘，中途每次移动都写 SP 太吵
                if (actionState == ItemTouchHelper.ACTION_STATE_IDLE && dragHappened) {
                    dragHappened = false
                    Store.save(this@MainActivity, siteAdapter.currentItems())
                    bindHome(force = true)
                }
            }
        })
    }

    /** 体检用的短超时客户端：只是探活，绝不能像播放那样久等 */
    private val healthClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 版本号只在「我的」页展示一份 —— 顶部那行的版本号随整行一起去掉了（v1.0.26）
        binding.tvVersionMine.text = "v" + versionName()

        // ---- 我的：检查更新（v1.0.54）----
        // 结果写在右侧那格（不靠 toast）：「已是最新」这种答案一闪而过的话，
        // 用户只会再点一遍 —— 他本来就是为了拿到一个确切的答案才点的。
        binding.tvUpdateHint.text = UpdateFlow.idleHint(this)
        binding.rowUpdate.setOnClickListener {
            UpdateFlow.start(this) { hint -> binding.tvUpdateHint.text = hint }
        }

        // ---- 首页：默认站源浏览器 ----
        browserHome = SiteBrowser(
            act = this,
            b = binding.browserHome,
            launchCalib = { key -> calibLauncher.launch(CalibrateActivity.intent(this, key)) },
            onOpenDetail = { key, item -> openDetail(key, item) },
            // 搜索结果统一交给二级页：左侧能直接换站源，比"退出去换站再搜一遍"省两步。
            // 当前站默认选中 —— 用户点了「本站」范围进来，第一眼就该是它的结果。
            openSearch = { kw, agg ->
                startActivity(SearchActivity.intent(this, kw, agg, browserHome.site?.key.orEmpty()))
            }
        )
        browserHome.setup(showBack = false) { }

        // ---- 站源：添加入口 ----
        binding.btnDetect.setOnClickListener { detect() }
        binding.btnSniff.setOnClickListener { sniffFromInput() }
        binding.btnBrowseWeb.setOnClickListener { browseWeb() }
        binding.btnPlayDirect.setOnClickListener { playDirect() }
        binding.inputUrl.doAfterTextChanged { updateDirectButton() }
        binding.inputUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                detect()
                true
            } else false
        }

        // ---- 站源：站点列表 ----
        binding.rvSites.layoutManager = LinearLayoutManager(this)
        binding.rvSites.adapter = siteAdapter
        touchHelper.attachToRecyclerView(binding.rvSites)
        siteAdapter.onStartDrag = { vh -> touchHelper.startDrag(vh) }

        // ---- 站源：管理动作（FN-6）—— 排版 2.0 收进「管理」菜单（v1.0.60）：
        //      4 个并排 38dp 文字按钮热区不足、与主操作抢权重；菜单项与旧按钮
        //      一一对应，行为不变。 ----
        binding.btnManage.setOnClickListener { anchor ->
            val pm = android.widget.PopupMenu(this, anchor)
            pm.menu.add(0, 1, 0, R.string.site_import)
            pm.menu.add(0, 2, 0, R.string.site_export)
            pm.menu.add(0, 3, 0, R.string.site_dedup)
            pm.menu.add(0, 4, 0, R.string.site_health)
            pm.setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    1 -> importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    2 -> {
                        if (Store.sites(this).isEmpty()) {
                            toast(getString(R.string.site_empty_action))
                        } else {
                            exportLauncher.launch("videoshell-sites.json")
                        }
                    }
                    3 -> dedupSites()
                    4 -> healthCheck()
                }
                true
            }
            pm.show()
        }

        // ---- 我的 ----
        binding.rowHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        binding.rowFav.setOnClickListener { startActivity(Intent(this, FavActivity::class.java)) }

        val sp = getSharedPreferences(SP, MODE_PRIVATE)
        binding.swAutoOrient.isChecked = sp.getBoolean("setting_auto_orient", true)
        binding.swAutoOrient.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("setting_auto_orient", checked).apply()
        }
        binding.swAutoNext.isChecked = sp.getBoolean("setting_auto_next", true)
        binding.swAutoNext.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("setting_auto_next", checked).apply()
        }
        binding.swResume.isChecked = sp.getBoolean("setting_resume", true)
        binding.swResume.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("setting_resume", checked).apply()
        }
        // 深色三态（UI-4）：跟随系统 / 浅色 / 深色
        selectDarkMode(sp.getString(App.KEY_DARK_MODE, "system").orEmpty())
        binding.tgDark.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.tgDarkLight -> "light"
                R.id.tgDarkDark -> "dark"
                else -> "system"
            }
            sp.edit().putString(App.KEY_DARK_MODE, mode).apply()
            App.applyDarkMode(mode)
        }

        // 后台播放（FN-4）：默认关
        binding.swBgPlay.isChecked = sp.getBoolean(App.KEY_BG_PLAY, false)
        binding.swBgPlay.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean(App.KEY_BG_PLAY, checked).apply()
            toast(if (checked) getString(R.string.bg_play_on) else getString(R.string.bg_play_off))
        }

        // ---- 底部导航（v1.0.62：手写 Dock）----
        // BottomNavigationView 的 icon↔label 间距是库内固定值、没有对外属性可调
        // （用户反馈的"图标与文字挤在一起"就是它造成的）⇒ 换成手写三格。
        // 代价是丢了 menu 的 checked 状态机：选中态从此由 selectTab 一个入口负责。
        binding.tabHome.setOnClickListener { selectTab(PAGE_HOME) }
        binding.tabSites.setOnClickListener { selectTab(PAGE_SITES) }
        binding.tabMine.setOnClickListener { selectTab(PAGE_MINE) }
        // 底部导航现在是**浮**在内容之上的一层，各列表得自己让出它的高度
        // （实测高度而不是写死，理由见 applyNavClearance 的注释）
        applyNavClearance()
        // 边缘折射层（v1.0.50）：透明度管"看得见背后"，这一层管"像不像玻璃"
        applyNavRefraction()
        setupBack()
        showPage(PAGE_HOME)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        bindHome()
    }

    // ------------------------------------------------------------------ 暗色（UI-4）

    private fun selectDarkMode(mode: String) {
        val id = when (mode) {
            "light" -> R.id.tgDarkLight
            "dark" -> R.id.tgDarkDark
            else -> R.id.tgDarkSystem
        }
        binding.tgDark.check(id)
    }

    // ------------------------------------------------------------------ 页面切换

    /** 已经显示过的页；-1 = 还没初始化（onCreate 里那次不算"切换"） */
    private var currentPage = -1

    /**
     * 切页（v1.0.49 起带动效）。
     *
     * 三页是**同层叠放 + 切 visibility**（没有 Fragment/ViewPager 的转场），所以动效
     * 得自己做。做法是「目标页淡入 + 从下方 10dp 浮上来」：
     *
     * - 方向朝上而不是朝左右：底部导航在下面，内容从下方浮起与手指的方向一致；
     *   左右滑会被误读成"可以横向划"，而这里根本划不动。
     * - 220ms + 减速插值：再长显得拖，再短看不出是动画（人眼门槛约 150ms）。
     * - ⚠️ 每次都要先 `cancel()`：连着点 tab 时上一段动画还在跑，新动画会从"当前
     *   进度"接管，不 cancel 就是两段叠加 —— 表现为闪一下或卡在半透明状态。
     * - ⚠️ 首次进入不播动画：onCreate 里那次调用只是确立初始页，
     *   播了等于开屏抖一下。
     */
    private fun showPage(page: Int) {
        val target = when (page) {
            PAGE_SITES -> binding.pageSites
            PAGE_MINE -> binding.pageMine
            else -> binding.pageHome
        }
        val first = currentPage == -1
        if (!first && page == currentPage) return
        currentPage = page

        for (v in listOf(binding.pageHome, binding.pageSites, binding.pageMine)) {
            if (v !== target) v.visibility = View.GONE
        }

        target.animate().cancel()
        target.visibility = View.VISIBLE
        if (first) {
            target.alpha = 1f
            target.translationY = 0f
        } else {
            target.alpha = 0f
            target.translationY = 10f * resources.displayMetrics.density
            target.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        if (page == PAGE_SITES) refresh()
        if (page == PAGE_HOME) bindHome()
    }

    /**
     * 切页 + 同步 Dock 选中态（v1.0.62）。
     *
     * 两者必须**同进同出**：只切页不刷底栏会出现"内容回首页了、底栏还亮着站源"的
     * 分裂状态。这条不变量原来由 BottomNavigationView 的 menu 状态机代管，
     * 手写 Dock 之后没有任何东西代管了，所以必须做成一个入口 —— 返回键回首页
     * 走的也是这里。
     *
     * 选中态用 `isSelected` 而不是自定义布尔字段：View 的 selected 状态会
     * **自动向下传播**给子 View，所以格子里图标的 tint（nav_item 是 ColorStateList）
     * 与文字色会跟着变，不需要逐个设置 —— 这也正是 bg_nav_item / nav_item 两个
     * selector 都判 state_selected 的原因。
     */
    private fun selectTab(page: Int) {
        for ((tab, p) in listOf(
            binding.tabHome to PAGE_HOME,
            binding.tabSites to PAGE_SITES,
            binding.tabMine to PAGE_MINE
        )) {
            tab.isSelected = p == page
        }
        showPage(page)
    }

    // ------------------------------------------------------------------ 底部导航让位

    /**
     * 给浮在内容之上的底部导航腾出高度（v1.0.49）。
     *
     * 导航栏改成浮层之后，各列表必须自己留出它的高度，否则最后一行永远被压在导航栏
     * 底下、点不到也看不清。
     *
     * ⚠️ 让位加在**列表自己的 paddingBottom** 上（rvVideos / rvSites 都是
     * `clipToPadding="false"`）：加在外层容器上只会把内容整体上推，滚动时内容依旧
     * 不会从玻璃底下穿过 —— 那样半透明材质等于白做。
     *
     * 高度**实测**而不是写死：BottomNavigationView 在 Material2 主题下是 56dp、
     * 在 M3 样式下是 80dp，写死任何一个都会在另一套样式下算错
     * （56 会盖住内容、80 会多出一大截空白）。
     */
    private fun applyNavClearance() {
        binding.bottomNav.doOnLayout { nav ->
            val gap = resources.getDimensionPixelSize(R.dimen.island_gap)
            val pad = nav.height + gap * 2
            browserHome.setBottomInset(pad)
            binding.rvSites.setPadding(
                binding.rvSites.paddingStart,
                binding.rvSites.paddingTop,
                binding.rvSites.paddingEnd,
                pad
            )
            binding.mineContent.setPadding(
                binding.mineContent.paddingStart,
                binding.mineContent.paddingTop,
                binding.mineContent.paddingEnd,
                pad
            )
        }
    }

    // ------------------------------------------------------------------ 导航栏边缘折射（v1.0.50）

    /**
     * 给底部导航浮岛加一圈**边缘折射**。
     *
     * 为什么是"叠一层"而不是替换 `bg_glass_nav`：那一份 XML 里已经有主体渐变、顶部高光、
     * 砂质、描边四层，换掉就得在这里把四层重画一遍 —— 下次改配色要改两处，
     * 而"两处各维护一份必然不一致"正是本项目反复踩过的坑（见 bg_glass_nav 注释里
     * radius_hero 那段：注释说的和代码做的已经不是一回事了）。
     * `LayerDrawable` 里**后画的在上**，所以折射层放第二个。
     *
     * ⚠️ 三支颜色随主题（values / values-night 各一份，极性相反 —— 亮色用暗棱、
     * 暗色用亮棱），所以必须在这里按当前主题构造。切深浅色会重建 Activity，
     * 这一层自然跟着换，不需要额外监听。
     */
    private fun applyNavRefraction() {
        val base = ContextCompat.getDrawable(this, R.drawable.bg_glass_nav) ?: return
        binding.bottomNav.background =
            LayerDrawable(arrayOf(base, GlassEdgeDrawable.forNav(this)))
    }

    // ------------------------------------------------------------------ 返回键

    /**
     * 返回键的三级语义（v1.0.50）。
     *
     * 1. 搜索区开着 ⇒ **先退出搜索**（收起搜索行 + 回到分类浏览）。
     *    搜索以前根本没有出口：唯一的开关是标题栏那枚放大镜，而它在搜索态下看起来
     *    仍然是"搜索"，没人知道再点一次是退出 —— 用户的原话就是
     *    「搜索后无法退出搜索回到首页」。现在 ✕ 与返回键走**同一个入口**
     *    （[SiteBrowser.exitSearch]），两条路不会各说各话。
     * 2. 不在首页 ⇒ 回首页，并把底栏选中态一起拨过去，否则会出现
     *    "内容回首页了、底栏还亮着站源"的分裂状态。
     * 3. 首页且没在搜索 ⇒ 交回系统（退出应用）。
     *
     * 用 `OnBackPressedCallback` 而不是覆写已废弃的 `onBackPressed()`：
     * 第 3 步要"把自己摘出去再转交"，只有 dispatcher 这套能做到不递归。
     */
    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPage == PAGE_HOME && browserHome.exitSearch()) return
                if (currentPage != PAGE_HOME) {
                    // selectTab 一个入口管两件事：刷底栏选中态 + showPage
                    selectTab(PAGE_HOME)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    // ------------------------------------------------------------------ 首页：默认站源

    private fun bindHome(force: Boolean = false) {
        val site = Store.defaultSite(this)
        if (site == null) {
            homeKeyBound = ""
            browserHome.showNoSite()
            return
        }
        if (force || site.key != homeKeyBound) {
            homeKeyBound = site.key
            browserHome.bindSite(site, force = true)
        }
    }

    private fun openDetail(key: String, item: VideoItem) {
        startActivity(DetailActivity.intent(this, key, item))
    }

    // ------------------------------------------------------------------ 站源管理

    private fun refresh() {
        val list = Store.sites(this)
        siteAdapter.defaultKey = Store.defaultKey(this).ifBlank { list.firstOrNull()?.key.orEmpty() }
        siteAdapter.submit(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setDefaultSite(site: SiteConfig) {
        Store.setDefault(this, site.key)
        refresh()
        bindHome(force = true)
        toast(getString(R.string.set_default_done))
    }

    private fun renameSite(site: SiteConfig) {
        val input = android.widget.EditText(this).apply {
            setText(site.name.ifBlank { site.baseUrl })
            setSingleLine(true)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.site_rename_title))
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("名称不能为空")
                    return@setPositiveButton
                }
                Store.rename(this, site.key, name)
                refresh()
                bindHome(force = true)
                toast(getString(R.string.site_rename_done))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(site: SiteConfig) {
        AlertDialog.Builder(this)
            .setTitle(site.name.ifBlank { site.baseUrl })
            .setMessage(getString(R.string.delete_confirm))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                Store.remove(this, site.key)
                refresh()
                bindHome(force = true)
            }
            .show()
    }

    /** 合并重复站点（FN-6）：按 host 归一去重，结果如实报数 */
    private fun dedupSites() {
        val before = Store.sites(this).size
        if (before < 2) {
            toast("站点不足两条，无需合并")
            return
        }
        val (kept, removed) = Store.dedupSites(this)
        siteAdapter.clearHealth()
        refresh()
        bindHome(force = true)
        if (removed == 0) toast("没有发现重复站点")
        else toast(getString(R.string.site_dedup_done, kept, removed))
    }

    /**
     * 站点体检（FN-6）：并发探活每个站点，把结果标在列表行上。
     *
     * 判据只有「连不连得上」——403/404 说明站是活的（只是不认我们的请求），
     * 只有网络层失败（DNS / 连接超时 / TLS）才算挂。把 4xx 判成"不可访问"
     * 会让一堆正常站被误标，那是比不体检更糟的结果。
     */
    private fun healthCheck() {
        val list = Store.sites(this)
        if (list.isEmpty()) {
            toast(getString(R.string.site_empty_action))
            return
        }
        siteAdapter.markChecking(list.map { it.key })
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                list.map { s -> async { s.key to pingSite(s.baseUrl) } }.awaitAll()
            }
            siteAdapter.applyHealth(results.toMap())
            val ok = results.count { it.second }
            toast(getString(R.string.site_health_done, ok, results.size))
        }
    }

    /** 单站探活：有响应就算活（HEAD 不行退 GET） */
    private fun pingSite(baseUrl: String): Boolean {
        if (baseUrl.isBlank()) return false
        return runCatching {
            healthClient.newCall(Request.Builder().url(baseUrl).head().build()).execute().close()
            true
        }.recoverCatching {
            healthClient.newCall(Request.Builder().url(baseUrl).get().build()).execute().close()
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 添加站点 / 播放

    private fun updateDirectButton() {
        val url = binding.inputUrl.text.toString().trim()
        binding.btnPlayDirect.visibility = if (Media.isDirect(url)) View.VISIBLE else View.GONE
    }

    private fun detect() {
        val url = binding.inputUrl.text.toString().trim()
        if (url.isEmpty()) {
            toast(getString(R.string.err_empty_url))
            return
        }
        if (Media.isDirect(url)) {
            playDirect()
            return
        }
        setBusy(true)
        binding.tvHint.visibility = View.VISIBLE
        binding.tvHint.text = getString(R.string.detecting)

        lifecycleScope.launch {
            val r = runCatching { SiteDetector.detect(url) }.getOrNull()
            setBusy(false)
            if (r == null) {
                binding.tvHint.text = "识别失败：网络异常或该站无法访问"
                return@launch
            }
            binding.tvHint.text = r.message
            val site = r.site
            if (r.isVideoSite && site != null) {
                val added = Store.add(this@MainActivity, site)
                refresh()
                if (added) {
                    toast("已适配：${site.name}")
                    binding.inputUrl.setText("")
                    openSite(site)
                } else {
                    toast("该站点已存在，列表已刷新")
                }
            } else {
                pendingPageUrl = r.pageUrl ?: url
                toast(getString(R.string.err_no_video_site))
            }
        }
    }

    private fun sniffFromInput() {
        val url = binding.inputUrl.text.toString().trim().ifBlank { pendingPageUrl }
        if (url.isEmpty()) {
            toast(getString(R.string.err_empty_url))
            return
        }
        startActivity(
            SniffActivity.intent(this, url, hostOf(url), mapOf("User-Agent" to Http.UA))
        )
    }

    /**
     * 打开「网页浏览」（v1.0.37）。
     *
     * 不填网址 ⇒ 打开搜索引擎（= 搜全网的入口）；填了 ⇒ 直接打开那个网址。
     *
     * 走的是同一个 [SniffActivity]，只是带 `browse = true` —— **浏览模式不自动播**，
     * 而且页面上多了「识别并添加 / 手动校准」两个按钮。
     * 与「用网页嗅探打开」的区别只有这一条：那个是"我要播这一页"，这个是"我要逛"。
     */
    private fun browseWeb() {
        val raw = binding.inputUrl.text.toString().trim()
        val target = when {
            // 没填网址 ⇒ 搜索引擎首页。用**用户在上次选定的那个引擎**，
            // 否则会出现"我从首页进全网还是 Bing、从站源页进却是百度"
            raw.isEmpty() -> SearchScope.webHomeUrl(
                SearchEngine.of(Store.searchEngine(this))
            )
            raw.startsWith("http") -> raw
            else -> "http://$raw"
        }
        startActivity(
            SniffActivity.intent(
                this,
                target,
                getString(R.string.web_browse_title),
                mapOf("User-Agent" to Http.UA),
                browse = true
            )
        )
    }

    private fun playDirect() {
        val url = binding.inputUrl.text.toString().trim()
        if (url.isEmpty()) {
            toast(getString(R.string.err_empty_url))
            return
        }
        startActivity(
            PlayerActivity.intent(this, url, hostOf(url), mapOf("User-Agent" to Http.UA))
        )
    }

    private fun openSite(site: SiteConfig) {
        startActivity(SiteActivity.intent(this, site.key))
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnDetect.isEnabled = !busy
        binding.btnSniff.isEnabled = !busy
    }

    private fun hostOf(url: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?: url

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    companion object {
        private const val SP = "videoshell"
        private const val KEY_AUTO_DARK = "setting_dark_auto"
        private const val PAGE_HOME = 0
        private const val PAGE_SITES = 1
        private const val PAGE_MINE = 2
    }
}
