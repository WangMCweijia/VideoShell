package com.videoshell.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.videoshell.data.Store
import com.videoshell.data.net.Http
import com.videoshell.data.net.WebAdBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 预渲染取 DOM（v1.0.25，适配优化方案第 6 条）。
 *
 * 有一类站**首屏 HTML 里没有卡片**：列表是 JS 拼出来的（Vue/React 挂载、
 * 模板字符串注入、或数据藏在加密接口里由脚本解密后插入）。此时再强的解析规则
 * 也无处施展 —— 源码里根本没有那些节点。
 *
 * 做法：把一个 1×1、透明的 WebView 挂到当前 Activity 上，真跑一遍页面，
 * 等 `onPageFinished` 后再给 JS 一点渲染时间，然后取 `documentElement.outerHTML`，
 * 把结果交回 HTML 适配器解析（[com.videoshell.data.site.SiteAdapter.parseListFromHtml]）。
 *
 * 约束与取舍：
 * - 必须在主线程创建 WebView，因此调用方在 UI 层（SiteBrowser / DetailActivity）；
 * - **只在首屏什么都解析不出来时兜底一次**，不做常规路径（WebView 比 OkHttp 贵得多）；
 * - 超时 12s 主动放弃并回收，绝不让用户对着转圈等。
 */
object WebRender {

    private const val RENDER_SETTLE_MS = 1_500L
    private const val TIMEOUT_MS = 12_000L

    /** 取渲染后的 HTML；失败/超时返回 null */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun html(act: AppCompatActivity, url: String): String? = withContext(Dispatchers.Main) {
        if (url.isBlank()) return@withContext null
        suspendCancellableCoroutine { cont ->
            val view = WebView(act)
            val root = act.findViewById<ViewGroup>(android.R.id.content)
            var finished = false
            var viewAdded = false
            val handler = Handler(Looper.getMainLooper())

            fun cleanup() {
                handler.removeCallbacksAndMessages(null)
                runCatching {
                    view.stopLoading()
                    view.webViewClient = WebViewClient()
                    if (viewAdded) root?.removeView(view)
                    view.destroy()
                }
            }

            fun finish(result: String?) {
                if (finished) return
                finished = true
                cleanup()
                if (cont.isActive) cont.resume(result)
            }

            runCatching {
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = false     // 只要 DOM，不加载图片，省流量也快
                    blockNetworkImage = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = Http.UA
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                view.alpha = 0f
                view.layoutParams = ViewGroup.LayoutParams(1, 1)
                root?.addView(view)
                viewAdded = true
                view.webViewClient = object : WebViewClient() {
                    /**
                     * 广告 / 统计资源不回内容（v1.0.52）。
                     *
                     * 这个隐藏 WebView 存在的意义只是"把 DOM 跑出来"，而广告脚本恰恰是
                     * 往 DOM 里塞节点的东西 —— 少塞一批，交回解析器的 DOM 就更接近正文。
                     * 判据与嗅探页、校准页**共用同一份**（[com.videoshell.data.net.AdBlock]），
                     * 三处"什么算广告"永远一致，不会出现"这个页面拦、那个页面不拦"。
                     */
                    override fun shouldInterceptRequest(
                        v: WebView?,
                        r: WebResourceRequest?
                    ): WebResourceResponse? =
                        if (Store.adBlock(act)) WebAdBlock.intercept(r?.url?.toString()) else null

                    override fun onPageFinished(v: WebView?, u: String?) {
                        handler.postDelayed({
                            runCatching {
                                v?.evaluateJavascript(DOM_JS) { value ->
                                    finish(jsonToString(value))
                                }
                            }.onFailure { finish(null) }
                        }, RENDER_SETTLE_MS)
                    }
                }
                view.loadUrl(url, mapOf("User-Agent" to Http.UA))
                handler.postDelayed({ finish(null) }, TIMEOUT_MS)
            }.onFailure { finish(null) }

            cont.invokeOnCancellation { finish(null) }
        }
    }

    private val DOM_JS =
        "(function(){try{return document.documentElement?document.documentElement.outerHTML:''}catch(e){return ''}})()"

    private fun jsonToString(v: String?): String? {
        if (v.isNullOrBlank() || v == "null") return null
        return runCatching { (JSONTokener(v).nextValue() as? String)?.takeIf { it.length > 200 } }
            .getOrNull()
    }
}
