package com.videoshell.ui

// CalibrateActivity 的 **WebView 装配与去广告**（拆出来的第二块）：建 WebView、
// 隐藏样式注入、拾取脚本注入、顶层跳转守卫。
//
// 为什么这一族放一起：它们全部挂在同一个 WebView 上，而且**注入顺序有语义** ——
// 去广告的隐藏样式要先于拾取脚本，否则站点弹层会盖住用户要点的元素，
// 表现为「点了没反应」（v1.0.52 踩过）。
//
// ⚠️ `Bridge`（inner class，501-506）没有跟着搬：`inner` 的语义（要绑外部实例）
// 一旦落到顶层文件就没了，而它只做「把 JS 的回调转回 UI 线程」，留在这里最省事。
// 拆法与约束同上（纯搬运 + internal 扩展函数）。

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.videoshell.R
import com.videoshell.data.Store
import com.videoshell.data.model.Episode
import com.videoshell.data.model.MediaSource
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.net.AdBlock
import com.videoshell.data.net.Http
import com.videoshell.data.net.WebAdBlock
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.HtmlTemplates
import com.videoshell.data.site.RecipeStore
import com.videoshell.data.site.SiteCalib
import com.videoshell.databinding.ActivityCalibrateBinding
import com.videoshell.player.PlayerActivity
import com.videoshell.util.toast
import kotlinx.coroutines.launch
import org.json.JSONObject

// ------------------------------------------------------------------ WebView

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
internal fun CalibrateActivity.setupWebView() {
    binding.webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadsImagesAutomatically = true
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        useWideViewPort = true
        loadWithOverviewMode = true
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = Http.UA
    }
    binding.webView.addJavascriptInterface(Bridge(), "VS")
    binding.webView.webChromeClient = WebChromeClient()
    binding.webView.webViewClient = object : WebViewClient() {
        /**
         * 子资源拦截（v1.0.52）：广告脚本 / 统计 / 广告 iframe 一律回空响应。
         *
         * 校准本身**不看渲染后的 DOM**（分类形状判据走 `Http.getOrNull` 拿到的服务端
         * 原始 HTML），所以这里拦广告不会让"学到的规则"变成"拦过广告的 DOM 的规则" ——
         * 它只影响用户眼前那一片：少几个浮层，也就少几次点错。
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? =
            if (adBlockOn) WebAdBlock.intercept(request?.url?.toString()) else null

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            pageUrl = url.orEmpty()
            navBlockNotified = false
            injectAdBlockCss()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            pageUrl = url.orEmpty()
            injectPicker()
            injectAdBlockCss()
        }

        /**
         * 顶层跳转守卫。
         *
         * ⚠️ **必须在 `pageUrl = ...` 之前**：被拦下的广告页一旦写进 [pageUrl]，
         * 第 4 步学到的"结果页地址"就成了广告页的地址 —— 而用户看到的还是原来那一页。
         * 这是本版要修的那种错：所有报错都是绿的，只有学出来的规则是错的。
         */
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val to = request?.url?.toString().orEmpty()
            if (guardNav(to, request)) return true
            pageUrl = to
            return false
        }

        /** API 21~23 走的老签名（新签名 24 才有，那边拿不到手势信息） */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val to = url.orEmpty()
            if (guardNav(to, null)) return true
            pageUrl = to
            return false
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                state(getString(R.string.calib_page_failed, "${error?.errorCode}: ${error?.description}"))
            }
        }
    }
}

// ------------------------------------------------------------------ 去广告（v1.0.52）

internal fun CalibrateActivity.renderAdBlockChip() {
    binding.btnAdBlock.setText(if (adBlockOn) R.string.adblock_on else R.string.adblock_off)
}

/**
 * 开关去广告：切换后**重载当前页**。
 *
 * 已经拦下的资源不会因为我们改了主意而复活；只改判据不重载，用户会看到
 * "关掉了但广告还在"这种无法解释的中间态。重载不重置步骤 —— 用户是在同一页上做实验。
 */
internal fun CalibrateActivity.toggleAdBlock() {
    adBlockOn = !adBlockOn
    WebAdBlock.setOn(this, adBlockOn)
    renderAdBlockChip()
    WebAdBlock.reset()
    navBlockNotified = false
    toast(getString(if (adBlockOn) R.string.adblock_on_toast else R.string.adblock_off_toast))
    binding.webView.reload()
}

/**
 * 顶层跳转守卫：拦下弹窗 / 诱导跳 App，**并且说出来**。
 *
 * 那条"跨站 + 无手势 = 弹窗"是推断，一定会偶尔错杀站点自己的 JS 跳转（域名轮换的站
 * 就靠它）。所以拦下时不能静默 —— 一句提示 + 顶栏那个开关，就是用户自救的路。
 */
internal fun CalibrateActivity.guardNav(to: String, request: WebResourceRequest?): Boolean {
    if (!adBlockOn || to.isBlank()) return false
    val from = binding.webView.url.orEmpty().ifBlank { pageUrl }
    if (!WebAdBlock.navBlocked(from, to, request)) return false
    if (!navBlockNotified) {
        navBlockNotified = true
        toast(getString(R.string.adblock_nav_blocked, Store.hostOf(to)))
    }
    return true
}

/** 反复注入隐藏样式：站点自己插的浮层在加载完之后才出现（幂等，见 AdBlock.hideJs） */
internal fun CalibrateActivity.injectAdBlockCss() {
    if (!adBlockOn) return
    WebAdBlock.injectCss(binding.webView)
}

internal fun CalibrateActivity.injectPicker() {
    runCatching {
        binding.webView.evaluateJavascript(PICK_JS) { r ->
            // 自检：脚本到底挂上了没有。没挂上，用户点任何东西都不会有反馈，
            // 他只会看到"卡住了" —— 这种情况必须明确说出来，而不是让他对着死界面点。
            val plain = r?.trim()?.removeSurrounding("\"")
            if (plain != "ok") {
                state(getString(R.string.calib_inject_failed, plain ?: "null"))
            }
        }
    }
}
