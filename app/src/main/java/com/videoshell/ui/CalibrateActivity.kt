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

        fun intent(context: Context, siteKey: String): Intent =
            Intent(context, CalibrateActivity::class.java).putExtra(EXTRA_KEY, siteKey)
    }

    private lateinit var binding: ActivityCalibrateBinding
    private lateinit var site: SiteConfig

    private var step = SiteCalib.Step.CAT
    private var pageUrl = ""

    /** 三步各自学到的规则 */
    private var navSel: String? = null
    private var catTpl: String? = null
    private var detailTpl: String? = null
    private var playTpl: String? = null
    /** 第 4 步（可选）学到的搜索模板 */
    private var searchTpl: String? = null

    /** 第 3 步选中的播放页地址 —— 第 4 步结束时才真正去解析 / 试播 */
    private var playPickAbs: String = ""

    // ---------------------------------------------------------------- v1.0.29：可跳过某一步
    //
    // 站点之间结构差异极大：有的站没有分类页（第一步无从点起），有的站点封面就直接播放
    // （没有独立详情页，第二步和第三步是同一页）。旧流程强制四步走完，用户只能"随便点一个"
    // 交差 —— 于是把**错误模板**固化进去，接下来解析全用错规则，
    // 表现就是「校准完了结果还是没生效」。

    private var skipCat = false
    private var skipDetail = false
    private var skipPlay = false

    /**
     * 配方**真的被写过**。v1.0.29 修 ③ 用：
     * 旧代码只有 [finishOk] 会 `setResult(RESULT_OK)`，用户点左上角返回时
     * 返回码是 `CANCELED` ⇒ 站源页的 `onCalibReturned()` 不执行 ⇒
     * 配方其实已落盘，界面却还是旧的，用户看到的就是"校准结果没生效"。
     */
    private var committed = false

    /** 第三步走完（别再响应网页点击）；真正收尾看 [resolvingStarted] */
    private var finished = false

    /** 试播 / 固化流程已启动 —— 按钮退场，别再响应确定 */
    private var resolvingStarted = false

    /**
     * 当前**已选中但还没确认**的点击。
     *
     * 这是 v1.0.14 的核心状态：点击只把候选放这儿，**推进必须由用户按「确定」**。
     * 上一版是"点完链接判据通过就自动推进"，判据一否决策略性 `return`，
     * 用户既没有按钮可按、也不知道自己在等什么 —— 于是卡死在第一步。
     */
    private var pending: Pick? = null

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
        binding.btnConfirm.setOnClickListener { confirm() }
        binding.btnReselect.setOnClickListener { reselect() }
        binding.btnSkip.setOnClickListener { skipStep() }

        // 已校准过的话，把上次的规则先亮出来，方便对照着点
        RecipeStore.load(site.baseUrl)?.let {
            navSel = it.navSel?.takeIf { c -> c.isNotBlank() }
        }

        render()
        setupWebView()
        binding.webView.loadUrl(site.baseUrl)
    }

    // ------------------------------------------------------------------ 引导文案

    /**
     * 跳过当前这一步（v1.0.29）。
     *
     * 跳过 ≠ 什么都不做：它表示「本站确实没有这一步」，所以要**清空**该步的旧规则 ——
     * 否则 [commit] 里的 `?:` 会把上一次校准的残留留着，越校越错。
     */
    private fun skipStep() {
        if (resolvingStarted) return
        pending = null
        when (step) {
            SiteCalib.Step.CAT -> {
                skipCat = true
                catTpl = null
                navSel = null
                step = SiteCalib.Step.DETAIL
                state(getString(R.string.calib_skip_cat))
            }
            SiteCalib.Step.DETAIL -> {
                skipDetail = true
                detailTpl = null
                step = SiteCalib.Step.PLAY
                state(getString(R.string.calib_skip_detail))
            }
            SiteCalib.Step.PLAY -> {
                skipPlay = true
                playTpl = null
                // ⚠️ 不清 playPickAbs：如果第 2 步点到的是播放页（"点封面直接播放"的站），
                // 那个地址就是可用的试播样本，留着还能验一次；从没点过才是空。
                step = SiteCalib.Step.SEARCH
                state(getString(R.string.calib_skip_play))
                binding.webView.loadUrl(site.baseUrl)
            }
            // 第 4 步本来就是「按确定后留空 = 跳过」，不再给第二个入口
            SiteCalib.Step.SEARCH -> state(getString(R.string.calib_skip_none))
        }
        render()
    }

    private fun render() {
        // v1.0.20：三步之后还有可选的第 4 步（搜索），标题统一显示步数
        binding.tvTitle.text = getString(R.string.calib_title, step.n)
        when (step) {
            SiteCalib.Step.CAT -> {
                binding.tvStep.setText(R.string.calib_step1)
                binding.tvHint.setText(R.string.calib_hint1)
            }
            SiteCalib.Step.DETAIL -> {
                binding.tvStep.setText(R.string.calib_step2)
                binding.tvHint.setText(R.string.calib_hint2)
            }
            SiteCalib.Step.PLAY -> {
                binding.tvStep.setText(R.string.calib_step3)
                binding.tvHint.setText(R.string.calib_hint3)
            }
            SiteCalib.Step.SEARCH -> {
                binding.tvStep.setText(R.string.calib_step4)
                binding.tvHint.setText(R.string.calib_hint4)
            }
        }
        renderActions()
    }

    /**
     * 确定按钮的文案随步骤变，用户一眼知道"按下去会发生什么"。
     * 按钮**永远可点**（不置灰）：置灰等于又一次"点了没反应"，宁可点了给提示。
     */
    private fun renderActions() {
        binding.btnConfirm.setText(
            when (step) {
                SiteCalib.Step.CAT -> R.string.calib_confirm_cat
                SiteCalib.Step.DETAIL -> R.string.calib_confirm_detail
                SiteCalib.Step.PLAY -> R.string.calib_confirm_play
                SiteCalib.Step.SEARCH -> R.string.calib_confirm_search
            }
        )
        binding.btnConfirm.alpha = if (pending != null || step == SiteCalib.Step.SEARCH) 1f else 0.55f
        val show = !resolvingStarted
        binding.btnConfirm.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnReselect.visibility =
            if (show && pending != null) View.VISIBLE else View.GONE
        // 前三步都能跳过；第 4 步本身就是「留空 = 跳过」，不再给第二个入口
        binding.btnSkip.visibility =
            if (show && step != SiteCalib.Step.SEARCH) View.VISIBLE else View.GONE
    }

    private fun state(msg: String) {
        binding.tvState.text = msg
    }

    private fun restart() {
        step = SiteCalib.Step.CAT
        finished = false
        resolvingStarted = false
        pending = null
        // ⚠️ 分类那两项以前没清（只清了详情/播放/搜索）。
        // 于是"重新校准"时旧形状会一直挂在 `mergeRecipe` 的 `?:` 兜底上，
        // 用户以为在重新学，实际用的还是上一次的点法 —— 越校越错。
        catTpl = null
        navSel = null
        detailTpl = null
        playTpl = null
        searchTpl = null
        playPickAbs = ""
        skipCat = false
        skipDetail = false
        skipPlay = false
        binding.pb.visibility = View.GONE
        render()
        state(getString(R.string.calib_restarted))
        binding.webView.loadUrl(site.baseUrl)
    }

    // ------------------------------------------------------------------ 确认 / 重选

    private fun confirm() {
        if (resolvingStarted) return
        val p = pending
        when (step) {
            SiteCalib.Step.SEARCH -> {
                // 第 4 步不靠点击靠结果页地址；直接弹词框（留空 = 跳过）
                pending = null
                renderActions()
                askSearchKeyword()
                return
            }
            else -> Unit
        }
        if (p == null) {
            state(
                getString(
                    when (step) {
                        SiteCalib.Step.CAT -> R.string.calib_need_pick_cat
                        SiteCalib.Step.DETAIL -> R.string.calib_need_pick_detail
                        SiteCalib.Step.PLAY -> R.string.calib_need_pick_play
                        SiteCalib.Step.SEARCH -> R.string.calib_confirm_search
                    }
                )
            )
            return
        }
        pending = null
        renderActions()
        when (step) {
            SiteCalib.Step.CAT -> lifecycleScope.launch { pickCategory(p) }
            SiteCalib.Step.DETAIL -> pickDetail(p)
            SiteCalib.Step.PLAY -> pickPlay(p)
            SiteCalib.Step.SEARCH -> Unit
        }
    }

    private fun reselect() {
        pending = null
        renderActions()
        state(
            getString(
                when (step) {
                    SiteCalib.Step.CAT -> R.string.calib_need_pick_cat
                    SiteCalib.Step.DETAIL -> R.string.calib_need_pick_detail
                    SiteCalib.Step.PLAY -> R.string.calib_need_pick_play
                    SiteCalib.Step.SEARCH -> R.string.calib_hint4
                }
            )
        )
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

    /** JS 只做一件事：把用户点了哪个链接报上来。判定全在 Kotlin。 */
    private inner class Bridge {
        @JavascriptInterface
        fun onPick(json: String) {
            runOnUiThread { handlePick(json) }
        }
    }

    // ------------------------------------------------------------------ 点击处理

    /**
     * 一次网页点击 = **选中候选**（不推进）。推进只发生在用户按「确定」时。
     *
     * 关键：**任何点击都要给出反馈**。上一版对「没有 href」「javascript:」「判据不认」
     * 三种情况一律静默 `return`，用户点半天界面纹丝不动 —— 这就是"卡在第一步"的观感来源。
     * 现在这三种都会在引导卡上写明原因，且已选中的候选不会被一次误点冲掉。
     */
    private fun handlePick(json: String) {
        if (finished || resolvingStarted) return
        if (step == SiteCalib.Step.SEARCH) {
            // 第 4 步靠「结果页地址」不靠点击；点了也给个说明，免得像没反应
            state(getString(R.string.calib_hint4))
            return
        }
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val raw = o.optString("href").trim()
        val text = o.optString("text").trim()
        val jsSel = o.optString("sel").trim()
        val page = o.optString("url").trim().ifBlank { pageUrl }

        val abs = absUrl(raw, page)
        when (SiteCalib.classify(raw, abs, step)) {
            SiteCalib.PickKind.NOT_LINK -> {
                state(getString(R.string.calib_not_link))
                return
            }
            SiteCalib.PickKind.SCRIPT_LINK -> {
                state(getString(R.string.calib_script_link))
                return
            }
            else -> Unit
        }

        val p = Pick(raw, abs, text, jsSel)
        pending = p
        renderActions()
        state(pickedHint(p))
    }

    /** 选中后的提示：判据认了就报"已选中"，不认就先说清"哪一条没学到、仍可继续"。 */
    private fun pickedHint(p: Pick): String {
        val kind = SiteCalib.classify(p.raw, p.abs, step)
        val label = p.text.ifBlank { p.abs }
        if (kind == SiteCalib.PickKind.GOOD) {
            return getString(
                when (step) {
                    SiteCalib.Step.CAT -> R.string.calib_picked_cat
                    SiteCalib.Step.DETAIL -> R.string.calib_picked_detail
                    SiteCalib.Step.PLAY -> R.string.calib_picked_play
                    SiteCalib.Step.SEARCH -> R.string.calib_hint4
                },
                label
            )
        }
        val soft = when (step) {
            SiteCalib.Step.CAT -> getString(R.string.calib_soft_cat)
            SiteCalib.Step.DETAIL ->
                if (HtmlTemplates.isPlayLink(p.raw) || HtmlTemplates.isPlayLink(p.abs)) {
                    getString(R.string.calib_soft_detail_is_play)
                } else {
                    getString(R.string.calib_soft_detail)
                }
            SiteCalib.Step.PLAY -> getString(R.string.calib_soft_play)
            SiteCalib.Step.SEARCH -> getString(R.string.calib_hint4)
        }
        return soft + "\n" + getString(R.string.calib_picked_other, label)
    }

    /**
     * ① 分类：记下两样东西 —— **分类页 URL 形状**（主）与**所在容器**（备）。
     *
     * 形状才是"分类逻辑"：站点把 maccms 的目录名改成了 `bspvt`，这件事只有用户点一下才能知道；
     * 而分类标签会散落在主菜单 / 二级面板 / 底部导航里，认形状才能一次全收。
     *
     * 形状没认出来（`catTpl == null`）**也照样推进** —— 容器 / 默认逻辑还在，不该把用户锁在第一步。
     *
     * ## v1.0.33：学到形状之后，**当场数一遍**再决定收不收
     *
     * 野果实测（2026-09-19）：用户点的是侧栏导航项 `<a href="/explore/drama/">探索分类</a>`。
     * 它会被 [HtmlTemplates.catTplFrom] 泛化成 `/explore/{slug}/`，与站点真分类 `/tag/{slug}/`
     * 形状**完全一样**（同一个文件的注释早就点名 `/explore/drama/` 是"功能页形状"）；
     * 但首页 330 个 `<a>` 里它只出现 **1 次**，而 `/tag/{slug}/` 有 **252 条**。
     * 运行时判据要求 ≥2 ⇒ 这一条规则**永远不可能生效**。
     *
     * 于是就有了那句查不出原因的反馈：「校准走完了，但还是按原规则显示」。
     * 现在改成当场拦下并说明白：**不收、并告诉用户他点的是导航项而不是分类列表**。
     *
     * ⚠️ 拒收时**保留旧配方**（`catTpl = null`，`mergeRecipe` 按「没学到」处理）。
     * 点错一次不等价于"本站没有分类"，不能拿它抹掉上一次学对的规则；
     * 真要清规则，走站源页的「站点配方重置」。
     */
    private suspend fun pickCategory(p: Pick) {
        val shape = HtmlTemplates.catTplFrom(p.abs)
        // -1 = 数不出来（抓不到页面 / 不是 HTML 适配器）⇒ 放弃判断，绝不误杀用户的点击
        val hits = if (shape != null) {
            runCatching { Http.getOrNull(site.baseUrl, referer = site.baseUrl) }.getOrNull()
                ?.let { html ->
                    runCatching { AdapterFactory.create(site).countCatTplHits(html, shape) }
                        .getOrDefault(-1)
                } ?: -1
        } else -1
        val rejected = hits in 0..1

        catTpl = if (rejected) null else shape
        navSel = if (rejected) null
        else (deriveNavSel(p.abs) ?: p.jsSel.takeIf { it.isNotBlank() })
        step = SiteCalib.Step.DETAIL
        render()

        val head = getString(R.string.calib_got_cat, p.text.ifBlank { p.abs })
        val shapeText = when {
            rejected -> "⚠️ 已跳过这条形状（$shape 在本页只命中 $hits 条，判据要求 ≥2）"
            catTpl != null -> getString(R.string.calib_cat_tpl, catTpl!!)
            else -> getString(R.string.calib_cat_tpl_none)
        }
        val tail = when {
            rejected ->
                "它多半是**一个导航项**、不是分类列表 —— 返回上一步，改点页面上真正的那一串分类标签。"
            navSel != null -> getString(R.string.calib_nav_sel, navSel!!)
            else -> getString(R.string.calib_nav_sel_none)
        }
        // 用户可能已经点到第 2 步了，别把新提示覆盖掉
        if (step == SiteCalib.Step.DETAIL) state("$head　$shapeText　$tail")
    }

    /** ② 影片：反推详情页模板。没学到也推进（退用默认逻辑），只有"这像播放页"会额外说一句。 */
    private fun pickDetail(p: Pick) {
        val isPlay = HtmlTemplates.isPlayLink(p.raw) || HtmlTemplates.isPlayLink(p.abs)
        val id = HtmlTemplates.videoIdOf(p.raw, false) ?: HtmlTemplates.videoIdOf(p.abs, false)
        detailTpl = if (isPlay || id.isNullOrBlank()) null
        else HtmlTemplates.detailTplFrom(p.abs, id)
        // "点封面就直接播放"的站：这个地址本身就是播放页 ⇒ 留作试播样本。
        // 用户接下来如果跳过第 3 步（那种站第 3 步无从点起），第 4 步结束时照样能试播一次。
        if (isPlay) playPickAbs = p.abs
        step = SiteCalib.Step.PLAY
        render()
        val tpl = detailTpl
        state(
            when {
                isPlay -> getString(R.string.calib_soft_detail_is_play)
                tpl != null -> getString(R.string.calib_got_detail, tpl)
                else -> getString(R.string.calib_soft_detail)
            }
        )
    }

    /**
     * ③ 分集：反推播放页模板。v1.0.20 起这里**不再立刻试播收尾**，而是进入第 4 步
     * （搜索校准）—— 试播挪到第 4 步按确定之后，规则一次性固化。
     *
     * v1.0.21：进第 4 步时**自动回到站点首页** —— 第 4 步的任务是"去站内搜一次"，
     * 而用户此刻还停在第三步选中的播放页上；搜索框在首页，不回去就没法搜。
     */
    private fun pickPlay(p: Pick) {
        playTpl = HtmlTemplates.playTplFrom(p.abs)
        playPickAbs = p.abs
        step = SiteCalib.Step.SEARCH
        render()
        state(
            playTpl?.let { getString(R.string.calib_got_play, it) }
                ?: getString(R.string.calib_soft_play)
        )
        binding.webView.loadUrl(site.baseUrl)
    }

    // ------------------------------------------------------------------ ④ 搜索校准

    /**
     * 问用户刚才搜的词。留空 = 跳过搜索校准（规则照常固化、照常试播）。
     *
     * v1.0.21：填了词却学不到模板（pageUrl 里没有关键词 —— 多半是还没去搜索、
     * 或站点的搜索地址不走 URL）⇒ **留在第 4 步**，让用户去搜完再按一次「确定」；
     * 旧行为是直接 resolveAndPlay 收尾，用户一次没搜对，整个校准就结束了。
     */
    private fun askSearchKeyword() {
        val input = android.widget.EditText(this).apply {
            hint = "比如：测试"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.calib_search_kw_title)
            .setMessage(R.string.calib_search_kw_msg)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                applySearchCalib(input.text.toString().trim())
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                applySearchCalib("")
            }
            .show()
    }

    private fun applySearchCalib(kw: String) {
        if (kw.isEmpty()) {
            state(getString(R.string.calib_search_skipped))
            resolveAndPlay(playPickAbs)
            return
        }
        val tpl = SiteCalib.searchTplFromUrl(pageUrl, kw)
        if (tpl == null) {
            // 不收尾：提示后停在第 4 步，用户搜完再按「确定」即可；「取消」按钮可随时跳过
            state(getString(R.string.calib_search_failed))
            return
        }
        searchTpl = tpl
        state(getString(R.string.calib_got_search, tpl))
        resolveAndPlay(playPickAbs)
    }

    // ⚠️ v1.0.33 试过在这里加一道「搜索模板当场自证」（真取一次结果页、数结果链接），
    //    已**撤销** —— 判据不成立，见下面的实测记录，别再犯：
    //
    //    野果（agenda.fzchosdi.cc）实测：
    //      · `/?s=<任意词>`  —— 连 `zzzq不存在的词` 都返回**与首页 sha256 完全相同**的页面
    //        ⇒ 这是**软 404 回首页**，不是搜索结果页。它首页自带 50 个推荐卡片，
    //        其中 3 处提到「庆余年」，于是"严格遍要求标题含关键词"被**首页噪声**蒙混过关，
    //        适配器还把它固化成了搜索模板 ⇒ 表现就是「搜什么都一样」。
    //      · `/search/drama/{kw}/` —— HTTP 200 但 5.4 KB 空壳、0 条结果（JS 渲染）。
    //    ⇒ 对**客户端渲染**的搜索页，"数 SSR HTML 里的结果链接"得出的数字
    //      既可能把首页噪声数成"可用"，也可能把正常的 JS 空壳数成"无效"。
    //      两个方向都会给出**自信而错误**的结论 —— 比不给结论更糟。
    //    真正的判据必须是"结果页与首页内容是否相同"（软 404），而不是"数到几个链接"；
    //    且该站搜索**本身就依赖 JS 渲染**，属另一条线（WebRender / 站点搜索 API），
    //    需要单独一轮来做。

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
        // 跳过了第 3 步 ⇒ 没有可试播的样本。别拿空地址去 resolve ——
        // 那必然返回「播放地址为空」，用户刚跳完就看到"解析失败"，等于白跳。
        if (playUrl.isBlank()) {
            commit("", "已跳过试播")
            AlertDialog.Builder(this)
                .setTitle(R.string.calib_title_done)
                .setMessage(getString(R.string.calib_saved_noplay) + "\n\n" + summary())
                .setCancelable(false)
                .setNegativeButton(R.string.calib_finish) { _, _ -> finishOk() }
                .show()
            return
        }
        resolvingStarted = true
        renderActions()
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
                        // ⚠️ 这里以前直接是 `setNegativeButton(R.string.cancel, null)` ——
                        // 点"取消"**什么都不写就退出**。用户走完四步、只看到一个疑似失败的面板，
                        // 随手点个"取消"，整场校准就白做了：配方没写、返回码也不是 RESULT_OK，
                        // 界面上再没有任何"规则没保存"的痕迹。
                        // 这正是"校准走完了却没生效"最省事的一种解释 —— 所以必须先确认丢弃。
                        .setNegativeButton(R.string.cancel) { _, _ -> confirmDiscardCalib() }
                        .show()
                }
            }
        }
    }

    /** 校准摘要：把四步学到的东西原样摊给用户看 —— 他才知道"固化"到底固化了什么 */
    private fun summary(): String = getString(
        R.string.calib_done_msg,
        catTpl ?: "—", navSel ?: "—", detailTpl ?: "—", playTpl ?: "—", searchTpl ?: "—"
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
        val skipped = buildList {
            if (skipCat) add("分类")
            if (skipDetail) add("详情")
            if (skipPlay) add("分集")
        }.joinToString("/").ifBlank { "无" }
        val note = "分类形状=" + (catTpl ?: "—") +
                "；分类容器=" + (navSel ?: "—") +
                "；详情=" + (detailTpl ?: "—") +
                "；播放=" + (playTpl ?: "—") +
                "；搜索=" + (searchTpl ?: "—") +
                "；跳过=" + skipped +
                "；$kind；样例=" + short(playUrl)
        RecipeStore.update(site.baseUrl) { r ->
            // 归并逻辑（学到了 / 没学到 / 明确跳过 三种语义）在 SiteCalib 里 ——
            // 纯函数、有离线断言，见 `mergeRecipe`。
            SiteCalib.mergeRecipe(
                cur = r,
                catTpl = catTpl,
                navSel = navSel,
                detailTpl = detailTpl,
                playTpl = playTpl,
                searchTpl = searchTpl,
                skipCat = skipCat,
                skipDetail = skipDetail,
                skipPlay = skipPlay,
                note = note,
                now = System.currentTimeMillis()
            )
        }
        committed = true
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

    /**
     * 「取消」= **丢弃本次校准**，所以必须二次确认（v1.0.32）。
     *
     * 手工点完四步是这个模式里最贵的一段用户操作（分页导航 + 找剧集 + 点分集 + 去搜索）。
     * 旧实现里试着播失败后，一个措辞含糊的"取消"就能把这一切静默丢掉 ——
     * 用户事后只会说「我明明校准过了」，而且没有任何证据留下。
     */
    private fun confirmDiscardCalib() {
        if (committed) {
            finishOk()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("丢弃本次校准？")
            .setMessage(
                "这四步学到的规则**还没有写盘**，丢弃后分类形状/容器、详情模板、播放模板、搜索模板都会丢失。\n\n" +
                        "只是想跳过试播、但要把规则留下，请选「保留规则」。"
            )
            .setPositiveButton("丢弃并退出") { _, _ -> finish() }
            .setNegativeButton("保留规则") { _, _ ->
                commit(playPickAbs, "仅规则")
                finishOk()
            }
            .show()
    }

    /**
     * 只要配方**真的写过**，无论从哪条路退出都回 `RESULT_OK`（v1.0.29）。
     *
     * 旧实现只有 [finishOk] 会 setResult，而用户点左上角返回键（或按系统返回）退出时
     * 返回码是 `CANCELED` ⇒ 站源页的 `onCalibReturned()` 直接 return ⇒
     * 配方其实已经落盘，界面却还是校准前的样子 —— 用户看到的就是"校准结果没生效"。
     */
    override fun finish() {
        if (committed) setResult(RESULT_OK)
        super.finish()
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

    /**
     * v1.0.21：系统返回键 = **网页后退**，不再是退出校准。
     *
     * 第 4 步要求用户"在站内搜一次"—— 从播放页退回首页靠的就是返回键；
     * 旧行为直接 finish()，用户按一下就丢掉前三步的成果（界面上看就是"无法校准"）。
     * 网页退无可退时才真正退出。
     */
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

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
     *
     * v1.0.14 两处修正：
     * 1. **点不到 `<a>` 时也上报**（`link:false`）—— 上一版直接 `return`，
     *    用户点到图标 / 按钮时界面毫无动静，看起来就是"卡住了"。
     * 2. **末尾返回 `'ok'` 供 Kotlin 自检**——注入失败时用户点任何东西都不会有反应，
     *    这种情况必须显式告诉他，而不是让他对着一个死界面点。
     */
    private val PICK_JS = """
        (function(){
          try {
            if (!window.__vsCalib) {
              window.__vsCalib = 1;
              var one = function(e){
                if(!e || !e.tagName) return '';
                if(e.id) return e.tagName.toLowerCase()+'#'+e.id;
                var cs = (e.getAttribute('class')||'').trim().split(/\s+/);
                for(var i=0;i<cs.length;i++){
                  var c = cs[i];
                  if(c && c.length>=3 && c.length<=24 && !/^[0-9]/.test(c)) return e.tagName.toLowerCase()+'.'+c;
                }
                return e.tagName.toLowerCase();
              };
              var cont = function(a){
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
              };
              document.addEventListener('click', function(e){
                var n = e.target, a = null;
                while(n && n !== document.body && !a){
                  if(n.tagName === 'A') a = n;
                  n = n.parentElement;
                }
                if(a && a.target && a.target !== '_self'){ try{ a.target = '_self'; }catch(err){} }
                var href = a ? (a.getAttribute('href') || '') : '';
                var src = a || e.target;
                var text = ((src && src.textContent) || '').replace(/\s+/g,' ').trim().slice(0,40);
                var sel = one(a ? cont(a) : e.target);
                try {
                  VS.onPick(JSON.stringify({href:href, text:text, sel:sel, url:location.href, link: !!a}));
                } catch(err){}
              }, true);
            }
            return 'ok';
          } catch(err) { return 'err:' + err; }
        })();
    """.trimIndent()
}
