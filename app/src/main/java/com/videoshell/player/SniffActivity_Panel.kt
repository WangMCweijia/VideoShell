package com.videoshell.player

// SniffActivity 的**浮窗面板与 WebView 装配**（拆出来的第一块）。
//
// 前半是面板（收起 / 拖动 / 夹取 / 网页内返回），后半是 WebView 与去广告、
// 轮询、拾取脚本注入。
//
// 为什么合成一块：面板高度和 WebView 尺寸是**互相牵制**的 —— 面板收起时要补一次夹取，
// 否则会挪出屏幕外找不回来（v1.0.31 踩过）。两边分开就会出现「改了 WebView 高度、
// 忘了面板的可挪范围也跟着变」。
//
// ⚠️ 区间起点是 [433] 而不是 [434]：434 行那句 `private fun setupWebView()` 头顶有
// `@SuppressLint("SetJavaScriptEnabled")`（433 行）。注解属于它修饰的函数，
// 漏掉它会把注解留给**下一个**函数 —— lint 抑制挂错对象，编译照样绿。
// ⚠️ `onBackPressed`（428-431 行，override）卡在两段区间中间，**必须留在原类**：
// 扩展函数不能覆盖成员。这正是第一段区间止于 427 的原因。
//
// 拆法与约束同 PlayerActivity_Play.kt（纯搬运 + internal 扩展函数）。

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.videoshell.R
import com.videoshell.data.Store
import com.videoshell.data.net.AdBlock
import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
import com.videoshell.data.net.WebAdBlock
import com.videoshell.data.site.JxParser
import com.videoshell.data.site.SniffSession
import com.videoshell.data.site.WebSiteKit
import com.videoshell.databinding.ActivitySniffBinding
import com.videoshell.ui.CalibrateActivity
import com.videoshell.ui.SiteActivity
import com.videoshell.ui.adapter.CandidateAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONTokener

// ------------------------------------------------------------------ 浮窗：收起 / 拖动

internal fun SniffActivity.togglePanel() {
    panelCollapsed = !panelCollapsed
    renderPanel()
}

/**
 * 收起 = 只留手柄那一行。
 *
 * 收起后**必须补一次夹取**：面板矮了，它的 bottom 变小、可下移的范围变大，
 * 之前拖到贴底的位置会变成"浮在半空" —— 用户会以为拖动坏了。
 */
internal fun SniffActivity.renderPanel() {
    binding.panelBody.visibility = if (panelCollapsed) View.GONE else View.VISIBLE
    binding.tvPanelBrief.visibility = if (panelCollapsed) View.VISIBLE else View.GONE
    binding.panelGrip.visibility = if (panelCollapsed) View.VISIBLE else View.GONE
    binding.btnPanelToggle.setText(
        if (panelCollapsed) R.string.sniff_panel_expand else R.string.sniff_panel_collapse
    )
    refreshPanelBrief()
    binding.bottomPanel.post { clampPanel() }
}

internal fun SniffActivity.refreshPanelBrief() {
    if (!panelCollapsed) return
    binding.tvPanelBrief.text = getString(R.string.sniff_panel_collapsed, candidates.size)
}

/**
 * 拖动手柄：整行都能拖，**没拖动时按一下 = 收起/展开**。
 *
 * 用 `rawX/rawY` 而不是 `x/y`：后者相对当前被按的 View，手指移出手柄后数值就乱了。
 * 判定"这是拖动还是点击"用 8dp 的位移阈值 —— 手指按下去总会抖一两像素，
 * 没有阈值的话每一次"想点一下"都会被当成微小拖动，收起功能就永远触发不了。
 */
@SuppressLint("ClickableViewAccessibility")
internal fun SniffActivity.setupPanelDrag() {
    val panel = binding.bottomPanel
    val slop = SniffActivity.SLOP_DP * resources.displayMetrics.density
    var downX = 0f
    var downY = 0f
    var baseTx = 0f
    var baseTy = 0f
    var moved = false
    binding.panelHandle.setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                baseTx = panel.translationX
                baseTy = panel.translationY
                moved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                    moved = true
                }
                if (moved) {
                    panel.translationX = baseTx + dx
                    panel.translationY = baseTy + dy
                    clampPanel()
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved) togglePanel()
                true
            }
            else -> false
        }
    }
}

/** 把浮窗夹在父容器里：能挪开，但不能挪出屏幕外找不回来 */
internal fun SniffActivity.clampPanel() {
    val panel = binding.bottomPanel
    val parent = panel.parent as? View ?: return
    if (parent.width == 0 || parent.height == 0) return
    panel.translationX = panel.translationX
        .coerceIn(-panel.left.toFloat(), (parent.width - panel.right).toFloat())
    panel.translationY = panel.translationY
        .coerceIn(-panel.top.toFloat(), (parent.height - panel.bottom).toFloat())
}

/**
 * 浏览模式下的「返回」= **网页后退**。
 *
 * 用户从搜索结果点进一个站，想回结果页继续挑下一个 —— 这里若直接关掉整个页面，
 * 他得重新搜一次。退无可退才真正退出（这一点与 [CalibrateActivity] 的处理一致）。
 */
internal fun SniffActivity.backInWeb() {
    if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
}

@SuppressLint("SetJavaScriptEnabled")
internal fun SniffActivity.setupWebView() {
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
    binding.webView.setBackgroundColor(0xFF0E1013.toInt())
    binding.webView.webChromeClient = WebChromeClient()
    binding.webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val u = request?.url?.toString()
            // ★ 去广告（v1.0.52）：命中就**不发出去**（回一个空响应）。
            //   效果不是"少显示一张图"，而是**广告播放器根本没被创建** ——
            //   于是它那条 m3u8 请求压根不会出现，候选清单从源头就干净了
            //   （[SniffRank] 只能事后给广告减分，拦在门口省事得多）。
            //   ⚠️ 媒体地址一律不拦（判据在 [AdBlock.blockedResource] 里）：
            //   漏拦一个广告只是少省一次请求，误拦一个分片就是播放挂掉。
            if (adBlockOn) WebAdBlock.intercept(u)?.let { return it }
            u?.let { offer(it) }
            return null
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean = guardNav(request?.url?.toString().orEmpty(), request)

        /**
         * API 21~23 走的是这个老签名（带手势信息的新签名 24 才有）。
         * 那边拿不到"有没有用户手势" ⇒ 只拦"目标本身就是广告 / 跳 App"，
         * 不拦推断出来的弹窗 —— 老设备上少拦一次弹窗，比多拦一次正常跳转划算。
         */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
            guardNav(url.orEmpty(), null)

        override fun onLoadResource(view: WebView?, url: String?) {
            // 被拦下的资源在 onLoadResource 里**仍会被通知一次** ⇒ 这里要再挡一道，
            // 否则"广告不进候选清单"只做了一半：另一条路又把它捡回来了
            if (adBlockOn && url != null && AdBlock.blockedResource(url)) return
            url?.let { offer(it) }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            pageError = ""
            navBlockNotified = false
            injectHook()
            injectAdBlockCss()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                pageError = "${error?.errorCode}: ${error?.description}"
                updateStatus()
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            if (request?.isForMainFrame == true) {
                pageError = "HTTP ${errorResponse?.statusCode}"
                updateStatus()
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            injectHook()
            binding.webView.alpha = if (webVisible) 1f else 0f
            refreshUrlLine()
            // 会话记忆：把'这个站该带什么 Referer'留下，播放/解析复用（换集、从历史进来都受益）
            SniffSession.remember(pageUrl, pageUrl, Http.UA)
        }
    }
}

internal fun SniffActivity.loadPage() {
    val extra = HashMap<String, String>()
    pageHeaders.forEach { (k, v) ->
        if (k.equals("Referer", true) || k.equals("User-Agent", true)) extra[k] = v
    }
    binding.webView.loadUrl(pageUrl, extra)
}

internal fun SniffActivity.restart() {
    candidates.clear()
    tsHits.clear()
    candidateAdapter.submit(emptyList())
    SniffQueue.clear()
    autoPlayed = false
    firstSeenAt = 0L
    ticks = 0
    probeRounds = 0
    probing = false
    textProbed = false
    loginWall = false
    loginWords = ""
    pageError = ""
    loggedOneShot = false
    navBlockNotified = false
    WebAdBlock.reset()
    binding.tvStatus.text = getString(
        if (browse) R.string.sniffer_browse else R.string.sniffer_running
    )
    binding.rvCandidates.visibility = View.GONE
    binding.pb.visibility = View.VISIBLE
    binding.webView.reload()
    startPolling()
}

internal fun SniffActivity.startPolling() {
    if (polling) return
    polling = true
    handler.removeCallbacks(pollTask)
    handler.postDelayed(pollTask, 800)
}

internal fun SniffActivity.injectHook() {
    runCatching { binding.webView.evaluateJavascript(HOOK_JS, null) }
}

/**
 * 隐藏广告容器的样式要**反复补**：站点自己的脚本会在页面加载完之后再插浮层
 * （那些脚本不在我们的黑名单里，拦不掉），只在 onPageStarted 注入一次会漏掉它们。
 * 注入是幂等的（脚本里 `window.__vsAdCss` 挡了一道），所以挂在轮询上很便宜。
 * v1.0.57 起同时注入 **DOM 清扫**（宽幅外链图幅 / 大浮层，见 AdBlock.sweepJs）。
 */
internal fun SniffActivity.injectAdBlockCss() {
    if (!adBlockOn) return
    WebAdBlock.injectCss(binding.webView)
    WebAdBlock.injectSweep(binding.webView)
}
