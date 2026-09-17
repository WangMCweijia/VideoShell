package com.videoshell.player

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.videoshell.R
import com.videoshell.data.net.Http
import com.videoshell.databinding.ActivitySniffBinding
import com.videoshell.ui.adapter.CandidateAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONTokener

/**
 * 网页嗅探：用 WebView 打开播放页，拦截 / 钩住页面发出的媒体请求（m3u8、mp4…），
 * 拿到真实播放地址后交给内置播放器。默认网页可见，方便手动点一下页面上的播放按钮触发请求。
 *
 * 关于「怎么知道该播哪一个」：一个播放页跑起来往往会产生**多个**媒体请求
 * （正片 + 预roll 广告 + 埋点 + 预加载的其它线路）。旧实现只按文件类型打分，
 * 广告 m3u8 与正片 m3u8 分数相同，平局看命中次数 —— 于是**广告经常赢**，
 * 而且挑完就 `finish()` 跳走，候选清单没了，播错只能从头再来。
 *
 * 现在：
 *  - 排序交给 [SniffRank]（URL 层剔除广告/埋点 + **内容层真实探测 playlist**）
 *  - 只有"首选明确是正片"或"只有一个候选"才自动播；判不出来就停在列表让用户点
 *  - 候选清单通过 [SniffQueue] 交给播放器，**播不出来能直接换下一个源**
 *  - 本页不再 `finish()` 自己 —— 从播放器返回即可重新挑
 */
class SniffActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "page_url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADERS = "headers"

        /** 首个候选出现后先等一会儿再动手：让页面把该发的请求都发出来，免得"先到的广告"被当成唯一选项 */
        private const val SETTLE_MS = 2_500L

        /** 最多真正探测几个候选的 playlist（每次探测一个网络请求，不宜贪多） */
        private const val MAX_PROBE = 4

        /** 最多重新探测两轮（页面可能陆续吐出更多候选） */
        private const val MAX_PROBE_ROUNDS = 2

        private const val MAX_TICKS = 240

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

    /** 播放页地址里的视频 id —— 候选地址含它时是很强的正面信号 */
    private var videoId: String = ""

    private val candidates = LinkedHashMap<String, SniffCandidate>()

    /** 每个目录下观测到的 .ts 分片请求数：正片目录会被打几百次，广告目录只有十几次 */
    private val tsHits = HashMap<String, Int>()

    private val candidateAdapter = CandidateAdapter { c -> startPlayer(c) }

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var autoPlayed = false
    private var firstSeenAt = 0L
    private var webVisible = true
    private var ticks = 0

    /** 主文档加载失败的原因；有值时状态栏直接显示，不再让用户对着空白页猜 */
    private var pageError = ""

    /** 页面要求登录（这类站根本不会下发播放地址，嗅探必然扑空） */
    private var loginWall = false
    private var loginWords = ""

    private var probeRounds = 0
    private var probing = false
    private var textProbed = false

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) return
            ticks++
            if (ticks > MAX_TICKS) {
                polling = false
                updateStatus()
                return
            }
            collectJs()
            maybeProbeAndAutoPlay()
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
        videoId = Regex("(\\d{4,})").find(pageUrl.substringBefore('?'))?.value.orEmpty()
        pageHeaders = runCatching {
            val t = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson<Map<String, String>>(intent.getStringExtra(EXTRA_HEADERS), t)
        }.getOrDefault(emptyMap())

        if (pageUrl.isBlank()) {
            toast("播放页地址为空")
            finish()
            return
        }

        SniffQueue.clear()

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

        // 状态栏点一下就把候选报告复制走 —— 排查"到底抓到了什么"时比截图有用
        binding.tvStatus.setOnClickListener { copyReport() }

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
        val type = SniffRank.classify(raw) ?: return
        runOnUiThread {
            if (type == "TS") {
                // 分片本身不是播放入口（真点了也只会播 2 秒），但它证明"这个目录确实在被播放"，
                // 而这个证据正好可以用来给同目录下的 m3u8 加分 —— 比凭关键词猜可靠得多。
                val d = SniffRank.dirOf(raw)
                if (d.isNotBlank()) tsHits[d] = (tsHits[d] ?: 0) + 1
            } else {
                val c = candidates[raw]
                if (c == null) {
                    candidates[raw] = SniffCandidate(
                        raw, type, 1, suspect = SniffRank.isSuspect(raw)
                    )
                    if (firstSeenAt == 0L) firstSeenAt = System.currentTimeMillis()
                } else {
                    c.hits++
                }
                updateStatus()
            }
        }
    }

    // ------------------------------------------------------------------ 排序 / 探测 / 自动播放

    private fun ranked(): List<SniffCandidate> =
        SniffRank.rank(candidates.values, videoId, tsHits)

    private fun maybeProbeAndAutoPlay() {
        if (autoPlayed) return
        if (candidates.isEmpty()) {
            maybeDetectLoginWall()
            return
        }
        if (firstSeenAt == 0L) return
        if (System.currentTimeMillis() - firstSeenAt < SETTLE_MS) return
        if (probing || probeRounds >= MAX_PROBE_ROUNDS) return
        probing = true
        probeRounds++
        lifecycleScope.launch {
            runCatching { probeTopCandidates() }
            probing = false
            updateStatus()
            tryAutoPlay()
        }
    }

    /**
     * 真的把候选的 playlist 拉下来看它是什么 —— 这是整轮排序里唯一**不靠猜**的一步。
     * 正片几百上千个分片（几十分钟），广告十几个（几十秒），`SniffRank.verdict()` 就是据此刻的。
     */
    private suspend fun probeTopCandidates() {
        val referer = pageHeaders.entries
            .firstOrNull { it.key.equals("Referer", true) }?.value ?: pageUrl
        var n = 0
        for (c in ranked()) {
            if (n >= MAX_PROBE) break
            if (c.type != "HLS" && c.type != "DASH") continue
            if (c.suspect) continue                  // 已知广告嫌疑，不值得再花一个请求
            n++
            val text = Http.getPlaylistOnce(c.url, referer) ?: continue
            c.content = SniffRank.verdict(text)
            c.note = SniffRank.describe(text)
        }
    }

    private fun tryAutoPlay() {
        if (autoPlayed) return
        val pick = SniffRank.autoPick(ranked(), probed = probeRounds > 0) ?: return
        autoPlayed = true
        startPlayer(pick)
    }

    private fun updateStatus() {
        val n = candidates.size
        binding.tvStatus.text = when {
            n == 0 && loginWall -> getString(R.string.sniffer_login_wall, loginWords)
            n == 0 && pageError.isNotBlank() -> getString(R.string.sniffer_page_error, pageError)
            n == 0 && ticks > 25 -> getString(R.string.sniffer_timeout)
            n == 0 -> getString(R.string.sniffer_none)
            autoPlayed -> getString(
                R.string.sniffer_playing, SniffQueue.index + 1, n
            )
            probeRounds > 0 -> getString(R.string.sniffer_ambiguous, n)
            else -> getString(R.string.sniffer_scanning, n)
        }
        binding.pb.visibility = if (n == 0) View.VISIBLE else View.GONE
        candidateAdapter.submit(ranked())
        binding.rvCandidates.visibility = if (n == 0) View.GONE else View.VISIBLE
    }

    private val LOGIN_WORDS = listOf(
        "登录后即可观看", "登录后播放", "请先登录", "请登录", "立即登录",
        "开通会员", "购买后", "会员专享"
    )

    /** 长时间一个候选都没有：看看页面是不是在要求登录 —— 这类站嗅探必然扑空，得如实告诉用户 */
    private fun maybeDetectLoginWall() {
        if (textProbed || ticks < 8) return
        textProbed = true
        runCatching {
            binding.webView.evaluateJavascript(TEXT_JS) { v ->
                val t = jsonString(v)
                val hit = LOGIN_WORDS.filter { t.contains(it) }
                if (hit.isNotEmpty()) {
                    loginWall = true
                    loginWords = hit.first()
                    updateStatus()
                }
            }
        }
    }

    private fun jsonString(v: String?): String {
        if (v.isNullOrBlank() || v == "null") return ""
        return runCatching { (JSONTokener(v).nextValue() as? String).orEmpty() }.getOrDefault("")
    }

    private fun copyReport() {
        val sb = StringBuilder()
        sb.appendLine("===== 嗅探报告 =====")
        sb.appendLine("版本：v${appVersion()}")
        sb.appendLine("标题：$title")
        sb.appendLine("播放页：$pageUrl")
        sb.appendLine("视频 id：${videoId.ifBlank { "(未识别)" }}")
        sb.appendLine("页面错误：${pageError.ifBlank { "无" }}")
        sb.appendLine("登录墙：${if (loginWall) loginWords else "未检测到"}")
        sb.appendLine("候选 ${candidates.size} 个（含排序依据）：")
        ranked().forEachIndexed { i, c ->
            sb.appendLine("  [${i + 1}] ${c.display()}   分数=${c.score}")
            sb.appendLine("      ${c.url}")
        }
        if (candidates.isEmpty()) sb.appendLine("  （无）")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("嗅探报告", sb.toString()))
        toast(getString(R.string.sniffer_copied))
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun startPlayer(c: SniffCandidate) {
        if (isFinishing) return
        // 无论自动还是手点，一旦交棒给播放器就置位 —— 否则用户从播放器返回时
        // 轮询会再次"自动挑一个"把人跳走，非常突然。
        autoPlayed = true
        val list = ranked()
        val at = list.indexOfFirst { it.url == c.url }.coerceAtLeast(0)
        // 把整份候选清单交出去：播放器那边播不出来会自动换下一个，不用回来重新嗅探
        SniffQueue.set(list, at)

        val h = HashMap<String, String>()
        h.putAll(pageHeaders)
        if (h.keys.none { it.equals("Referer", true) } && pageUrl.isNotBlank()) h["Referer"] = pageUrl

        startActivity(
            PlayerActivity.intent(
                this, c.url, title.ifBlank { "播放" }, h,
                pageUrl = pageUrl, fromSniff = true
            )
        )
        // 刻意**不** finish()：候选清单留在返回栈里，播不出来时返回就能换一个源。
        // 同时停掉轮询，别在后台继续往网页里灌脚本。
        polling = false
        handler.removeCallbacks(pollTask)
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onStart() {
        super.onStart()
        runCatching {
            binding.webView.onResume()
            binding.webView.resumeTimers()
        }
        // 从播放器返回时继续收集：候选清单保持"活着"，注解（时长/分片数）也会继续更新。
        // 不会再自动跳走 —— 见 startPlayer() 里的 autoPlayed 置位。
        startPolling()
    }

    override fun onStop() {
        super.onStop()
        polling = false
        handler.removeCallbacks(pollTask)
        runCatching {
            binding.webView.onPause()
            binding.webView.pauseTimers()
        }
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

    /** 页面可见文字（截断）—— 只在"一个候选都没有"时用，判断是不是登录墙 */
    private val TEXT_JS =
        "(function(){try{return document.body?document.body.innerText.slice(0,1200):''}catch(e){return ''}})()"
}
