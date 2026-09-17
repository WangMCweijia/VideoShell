package com.videoshell.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.videoshell.R
import com.videoshell.data.net.Http
import com.videoshell.databinding.ActivitySniffBinding
import com.videoshell.ui.adapter.CandidateAdapter
import com.videoshell.util.toast
import org.json.JSONArray
import org.json.JSONTokener

/**
 * 网页嗅探：用 WebView 打开播放页，拦截 / 钩住页面发出的媒体请求（m3u8、mp4…），
 * 拿到真实播放地址后交给内置播放器。默认网页可见，方便手动点一下页面上的播放按钮触发请求。
 */
class SniffActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "page_url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADERS = "headers"

        fun intent(context: Context, pageUrl: String, title: String, headers: Map<String, String>): Intent =
            Intent(context, SniffActivity::class.java).apply {
                putExtra(EXTRA_URL, pageUrl)
                putExtra(EXTRA_TITLE, title)
                putExtra(
                    EXTRA_HEADERS,
                    Gson().toJson(headers, object : TypeToken<Map<String, String>>() {}.type)
                )
            }
    }

    private lateinit var binding: ActivitySniffBinding
    private lateinit var pageUrl: String
    private var title: String = ""
    private var pageHeaders: Map<String, String> = emptyMap()

    private val candidates = LinkedHashMap<String, SniffCandidate>()
    private val candidateAdapter = CandidateAdapter { c -> startPlayer(c.url) }

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var autoPlayed = false
    private var firstSeenAt = 0L
    private var webVisible = true
    private var ticks = 0

    /** 主文档加载失败的原因；有值时状态栏直接显示，不再让用户对着空白页猜 */
    private var pageError = ""

    private val autoPlayTask = Runnable {
        if (autoPlayed) return@Runnable
        val best = bestCandidate() ?: return@Runnable
        if (best.type == "HLS" || best.type == "DASH") {
            autoPlayed = true
            startPlayer(best.url)
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) return
            ticks++
            if (ticks > 150) {
                polling = false
                updateStatus()
                return
            }
            collectJs()
            if (!autoPlayed && firstSeenAt > 0 && System.currentTimeMillis() - firstSeenAt > 3500) {
                handler.post(autoPlayTask)
            }
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySniffBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        pageHeaders = runCatching {
            val t = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson<Map<String, String>>(intent.getStringExtra(EXTRA_HEADERS), t)
        }.getOrDefault(emptyMap())

        if (pageUrl.isBlank()) {
            toast("播放页地址为空")
            finish()
            return
        }

        binding.tvTitle.text = title.ifBlank { "嗅探中" }
        binding.btnBack.setOnClickListener { finish() }

        binding.rvCandidates.layoutManager = LinearLayoutManager(this)
        binding.rvCandidates.adapter = candidateAdapter

        binding.btnToggleWeb.setOnClickListener {
            webVisible = !webVisible
            binding.webView.alpha = if (webVisible) 1f else 0f
            binding.btnToggleWeb.text =
                getString(if (webVisible) R.string.sniffer_hide_web else R.string.sniffer_show_web)
        }
        binding.btnRetry.setOnClickListener { restart() }

        setupWebView()
        loadPage()
        startPolling()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
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
                request?.url?.toString()?.let { offer(it) }
                return null
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                url?.let { offer(it) }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageError = ""
                injectHook()
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
            }
        }
    }

    private fun loadPage() {
        val extra = HashMap<String, String>()
        pageHeaders.forEach { (k, v) ->
            if (k.equals("Referer", true) || k.equals("User-Agent", true)) extra[k] = v
        }
        binding.webView.loadUrl(pageUrl, extra)
    }

    private fun restart() {
        candidates.clear()
        candidateAdapter.submit(emptyList())
        autoPlayed = false
        firstSeenAt = 0L
        ticks = 0
        pageError = ""
        binding.tvStatus.text = getString(R.string.sniffer_running)
        binding.rvCandidates.visibility = View.GONE
        binding.pb.visibility = View.VISIBLE
        binding.webView.reload()
        startPolling()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.removeCallbacks(pollTask)
        handler.postDelayed(pollTask, 800)
    }

    private fun injectHook() {
        runCatching { binding.webView.evaluateJavascript(HOOK_JS, null) }
    }

    private fun collectJs() {
        runCatching {
            binding.webView.evaluateJavascript(COLLECT_JS) { value ->
                for (item in parseJs(value)) {
                    val p = item.lastIndexOf('|')
                    if (p <= 0) offer(item) else offer(item.substring(0, p))
                }
            }
        }
    }

    private fun parseJs(value: String?): List<String> {
        if (value.isNullOrBlank() || value == "null" || value == "\"\"") return emptyList()
        return runCatching {
            when (val o = JSONTokener(value).nextValue()) {
                is JSONArray -> (0 until o.length()).map { o.optString(it) }
                is String -> {
                    if (o.isBlank()) emptyList()
                    else (JSONTokener(o).nextValue() as? JSONArray)?.let { a ->
                        (0 until a.length()).map { a.optString(it) }
                    } ?: emptyList()
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** 收到一个网络请求地址，判断是不是媒体 */
    private fun offer(raw: String) {
        if (raw.isBlank() || raw.startsWith("blob:") || raw.startsWith("data:")) return
        if (!raw.startsWith("http")) return
        val type = classify(raw) ?: return
        runOnUiThread {
            val c = candidates[raw]
            if (c == null) {
                candidates[raw] = SniffCandidate(raw, type, 1)
                if (firstSeenAt == 0L) firstSeenAt = System.currentTimeMillis()
            } else {
                c.hits++
            }
            updateStatus()
        }
    }

    private fun classify(url: String): String? {
        val u = url.lowercase()
        return when {
            u.contains("m3u8") -> "HLS"
            u.contains(".mpd") -> "DASH"
            u.contains(".mp4") -> "MP4"
            u.contains(".flv") -> "FLV"
            u.contains(".ts") -> "TS"
            else -> null
        }
    }

    private fun score(c: SniffCandidate): Int = when (c.type) {
        "HLS" -> 100
        "DASH" -> 70
        "MP4" -> 60
        "FLV" -> 50
        else -> 10
    }

    private fun bestCandidate(): SniffCandidate? =
        candidates.values.sortedWith(
            compareByDescending<SniffCandidate> { score(it) }.thenByDescending { it.hits }
        ).firstOrNull()

    private fun updateStatus() {
        val n = candidates.size
        binding.tvStatus.text = when {
            n > 0 -> getString(R.string.sniffer_found, n)
            pageError.isNotBlank() -> getString(R.string.sniffer_page_error, pageError)
            ticks > 20 -> getString(R.string.sniffer_timeout)
            else -> getString(R.string.sniffer_none)
        }
        binding.pb.visibility = if (n == 0) View.VISIBLE else View.GONE
        val sorted = candidates.values.sortedWith(
            compareByDescending<SniffCandidate> { score(it) }.thenByDescending { it.hits }
        )
        candidateAdapter.submit(sorted)
        binding.rvCandidates.visibility = if (n == 0) View.GONE else View.VISIBLE
    }

    private fun startPlayer(url: String) {
        if (isFinishing) return
        val h = HashMap<String, String>()
        h.putAll(pageHeaders)
        if (h.keys.none { it.equals("Referer", true) } && pageUrl.isNotBlank()) h["Referer"] = pageUrl
        startActivity(PlayerActivity.intent(this, url, title.ifBlank { "播放" }, h))
        finish()
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        runCatching {
            binding.webView.stopLoading()
            binding.webView.loadUrl("about:blank")
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    private val HOOK_JS = """
        (function(){
          if (window.__vsHooked) return; window.__vsHooked = true;
          window.__vsFound = window.__vsFound || [];
          function push(u){
            try {
              if (!u) return;
              window.__vsFound.push(String(u) + '|auto');
              if (window.__vsFound.length > 60) window.__vsFound.splice(0, 20);
            } catch(e){}
          }
          try {
            var _open = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u){ push(u); return _open.apply(this, arguments); };
          } catch(e){}
          try {
            var _fetch = window.fetch;
            if (_fetch) {
              window.fetch = function(i, o){ push((typeof i === 'string') ? i : (i && i.url)); return _fetch.apply(this, arguments); };
            }
          } catch(e){}
          try {
            document.addEventListener('play', function(e){
              var v = e.target; if (v && v.tagName === 'VIDEO' && v.currentSrc) push(v.currentSrc);
            }, true);
            document.addEventListener('loadedmetadata', function(e){
              var v = e.target; if (v && v.tagName === 'VIDEO' && v.currentSrc) push(v.currentSrc);
            }, true);
          } catch(e){}
          setInterval(function(){
            try {
              var vs = document.querySelectorAll('video, source');
              for (var i = 0; i < vs.length; i++){
                var u = vs[i].currentSrc || vs[i].src;
                if (u) push(u);
              }
            } catch(e){}
          }, 1500);
        })();
    """.trimIndent()

    private val COLLECT_JS =
        "(function(){var a=window.__vsFound||[];window.__vsFound=[];return JSON.stringify(a);})()"
}
