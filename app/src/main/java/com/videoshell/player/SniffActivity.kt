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
        internal const val SETTLE_MS = 2_500L

        /** 最多真正探测几个候选的 playlist（每次探测一个网络请求，不宜贪多） */
        internal const val MAX_PROBE = 4

        /** 最多重新探测两轮（页面可能陆续吐出更多候选） */
        internal const val MAX_PROBE_ROUNDS = 2

        private const val MAX_TICKS = 240

        /**
         * 判定"这是拖动而不是点一下"的位移阈值（**dp**，用时乘屏幕密度）。
         *
         * 手指按下去一定会抖一两个像素，没有阈值的话每次"想点一下收起"都会被当成
         * 一次微小拖动 ⇒ 收起功能永远触发不了，而用户只会觉得"这按钮没反应"。
         * 写成 dp 而不是 px：3x 屏上 8px 只有 2.7dp，抖一下就过线了。
         */
        internal const val SLOP_DP = 6f

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

    internal lateinit var binding: ActivitySniffBinding
    internal lateinit var pageUrl: String
    internal var title: String = ""
    internal var pageHeaders: Map<String, String> = emptyMap()

    /** 浏览模式：不自动播、不自动探测，只把网页当网页用（见 [EXTRA_BROWSE] 说明） */
    internal var browse = false

    /** 「识别并添加」正在进行中：防连点（每次识别都要真发几个请求） */
    internal var grabbing = false

    /**
     * 去广告（v1.0.52）：**默认开**，浮窗上可关。
     *
     * 这个页面是全 App 唯一把第三方站真跑起来的地方，而广告在这里造成的是**实打实的误判**：
     * 广告播放器会先于正片发出自己的 m3u8（[SniffRank] 只能事后给它减分），
     * 广告浮层还会盖住网页上真正该点的「选集 / 播放」。
     *
     * 留开关不留成"永远开"：判据里有一条（跨站 + 无手势 + 非重定向 = 弹窗）是**靠行为推断**
     * 的，而域名轮换的站在点击后确实可能无手势地跳到别的域名 —— 推断就会错，
     * 错了必须让人关得掉（关不掉的过滤器比没有过滤器更危险）。
     */
    internal var adBlockOn = true

    /** 「已拦截广告跳转」每页只提示一次（弹窗会反复重试，不节流会连弹十几个 toast） */
    internal var navBlockNotified = false

    /** 播放页地址里的视频 id —— 候选地址含它时是很强的正面信号 */
    internal var videoId: String = ""

    internal val candidates = LinkedHashMap<String, SniffCandidate>()

    /** 每个目录下观测到的 .ts 分片请求数：正片目录会被打几百次，广告目录只有十几次 */
    internal val tsHits = HashMap<String, Int>()

    internal val candidateAdapter = CandidateAdapter { c -> startPlayer(c) }

    internal val handler = Handler(Looper.getMainLooper())
    internal var polling = false
    internal var autoPlayed = false
    internal var firstSeenAt = 0L
    internal var webVisible = true
    internal var ticks = 0

    /** 主文档加载失败的原因；有值时状态栏直接显示，不再让用户对着空白页猜 */
    internal var pageError = ""

    /** 页面要求登录（这类站根本不会下发播放地址，嗅探必然扑空） */
    internal var loginWall = false
    internal var loginWords = ""

    internal var probeRounds = 0
    internal var probing = false
    internal var textProbed = false

    /**
     * 浮窗是否收起（v1.0.38）。
     *
     * 不持久化，而且浏览模式**默认收起**：这个页面是拿来看网页的，浮窗压在底部会挡住
     * 站点的「选集 / 换线路 / 播放」按钮。默认收起 = 一进来就不挡事；
     * 要用「识别并添加」再展开，那正是需要点浮窗的时候。
     *
     * 位置（[panelTx]/[panelTy]）同理不落盘 —— 挪开它是为了避开**当前这一页**的按钮，
     * 记到下一站反而是拿上一站的布局去挡这一站的。
     */
    internal var panelCollapsed = false

    /** ③ jx 解析接口只试一次 */
    private var jxTried = false

    /** 「没抓到候选」的原因只记一次，别把播放记录刷满 */
    internal var loggedOneShot = false

    internal val pollTask = object : Runnable {
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

        // ---- 去广告（v1.0.52）----
        // 计数器按"这一页"算，报告里的数字才有意义（换页/重新嗅探时清零）
        adBlockOn = WebAdBlock.on(this)
        WebAdBlock.reset()
        binding.btnAdBlock.setOnClickListener { toggleAdBlock() }
        renderAdBlockChip()

        // ---- 浮窗：可收起 + 可拖动（v1.0.38）----
        // 浏览模式默认收起：这个页面是拿来看网页的，浮窗不管内容只挡按钮
        panelCollapsed = browse
        binding.btnPanelToggle.setOnClickListener { togglePanel() }
        setupPanelDrag()
        renderPanel()

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

    // ------------------------------------------------------------------ 去广告（v1.0.52）

    private fun renderAdBlockChip() {
        binding.btnAdBlock.setText(
            if (adBlockOn) R.string.adblock_on else R.string.adblock_off
        )
    }

    /**
     * 开关去广告。切换后**重载一次**当前页 —— 已经拦下的资源不会因为我们改了主意而复活，
     * 只改判据不重载，用户会看到"关掉了但广告还在"这种无法解释的中间态。
     */
    private fun toggleAdBlock() {
        adBlockOn = !adBlockOn
        WebAdBlock.setOn(this, adBlockOn)
        renderAdBlockChip()
        WebAdBlock.reset()
        navBlockNotified = false
        toast(getString(if (adBlockOn) R.string.adblock_on_toast else R.string.adblock_off_toast))
        binding.webView.reload()
    }

    /**
     * 顶层跳转守卫：拦下弹窗/诱导跳 App，**并且说出来**。
     *
     * 那条"跨站 + 无手势 = 弹窗"的规则是推断，一定会偶尔错杀站点自己的 JS 跳转，
     * 所以拦下时不能静默：状态栏那句提示就是用户判断"是不是它挡了我"的依据，
     * 而浮窗上的开关是他能立刻自救的手段（PITFALLS：静默失败最贵）。
     */
    internal fun guardNav(to: String, request: WebResourceRequest?): Boolean {
        if (!adBlockOn || to.isBlank()) return false
        val from = binding.webView.url.orEmpty()
        if (!WebAdBlock.navBlocked(from, to, request)) return false
        if (!navBlockNotified) {
            navBlockNotified = true
            toast(getString(R.string.adblock_nav_blocked, Store.hostOf(to)))
        }
        return true
    }

    override fun onBackPressed() {
        if (browse && binding.webView.canGoBack()) binding.webView.goBack()
        else super.onBackPressed()
    }

    internal fun updateStatus() {
        val n = candidates.size
        // 收起时那一行也要跟着更新：它是收起来之后**唯一**还能看见的信息，
        // 停在旧数字上就等于这块浮窗不收也不对、收起来也不对
        refreshPanelBrief()
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
    internal fun currentWebUrl(): String =
        binding.webView.url.orEmpty().trim().ifBlank { pageUrl }

    internal fun refreshUrlLine() {
        val u = currentWebUrl()
        binding.tvCurUrl.text = if (u.isBlank()) getString(R.string.sniffer_url_loading)
        else getString(R.string.sniffer_url_line, u)
    }

    internal val LOGIN_WORDS = listOf(
        "登录后即可观看", "登录后播放", "请先登录", "请登录", "立即登录",
        "开通会员", "购买后", "会员专享"
    )

    /** 长时间一个候选都没有：看看页面是不是在要求登录 —— 这类站嗅探必然扑空，得如实告诉用户 */
    internal fun maybeDetectLoginWall() {
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

    internal fun jsonString(v: String?): String {
        if (v.isNullOrBlank() || v == "null") return ""
        return runCatching { (JSONTokener(v).nextValue() as? String).orEmpty() }.getOrDefault("")
    }

    /**
     * ③ 解析接口（jx）跟随：页面里只有 iframe 指向解析接口时，
     * 直接把接口地址扒出来 GET 一次，很多时候一次就拿到 m3u8，不必等页面把流跑起来。
     */
    internal fun maybeFollowJx() {
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

    internal val HOOK_JS = """
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

    internal val COLLECT_JS =
        "(function(){var a=window.__vsFound||[];window.__vsFound=[];return JSON.stringify(a);})()"

    /**
     * ② 嗅探增强：两处补充来源
     * 1. `performance.getEntriesByType('resource')` —— 浏览器自己记的完整资源时间线，
     *    比我们挂钩子更全（含 hook 之前就发出的请求）；
     * 2. 页面内联网脚本文本里的媒体地址（`<script>` 配置对象、内联 JSON）。
     */
    internal val PERF_JS = """
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
