package com.videoshell.player

// PlayerActivity（内置播放器）的**播放核心**：建播放器 / 拉流 / 手势 / 选集 / 卡顿自愈。
//
// 为什么拆出来：这个类原来 1692 行，改一处播放逻辑要在同一屏里穿越建播放器、手势、
// 选集、看门狗四段互不相关的代码 —— 真正的改动点被淹没，评审时也看不出「这次动了哪一块」。
// 拆法是**纯搬运**（一行逻辑都没改）：方法变成本文件里的 `PlayerActivity` 扩展函数，
// 类本身只留「界面 / 生命周期 / 控制条」。
//
// ⚠️ 扩展函数访问不了 `private` 成员，所以被它用到的字段/方法在 PlayerActivity 里是
// `internal`。这是这次拆分唯一放宽的东西 —— 详见 docs/PITFALLS.md §4.42。

// ⚠️ 守卫怎么还能锁到这些代码：tools/verify 的源码守卫按「主文件 + 同主名拆分子文件」
// 读源码（见 _cp.py 的 kt()），所以断言里写的 PlayerActivity.kt 覆盖本文件。

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
import com.videoshell.data.pan.PanResolver
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.Media
import com.videoshell.data.site.MirrorRace
import com.videoshell.databinding.ActivityPlayerBinding
import com.videoshell.ui.adapter.EpisodeAdapter
import com.videoshell.ui.askDriveLogin
import com.videoshell.util.formatTime
import com.videoshell.util.toast
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ------------------------------------------------------------------ 播放器

internal fun PlayerActivity.setupPlayer() {
    // 缓冲放宽：不少源是"扁平 TS + 单码率"，链路一抖就得等；给足缓冲比尽快报错更友好。
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(30_000, 90_000, 2_500, 5_000)
        .build()
    val p = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
    player = p
    binding.playerView.player = p

    p.addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            binding.pbBuffering.visibility =
                if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_READY) {
                resetErrorState()
                panReResolveTried = false
                if (!readyLogged) {
                    readyLogged = true
                    PlayLog.record("✓ 播放就绪 ${shorten(currentUrl)}")
                }
                // v1.0.21：续播点失效守卫。seekTo 发生在 prepare 之前，此刻流的真实时长
                // 还未知 —— 若存的续播点**超出本条流**（站点截断/换源后时长变短/旧版残留），
                // ExoPlayer 会把它钳到片尾，用户看到的就是「一打开就从末尾快结束时播」。
                // 在第一个 READY 时核对一次：落点掉进最后 15 秒 ⇒ 判定失效，回 0 从头播。
                if (appliedResume > 0L) {
                    val dur = p.duration
                    if (dur > 0L && Media.resumeAtEnd(p.currentPosition, dur)) {
                        PlayLog.record(
                            "⚠ 续播点 ${appliedResume}ms 落在本条流（时长 ${dur}ms）末尾 —— 已失效，从头播放"
                        )
                        p.seekTo(0L)
                        showHud(getString(R.string.hud_resume_dead))
                    } else if (dur > 0L) {
                        PlayLog.record("续播 ${appliedResume}ms / 时长 ${dur}ms")
                    }
                    appliedResume = 0L
                }
            }
            if (playbackState == Player.STATE_ENDED) {
                if (PlayQueue.hasNext() && sp.getBoolean(PlayerActivity.KEY_AUTO_NEXT, true)) {
                    playEpisode(PlayQueue.episodeIndex + 1)
                } else {
                    // 自然结束且无下一集：通知没用了，及时撤掉
                    keepNotification = false
                    syncMediaNotification()
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            lastVideoSize = videoSize
            applyResLabel(videoSize)
            applyVideoOrientation(videoSize)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.ivPlay.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            scheduleHide()
            syncMediaNotification()
        }

        override fun onPlayerError(error: PlaybackException) {
            binding.pbBuffering.visibility = View.GONE
            // 网盘媒体直链 403（v1.0.69）：`__puus` 是滚动凭据，落盘快照旧了就是全分片 403。
            // 重新解析一次 = 重走 save→play，服务端会随响应下发新凭据、媒体头随之更新。
            // 只自动做一次（[panReResolveTried] 在 READY 才复位）——再做还是 403 就交给面板，
            // 无限自愈只会把"真的没凭据"演成无限转圈。普通站点流的 403 不走这里：
            // 那是防盗链/内容下架，重解析救不了，也不该把转圈拉长。
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                !panReResolveTried && PanResolver.isPanMediaUrl(currentUrl)
            ) {
                panReResolveTried = true
                PlayLog.record("⚠ 网盘直链 403 ⇒ 自动重新解析一次（凭据已滚动，重换取新凭据）")
                handler.postDelayed(
                    { if (!isFinishing) playEpisode(PlayQueue.episodeIndex, autoHeal = true) },
                    300
                )
                return
            }
            showDiag(error)
        }
    })
}

internal fun PlayerActivity.resetErrorState() {
    binding.diagPanel.visibility = View.GONE
}

internal fun PlayerActivity.playUrl(rawUrl: String, fromRetry: Boolean = false) {
    val p = player ?: return
    if (rawUrl.isBlank()) return
    // 统一把非 ASCII 路径编码成 %XX 再交给播放器。
    // 播放器底层是 HttpURLConnection，不像 OkHttp 那样自动百分号编码 ——
    // 中文路径会被原样塞进请求行，CDN 查不到资源直接 404（表现为"所有剧集都播不了"）。
    val url = Media.encodeUrl(rawUrl)
    currentUrl = url
    if (url != rawUrl) android.util.Log.w("VideoShell", "playUrl 非 ASCII 已编码：$rawUrl -> $url")
    if (!fromRetry) {
        autoSniffTried = false
        stalledSwitched = false
    }
    // 看门狗重新开始计：换源/重试之后上一次的"卡住"结论不再适用
    lastBuffered = -1L
    stallSince = 0L

    readyLogged = false
    keepNotification = true
    // 上一集的画面尺寸不能留给下一集：PiP 会拿它当纵横比，脏值会把窗口比例带偏。
    lastVideoSize = null
    PlayLog.newSession()
    PlayLog.record("▶ 开始播放 ${shorten(url)}" + if (headers.isEmpty()) "" else "  头=${headers.keys.joinToString(",")}")

    // 数据源在 [buildSource] 里统一构造：http(s)→OkHttp+HlsFix（与解析/嗅探同栈，且保留 HLS 规范化），
    // content/file→系统数据源（外挂字幕是 content://，必须由它能读）。
    val policy = DefaultLoadErrorHandlingPolicy(if (fromRetry) PlayerActivity.MEDIA_RETRIES + 2 else PlayerActivity.MEDIA_RETRIES)

    val source = buildSource(url, subtitleUri, policy)

    val resume = if (fromRetry) 0L else resumePosition(activeKey())
    p.setMediaSource(source)
    p.playbackParameters = PlaybackParameters(PlayerActivity.SPEEDS[speedIndex])
    p.volume = if (muted) 0f else 1f
    if (resume > 0L) {
        p.seekTo(resume)
        showHud(getString(R.string.hud_resume, formatTime(resume)))
    }
    p.prepare()
    p.playWhenReady = true
    binding.ivPlay.setImageResource(R.drawable.ic_pause)
    binding.tvPos.text = "00:00"
    binding.tvDur.text = "00:00"
    binding.seekBar.progress = 0
}

/**
 * 播放器实际发出的请求头（v1.0.38）：在调用方给的头之外补上 `Origin`。
 *
 * 为什么补这个：同一个地址"网页端能播、App 里不能播"时，**两边的请求到底差在哪**
 * 是唯一真正要回答的问题。hls.js 走 XHR/fetch ⇒ 浏览器必然带 `Origin: <页面源>`，
 * 而部分 CDN / WAF 拿它做防盗链校验 —— 只带 Referer、不带 Origin 的请求会被挂住，
 * 表现就是"网页端同一个链接能播，App 里一直转圈"。
 *
 * ⚠️ **只有 http(s) 的播放页才配当 Origin / Referer 的来源**（v1.0.78，§4.78）。
 * 网盘直连的"播放页"是 `panref://quark/{share}/{fid}/{token}` —— 它压根不是网页，
 * 拿它去算会发出 `Origin: panref://quark`（scheme 都不是 http 的**非法 Origin**），
 * CDN/WAF 直接 403。自检 [9] 用同一套 DataSource、**只发 Cookie** 实测 200 ⇒
 * 网盘这条链路就按"只发 Cookie"走，与已被证明能播的那套头逐字一致。
 *
 * 只补**缺失**的，绝不覆盖调用方已经设好的值（那些值是按站点试出来的）。
 */
internal fun PlayerActivity.browserHeaders(): Map<String, String> {
    val h = HashMap(headers)
    val page = fallbackPage.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    if (h.keys.none { it.equals("Origin", true) }) {
        page?.let { originOf(it) }?.let { h["Origin"] = it }
    }
    if (h.keys.none { it.equals("Referer", true) } && page != null) {
        h["Referer"] = page
    }
    return h
}

/**
 * `https://a.b/c?d` → `https://a.b`。拿不到就返回 null —— 宁可少发一个头，也不发假值。
 *
 * ⚠️ **非 http(s) 一律 null**（v1.0.78，§4.78）：`panref://quark/…` 曾被算成
 * `panref://quark` 当 Origin 发出去（真机：网盘直连播放全 403，见 §4.78）。
 * 判据与 [com.videoshell.data.site.WebSiteKit.originOf] 保持同一条。
 */
internal fun PlayerActivity.originOf(url: String): String? = runCatching {
    val u = java.net.URI(url)
    val s = u.scheme ?: return@runCatching null
    if (!s.equals("http", true) && !s.equals("https", true)) return@runCatching null
    if (u.authority == null) null else "$s://${u.authority}"
}.getOrNull()

internal fun PlayerActivity.refreshProgress() {
    val p = player ?: return
    watchStall()
    if (!dragging) {
        val pos = p.currentPosition.coerceAtLeast(0L)
        val dur = p.duration.let { if (it > 0) it else 0L }
        binding.tvPos.text = formatTime(pos)
        binding.tvDur.text = formatTime(dur)
        if (dur > 0) {
            binding.seekBar.max = dur.toInt().coerceAtLeast(1)
            binding.seekBar.progress = pos.toInt().coerceAtMost(binding.seekBar.max)
        }
    }
    if (binding.pbBuffering.visibility != View.VISIBLE &&
        p.playbackState == Player.STATE_BUFFERING
    ) {
        binding.pbBuffering.visibility = View.VISIBLE
    } else if (p.playbackState != Player.STATE_BUFFERING) {
        binding.pbBuffering.visibility = View.GONE
    }
}

internal fun PlayerActivity.seekPreview(delta: Long) {
    val p = player ?: return
    val dur = p.duration.let { if (it > 0) it else 0L }
    val target = (p.currentPosition + delta).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
    showHud("${formatTime(target)} / ${formatTime(dur)}", false)
}

internal fun PlayerActivity.seekCommit(delta: Long) {
    val p = player ?: return
    val dur = p.duration.let { if (it > 0) it else 0L }
    val target = (p.currentPosition + delta).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
    p.seekTo(target)
    showHud("已跳转 ${formatTime(target)}")
}

// ------------------------------------------------------------------ 手势

internal fun PlayerActivity.setupGestures() {
    binding.gesture.onSingleTap = {
        setBarsVisible(!controllerVisible)
        scheduleHide()
    }
    binding.gesture.onDoubleTap = {
        togglePlay()
        setBarsVisible(true)
    }
    binding.gesture.onSeekPreview = { delta -> seekPreview(delta) }
    binding.gesture.onSeekCommit = { delta -> seekCommit(delta) }
    binding.gesture.onBrightnessDelta = { d ->
        val attrs = window.attributes
        bright = (bright + d).coerceIn(0.02f, 1f)
        attrs.screenBrightness = bright
        window.attributes = attrs
        showHud("亮度 ${(bright * 100).roundToInt()}%", false)
    }
    binding.gesture.onVolumeDelta = { d ->
        muted = false
        binding.ivMute.setImageResource(R.drawable.ic_volume)
        volFloat = (volFloat + d).coerceIn(0f, 1f)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volFloat * max).roundToInt(), 0)
        showHud("音量 ${(volFloat * 100).roundToInt()}%", false)
    }
    binding.gesture.onGestureEnd = { handler.removeCallbacks(hideHud); hideHud.run() }
    // 长按临时加速，松手恢复
    binding.gesture.onLongPressStart = {
        player?.playbackParameters = PlaybackParameters(PlayerActivity.LONG_PRESS_SPEED)
        showHud("${PlayerActivity.LONG_PRESS_SPEED}x 加速中", false)
    }
    binding.gesture.onLongPressEnd = {
        player?.playbackParameters = PlaybackParameters(PlayerActivity.SPEEDS[speedIndex])
        handler.removeCallbacks(hideHud)
        hideHud.run()
    }
}

internal fun PlayerActivity.switchSpeed() {
    speedIndex = (speedIndex + 1) % PlayerActivity.SPEEDS.size
    val s = PlayerActivity.SPEEDS[speedIndex]
    player?.playbackParameters = PlaybackParameters(s)
    binding.tvSpeed.text = if (s == s.toInt().toFloat()) "${s.toInt()}.0x" else "${s}x"
    showHud(binding.tvSpeed.text.toString())
}

// ------------------------------------------------------------------ 选集

internal fun PlayerActivity.setupEpisodes() {
    val eps = PlayQueue.episodes()
    if (eps.isEmpty()) {
        binding.btnEpisodes.visibility = View.GONE
        binding.ivPrev.visibility = View.GONE
        binding.ivNext.visibility = View.GONE
        return
    }
    binding.btnEpisodes.visibility = View.VISIBLE
    binding.tvPanelTitle.text = (PlayQueue.title.ifBlank { "选集" }) + " · 共 ${eps.size} 集"
    // 列数不在这里定：submitEpisodes() 会按整列名字算出合适的列数并重设
    // layoutManager（综艺长名 5→3 列）。这里只挂 adapter，避免两处各写一份列数。
    binding.rvEpisodes.adapter = episodeAdapter
    // 显示顺序（正序/倒序）与详情页共用同一个偏好；选中态永远是**组内原始序号**
    binding.tvOrder.text = getString(if (Store.episodeDesc(this)) R.string.order_desc else R.string.order_asc)
    binding.tvOrder.setOnClickListener { toggleEpisodeOrder() }
    submitEpisodes()
    binding.btnEpisodes.setOnClickListener {
        val show = binding.episodePanel.visibility != View.VISIBLE
        binding.episodePanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            setBarsVisible(true)
            handler.removeCallbacks(autoHide)
        } else {
            scheduleHide()
        }
    }
}

/**
 * 按当前偏好提交分集列表，并把正在播的那一集标出来。
 *
 * v1.0.49：列数与「是否两行」走与详情页**同一个**决策函数
 * （EpisodeCell.plan，按整列名字算）—— 同一个剧在详情页和播放页必须长得一样，
 * 各写一套（以前这里是写死的 `if (isLandscape()) 8 else 5`）迟早会不一致。
 */
internal fun PlayerActivity.submitEpisodes() {
    val eps = PlayQueue.episodes()
    val plan = com.videoshell.util.EpisodeCell.plan(eps, isLandscape())
    binding.rvEpisodes.layoutManager = GridLayoutManager(this, plan.cols)
    episodeAdapter.submit(eps, Store.episodeDesc(this), plan.twoLine)
    episodeAdapter.select(PlayQueue.episodeIndex)
}

internal fun PlayerActivity.toggleEpisodeOrder() {
    val desc = !Store.episodeDesc(this)
    Store.setEpisodeDesc(this, desc)
    binding.tvOrder.text = getString(if (desc) R.string.order_desc else R.string.order_asc)
    submitEpisodes()
    showHud(getString(if (desc) R.string.order_hud_desc else R.string.order_hud_asc))
    // 换了顺序，列表从头看起更顺（选中项可能已经跑到很后面）
    binding.rvEpisodes.scrollToPosition(0)
}

internal fun PlayerActivity.stepEpisode(delta: Int) {
    val list = PlayQueue.episodes()
    if (list.isEmpty()) return
    val target = PlayQueue.episodeIndex + delta
    if (target < 0) {
        showHud("已经是第一集")
        return
    }
    if (target >= list.size) {
        showHud("已经是最后一集")
        return
    }
    playEpisode(target)
}

/**
 * 播放第 [index] 集。
 *
 * @param autoHeal 由「卡住」看门狗发起（v1.0.39）。**必须与用户主动换集区分开**：
 *   自愈走的也是这条路，若在这里清零 [stallReResolveTries]，一次卡住会被无限重解析下去。
 *   所以只有用户发起的（默认 false）才清零，也就是"用户动一下 = 给一次新的自愈预算"。
 */
internal fun PlayerActivity.playEpisode(index: Int, autoHeal: Boolean = false) {
    // 扩展函数里没有 `this@PlayerActivity` 这个标签，先取一份 receiver 供内部 lambda 用
    val self = this
    val ep = PlayQueue.episodes().getOrNull(index) ?: return
    if (!autoHeal) stallReResolveTries = 0
    // ⚠️ 换集前先把"正在看的这一集"的进度落盘。之前只在 onStop 写盘，
    // 而 Activity 内部换集不会走 onStop ⇒ 上一集看到一半的位置直接丢。
    savePosition()
    recordHistory()
    PlayQueue.episodeIndex = index
    // 进度身份切到新的一集（此刻才换 key，保证上面那次 savePosition 写的是旧集）
    currentEpKey = episodeKey(PlayQueue.siteKey, PlayQueue.title, ep.name, index)
    // 换了集，上一集的嗅探候选就作废了
    SniffQueue.clear()
    fromSniff = false
    lastSwitchedFrom = ""
    episodeAdapter.select(index)
    binding.episodePanel.visibility = View.GONE
    currentTitle = "${PlayQueue.title} ${ep.name}".trim()
    // 新一集是新的流：分辨率标签清空（等 onVideoSizeChanged 重新取），自动横竖屏复位
    resLabel = ""
    orientAuto = true
    applyTitle()
    fallbackPage = ep.url

    val site = PlayQueue.siteKey.takeIf { it.isNotBlank() }?.let { Store.find(this, it) }
    // 没有站点也可以播 —— 但**只限网盘链接**（用户直接粘了一条分享链接，或从网盘详情页
    // 进来）。普通地址仍然照旧直接交给播放器，不走解析（省一次协程与一次网络）。
    if (site == null && !PanResolver.handles(ep.url)) {
        currentMime = null
        playUrl(ep.url)
        return
    }
    lifecycleScope.launch {
        binding.pbBuffering.visibility = View.VISIBLE
        val r = runCatching {
            // MirrorRace.of：站点若带备用地址，这里用"当前最快的那个"去解析集地址（v1.0.67）。
            // **必须在 resolve 之前**：解析链里那些页面地址都是用 site.baseUrl 拼的，
            // 挑出来的地址晚一步套上就等于这一整条链路还打在死域名上。
            if (site != null) AdapterFactory.create(MirrorRace.of(site)).resolve(ep)
            else PanResolver.resolve(ep.url)
        }.getOrNull()
        binding.pbBuffering.visibility = View.GONE
        when (r) {
            is MediaSource.Direct -> {
                headers = r.headers
                currentMime = r.mimeType
                retried = false
                playUrl(r.url)
            }
            is MediaSource.Sniff -> {
                startActivity(
                    SniffActivity.intent(
                        self,
                        r.pageUrl,
                        binding.tvTitle.text.toString(),
                        r.headers
                    )
                )
                finish()
            }
            // v1.0.65：网盘未登录。给一个**能直接走的下一步**（跳账号页），
            // 而不是只弹一句 toast —— 用户知道"要登录"却不知道去哪儿登，等于没说。
            is MediaSource.NeedLogin -> askDriveLogin(r)
            // 解析失败也要落一份播放记录：站点自检**只能跑站点、不能指定某一部**，
            // 而报告尾部会带上 PlayLog —— 这条就是"把某一部的失败原因带回来"的唯一出口。
            // 这里原先只弹 toast（不进 PlayLog），于是恰恰是**解析失败**没有出口。
            is MediaSource.Error -> {
                toast(r.message)
                PlayLog.record("✗ 解析失败：${r.message}")
            }
            null -> toast("解析播放地址失败")
        }
    }
}

/**
 * 「直接播放」粘贴了一条网盘分享链接（v1.0.65）。
 *
 * 走到这里说明 [com.videoshell.data.pan.PanResolver.handles] 认了这条地址，
 * 所以必须先在网盘层解析成直链，再交给播放器 —— 直接把 `pan.quark.cn/s/…` 丢给 ExoPlayer
 * 只会得到一个 HTML 页面和一句"无法播放"。
 */
internal fun PlayerActivity.resolvePanInline(url: String) {
    lifecycleScope.launch {
        binding.pbBuffering.visibility = View.VISIBLE
        val r = runCatching { PanResolver.resolve(url) }.getOrNull()
        binding.pbBuffering.visibility = View.GONE
        when (r) {
            is MediaSource.Direct -> {
                headers = r.headers
                currentMime = r.mimeType
                retried = false
                playUrl(r.url)
            }
            is MediaSource.NeedLogin -> askDriveLogin(r)
            is MediaSource.Error -> {
                toast(r.message)
                PlayLog.record("✗ 网盘解析失败：${r.message}")
            }
            else -> toast("解析播放地址失败")
        }
    }
}

// ------------------------------------------------------------------ 错误诊断

// ------------------------------------------------------------------ 「卡住」看门狗（v1.0.38）

/**
 * 用户报的现象：**播放器能读出本集时长，但画面一直转圈**。
 *
 * 这类情况 ExoPlayer 既不报错也不前进 —— 它的默认加载策略遇到取不到的分片会**静默重试**
 * （十次、退避到 5 秒一轮，加起来四十多秒一句话都不说）。用户那边看到的就是
 * 一个没有任何信息的转圈，而"转圈"这两个字里没有任何可查的东西。
 *
 * 判据只用一条**观测得到的事实**：`STATE_BUFFERING` 持续 [STALL_MS] 而
 * `bufferedPosition` 一直没变。不猜原因，只负责把这件事说出来 ——
 * 能确定的东西（是否被判成直播、缓冲到哪、当前地址）一起摆出来。
 */
internal fun PlayerActivity.watchStall() {
    val p = player ?: return
    if (p.playbackState != Player.STATE_BUFFERING) {
        lastBuffered = -1L
        stallSince = 0L
        return
    }
    val buffered = p.bufferedPosition
    if (stallSince == 0L || buffered != lastBuffered) {
        lastBuffered = buffered
        stallSince = System.currentTimeMillis()
        return
    }
    if (System.currentTimeMillis() - stallSince < PlayerActivity.STALL_MS) return
    if (binding.diagPanel.visibility == View.VISIBLE) return   // 面板已在提示，别叠加
    stallSince = System.currentTimeMillis()                     // 重置，免得反复弹
    onStalled(buffered)
}

internal fun PlayerActivity.onStalled(buffered: Long) {
    val live = runCatching { player?.isCurrentMediaItemLive == true }.getOrDefault(false)
    val dur = player?.duration?.let { if (it > 0) it else 0L } ?: 0L
    PlayLog.record(
        "⚠ 播放卡住：缓冲 ${buffered}ms / 共 ${dur}ms，isLive=$live 地址=" + shorten(currentUrl)
    )
    // 同时打进 logcat（`adb logcat -s VideoShell`）：界面只给结论，日志留证据
    android.util.Log.w(
        "VideoShell", "播放卡住 buffered=$buffered dur=$dur live=$live url=$currentUrl"
    )
    // 还有下一路候选就直接换 —— 用户要的是"能播"，不是看我们解释
    if (fromSniff && SniffQueue.hasNext() && !stalledSwitched && currentUrl != lastSwitchedFrom) {
        stalledSwitched = true
        lastSwitchedFrom = currentUrl
        showHud(getString(R.string.stall_switched))
        handler.postDelayed({ if (!isFinishing) nextSniffSource() }, 300)
        return
    }
    // 非嗅探源（直链 / 解析出来的地址）卡住 ⇒ **重新解析本集**（v1.0.39）。
    //
    // 为什么是「重新解析」而不是「把同一个地址再试一遍」：这类线路（枫叶影院的 ps:1 线路
    // 就是）的播放地址是**播放时现取**的，地址里带 psid / auth_key 这种**按会话、按时间**
    // 的令牌。令牌一旦作废，playlist 往往早就拿到手了（所以**时长显示正常**），
    // 但分片会整片取不到 —— 正是用户说的「能读到时長、一直转圈」。
    // 同一个地址再取一遍是白费（令牌没变），必须**重新解析**换一份新的。
    // 这也正是用户自己会做的动作（退回详情页再点一次），我们只是替他把这一步做了。
    val maxReResolve = 2
    if (!fromSniff && stallReResolveTries < maxReResolve) {
        stallReResolveTries++
        PlayLog.record("⚠ 卡住 ⇒ 重新解析本集（第 $stallReResolveTries/$maxReResolve 次）换取新令牌")
        showHud(getString(R.string.stall_reparse))
        handler.postDelayed(
            { if (!isFinishing) playEpisode(PlayQueue.episodeIndex, autoHeal = true) },
            300
        )
        return
    }
    showStallPanel(buffered, dur, live)
}

/**
 * 卡住时的面板。**复用出错时的诊断面板**（同一块 UI、同一套按钮）：
 * 用户学一次就够，我们也不必维护两套几乎一样的界面。
 */
internal fun PlayerActivity.showStallPanel(buffered: Long, dur: Long, live: Boolean) {
    val sb = StringBuilder()
    sb.append(getString(R.string.stall_state_buffering)).append('\n')
    if (live) sb.append(getString(R.string.stall_state_live)).append('\n')
    sb.append("已缓冲 ").append(formatTime(buffered)).append(" / ")
        .append(if (dur > 0L) formatTime(dur) else "时长未知").append('\n')
    sb.append("地址：").append(shorten(currentUrl)).append('\n')
    // 「转圈」这两个字里没有任何可查的东西。把播放器这一侧**最后一条失败的请求**摆出来：
    // 分片是被拒了（402/403）、超时了，还是压根没人回 —— 少了这一行就只能靠猜，
    // 而"猜"正是这个问题拖了这么久的唯一原因。
    val lastFail = NetLog.lastFailure()
    if (lastFail.isNotBlank()) {
        sb.append("最近一次失败请求：").append(lastFail.take(180)).append('\n')
    }
    sb.append("数据源：OkHttp（与自检同栈）")
    binding.tvDiag.text = sb.toString()
    binding.tvDiagTitle.text = if (fromSniff && SniffQueue.candidates.isNotEmpty()) {
        getString(R.string.stall_title) +
            "（源 ${SniffQueue.index + 1}/${SniffQueue.candidates.size}）"
    } else {
        getString(R.string.stall_title)
    }
    binding.btnDiagNext.visibility =
        if (fromSniff && SniffQueue.hasNext()) View.VISIBLE else View.GONE
    binding.btnDiagSniff.text = getString(
        if (fromSniff && SniffQueue.candidates.isNotEmpty()) R.string.player_error_back_candidates
        else R.string.player_error_sniff
    )
    binding.pbBuffering.visibility = View.GONE
    binding.diagPanel.visibility = View.VISIBLE
    if (!controllerVisible) setBarsVisible(true)
}
