package com.videoshell.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.videoshell.R
import com.videoshell.data.Store
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.data.site.Media
import com.videoshell.data.site.SiteDetector
import com.videoshell.databinding.ActivityMainBinding
import com.videoshell.player.PlayerActivity
import com.videoshell.player.SniffActivity
import com.videoshell.ui.adapter.SiteListAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.launch

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 版本号只在「我的」页展示一份 —— 顶部那行的版本号随整行一起去掉了（v1.0.26）
        binding.tvVersionMine.text = "v" + versionName()

        // ---- 首页：默认站源浏览器 ----
        browserHome = SiteBrowser(
            act = this,
            b = binding.browserHome,
            launchCalib = { key -> calibLauncher.launch(CalibrateActivity.intent(this, key)) },
            onOpenDetail = { key, item -> openDetail(key, item) }
        )
        browserHome.setup(showBack = false) { }

        // ---- 站源：添加入口 ----
        binding.btnDetect.setOnClickListener { detect() }
        binding.btnSniff.setOnClickListener { sniffFromInput() }
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
        // 自动暗色：开 = 跟随系统；关 = 固定亮色
        binding.swDark.isChecked = sp.getBoolean(KEY_AUTO_DARK, true)
        binding.swDark.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean(KEY_AUTO_DARK, checked).apply()
            applyNightMode(checked)
        }

        // ---- 底部导航 ----
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_sites -> showPage(PAGE_SITES)
                R.id.nav_mine -> showPage(PAGE_MINE)
                else -> showPage(PAGE_HOME)
            }
            true
        }
        showPage(PAGE_HOME)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        bindHome()
    }

    // ------------------------------------------------------------------ 暗色

    private fun applyNightMode(auto: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (auto) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    // ------------------------------------------------------------------ 页面切换

    private fun showPage(page: Int) {
        binding.pageHome.visibility = if (page == PAGE_HOME) View.VISIBLE else View.GONE
        binding.pageSites.visibility = if (page == PAGE_SITES) View.VISIBLE else View.GONE
        binding.pageMine.visibility = if (page == PAGE_MINE) View.VISIBLE else View.GONE
        if (page == PAGE_SITES) refresh()
        if (page == PAGE_HOME) bindHome()
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
