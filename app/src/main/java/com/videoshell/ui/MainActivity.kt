package com.videoshell.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
import com.videoshell.util.toast
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingPageUrl: String = ""

    private val siteAdapter = SiteListAdapter(
        onClick = { openSite(it) },
        onDelete = { confirmDelete(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "v" + versionName()

        binding.rvSites.layoutManager = LinearLayoutManager(this)
        binding.rvSites.adapter = siteAdapter

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

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = Store.sites(this)
        siteAdapter.submit(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateDirectButton() {
        val url = binding.inputUrl.text.toString().trim()
        binding.btnPlayDirect.visibility = if (Media.isDirect(url)) View.VISIBLE else View.GONE
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnDetect.isEnabled = !busy
        binding.btnSniff.isEnabled = !busy
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

    private fun hostOf(url: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?: url

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
