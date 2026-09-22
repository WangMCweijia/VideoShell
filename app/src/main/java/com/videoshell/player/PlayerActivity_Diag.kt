package com.videoshell.player

// PlayerActivity 的**诊断 · 续播 · 后台通知 · 分享**（拆出来的第二块）。
//
// 共同点：它们都只读「当前在播什么」这一份状态（currentUrl / currentTitle / currentEpKey），
// 与播放器内核无关 —— 崩了、卡了、想分享、想接着上次看，走的是同一批数据。
// 拆法与约束同 PlayerActivity_Play.kt（纯搬运 + `internal fun PlayerActivity.`）。

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.videoshell.App
import com.videoshell.R
import com.videoshell.data.HistEntry
import com.videoshell.data.Library
import com.videoshell.data.Store
import com.videoshell.data.model.MediaSource
import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.Media
import com.videoshell.databinding.ActivityPlayerBinding
import com.videoshell.ui.adapter.EpisodeAdapter
import com.videoshell.util.formatTime
import com.videoshell.util.toast
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun PlayerActivity.showDiag(error: PlaybackException) {
    val chain = generateSequence(error.cause) { it.cause }
    val root = chain.last()
    val httpCode = generateSequence(error.cause) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode
    val sb = StringBuilder()
    sb.append("错误码：").append(error.errorCodeName).append(" (").append(error.errorCode).append(")\n")
    if (httpCode != null) sb.append("HTTP ").append(httpCode).append('\n')
    val detail = listOfNotNull(root.message, root.javaClass.simpleName)
        .joinToString(" / ")
        .take(220)
    if (detail.isNotBlank()) sb.append("原因：").append(detail).append('\n')
    sb.append("地址：").append(currentUrl.take(140))
    if (retried) sb.append("\n（已重试过一次）")
    sb.append("\n数据源：OkHttp（与自检同栈）")
    binding.tvDiag.text = sb.toString()
    binding.tvDiagTitle.text = if (fromSniff && SniffQueue.candidates.isNotEmpty()) {
        getString(R.string.player_error_title) +
            "（源 ${SniffQueue.index + 1}/${SniffQueue.candidates.size}）"
    } else {
        getString(R.string.player_error_title)
    }
    binding.btnDiagNext.visibility =
        if (fromSniff && SniffQueue.hasNext()) View.VISIBLE else View.GONE
    binding.btnDiagSniff.text = getString(
        if (fromSniff && SniffQueue.candidates.isNotEmpty()) R.string.player_error_back_candidates
        else R.string.player_error_sniff
    )
    binding.diagPanel.visibility = View.VISIBLE
    binding.ivPlay.setImageResource(R.drawable.ic_play)
    if (!controllerVisible) setBarsVisible(true)

    // 落一份「播放记录」：用户下次跑站点自检时，报告末尾会自动带上它 ——
    // 这是唯一能把"播放器自己说的那句话"带回来的通道（错误面板只能截图，又长又碎）。
    PlayLog.record(
        "✗ 播放失败 ${error.errorCodeName}(${error.errorCode})" +
            (if (httpCode != null) " HTTP$httpCode" else "") +
            " 原因=" + detail.replace('\n', ' ').take(120) +
            " 地址=" + shorten(currentUrl)
    )

    // 同时打进 logcat（`adb logcat -s VideoShell`）：界面只摘要根因那一行，
    // 日志里有完整 cause 链，排查"到底卡在哪一层"更准。
    android.util.Log.e(
        "VideoShell",
        "播放失败 code=${error.errorCodeName}(${error.errorCode}) url=$currentUrl",
        error
    )

    // ① 来自嗅探：先自动换下一个候选源。
    //    一个播放页往往同时产出正片/广告/预加载线路等多个媒体地址，排序不可能永远对 ——
    //    换一个的成本远低于"退回嗅探页再嗅一次"，这正是"播错了文件"最实用的兜底。
    if (fromSniff && SniffQueue.hasNext() && currentUrl != lastSwitchedFrom) {
        lastSwitchedFrom = currentUrl
        val at = "${SniffQueue.index + 2}/${SniffQueue.candidates.size}"
        showHud("该源不可用，正在换下一个源（$at）…")
        handler.postDelayed({
            if (!isFinishing) nextSniffSource()
        }, 900)
        return
    }

    // ② 直链播不了（CDN 404 / 超时 / 拒绝）就自动改走网页嗅探 ——
    //    用户不必自己判断"是源挂了还是解析错了"，换条路能把片放出来才是目的。
    if (shouldAutoSniff(error)) {
        autoSniffTried = true
        val page = fallbackPage
        showHud("直链不可用，正在改用网页嗅探…")
        handler.postDelayed({
            startActivity(SniffActivity.intent(this, page, currentTitle, headers))
            finish()
        }, 1200)
    }
}

// ------------------------------------------------------------------ 诊断复制

/** 把当前播放诊断 + 网络记录 + 播放记录一起复制走 */
internal fun PlayerActivity.copyDiag() {
    val sb = StringBuilder()
    sb.appendLine("===== 播放诊断 =====")
    sb.appendLine("版本：v${appVersion()}")
    sb.appendLine("来源：${if (fromSniff) "网页嗅探（候选 ${SniffQueue.candidates.size} 个）" else "直链/解析"}")
    sb.appendLine("播放页：${fallbackPage.ifBlank { "-" }}")
    sb.appendLine("本次已重试：$retried")
    sb.appendLine("--- 错误面板 ---")
    sb.appendLine(binding.tvDiag.text)
    sb.appendLine()
    sb.appendLine("---------- HTTP 记录 ----------")
    sb.appendLine(NetLog.report())
    sb.appendLine()
    sb.appendLine("---------- 播放记录 ----------")
    sb.appendLine(PlayLog.report())
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("播放诊断", sb.toString()))
    toast(getString(R.string.player_diag_copied))
}

internal fun PlayerActivity.appVersion(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
}.getOrDefault("?")

/** 地址太长会撑爆错误面板与记录，中段省略 */
internal fun PlayerActivity.shorten(u: String): String =
    if (u.length <= 110) u else u.take(70) + "…" + u.takeLast(30)

// ------------------------------------------------------------------ 换源

/** 换到下一个嗅探候选源；没有更多候选就返回 false */
internal fun PlayerActivity.nextSniffSource(): Boolean {
    val next = SniffQueue.advance() ?: return false
    retried = false
    autoSniffTried = false
    lastSwitchedFrom = ""
    playUrl(next.url)
    return true
}

/**
 * 是否值得自动降级到嗅探。
 * 只对**网络/HTTP 层**错误降级（这些换条路往往能成）；
 * 解码器/格式错误降级也没用，就别折腾用户了。
 */
internal fun PlayerActivity.shouldAutoSniff(error: PlaybackException): Boolean {
    if (autoSniffTried) return false
    if (fallbackPage.isBlank()) return false
    var c: Throwable? = error
    var depth = 0
    while (c != null && depth++ < 8) {
        if (c is HttpDataSource.InvalidResponseCodeException) return true
        if (c is java.io.IOException) return true
        c = c.cause
    }
    return false
}

// ------------------------------------------------------------------ HUD / 进度记忆

internal fun PlayerActivity.showHud(text: String, autoHideIt: Boolean = true) {
    binding.tvHud.text = text
    binding.tvHud.visibility = View.VISIBLE
    handler.removeCallbacks(hideHud)
    if (autoHideIt) handler.postDelayed(hideHud, 900)
}

internal fun PlayerActivity.isLandscape(): Boolean =
    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * 当前这一集的进度身份。**进度必须挂在"哪一部剧的哪一集"上，不能挂在媒体地址上。**
 *
 * 之前的写法是 `"resume_${url.hashCode()}"`，有两个死穴：
 * 1. **串台**：短剧站很常见"整部剧共用一条 m3u8"（或同一播放页换集），
 *    那么所有集的媒体地址**完全一样** ⇒ 同一个 key ⇒ 记住 A 的进度，播 B 也从这个点续播。
 *    URL 只是"从哪取流"，不是"这一集是谁"，拿它当身份本身就错了。
 * 2. **丢进度**：带时效签名的直链每次解析都不同，hash 自然每次都变，存了也读不回来。
 *
 * 改为「站点 + 剧名 + 集名 + 组内序号」：
 * - v1.0.18 只用集名，被骚火打脸 —— 该站全集集名都是「高清」⇒ 52 集共用一个槽位，
 *   看到接近片尾切下一集，位置灌给全集 ⇒「所有集都从末尾快结束时播」。
 * - 补组内序号后每集必有唯一槽位；同序号跨线路天然共享（两条线的第 5 集都是 index 4），
 *   换线路续播的语义保留。
 */

internal fun PlayerActivity.episodeKey(siteKey: String, show: String, epName: String, epIdx: Int): String =
    "resume_" + Media.digest("$siteKey|$show|${epName}#$epIdx")

/** 无剧集上下文（直链播放 / 嗅探进来）时的兜底：按地址摘要记。digest 取代 32 位 hashCode。 */
internal fun PlayerActivity.urlKey(url: String): String = "resume_url_" + Media.digest(url)

/** 有剧集身份就用剧集身份，否则退回地址摘要 */
internal fun PlayerActivity.activeKey(): String = currentEpKey.ifBlank { urlKey(currentUrl) }

internal fun PlayerActivity.resumePosition(key: String): Long {
    if (!sp.getBoolean(PlayerActivity.KEY_RESUME, true)) return 0L
    val v = runCatching { sp.getLong(key, 0L) }.getOrDefault(0L)
    return if (v > 10_000L) v else 0L   // 少于 10 秒不值得续播
}

internal fun PlayerActivity.savePosition() {
    if (!sp.getBoolean(PlayerActivity.KEY_RESUME, true)) return
    val p = player ?: return
    if (currentUrl.isBlank() && currentEpKey.isBlank()) return
    val pos = p.currentPosition
    val dur = p.duration
    val key = activeKey()
    if (pos < 15_000L || (dur > 0 && pos > dur - 15_000L)) {
        // 刚开始看 / 已经看到尾：清掉记忆，下次从头播
        sp.edit().remove(key).apply()
    } else {
        sp.edit().putLong(key, pos).apply()
    }
}

/**
 * 写播放历史：在进度落盘的同一时机（onStop / 换集前）调用。
 * 无剧集上下文（直链 / 嗅探）不记 —— 没法回跳详情页。
 */
internal fun PlayerActivity.recordHistory() {
    if (PlayQueue.title.isBlank() || PlayQueue.siteKey.isBlank()) return
    val ep = PlayQueue.current() ?: return
    val p = player
    val pos = p?.currentPosition ?: 0L
    val dur = p?.duration ?: 0L
    Library.addHistory(
        this,
        HistEntry(
            siteKey = PlayQueue.siteKey,
            vid = PlayQueue.vid,
            name = PlayQueue.title,
            pic = PlayQueue.pic,
            ep = ep.name,
            epIdx = PlayQueue.episodeIndex,
            pos = pos,
            dur = dur,
            ts = System.currentTimeMillis()
        )
    )
}

// ------------------------------------------------------------------ 新增功能：画中画 / 字幕 / 投屏 / 分享 / 后台通知

/** 后台播放开关（FN-4），与「我的」页设置同源 */
internal fun PlayerActivity.bgPlayEnabled(): Boolean = sp.getBoolean(App.KEY_BG_PLAY, false)

/**
 * 媒体通知同步（FN-4）。
 * 后台播放关、或播放已结束（keepNotification=false）→ 撤掉通知；
 * 否则按当前播放态启动/刷新前台通知（图标随播放/暂停翻转）。
 */
internal fun PlayerActivity.syncMediaNotification() {
    if (!bgPlayEnabled() || !keepNotification) {
        MediaNotificationService.stop(this)
        return
    }
    MediaNotificationService.start(this, currentTitle, player?.isPlaying == true)
}

internal fun PlayerActivity.registerNotifReceiver() {
    val filter = IntentFilter().apply {
        addAction(MediaNotificationService.ACTION_PLAY)
        addAction(MediaNotificationService.ACTION_PAUSE)
        addAction(MediaNotificationService.ACTION_STOP)
    }
    ContextCompat.registerReceiver(
        this, notifReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
    )
}

// ------------------------------------------------------------------ 分享（FN-9）

internal fun PlayerActivity.showShare() {
    if (currentUrl.isBlank()) {
        toast("还没有可分享的播放地址")
        return
    }
    val items = arrayOf(
        getString(R.string.share_copy_url),
        getString(R.string.share_video)
    )
    AlertDialog.Builder(this)
        .setTitle(R.string.share)
        .setItems(items) { _, which ->
            when (which) {
                0 -> copyPlayUrl()
                1 -> shareVideo()
            }
        }
        .show()
}

internal fun PlayerActivity.copyPlayUrl() {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("播放地址", currentUrl))
    toast(getString(R.string.share_copied))
}

internal fun PlayerActivity.shareVideo() {
    val text = if (fallbackPage.isNotBlank()) "$currentTitle\n$fallbackPage"
    else "$currentTitle\n$currentUrl"
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(i, getString(R.string.share_video)))
}
