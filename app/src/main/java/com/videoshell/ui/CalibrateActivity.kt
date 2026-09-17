package com.videoshell.ui

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
import com.videoshell.data.net.Http
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.HtmlTemplates
import com.videoshell.data.site.RecipeStore
import com.videoshell.data.site.SiteCalib
import com.videoshell.databinding.ActivityCalibrateBinding
import com.videoshell.player.PlayerActivity
import com.videoshell.util.toast
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 调试校准模式。
 *
 * ## 为什么需要它
 *
 * 自动适配是「按形状猜」，猜不中时（站点把 maccms 的目录名改了、导航结构少见）
 * 用户只能看着一个不好使的壳。但**用户自己知道哪个是分类、哪个是影片、哪个是分集** ——
 * 让他点一遍，比我们继续加正则要可靠得多。
 *
 * ## 流程（三步，全程在真实网页里点）
 *
 * ```
 * ① 点分类名  -> 记下「分类所在的导航容器」（选择器）+ 校验分类形状判据认不认它
 * ② 点影片    -> 反推「详情页模板」 /bspvd/{id}.html
 * ③ 点一集    -> 反推「播放页模板」 /bspvp/{id}-1-1.html
 *              -> 立刻用这个分集真解析一次播放地址
 *              -> Direct：固化 + 直接试播；Sniff：固化 + 改用嗅探；失败：固化 + 告知
 * ```
 *
 * 三步的产物都写进 [RecipeStore]，[com.videoshell.data.site.HtmlAdapter] 下次任何
 * Activity 新建实例时直接生效 —— 这就是「识别一次、之后固化」。
 *
 * ## 实现要点
 *
 * - 页面点击**不拦截**（不 preventDefault），让网页自己正常跳转，用户看到的就是真实网站。
 *   我们只在捕获阶段旁听一次点击，拿到 href / 文本。
 * - 判定一律在 Kotlin 侧用 [HtmlTemplates] 做，JS 只负责报地址 —— 判据只有一份，
 *   不会出现「JS 认了、适配器不认」这种两套标准。
 * - 分类容器选择器由 Kotlin 用 Jsoup 从抓到的首页 DOM 里反推（比信 JS 给的类名稳），
 *   JS 报的类名只作兜底。
 */
class CalibrateActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_KEY = "site_key"
        private const val STEP_CAT = 1
        private const val STEP_DETAIL = 2
        private const val STEP_PLAY = 3

        fun intent(context: Context, siteKey: String): Intent =
            Intent(context, CalibrateActivity::class.java).putExtra(EXTRA_KEY, siteKey)
    }

    private lateinit var binding: ActivityCalibrateBinding
    private lateinit var site: SiteConfig

    private var step = STEP_CAT
    private var pageUrl = ""

    /** 三步各自学到的规则 */
    private var navSel: String? = null
    private var catTpl: String? = null
    private var detailTpl: String? = null
    private var playTpl: String? = null

    /** 第三步走完（规则已齐），别再响应后续点击 */
    private var finished = false

    /** 一次点击的原始信息 */
    private data class Pick(val raw: String, val abs: String, val text: String, val jsSel: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val s = Store.find(this, intent.getStringExtra(EXTRA_KEY).orEmpty())
        if (s == null) {
            toast("站点不存在")
            finish()
            return
        }
        site = s

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRestart.setOnClickListener { restart() }

        // 已校准过的话，把上次的规则先亮出来，方便对照着点
        RecipeStore.load(site.baseUrl)?.let {
            navSel = it.navSel?.takeIf { c -> c.isNotBlank() }
        }

        render()
        setupWebView()
        binding.webView.loadUrl(site.baseUrl)
    }

    // ------------------------------------------------------------------ 引导文案

    private fun render() {
        binding.tvTitle.text =
            if (finished) getString(R.string.calib_title_done)
            else getString(R.string.calib_title, step)
        when (step) {
            STEP_CAT -> {
                binding.tvStep.setText(R.string.calib_step1)
                binding.tvHint.setText(R.string.calib_hint1)
            }
            STEP_DETAIL -> {
                binding.tvStep.setText(R.string.calib_step2)
                binding.tvHint.setText(R.string.calib_hint2)
            }
            else -> {
                binding.tvStep.setText(R.string.calib_step3)
                binding.tvHint.setText(R.string.calib_hint3)
            }
        }
    }

    private fun state(msg: String) {
        binding.tvState.text = msg
    }

    private fun restart() {
        step = STEP_CAT
        finished = false
        detailTpl = null
        playTpl = null
        binding.pb.visibility = View.GONE
        render()
        state(getString(R.string.calib_restarted))
        binding.webView.loadUrl(site.baseUrl)
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
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
        binding.webView.addJavascriptInterface(Bridge(), "VS")
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageUrl = url.orEmpty()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageUrl = url.orEmpty()
                injectPicker()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                pageUrl = request?.url?.toString().orEmpty()
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

    private fun injectPicker() {
        runCatching { binding.webView.evaluateJavascript(PICK_JS, null) }
    }

    /** JS 只做一件事：把用户点了哪个链接报上来。判定全在 Kotlin。 */
    private inner class Bridge {
        @JavascriptInterface
        fun onPick(json: String) {
            runOnUiThread { handlePick(json) }
        }
    }

    // ------------------------------------------------------------------ 点击处理

    private fun handlePick(json: String) {
        if (finished) return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val raw = o.optString("href").trim()
        val text = o.optString("text").trim()
        val jsSel = o.optString("sel").trim()
        val page = o.optString("url").trim().ifBlank { pageUrl }
        // 不是跳转链接（线路 tab 常用 `javascript:void(0)`）就当没点到 —— 这类点击不该推进步骤
        if (raw.isBlank()) return
        if (raw.startsWith("javascript") || raw.startsWith("#") || raw.startsWith("mailto")) return
        val p = Pick(raw, absUrl(raw, page), text, jsSel)
        when (step) {
            STEP_CAT -> pickCategory(p)
            STEP_DETAIL -> pickDetail(p)
            STEP_PLAY -> pickPlay(p)
        }
    }

    /**
     * ① 分类：记下两样东西 —— **分类页 URL 形状**（主）与**所在容器**（备）。
     *
     * 形状才是"分类逻辑"：站点把 maccms 的目录名改成了 `bspvt`，这件事只有用户点一下才能知道；
     * 而分类标签会散落在主菜单 / 二级面板 / 底部导航里，认形状才能一次全收。
     */
    private fun pickCategory(p: Pick) {
        if (!SiteCalib.isCategoryShape(p.raw)) {
            state(getString(R.string.calib_bad_cat))
            return
        }
        catTpl = HtmlTemplates.catTplFrom(p.abs)
        step = STEP_DETAIL
        render()
        state(getString(R.string.calib_got_cat, p.text.ifBlank { p.abs }))
        lifecycleScope.launch {
            val sel = deriveNavSel(p.abs) ?: p.jsSel.takeIf { it.isNotBlank() }
            navSel = sel
            val parts = ArrayList<String>(3)
            parts += getString(R.string.calib_got_cat, p.text.ifBlank { p.abs })
            parts += catTpl?.let { getString(R.string.calib_cat_tpl, it) }
                ?: getString(R.string.calib_cat_tpl_none)
            parts += sel?.let { getString(R.string.calib_nav_sel, it) }
                ?: getString(R.string.calib_nav_sel_none)
            state(parts.joinToString("　"))
        }
    }

    /** ② 影片：反推详情页模板。点了播放页链接会被明确指出来（那是第 3 步该点的） */
    private fun pickDetail(p: Pick) {
        if (HtmlTemplates.isPlayLink(p.raw) || HtmlTemplates.isPlayLink(p.abs)) {
            state(getString(R.string.calib_bad_detail_is_play))
            return
        }
        val id = HtmlTemplates.videoIdOf(p.raw, false) ?: HtmlTemplates.videoIdOf(p.abs, false)
        val tpl = if (id.isNullOrBlank()) null else HtmlTemplates.detailTplFrom(p.abs, id)
        if (tpl.isNullOrBlank()) {
            state(getString(R.string.calib_bad_detail))
            return
        }
        detailTpl = tpl
        step = STEP_PLAY
        render()
        state(getString(R.string.calib_got_detail, tpl))
    }

    /** ③ 分集：反推播放页模板，然后**真解析一次**，能出地址就顺势试播 */
    private fun pickPlay(p: Pick) {
        if (!HtmlTemplates.isPlayLink(p.raw) && !HtmlTemplates.isPlayLink(p.abs)) {
            state(getString(R.string.calib_bad_play))
            return
        }
        val tpl = HtmlTemplates.playTplFrom(p.abs)
        if (tpl.isNullOrBlank()) {
            state(getString(R.string.calib_bad_play))
            return
        }
        playTpl = tpl
        finished = true
        render()
        state(getString(R.string.calib_got_play, tpl))
        resolveAndPlay(p.abs)
    }

    private fun isCategoryShape(href: String): Boolean = SiteCalib.isCategoryShape(href)

    // ------------------------------------------------------------------ 分类容器反推

    /**
     * 从抓到的首页 DOM 里反推「分类所在的导航容器」选择器。
     *
     * 逻辑本身在 [SiteCalib]（纯函数、有离线断言）；这里只负责把首页抓下来。
     * 用解析出来的原文 DOM 而不是 WebView 渲染后的 DOM —— 适配器实际用的是前者，
     * 在这里推出来的选择器必须在前者上有效，否则固化了也用不上。
     */
    private suspend fun deriveNavSel(clickedAbs: String): String? {
        val html = runCatching { Http.getOrNull(site.baseUrl, referer = site.baseUrl) }.getOrNull()
            ?: return null
        return SiteCalib.navSel(html, site.baseUrl, clickedAbs)
    }

    // ------------------------------------------------------------------ 解析 + 固化 + 试播

    private fun resolveAndPlay(playUrl: String) {
        binding.pb.visibility = View.VISIBLE
        state(getString(R.string.calib_resolving))
        lifecycleScope.launch {
            val a = AdapterFactory.create(site)
            val r = runCatching { a.resolve(Episode("分集", playUrl)) }
                .getOrElse { MediaSource.Error(it.javaClass.simpleName + ": " + it.message) }
            binding.pb.visibility = View.GONE

            when (r) {
                is MediaSource.Direct -> {
                    commit(playUrl, "直链可用")
                    // 网页里那个播放器可能已经在响，进播放器前先把它静下来
                    pauseWeb()
                    showDoneDialog { startPlayer(r.url, r.headers, playUrl) }
                }
                is MediaSource.Sniff -> {
                    commit(playUrl, "需网页嗅探")
                    pauseWeb()
                    AlertDialog.Builder(this@CalibrateActivity)
                        .setTitle(R.string.calib_title_done)
                        .setMessage(getString(R.string.calib_need_sniff) + "\n\n" + summary())
                        .setCancelable(false)
                        .setPositiveButton(R.string.calib_play) { _, _ ->
                            openSniff(playUrl, r.headers)
                        }
                        .setNegativeButton(R.string.calib_finish) { _, _ -> finishOk() }
                        .show()
                }
                is MediaSource.Error -> {
                    AlertDialog.Builder(this@CalibrateActivity)
                        .setTitle(R.string.calib_title_done)
                        .setMessage(getString(R.string.calib_resolve_failed, r.message))
                        .setCancelable(false)
                        .setPositiveButton(R.string.calib_keep_rules) { _, _ ->
                            commit(playUrl, "仅规则")
                            finishOk()
                        }
                        .setNeutralButton(R.string.calib_play) { _, _ ->
                            commit(playUrl, "仅规则")
                            openSniff(playUrl, emptyMap())
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    /** 校准摘要：把三步学到的东西原样摊给用户看 —— 他才知道"固化"到底固化了什么 */
    private fun summary(): String = getString(
        R.string.calib_done_msg,
        catTpl ?: "—", navSel ?: "—", detailTpl ?: "—", playTpl ?: "—"
    )

    private fun showDoneDialog(onPlay: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.calib_title_done)
            .setMessage(summary())
            .setCancelable(false)
            .setPositiveButton(R.string.calib_play) { _, _ -> onPlay() }
            .setNegativeButton(R.string.calib_finish) { _, _ -> finishOk() }
            .show()
    }

    private fun startPlayer(mediaUrl: String, headers: Map<String, String>, playUrl: String) {
        startActivity(
            PlayerActivity.intent(
                this,
                mediaUrl,
                site.name.ifBlank { site.baseUrl },
                headers,
                pageUrl = playUrl
            )
        )
        finishOk()
    }

    private fun openSniff(playUrl: String, headers: Map<String, String>) {
        startActivity(
            com.videoshell.player.SniffActivity.intent(
                this, playUrl, site.name.ifBlank { site.baseUrl }, headers
            )
        )
        finishOk()
    }

    /**
     * 把三步学到的规则写进配方。这是整个模式的目的所在 ——
     * 写完之后 [com.videoshell.data.site.HtmlAdapter] 在任何新实例里都能直接用。
     */
    private fun commit(playUrl: String, kind: String) {
        val note = "分类形状=" + (catTpl ?: "—") +
                "；分类容器=" + (navSel ?: "—") +
                "；详情=" + (detailTpl ?: "—") +
                "；播放=" + (playTpl ?: "—") +
                "；$kind；样例=" + short(playUrl)
        RecipeStore.update(site.baseUrl) { r ->
            r.copy(
                catTpl = catTpl ?: r.catTpl,
                navSel = navSel ?: r.navSel,
                detailTpl = detailTpl ?: r.detailTpl,
                playTpl = playTpl ?: r.playTpl,
                calibAt = System.currentTimeMillis(),
                calibNote = note
            )
        }
        switchToHtmlMode()
        toast(getString(R.string.calib_saved))
    }

    /**
     * 校准只对「网页结构」有效。站点若被探测成采集接口模式（maccms_json/xml），
     * 配方根本不会被读到 —— 这里顺手把它切到 html，否则用户白校准一场。
     */
    private fun switchToHtmlMode() {
        if (site.apiMode == SiteConfig.MODE_HTML) return
        val list = Store.sites(this).map {
            if (it.key == site.key) it.copy(apiMode = SiteConfig.MODE_HTML) else it
        }
        Store.save(this, list)
        site = site.copy(apiMode = SiteConfig.MODE_HTML)
        toast(getString(R.string.calib_switch_html))
    }

    private fun finishOk() {
        setResult(RESULT_OK)
        finish()
    }

    // ------------------------------------------------------------------ 工具

    private fun absUrl(href: String, page: String): String {
        val u = href.trim()
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:$u"
        val p = page.ifBlank { site.baseUrl }
        return runCatching {
            java.net.URI(p).resolve(u).toString()
        }.getOrElse {
            site.baseUrl.trimEnd('/') + "/" + u.trimStart('/')
        }
    }

    private fun pauseWeb() {
        runCatching {
            binding.webView.onPause()
            binding.webView.pauseTimers()
        }
    }

    /** 给自检报告 / 配方备注用的短地址 */
    private fun short(u: String): String = if (u.length <= 56) u else u.take(53) + "..."

    // ------------------------------------------------------------------ 生命周期

    override fun onStop() {
        super.onStop()
        pauseWeb()
    }

    override fun onDestroy() {
        runCatching {
            binding.webView.stopLoading()
            binding.webView.loadUrl("about:blank")
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    /**
     * 点击旁听脚本。
     *
     * **刻意不 preventDefault**：让网页按自己的方式跳转（用户看到的就是真实网站），
     * 我们只在捕获阶段记一笔。顺带把 `target=_blank` 改成 `_self` ——
     * 否则点「影片」会新开窗口，WebView 里原地不动，用户会以为没反应。
     */
    private val PICK_JS = """
        (function(){
          if (window.__vsCalib) return; window.__vsCalib = 1;
          function one(e){
            if(!e || !e.tagName) return '';
            if(e.id) return e.tagName.toLowerCase()+'#'+e.id;
            var cs = (e.getAttribute('class')||'').trim().split(/\s+/);
            for(var i=0;i<cs.length;i++){
              var c = cs[i];
              if(c && c.length>=3 && c.length<=24 && !/^[0-9]/.test(c)) return e.tagName.toLowerCase()+'.'+c;
            }
            return e.tagName.toLowerCase();
          }
          function cont(a){
            var cur = a.parentElement, best = null, hops = 0;
            while(cur && cur !== document.body && hops < 6){
              var n = 0, ls = cur.querySelectorAll ? cur.querySelectorAll('a[href]') : [];
              for(var i=0;i<ls.length;i++){
                var h = ls[i].getAttribute('href')||'';
                if(h.indexOf('javascript:')!==0 && h.indexOf('#')!==0) n++;
              }
              if(n >= 2) best = cur;
              if(n >= 5) break;
              cur = cur.parentElement; hops++;
            }
            return best || (a ? a.parentElement : null);
          }
          document.addEventListener('click', function(e){
            var n = e.target, a = null;
            while(n && n !== document.body && !a){
              if(n.tagName === 'A') a = n;
              n = n.parentElement;
            }
            if(!a) return;
            try { if(a.target && a.target !== '_self') a.target = '_self'; } catch(err){}
            var href = a.getAttribute('href') || '';
            var text = (a.textContent || '').replace(/\s+/g,' ').trim().slice(0,40);
            var sel = one(cont(a));
            try {
              VS.onPick(JSON.stringify({href:href, text:text, sel:sel, url:location.href}));
            } catch(err){}
          }, true);
        })();
    """.trimIndent()
}
