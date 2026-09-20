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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.videoshell.R
import com.videoshell.data.Store
import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
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

        /**
         * 浏览模式（v1.0.37）：把这个页面**当网页用**，而不是当"某一集的播放页"用。
         *
         * 差别只有一条，但影响很大：浏览模式**不做自动播放**。原始嗅探的逻辑是
         * "认得出的正片就自动跳播放器"，那是播放页该有的行为；可一旦用它来浏览
         * （搜索引擎结果页 → 点进一个影片站 → 再点别的），自动跳走会把用户的浏览打断，
         * 而且他并不知道自己是怎么被带走的。候选照常收集、照常可以手点。
         */
        private const val EXTRA_BROWSE = "browse"

        /** 首个候选出现后先等一会儿再动手：让页面把该发的请求都发出来，免得"先到的广告"被当成唯一选项 */
        private const val SETTLE_MS = 2_500L

        /** 最多真正探测几个候选的 playlist（每次探测一个网络请求，不宜贪多） */
        private const val MAX_PROBE = 4

        /** 最多重新探测两轮（页面可能陆续吐出更多候选） */
        private const val MAX_PROBE_ROUNDS = 2

        private const val MAX_TICKS = 240

        fun intent(
            context: Context,
            pageUrl: String,
            title: String,
            headers: Map<String, String>,
            browse: Boolean = false
        ): Intent =
            Intent(context, SniffActivity::class.java).apply {
                putExtra(EXTRA_URL, pageUrl)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BROWSE, browse)
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

    /** 浏览模式：不自动播、不自动探测，只把网页当网页用（见 [EXTRA_BROWSE] 说明） */
    private var browse = false

    /** 「识别并添加」正在进行中：防连点（每次识别都要真发几个请求） */
    private var grabbing = false

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

    /** ③ jx 解析接口只试一次 */
    private var jxTried = false

    /** 「没抓到候选」的原因只记一次，别把播放记录刷满 */
    private var loggedOneShot = false

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) return
            ticks++
            // 浏览模式没有"超时"这回事：用户可能在一个网页上停很久再往下点，
            // 到点就停会让"当前页"不再更新、候选也不再收集 —— 看起来就是功能坏了。
            if (!browse && ticks > MAX_TICKS) {
                polling = false
                updateStatus()
                return
            }
            collectJs()
            refreshUrlLine()
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
        browse = intent.getBooleanExtra(EXTRA_BROWSE, false)
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

        binding.tvTitle.text = title.ifBlank {
            getString(if (browse) R.string.sniffer_browse_title else R.string.sniffer_title)
        }
        binding.btnBack.setOnClickListener { finish() }

        binding.rvCandidates.layoutManager = LinearLayoutManager(this)
        binding.rvCandidates.adapter = candidateAdapter

        // ---- 浏览模式：把"这一页变成一个站源"的两个入口摆出来 ----
        if (browse) {
            binding.webBack.visibility = View.VISIBLE
            binding.webActionRow.visibility = View.VISIBLE
            binding.tvCurUrl.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.sniffer_browse)
            // 浏览模式下左上角是「关闭网页」，左边的箭头才是「网页后退」——
            // 两个图标必须看得出区别，否则用户按哪个都是猜
            binding.btnBack.setImageResource(R.drawable.ic_close)
            binding.btnBack.contentDescription = getString(R.string.sniffer_close_web)
            // 「重新嗅探」在浏览语境下就是「刷新这一页」—— 叫法要跟着用途走
            binding.btnRetry.setText(R.string.web_reload)
            binding.webBack.setOnClickListener { backInWeb() }
            binding.btnGrab.setOnClickListener { recognizeAndAdd() }
            binding.btnCalib.setOnClickListener { manualCalibrate() }
        }

        binding.btnToggleWeb.setOnClickListener {
            showWeb(!webVisible)
        }
        binding.btnRetry.setOnClickListener { restart() }

        // 顶栏的「复制报告」按钮 —— 之前只能点状态栏文字（界面上没有任何提示，
        // 用户反馈"嗅探页无法复制报告"），现在做成明确可见的按钮
        binding.btnCopy.setOnClickListener { copyReport() }
        binding.tvStatus.setOnClickListener { copyReport() }

        setupWebView()
        loadPage()
        startPolling()
    }

    private fun showWeb(show: Boolean) {
        webVisible = show
        binding.webView.alpha = if (show) 1f else 0f
        binding.btnToggleWeb.text =
            getString(if (show) R.string.sniffer_hide_web else R.string.sniffer_show_web)
    }

    /**
     * 浏览模式下的「返回」= **网页后退**。
     *
     * 用户从搜索结果点进一个站，想回结果页继续挑下一个 —— 这里若直接关掉整个页面，
     * 他得重新搜一次。退无可退才真正退出（这一点与 [CalibrateActivity] 的处理一致）。
     */
    private fun backInWeb() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
    }

    override fun onBackPressed() {
        if (browse && binding.webView.canGoBack()) binding.webView.goBack()
        else super.onBackPressed()
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
                refreshUrlLine()
                // 会话记忆：把'这个站该带什么 Referer'留下，播放/解析复用（换集、从历史进来都受益）
                SniffSession.remember(pageUrl, pageUrl, Http.UA)
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
        loggedOneShot = false
        binding.tvStatus.text = getString(
            if (browse) R.string.sniffer_browse else R.string.sniffer_running
        )
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
        // ② 嗅探增强：资源时间线 + 内联脚本里的地址（很多站把 m3u8 写在 <script> 的配置对象里，
        // 既不 fetch 也不进 video 标签，以前抓不到）
        if (ticks % 3 == 0) {
            runCatching {
                binding.webView.evaluateJavascript(PERF_JS) { value ->
                    for (item in parseJs(value)) offer(item)
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
        // 浏览模式：一个请求都不替用户发，也绝不自动跳播放器。
        // 这不是"少做一点"—— 用嗅探页当浏览器时，用户点的每一个链接都是他自己的意图，
        // 我们替他挑一个源并跳走，等于把他的浏览打断在自己不知道的地方。
        if (browse) return
        if (autoPlayed) return
        if (candidates.isEmpty()) {
            maybeDetectLoginWall()
            maybeFollowJx()
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
        if (browse) {
            // 浏览模式的状态行只说两件事：正在浏览、抓到了几个地址。
            // 刻意**不报**"超时 / 要求登录"—— 用户不是在这儿等嗅探，他是在看网页；
            // 一个永远转的进度圈会让他以为这页还没加载好。
            binding.tvStatus.text = if (n == 0) getString(R.string.sniffer_browse)
            else getString(R.string.sniffer_browse_found, n)
            binding.pb.visibility = View.GONE
            candidateAdapter.submit(ranked())
            binding.rvCandidates.visibility = if (n == 0) View.GONE else View.VISIBLE
            return
        }
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

        // 嗅探失败的原因也写进播放记录：自检报告会带上它，
        // 省得"嗅探页无法复制报告"时这条线索直接丢掉。
        if (n == 0 && !loggedOneShot && (loginWall || pageError.isNotBlank() || ticks > 25)) {
            loggedOneShot = true
            val why = when {
                loginWall -> "页面要求登录（$loginWords）"
                pageError.isNotBlank() -> "页面加载失败 $pageError"
                else -> "超时且一个媒体请求都没抓到"
            }
            PlayLog.record("✗ 嗅探失败：$why  页=${PlayLog.shortenPublic(pageUrl)}")
        }
    }

    // ------------------------------------------------------------------ 浏览模式：识别并添加 / 手动校准

    /** 当前真实所在页。`webView.url` 是唯一跟得上站内跳转（pushState / AJAX）的来源 */
    private fun currentWebUrl(): String =
        binding.webView.url.orEmpty().trim().ifBlank { pageUrl }

    private fun refreshUrlLine() {
        val u = currentWebUrl()
        binding.tvCurUrl.text = if (u.isBlank()) getString(R.string.sniffer_url_loading)
        else getString(R.string.sniffer_url_line, u)
    }

    /**
     * 一键「识别并添加」：把当前这一页当成一个视频站，走一遍完整识别并入库。
     *
     * 判据与出口全部在 [WebSiteKit] —— 与「手动校准」共用同一套匹配逻辑，
     * 不会出现"识别说已添加、校准又说找不到"的矛盾。
     */
    private fun recognizeAndAdd() {
        if (grabbing) return
        val url = currentWebUrl()
        if (url.isBlank()) {
            toast(getString(R.string.sniffer_no_url))
            return
        }
        grabbing = true
        binding.btnGrab.isEnabled = false
        binding.tvStatus.text = getString(R.string.sniffer_grab_running)
        lifecycleScope.launch {
            val r = runCatching { WebSiteKit.recognizeAndAdd(this@SniffActivity, url) }.getOrNull()
            grabbing = false
            binding.btnGrab.isEnabled = true
            updateStatus()
            if (r == null) {
                toast("识别失败：网络异常")
                return@launch
            }
            if (!r.ok || r.site == null) {
                // 失败也必须说清楚原因（"还在结果页上"和"这站不是视频站"是两件事）
                toast(r.message)
                return@launch
            }
            PlayLog.record("网页浏览识别成功：${r.site.name}  host=${Store.hostOf(r.site.baseUrl)}")
            AlertDialog.Builder(this@SniffActivity)
                .setTitle(R.string.sniffer_grab_ok_title)
                .setMessage(getString(R.string.sniffer_grab_ok_msg, r.message))
                .setPositiveButton(R.string.sniffer_grab_open) { _, _ ->
                    startActivity(SiteActivity.intent(this@SniffActivity, r.site.key))
                }
                .setNegativeButton(R.string.sniffer_grab_stay, null)
                .show()
        }
    }

    /**
     * 手动校准：先确保本站**在库里有配置**，再进四步校准。
     *
     * 不先 [WebSiteKit.ensureSite] 的话，`CalibrateActivity` 第一件事 `Store.find(key)`
     * 就会拿到 null 并 `finish()` —— 用户从网页里点「手动校准」会被无声弹回，
     * 表现就是"点了没反应"。这类"静默 exit"是本项目踩过最多次的一类。
     */
    private fun manualCalibrate() {
        val url = currentWebUrl()
        if (url.isBlank()) {
            toast(getString(R.string.sniffer_no_url))
            return
        }
        // ⚠️ 站点名要取**网页自己的标题**，不能取 [title]（那是我们带进来的 intent 标题：
        //    从「全网搜索」进来时它是关键词，拿它当站名会把站叫成"庆余年"）。
        val e = WebSiteKit.ensureSite(this, url, binding.webView.title.orEmpty())
        if (e == null) {
            toast(getString(R.string.sniffer_no_url))
            return
        }
        // 刚建的那一条要解释一句：用户会奇怪"我没添加过啊，怎么列表里多了个站"
        if (e.created) toast(getString(R.string.sniffer_calib_added, e.site.name))
        startActivity(CalibrateActivity.intent(this, e.site.key))
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

    /**
     * ③ 解析接口（jx）跟随：页面里只有 iframe 指向解析接口时，
     * 直接把接口地址扒出来 GET 一次，很多时候一次就拿到 m3u8，不必等页面把流跑起来。
     */
    private fun maybeFollowJx() {
        if (jxTried || ticks < 6) return
        jxTried = true
        runCatching {
            binding.webView.evaluateJavascript(DOM_JS) { value ->
                val html = jsonString(value)
                if (html.isBlank()) return@evaluateJavascript
                val jx = JxParser.findJxUrl(html) ?: return@evaluateJavascript
                lifecycleScope.launch {
                    val stream = JxParser.follow(jx, pageUrl)
                    if (!stream.isNullOrBlank()) {
                        SniffSession.remember(pageUrl, pageUrl)
                        offer(stream)
                        PlayLog.record("jx 解析接口命中：${PlayLog.shortenPublic(jx)}")
                        updateStatus()
                    }
                }
            }
        }
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
        sb.appendLine("分片命中目录 " + tsHits.size + " 个（正片目录会被打很多次）：")
        tsHits.entries.sortedByDescending { it.value }.take(5)
            .forEach { sb.appendLine("  ${it.value} 次  ${it.key}") }
        sb.appendLine()
        sb.appendLine("---------- HTTP 记录 ----------")
        sb.appendLine(NetLog.report())
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
        PlayLog.record(
            "嗅探候选 ${list.size} 个，选第 ${at + 1}：${c.display()}  " +
                PlayLog.shortenPublic(c.url)
        )

        val h = HashMap<String, String>()
        h.putAll(SniffSession.enrich(c.url, pageHeaders))
        if (h.keys.none { it.equals("Referer", true) } && pageUrl.isNotBlank()) h["Referer"] = pageUrl
        SniffSession.remember(pageUrl, h["Referer"] ?: pageUrl, h["User-Agent"] ?: Http.UA)

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
          window.__vsPush = push;
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
          // ② MediaSource：走 MSE 的站（自研播放器、hls.js）只喂 blob 分片，
          // 但 addSourceBuffer 的 mime 与后续 appendBuffer 的时长能证明'这个 blob 在被播放'；
          // 真正有用的是下面 URL.createObjectURL 的入参来源 —— 这里挂钩只为留下播放发生过的痕迹。
          try {
            if (window.MediaSource && MediaSource.prototype.addSourceBuffer) {
              var _add = MediaSource.prototype.addSourceBuffer;
              MediaSource.prototype.addSourceBuffer = function(m){ window.__vsMse = String(m); return _add.apply(this, arguments); };
            }
          } catch(e){}
          // ② JSON.parse：加密接口把结果解密成 JSON 后立刻 parse，这里顺手扫一遍字符串值
          try {
            var _parse = JSON.parse;
            JSON.parse = function(s){
              try { window.__vsPush(s); } catch(e){}
              return _parse.apply(this, arguments);
            };
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

    /**
     * ② 嗅探增强：两处补充来源
     * 1. `performance.getEntriesByType('resource')` —— 浏览器自己记的完整资源时间线，
     *    比我们挂钩子更全（含 hook 之前就发出的请求）；
     * 2. 页面内联网脚本文本里的媒体地址（`<script>` 配置对象、内联 JSON）。
     */
    private val PERF_JS = """
        (function(){
          var out = [];
          try {
            var es = performance.getEntriesByType('resource') || [];
            for (var i = 0; i < es.length; i++){
              var n = es[i].name || '';
              if (n.indexOf('.m3u8') >= 0 || n.indexOf('.mp4') >= 0 || n.indexOf('.flv') >= 0 || n.indexOf('.ts') >= 0) out.push(n);
            }
          } catch(e){}
          try {
            var ss = document.querySelectorAll('script:not([src])');
            var re = /https?:\/\/[^"'\s\\<>]+?\.(?:m3u8|mp4|flv)[^"'\s\\<>]*/g;
            for (var j = 0; j < ss.length; j++){
              var t = ss[j].textContent || '';
              if (t.indexOf('m3u8') < 0 && t.indexOf('.mp4') < 0) continue;
              var m; var c = 0;
              while ((m = re.exec(t)) !== null && c < 5){ out.push(m[0]); c++; }
            }
          } catch(e){}
          return JSON.stringify(out.slice(0, 40));
        })()
    """

    /** 页面可见文字（截断）—— 只在"一个候选都没有"时用，判断是不是登录墙 */
    private val TEXT_JS =
        "(function(){try{return document.body?document.body.innerText.slice(0,1200):''}catch(e){return ''}})()"

    /** 整页 HTML —— 只在没有候选时取一次，用来找 jx 解析接口地址 */
    private val DOM_JS =
        "(function(){try{return document.documentElement?document.documentElement.outerHTML:''}catch(e){return ''}})()"
}
