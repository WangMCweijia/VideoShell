package com.videoshell.ui

// CalibrateActivity 的**四步状态机**（拆出来的第一块）：跳步 / 渲染 / 重来 / 确认 / 重选。
//
// 为什么这一族放一起：`render`、`renderActions`、`state` 三个都是「把当前 step 摊到界面上」，
// 而 `skipStep` / `restart` / `confirm` / `reselect` 是唯一的四个状态迁移点。
// 拆开就会出现「加了一步、只改了渲染没改迁移」—— 症状是界面提示第 5 步、实际还停在第 4 步。
//
// ⚠️ 四个 `override fun`（onCreate / finish / onBackPressed / onStop / onDestroy）**一个都没搬**：
// 扩展函数不能覆盖成员，搬走等于把生命周期回调整个删掉。区间的上下界就是照着它们切的。
// ⚠️ `Pick` / `NavPick` 两个 private data class、`Bridge`（inner class）、`PICK_JS` 常量也都留下 ——
// 搬运只能搬函数，类的形状（inner / 私有嵌套）一搬就变。它们由 _fixvis 放宽成 internal。
//
// 拆法与约束同 PlayerActivity_Play.kt（纯搬运 + internal 扩展函数）：扩展函数访问不了
// private，所以被它用到的字段/方法在原类里是 internal；守卫按「主文件 + 同主名拆分子文件」
// 读源码，所以断言写的 CalibrateActivity.kt 覆盖本文件。

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

// ------------------------------------------------------------------ 引导文案

/**
 * 跳过当前这一步（v1.0.29）。
 *
 * 跳过 ≠ 什么都不做：它表示「本站确实没有这一步」，所以要**清空**该步的旧规则 ——
 * 否则 [commit] 里的 `?:` 会把上一次校准的残留留着，越校越错。
 */
internal fun CalibrateActivity.skipStep() {
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

internal fun CalibrateActivity.render() {
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
internal fun CalibrateActivity.renderActions() {
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

internal fun CalibrateActivity.state(msg: String) {
    binding.tvState.text = msg
}

internal fun CalibrateActivity.restart() {
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

internal fun CalibrateActivity.confirm() {
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

internal fun CalibrateActivity.reselect() {
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
