package com.videoshell.player

// SniffActivity 的**候选收集、排序与探测**（拆出来的第二块）。
//
// 从 JS 桥收上来的原始地址 → 解析 → 去重 → 排序 → 真去拉 playlist 验证 → 自动播放。
// 这条链上每一步都会**丢东西**：解析漏一种编码、排序把正片排到广告后面、探测超时；
// 而症状都是同一句「嗅探出来的不对」。摆在同一屏里才看得出是哪一步丢的。
//
// ⚠️ updateStatus()（原 700 行）**没有搬**：它被 onStart/onStop/onDestroy 三个
// 生命周期回调直接调用，留在这里离调用点更近。由 _fixvis 放宽成 internal。
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

internal fun SniffActivity.collectJs() {
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
        // 隐藏样式跟着这一趟一起补：站点自己插的浮层是在加载完之后才出现的（见函数注释）
        injectAdBlockCss()
    }
}

internal fun SniffActivity.parseJs(value: String?): List<String> {
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
internal fun SniffActivity.offer(raw: String) {
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

internal fun SniffActivity.ranked(): List<SniffCandidate> =
    SniffRank.rank(candidates.values, videoId, tsHits)

internal fun SniffActivity.maybeProbeAndAutoPlay() {
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
    if (System.currentTimeMillis() - firstSeenAt < SniffActivity.SETTLE_MS) return
    if (probing || probeRounds >= SniffActivity.MAX_PROBE_ROUNDS) return
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
internal suspend fun SniffActivity.probeTopCandidates() {
    val referer = pageHeaders.entries
        .firstOrNull { it.key.equals("Referer", true) }?.value ?: pageUrl
    var n = 0
    for (c in ranked()) {
        if (n >= SniffActivity.MAX_PROBE) break
        if (c.type != "HLS" && c.type != "DASH") continue
        if (c.suspect) continue                  // 已知广告嫌疑，不值得再花一个请求
        n++
        val text = Http.getPlaylistOnce(c.url, referer) ?: continue
        c.content = SniffRank.verdict(text)
        c.note = SniffRank.describe(text)
    }
}

internal fun SniffActivity.tryAutoPlay() {
    if (autoPlayed) return
    val pick = SniffRank.autoPick(ranked(), probed = probeRounds > 0) ?: return
    autoPlayed = true
    startPlayer(pick)
}
