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

    internal lateinit var binding: ActivityCalibrateBinding
    internal lateinit var site: SiteConfig

    internal var step = SiteCalib.Step.CAT
    internal var pageUrl = ""

    /**
     * 去广告（v1.0.52）：**默认开**，顶栏上可关。
     *
     * 这一页比嗅探页更需要它：校准学的是**用户点的那个链接的形状**，而广告浮层
     * （盖住正文的浮层、劫持点击的透明层、"3 秒后跳转"的弹窗）会让这次点击落在广告上 ——
     * 于是写进配方的是广告链接的形状，之后这个站"怎么点都不对"，
     * 而所有报错都是绿的。这类错误的排查成本最高，所以默认拦。
     */
    internal var adBlockOn = true

    /** 「已拦截广告跳转」每页只提示一次（弹窗会反复重试） */
    internal var navBlockNotified = false

    /** 三步各自学到的规则 */
    internal var navSel: String? = null
    internal var catTpl: String? = null
    internal var detailTpl: String? = null
    internal var playTpl: String? = null
    /** 第 4 步（可选）学到的搜索模板 */
    internal var searchTpl: String? = null

    /** 第 3 步选中的播放页地址 —— 第 4 步结束时才真正去解析 / 试播 */
    internal var playPickAbs: String = ""

    // ---------------------------------------------------------------- v1.0.29：可跳过某一步
    //
    // 站点之间结构差异极大：有的站没有分类页（第一步无从点起），有的站点封面就直接播放
    // （没有独立详情页，第二步和第三步是同一页）。旧流程强制四步走完，用户只能"随便点一个"
    // 交差 —— 于是把**错误模板**固化进去，接下来解析全用错规则，
    // 表现就是「校准完了结果还是没生效」。

    internal var skipCat = false
    internal var skipDetail = false
    internal var skipPlay = false

    /**
     * 配方**真的被写过**。v1.0.29 修 ③ 用：
     * 旧代码只有 [finishOk] 会 `setResult(RESULT_OK)`，用户点左上角返回时
     * 返回码是 `CANCELED` ⇒ 站源页的 `onCalibReturned()` 不执行 ⇒
     * 配方其实已落盘，界面却还是旧的，用户看到的就是"校准结果没生效"。
     */
    private var committed = false

    /** 第三步走完（别再响应网页点击）；真正收尾看 [resolvingStarted] */
    internal var finished = false

    /** 试播 / 固化流程已启动 —— 按钮退场，别再响应确定 */
    internal var resolvingStarted = false

    /**
     * 当前**已选中但还没确认**的点击。
     *
     * 这是 v1.0.14 的核心状态：点击只把候选放这儿，**推进必须由用户按「确定」**。
     * 上一版是"点完链接判据通过就自动推进"，判据一否决策略性 `return`，
     * 用户既没有按钮可按、也不知道自己在等什么 —— 于是卡死在第一步。
     */
    internal var pending: Pick? = null

    /**
     * 一次点击的原始信息。
     *
     * [page] 是**点击发生时那一页的地址**（JS 报的 `location.href`，比 `pageUrl` 实时 ——
     * SPA 里 `history.pushState` 不触发 `onPageFinished`，`pageUrl` 会停在很久以前那一次）。
     * v1.0.36 起两处依赖它：① 容器反推时可以退到"用户真正点的那一页"；
     * ② 第 4 步点结果页时能记住地址（搜索模板就是从这个地址里学的）。
     */
    internal data class Pick(
        val raw: String,
        val abs: String,
        val text: String,
        val jsSel: String,
        val page: String
    )

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

        // ---- 去广告（v1.0.52）----
        adBlockOn = WebAdBlock.on(this)
        WebAdBlock.reset()
        binding.btnAdBlock.setOnClickListener { toggleAdBlock() }
        renderAdBlockChip()

        // 已校准过的话，把上次的规则先亮出来，方便对照着点
        RecipeStore.load(site.baseUrl)?.let {
            navSel = it.navSel?.takeIf { c -> c.isNotBlank() }
        }

        render()
        setupWebView()
        binding.webView.loadUrl(site.baseUrl)
    }

    /** JS 只做一件事：把用户点了哪个链接报上来。判定全在 Kotlin。 */
    internal inner class Bridge {
        @JavascriptInterface
        fun onPick(json: String) {
            runOnUiThread { handlePick(json) }
        }
    }
    /** 容器反推的结果：[sel] 是选择器，[fromPage] = 取自"用户点的那一页"而不是首页 */
    internal data class NavPick(val sel: String, val fromPage: Boolean)

    // ------------------------------------------------------------------ ④ 搜索校准

    /**
     * 问用户刚才搜的词。留空 = 跳过搜索校准（规则照常固化、照常试播）。
     *
     * v1.0.21：填了词却学不到模板（当前页地址里没有关键词 —— 多半是还没去搜索、
     * 或站点的搜索地址不走 URL）⇒ **留在第 4 步**，让用户去搜完再按一次「确定」。
     *
     * ⚠️ v1.0.36：那条"留在第 4 步"必须**给得出路**。实测用户会卡死在这里：
     * 站点的搜索是 AJAX / pushState（网址不变）⇒ 永远学不到模板；
     * 而唯一能往下走的那个按钮写着「取消」—— 没人会把「取消」理解成
     * 「跳过搜索校准、把前三步固化掉并完成」。于是症状就是用户报的
     * **「填写搜索关键字后点确定，流程不会结束」**。
     */
    internal fun askSearchKeyword() {
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
            // 按钮文案必须是它**实际做的事**：跳过搜索校准 → 固化前三步 → 收尾。
            .setNegativeButton(R.string.calib_search_skip) { _, _ ->
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
        // 取"现在"的地址：WebView 自己的 url 在 pushState 之后会更新，而 [pageUrl]
        // 只由 onPageStarted / onPageFinished / shouldOverrideUrlLoading 喂 ——
        // SPA 的站内搜索三者都不触发 ⇒ 它可能还停在很久以前那个首页上。
        // 两个都试，谁带关键词用谁。
        val now = binding.webView.url.orEmpty().trim().ifBlank { pageUrl }
        val tpl = SiteCalib.searchTplFromUrl(now, kw)
            ?: SiteCalib.searchTplFromUrl(pageUrl, kw)
        if (tpl == null) {
            // ⚠️ 这里**绝不能只改一行状态文本就 return** —— 那正是用户报的「流程不会结束」：
            //    模板学不到时没有任何一条路能走完校准，只能反复按「确定」。
            //    改成一个**必达终点**的选择框：要么现在就完成（前三步规则照常落盘），
            //    要么回网页再搜一次 —— 两条路都不会把人留在这儿。
            state(getString(R.string.calib_search_failed))
            AlertDialog.Builder(this)
                .setTitle(R.string.calib_search_fail_title)
                .setMessage(getString(R.string.calib_search_fail_msg, short(now), kw))
                .setCancelable(false)
                .setPositiveButton(R.string.calib_search_fail_finish) { _, _ ->
                    resolveAndPlay(playPickAbs)
                }
                .setNegativeButton(R.string.calib_search_fail_retry) { _, _ ->
                    state(getString(R.string.calib_search_retrying))
                }
                .show()
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
     * 从抓到的原文 DOM 里反推「分类所在的导航容器」选择器。
     *
     * 逻辑本身在 [SiteCalib]（纯函数、有离线断言）；这里只负责把页面抓下来。
     * 用解析出来的原文 DOM 而不是 WebView 渲染后的 DOM —— 适配器实际用的是前者，
     * 在这里推出来的选择器必须在前者上有效，否则固化了也用不上。
     *
     * v1.0.36：**先试用户点的那一页，再试首页**。旧实现只抓首页 ——
     * 用户一旦在二级页面上点分类（很常见：先点进一个分类、再点另一个），
     * 首页里根本没有那条链接，于是推不出来、界面打出一句「容器未识别」。
     * 那句话描述的是"我们没找到"，不是"它不存在"；能不能找到取决于**找没找对页面**。
     *
     * 首页那一趟不能省：运行时 `HtmlAdapter.categoriesFrom` 拿到的是首页/分类页的 DOM，
     * 容器选择器必须在那上面也有效。所以顺序是「点的那一页 → 首页」，取到即用，
     * 并记下来源（非首页时界面要说明，见 [navTail]）。
     */
    internal suspend fun deriveNavSel(clickedAbs: String, page: String): NavPick? {
        val home = site.baseUrl.trimEnd('/')
        val targets = LinkedHashSet<String>()
        page.trim().takeIf { it.startsWith("http") }?.let { targets += it }
        targets += home
        for (u in targets) {
            val html = runCatching { Http.getOrNull(u, referer = home) }.getOrNull() ?: continue
            val sel = SiteCalib.navSel(html, u, clickedAbs) ?: continue
            return NavPick(sel, fromPage = u.trimEnd('/') != home)
        }
        return null
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

    internal fun absUrl(href: String, page: String): String {
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
    internal fun short(u: String): String = if (u.length <= 56) u else u.take(53) + "..."

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
    internal val PICK_JS = """
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
