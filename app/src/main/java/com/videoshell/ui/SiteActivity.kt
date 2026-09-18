package com.videoshell.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.videoshell.data.model.VideoItem
import com.videoshell.databinding.ActivitySiteBinding
import com.videoshell.util.toast

/**
 * 二级站源页：只是 [SiteBrowser] 的一个宿主（首页也是同一个浏览器）。
 *
 * v1.0.25 之前这里有一整套分类/搜索/自检逻辑，首页另写一套"默认站源 12 条"——
 * 于是首页没有分类与搜索，用户必须点进二级页才能用。现在逻辑只有一份。
 */
class SiteActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_KEY = "site_key"

        fun intent(context: Context, siteKey: String): Intent =
            Intent(context, SiteActivity::class.java).putExtra(EXTRA_KEY, siteKey)
    }

    private lateinit var binding: ActivitySiteBinding
    private lateinit var browser: SiteBrowser
    private var siteKey: String = ""

    private val calibLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        browser.onCalibReturned()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySiteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        siteKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        browser = SiteBrowser(
            act = this,
            b = binding.browser,
            launchCalib = { key -> calibLauncher.launch(CalibrateActivity.intent(this, key)) },
            onOpenDetail = { key, item -> openDetail(key, item) }
        )
        browser.setup(showBack = true) { finish() }

        if (siteKey.isBlank()) {
            toast("站点不存在")
            finish()
            return
        }
        browser.bind(siteKey)
    }

    private fun openDetail(key: String, item: VideoItem) {
        startActivity(DetailActivity.intent(this, key, item))
    }
}
