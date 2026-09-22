package com.videoshell.ui

import com.videoshell.ui.CalibrateActivity.Pick

import com.videoshell.ui.CalibrateActivity.NavPick

// CalibrateActivity 的**点击拾取与规则反推**（拆出来的第三块）：从用户点的那一下里
// 反推分类页形状 / 导航容器 / 详情页模板 / 播放页模板。
//
// 为什么这一族放一起：四个 pick* 共用同一套「不认也照样推进」的原则 ——
// 校准是「帮用户省事」，不是「卡住用户」（v1.0.20 前这里会把人锁在第一步）。
// 每一处静默 return 都会变成用户眼里的「点了没反应」，所以这一族里的反馈文案
// 和判据是同一个东西，摆在一起才改得对。
//
// ⚠️ `NavPick` 这个 private data class（原 655-656 行）**没有**被搬走（它留在原类里）：
// deriveNavSel（原 830 行，没搬）与 navTail 都要用它 —— 搬到扩展文件里会变成文件级私有，
// 那两个调用点就看不见了。由 _fixvis 放宽成 internal。
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

// ------------------------------------------------------------------ 点击处理

/**
 * 一次网页点击 = **选中候选**（不推进）。推进只发生在用户按「确定」时。
 *
 * 关键：**任何点击都要给出反馈**。上一版对「没有 href」「javascript:」「判据不认」
 * 三种情况一律静默 `return`，用户点半天界面纹丝不动 —— 这就是"卡在第一步"的观感来源。
 * 现在这三种都会在引导卡上写明原因，且已选中的候选不会被一次误点冲掉。
 */
internal fun CalibrateActivity.handlePick(json: String) {
    if (finished || resolvingStarted) return
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return
    val raw = o.optString("href").trim()
    val text = o.optString("text").trim()
    val jsSel = o.optString("sel").trim()
    val page = o.optString("url").trim().ifBlank { pageUrl }

    if (step == SiteCalib.Step.SEARCH) {
        // 第 4 步不用点击判据，但**要靠地址**：搜索大多不换页（AJAX / pushState），
        // 而 pushState 不触发 onPageFinished ⇒ [pageUrl] 会一直停在首页，
        // 于是用户在结果页上点任何东西，我们都记不到"他现在在哪一页"。
        // JS 每次点击都上报 `location.href`，这里就把它收下来。
        // （v1.0.36：这是「填了关键词点确定却永远学不到模板」的直接成因。）
        if (page.isNotBlank()) pageUrl = page
        state(getString(R.string.calib_hint4))
        return
    }
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

    val p = Pick(raw, abs, text, jsSel, page)
    pending = p
    renderActions()
    state(pickedHint(p))
}

/** 选中后的提示：判据认了就报"已选中"，不认就先说清"哪一条没学到、仍可继续"。 */
internal fun CalibrateActivity.pickedHint(p: Pick): String {
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
internal suspend fun CalibrateActivity.pickCategory(p: Pick) {
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

    // ⚠️ 拒收时**容器也不收**（v1.0.34 的实测结论，别改回去）：
    //    用户点的是导航项 `/explore/drama/`，而从**同一次点击**推出来的容器是
    //    `div.app-layout` 这种**页面外壳**，不是分类列表 —— 收下它只会让
    //    HtmlAdapter 的 manualNavSel 分支提前 return，把本来好用的默认判据顶掉。
    //    （野果实测：默认判据给出 40 个真分类。）
    //
    // v1.0.36 改的是**另一件事**：形状没被拒收、只是归纳不出来时（`/riju` 这类
    // 单段别名、maccms 的数字分类 id），容器照推 —— 那时推出来的是真容器。
    // 并且**先试用户点的那一页再试首页**：用户常在二级页面上点分类，
    // 首页里根本没有那条链接，只说"未识别"会让人以为白点了。
    val nav: NavPick? = if (rejected) null else deriveNavSel(p.abs, p.page)
    navSel = if (rejected) null
    else (nav?.sel ?: p.jsSel.takeIf { it.isNotBlank() })
    step = SiteCalib.Step.DETAIL
    render()

    val head = getString(R.string.calib_got_cat, p.text.ifBlank { p.abs })
    val shapeText = when {
        rejected -> "⚠️ 已跳过这条形状（$shape 在本页只命中 $hits 条，判据要求 ≥2）"
        catTpl != null -> getString(R.string.calib_cat_tpl, catTpl!!)
        // 「不需要学」与「没学到」必须分开说。站点自带判据本来就能认的数字分类 id
        // （maccms 的 `/vodshow/id/6.html`）形状归纳不出来，但它根本不需要形状规则。
        HtmlTemplates.isCategoryHref(p.abs, false) -> getString(R.string.calib_cat_shape_builtin)
        else -> getString(R.string.calib_cat_shape_none2)
    }
    // 拒收这条要说清"为什么不写" —— 用户看到"什么都没学到"会以为白点了，
    // 而真相是**故意不写**（写了会把上一次学对的规则挡掉）。
    val tail = if (rejected)
        "它多半是**一个导航项**、不是分类列表 —— 本次**不写入任何分类规则**" +
                "（写了反而会把上一次学对的规则挡掉；容器也只从这一次点击推、那只是页面外壳）。" +
                "本站分类继续走默认判据；要学形状，请返回上一步、点页面上真正的那一串分类标签。"
    else navTail(nav, navSel)
    state("$head　$shapeText　$tail")
}


/**
 * 「容器」那一句人话。
 *
 * 取自**用户点的那一页**时要说明 —— 用户常常是在二级页面上点分类（先点进一个分类、
 * 再点另一个），首页里本来就没有那条链接；把这种情况说成"未识别"会让人以为白点了。
 */
internal fun CalibrateActivity.navTail(nav: NavPick?, fallback: String?): String = when {
    nav != null && nav.fromPage -> getString(R.string.calib_nav_sel_off_page, nav.sel)
    nav != null -> getString(R.string.calib_nav_sel, nav.sel)
    fallback != null -> getString(R.string.calib_nav_sel, fallback)
    else -> getString(R.string.calib_nav_sel_none_tried)
}

/** ② 影片：反推详情页模板。没学到也推进（退用默认逻辑），只有"这像播放页"会额外说一句。 */
internal fun CalibrateActivity.pickDetail(p: Pick) {
    val isPlay = HtmlTemplates.isPlayLink(p.raw) || HtmlTemplates.isPlayLink(p.abs)
    val id = HtmlTemplates.videoIdOf(p.raw, false) ?: HtmlTemplates.videoIdOf(p.abs, false)
    // v1.0.36：学习顺序必须与**运行时**一致 —— HtmlAdapter 学详情模板时用的就是
    // `videoIdOf + detailTplFrom`，再退到 `tplFromNumericSegment`（自研站的 `/…/{id}/`）。
    // 校准这里少写一条，用户点对了却会被告知"没学到"。
    detailTpl = when {
        isPlay -> null
        id != null -> HtmlTemplates.detailTplFrom(p.abs, id)
            ?: HtmlTemplates.tplFromNumericSegment(p.abs)
        else -> HtmlTemplates.tplFromNumericSegment(p.abs)
    }
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
 *
 * v1.0.36：学习顺序与运行时 `learnPlayTpl` 对齐（maccms 形状 → 自研站 numeric segment），
 * 并把"这一页已经记作试播样本"这件事说出来 —— 模板学不到**不等于**这一步白点。
 */
internal fun CalibrateActivity.pickPlay(p: Pick) {
    val t = HtmlTemplates.playTplFrom(p.abs)
        ?: HtmlTemplates.tplFromNumericSegment(p.abs)?.takeIf { it != detailTpl }
    playTpl = t
    playPickAbs = p.abs
    step = SiteCalib.Step.SEARCH
    render()
    state(
        when {
            t != null -> getString(R.string.calib_got_play, t)
            else -> getString(R.string.calib_got_play_sample, short(p.abs)) + "\n" +
                    getString(R.string.calib_play_tpl_none_named)
        }
    )
    binding.webView.loadUrl(site.baseUrl)
}
