package com.videoshell.player

// SniffActivity 的**浏览模式与报告**（拆出来的第三块）。
//
// 浏览模式下「识别并添加」「手动校准」共用 [WebSiteKit] 一套判据，
// 而「复制报告」要把这一整轮的关键值摊出来给人看。
// 为什么合成一块：报告里写的就是上面这两条路的结论 —— 报告字段改了、判定没改，
// 用户拿到的就是一份自相矛盾的诊断（比没有诊断更糟）。
//
// ⚠️ LOGIN_WORDS 常量（原 823-826 行）与 maybeDetectLoginWall / jsonString /
// maybeFollowJx（原 829-873 行）都**没有搬** —— 它们属于「登录墙识别」这条独立的判据，
// 两段区间就是绕着它们切的。
// 拆法与约束同上（纯搬运 + internal 扩展函数）。

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
 * 一键「识别并添加」：把当前这一页当成一个视频站，走一遍完整识别并入库。
 *
 * 判据与出口全部在 [WebSiteKit] —— 与「手动校准」共用同一套匹配逻辑，
 * 不会出现"识别说已添加、校准又说找不到"的矛盾。
 */
internal fun SniffActivity.recognizeAndAdd() {
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
        val r = runCatching { WebSiteKit.recognizeAndAdd(this@recognizeAndAdd, url) }.getOrNull()
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
        AlertDialog.Builder(this@recognizeAndAdd)
            .setTitle(R.string.sniffer_grab_ok_title)
            .setMessage(getString(R.string.sniffer_grab_ok_msg, r.message))
            .setPositiveButton(R.string.sniffer_grab_open) { _, _ ->
                startActivity(SiteActivity.intent(this@recognizeAndAdd, r.site.key))
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
internal fun SniffActivity.manualCalibrate() {
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


internal fun SniffActivity.copyReport() {
    val sb = StringBuilder()
    sb.appendLine("===== 嗅探报告 =====")
    sb.appendLine("版本：v${appVersion()}")
    sb.appendLine("标题：$title")
    sb.appendLine("播放页：$pageUrl")
    sb.appendLine("视频 id：${videoId.ifBlank { "(未识别)" }}")
    sb.appendLine("页面错误：${pageError.ifBlank { "无" }}")
    sb.appendLine("登录墙：${if (loginWall) loginWords else "未检测到"}")
    // 去广告拦了什么必须跟着报告一起走：否则"网页里少了个东西"事后分不清是站点的
    // 问题还是我们的问题（被拦的地址本身也已经进了 NetLog 的底下那段）
    sb.appendLine(WebAdBlock.reportLine(adBlockOn))
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

internal fun SniffActivity.appVersion(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
}.getOrDefault("")

internal fun SniffActivity.startPlayer(c: SniffCandidate) {
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
