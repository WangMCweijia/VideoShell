package com.videoshell.player

// PlayerActivity 的**画中画 · 外挂字幕 · 媒体源构造**（拆出来的第三块）。
//
// 共同点：它们都在给「这一路的播放形态」加东西 —— PiP 窗口比例、字幕轨、包装过的数据源。
// ⚠️ enterPip / pipAspectRatio 与字幕三件套必须同文件：字幕换轨时重建的是**同一份**
// 媒体源（buildSource），而 PiP 比例依赖同一份 lastVideoSize —— 分开就会有人只改一半。
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

// ------------------------------------------------------------------ 画中画（FN-3）

/** 进入画中画：显式按钮 与 离开页面自动进入 共用 */
internal fun PlayerActivity.enterPip() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        toast("系统版本过低，不支持画中画")
        return
    }
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
        toast("当前设备不支持画中画")
        return
    }
    try {
        val params = PictureInPictureParams.Builder().apply {
            pipAspectRatio()?.let { setAspectRatio(it) }
        }.build()
        val ok = enterPictureInPictureMode(params)
        // 返回 false = 系统没让进（分屏、来电等场景），别装作成功
        if (!ok) toast("进入画中画失败：当前状态不允许")
    } catch (e: Exception) {
        toast("进入画中画失败：${e.message}")
    }
}

/**
 * 画中画窗口的纵横比。
 *
 * ⚠️ 不能直接把视频尺寸丢给系统：系统对 PiP 窗口有硬性区间（0.418410 ~ 2.390000），
 * 越界会抛「Aspect ratio is too extreme」并让整个功能失败（竖屏短剧、超宽画幅、
 * 带旋转角度的流都可能越界）。这里统一夹到安全区间内。
 *
 * 拿不到有效尺寸时返回 null —— 不设纵横比，交给系统用默认值，而不是瞎猜一个。
 */
internal fun PlayerActivity.pipAspectRatio(): android.util.Rational? {
    val vs = lastVideoSize ?: return null
    if (vs.width <= 0 || vs.height <= 0) return null
    val raw = vs.width.toFloat() / vs.height.toFloat()
    if (!raw.isFinite() || raw <= 0f) return null
    val safe = raw.coerceIn(PlayerActivity.PIP_MIN_RATIO, PlayerActivity.PIP_MAX_RATIO)
    // Rational 只收整数：乘 1000 保留三位精度足够
    return android.util.Rational((safe * 1000f).roundToInt(), 1000)
}

// ------------------------------------------------------------------ 外挂字幕（FN-5）

internal fun PlayerActivity.pickSubtitle() {
    subtitlePicker.launch(
        arrayOf(
            "application/x-subrip",
            "text/vtt",
            "text/plain",
            "application/octet-stream"
        )
    )
}

/** 重新以「当前进度」为起点重建媒体源并加载字幕（不会从头播） */
internal fun PlayerActivity.applySubtitle(uri: Uri) {
    val p = player ?: return
    if (currentUrl.isBlank()) {
        toast(getString(R.string.subtitle_none))
        return
    }
    val pos = p.currentPosition
    subtitleUri = uri
    val policy = DefaultLoadErrorHandlingPolicy(PlayerActivity.MEDIA_RETRIES)
    val source = buildSource(currentUrl, uri, policy)
    p.setMediaSource(source)
    p.seekTo(pos)
    p.prepare()
    p.playWhenReady = true
    binding.ivPlay.setImageResource(R.drawable.ic_pause)
    toast(getString(R.string.subtitle_loaded))
}

internal fun PlayerActivity.subtitleMime(uri: Uri): String {
    val name = uri.lastPathSegment ?: ""
    return if (name.endsWith(".vtt", true)) MimeTypes.TEXT_VTT
    else MimeTypes.APPLICATION_SUBRIP
}

// ------------------------------------------------------------------ 媒体源构造（含字幕）

internal fun PlayerActivity.buildMediaItem(url: String, sub: Uri?): MediaItem {
    val b = MediaItem.fromUri(url).buildUpon()
    if (sub != null) {
        b.setSubtitleConfigurations(
            listOf(
                MediaItem.SubtitleConfiguration.Builder(sub)
                    .setMimeType(subtitleMime(sub))
                    .setLanguage("zh")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )
        )
    }
    return b.build()
}

internal fun PlayerActivity.buildSource(
    url: String, sub: Uri?, policy: DefaultLoadErrorHandlingPolicy
): androidx.media3.exoplayer.source.MediaSource {
    val item = buildMediaItem(url, sub)
    val factory = OkHttpDataSourceFactory(Http.mediaClient, Http.UA, browserHeaders())
    // DefaultDataSource 按协议分派：http(s)→OkHttp+HlsFix（视频），content/file→系统源（字幕）。
    // 用 DefaultMediaSourceFactory（而非直接 HlsMediaSource.Factory）：它对 HLS 仍走 HlsMediaSource，
    // 但会**额外把 MediaItem 上的外挂字幕轨合并进来**——直连 HlsMediaSource.Factory 不会做这一步。
    val ds = DefaultDataSource.Factory(this, HlsFixDataSourceFactory(factory))
    return DefaultMediaSourceFactory(ds)
        .setLoadErrorHandlingPolicy(policy)
        .createMediaSource(item)
}
