package com.videoshell.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.videoshell.R
import com.videoshell.data.Store
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.net.Http
import com.videoshell.data.site.Media
import com.videoshell.data.site.SiteDetector
import com.videoshell.databinding.ActivityMainBinding
import com.videoshell.player.PlayerActivity
import com.videoshell.player.SniffActivity
import com.videoshell.ui.adapter.SiteListAdapter
import com.videoshell.ui.adapter.VideoAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.launch

/** 主界面：底部三 Tab —— 首页（添加 + 默认站源内容）/ 站源（管理）/ 我的（设置） */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingPageUrl: String = ""

    private val siteAdapter = SiteListAdapter(
        onClick = { openSite(it) },
        onDelete = { confirmDelete(it) },
        onLongClick = { renameSite(it) },
        onSetDefault = { setDefaultSite(it) }
    )
    private val homeAdapter = VideoAdapter { openDetail(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "v" + versionName()
        binding.tvVersionMine.text = "v" + versionName()

        // ---- 首页 ----
        binding.rvHome.layoutManager = GridLayoutManager(this, 3)
        binding.rvHome.adapter = homeAdapter
        binding.tvHomeState.setOnClickListener { loadHomeContent() }
        binding.tvHomeChange.setOnClickListener { binding.bottomNav.selectedItemId = R.id.nav_sites }

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

        // ---- 站源 ----
        binding.rvSites.layoutManager = LinearLayoutManager(this)
        binding.rvSites.adapter = siteAdapter

        // ---- 我的 ----
        binding.rowDefaultSite.setOnClickListener { pickDefaultSite() }
        binding.swLaunchDirect.isChecked = getSharedPreferences(SP, MODE_PRIVATE)
            .getBoolean(KEY_LAUNCH_DIRECT, false)
        binding.swLaunchDirect.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(SP, MODE_PRIVATE).edit()
                .putBoolean(KEY_LAUNCH_DIRECT, checked).apply()
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

        // 启动直达默认站源（「我的」里可开关）
        val direct = getSharedPreferences(SP, MODE_PRIVATE).getBoolean(KEY_LAUNCH_DIRECT, false)
        if (direct && savedInstanceState == null) {
            Store.defaultSite(this)?.let { openSite(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        loadHomeContent()
    }

    // ------------------------------------------------------------------ 页面切换

    private fun showPage(page: Int) {
        binding.pageHome.visibility = if (page == PAGE_HOME) View.VISIBLE else View.GONE
        binding.pageSites.visibility = if (page == PAGE_SITES) View.VISIBLE else View.GONE
        binding.pageMine.visibility = if (page == PAGE_MINE) View.VISIBLE else View.GONE
        binding.tvBarTitle.setText(
            when (page) {
                PAGE_SITES -> R.string.nav_sites
                PAGE_MINE -> R.string.nav_mine
                else -> R.string.app_name
            }
        )
        if (page == PAGE_SITES) refresh()
    }

    // ------------------------------------------------------------------ 首页：默认站源内容

    private fun loadHomeContent() {
        val site = Store.defaultSite(this)
        if (site == null) {
            binding.tvHomeSite.visibility = View.GONE
            binding.tvHomeChange.visibility = View.GONE
            homeAdapter.clear()
            showHomeState(getString(R.string.home_empty_default), visible = true)
            return
        }
        binding.tvHomeSite.visibility = View.VISIBLE
        binding.tvHomeSite.text = getString(R.string.home_default_fmt, site.name.ifBlank { site.baseUrl })
        binding.tvHomeChange.visibility = View.VISIBLE
        showHomeState(null, visible = false)
        lifecycleScope.launch {
            val res = runCatching {
                com.videoshell.data.site.AdapterFactory.create(site).browse("", 1)
            }
            val items = res.getOrElse { emptyList() }.take(12)
            if (items.isEmpty()) {
                homeAdapter.clear()
                showHomeState(getString(R.string.home_load_fail), visible = true)
            } else {
                showHomeState(null, visible = false)
                homeAdapter.submit(items, false)
            }
        }
    }

    private fun showHomeState(msg: String?, visible: Boolean) {
        binding.tvHomeState.text = msg.orEmpty()
        binding.tvHomeState.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun openDetail(item: com.videoshell.data.model.VideoItem) {
        val site = Store.defaultSite(this) ?: return
        startActivity(DetailActivity.intent(this, site.key, item))
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
        toast(getString(R.string.set_default_done))
    }

    private fun pickDefaultSite() {
        val list = Store.sites(this)
        if (list.isEmpty()) {
            toast(getString(R.string.home_empty_default))
            return
        }
        val names = list.map { it.name.ifBlank { it.baseUrl } }.toTypedArray()
        val current = Store.defaultKey(this)
        val checked = list.indexOfFirst { it.key == current }.takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.default_site))
            .setSingleChoiceItems(names, checked) { dialog, which ->
                Store.setDefault(this, list[which].key)
                refresh()
                binding.tvDefaultSite.text = names[which]
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        private const val KEY_LAUNCH_DIRECT = "launch_direct"
        private const val PAGE_HOME = 0
        private const val PAGE_SITES = 1
        private const val PAGE_MINE = 2
    }
}
