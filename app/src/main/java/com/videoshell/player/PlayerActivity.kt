package com.videoshell.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.videoshell.R
import com.videoshell.data.Store
import com.videoshell.data.model.MediaSource
import com.videoshell.data.net.Http
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.Media
import com.videoshell.databinding.ActivityPlayerBinding
import com.videoshell.ui.adapter.EpisodeAdapter
import com.videoshell.util.formatTime
import com.videoshell.util.toast
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 内置播放器（ExoPlayer / Media3）。
 *
 * 功能：横竖屏切换（默认横屏）、锁定、画面比例、静音、上/下一集、快退快进 10 秒、
 * 倍速（含长按临时加速）、选集、亮度/音量/快进退手势、进度记忆续播、失败诊断与重试。
 *
 * 播放前会用 [HlsFixDataSourceFactory] 包一层：拦截 m3u8 做规范化，
 * 解决"网页能播、壳子里播不了"的扁平 TS 播放列表（详见 [HlsPlaylistFixer]）。
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_PAGE = "page_url"

        private val SPEEDS = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f, 0.5f)
        private const val LONG_PRESS_SPEED = 2.5f

        /** 屏幕方向偏好：默认横屏 */
        private const val ORIENT_LANDSCAPE = 0
        private const val ORIENT_PORTRAIT = 1
        private const val ORIENT_AUTO = 2
        private const val SEEK_STEP_MS = 10_000L
        private const val AUTO_HIDE_MS = 4_000L
        private const val SP = "videoshell"
        private const val KEY_ORIENT = "player_orientation"

        fun intent(
            context: Context,
            url: String,
            title: String,
            headers: Map<String, String>,
            pageUrl: String = ""
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_PAGE, pageUrl)
            putExtra(
                EXTRA_HEADERS,
                Gson().toJson(headers, object : TypeToken<Map<String, String>>() {}.type)
            )
        }
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var headers: Map<String, String> = emptyMap()
    private var speedIndex = 0
    private var controllerVisible = true
    private var locked = false
    private var pausedByLifecycle = false
    private var bright = 0.5f
    private var volFloat = 0.5f
    private var muted = false
    private var dragging = false

    private var currentUrl = ""
    private var currentTitle = ""
    private var fallbackPage = ""
    private var retried = false

    /** 已经自动降级到嗅探过一次（防止反复跳转） */
    private var autoSniffTried = false

    private val sp: SharedPreferences by lazy { getSharedPreferences(SP, Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val hideHud = Runnable { binding.tvHud.visibility = View.GONE }
    private val autoHide = Runnable { if (!locked) setBarsVisible(false) }

    /** 画面比例档位 */
    private val ratioModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "自适应",
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "16:9",
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT to "4:3",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "铺满",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁剪铺满"
    )
    private var ratioIndex = 0

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val episodeAdapter = EpisodeAdapter { index, _ -> playEpisode(index) }

    private val ticker = object : Runnable {
        override fun run() {
            refreshProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        headers = runCatching {
            val t = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson<Map<String, String>>(intent.getStringExtra(EXTRA_HEADERS), t)
        }.getOrDefault(emptyMap())

        currentTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        fallbackPage = intent.getStringExtra(EXTRA_PAGE).orEmpty()
        binding.tvTitle.text = currentTitle

        bright = window.attributes.screenBrightness.let { if (it > 0f) it else 0.5f }
        volFloat = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1).toFloat()

        applyOrientationPref()

        setupBars()
        setupPlayer()
        setupGestures()
        setupEpisodes()

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isNotBlank()) {
            currentUrl = url
            playUrl(url)
        }
    }

    // ------------------------------------------------------------------ 系统栏 / 方向

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** 默认横屏；用户切过就按上次的选择 */
    private fun applyOrientationPref() {
        requestedOrientation = when (sp.getInt(KEY_ORIENT, ORIENT_LANDSCAPE)) {
            ORIENT_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ORIENT_AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun toggleOrientation() {
        val next = when (currentOrientMode()) {
            ORIENT_LANDSCAPE -> ORIENT_PORTRAIT
            ORIENT_PORTRAIT -> ORIENT_AUTO
            else -> ORIENT_LANDSCAPE
        }
        sp.edit().putInt(KEY_ORIENT, next).apply()
        requestedOrientation = when (next) {
            ORIENT_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ORIENT_AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val label = when (next) {
            ORIENT_PORTRAIT -> "竖屏"
            ORIENT_AUTO -> "跟随系统"
            else -> "横屏"
        }
        showHud("屏幕：$label")
    }

    private fun currentOrientMode(): Int = sp.getInt(KEY_ORIENT, ORIENT_LANDSCAPE)

    // ------------------------------------------------------------------ 控制条

    private fun setupBars() {
        binding.btnBack.setOnClickListener { finish() }
        binding.ivRotate.setOnClickListener { toggleOrientation() }
        binding.ivLock.setOnClickListener { setLocked(true) }
        binding.ivUnlock.setOnClickListener { setLocked(false) }
        binding.ivMute.setOnClickListener { toggleMute() }
        binding.ivResize.setOnClickListener { cycleRatio() }
        binding.ivPrev.setOnClickListener { stepEpisode(-1) }
        binding.ivNext.setOnClickListener { stepEpisode(1) }
        binding.ivRew.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        binding.ivFf.setOnClickListener { seekBy(SEEK_STEP_MS) }
        binding.ivPlay.setOnClickListener { togglePlay() }
        binding.tvSpeed.text = "1.0x"
        binding.tvSpeed.setOnClickListener { switchSpeed() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvPos.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
                handler.removeCallbacks(autoHide)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                player?.seekTo(sb.progress.toLong())
                scheduleHide()
            }
        })

        // 底部控制条会盖住手势层，把它的高度告诉手势层让事件穿透
        binding.bottomBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            binding.gesture.bottomBlockHeight = if (controllerVisible) bottom - top else 0
        }

        binding.diagPanel.visibility = View.GONE
        binding.btnDiagClose.setOnClickListener { binding.diagPanel.visibility = View.GONE }
        binding.btnDiagRetry.setOnClickListener {
            binding.diagPanel.visibility = View.GONE
            if (currentUrl.isNotBlank()) {
                retried = true
                playUrl(currentUrl, fromRetry = true)
            }
        }
        binding.btnDiagSniff.setOnClickListener {
            binding.diagPanel.visibility = View.GONE
            val page = fallbackPage.ifBlank { currentUrl }
            if (page.isBlank()) {
                toast("没有可嗅探的页面地址")
            } else {
                startActivity(SniffActivity.intent(this, page, currentTitle, headers))
                finish()
            }
        }
    }

    private fun setBarsVisible(visible: Boolean) {
        controllerVisible = visible
        binding.topBar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.gesture.bottomBlockHeight =
            if (visible && binding.bottomBar.height > 0) binding.bottomBar.height else 0
        if (!visible) {
            binding.episodePanel.visibility = View.GONE
            binding.diagPanel.visibility = View.GONE
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(autoHide)
        if (player?.isPlaying == true && !locked) handler.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private fun setLocked(v: Boolean) {
        locked = v
        binding.gesture.locked = v
        binding.ivUnlock.visibility = if (v) View.VISIBLE else View.GONE
        if (v) {
            setBarsVisible(false)
            binding.ivUnlock.visibility = View.VISIBLE
            showHud(getString(R.string.hud_locked))
        } else {
            setBarsVisible(true)
            scheduleHide()
        }
    }

    private fun toggleMute() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        binding.ivMute.setImageResource(if (muted) R.drawable.ic_mute else R.drawable.ic_volume)
        showHud(if (muted) "已静音" else "已恢复音量")
    }

    private fun cycleRatio() {
        ratioIndex = (ratioIndex + 1) % ratioModes.size
        val (mode, label) = ratioModes[ratioIndex]
        binding.playerView.resizeMode = mode
        showHud(getString(R.string.hud_ratio, label))
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        scheduleHide()
    }

    private fun seekBy(delta: Long) {
        val p = player ?: return
        val dur = p.duration.let { if (it > 0) it else 0L }
        val target = (p.currentPosition + delta).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        p.seekTo(target)
        showHud("${formatTime(target)} / ${formatTime(dur)}")
    }

    // ------------------------------------------------------------------ 播放器

    private fun setupPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_000, 4_000)
            .build()
        val p = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        player = p
        binding.playerView.player = p

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                binding.pbBuffering.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (playbackState == Player.STATE_READY) resetErrorState()
                if (playbackState == Player.STATE_ENDED && PlayQueue.hasNext()) {
                    playEpisode(PlayQueue.episodeIndex + 1)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.ivPlay.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                scheduleHide()
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.pbBuffering.visibility = View.GONE
                showDiag(error)
            }
        })
    }

    private fun resetErrorState() {
        binding.diagPanel.visibility = View.GONE
    }

    private fun playUrl(url: String, fromRetry: Boolean = false) {
        val p = player ?: return
        if (url.isBlank()) return
        currentUrl = url
        if (!fromRetry) autoSniffTried = false

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        // m3u8 规范化：解决"网页能播、壳子播不了"的扁平 TS playlist
        val ds = HlsFixDataSourceFactory(http)
        val policy = DefaultLoadErrorHandlingPolicy(if (fromRetry) 12 else 8)

        val source = if (Media.isHls(url)) {
            HlsMediaSource.Factory(ds)
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(MediaItem.fromUri(url))
        } else {
            DefaultMediaSourceFactory(ds)
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(MediaItem.fromUri(url))
        }

        val resume = if (fromRetry) 0L else resumePosition(url)
        p.setMediaSource(source)
        p.playbackParameters = PlaybackParameters(SPEEDS[speedIndex])
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

    private fun refreshProgress() {
        val p = player ?: return
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

    private fun seekPreview(delta: Long) {
        val p = player ?: return
        val dur = p.duration.let { if (it > 0) it else 0L }
        val target = (p.currentPosition + delta).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        showHud("${formatTime(target)} / ${formatTime(dur)}", false)
    }

    private fun seekCommit(delta: Long) {
        val p = player ?: return
        val dur = p.duration.let { if (it > 0) it else 0L }
        val target = (p.currentPosition + delta).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        p.seekTo(target)
        showHud("已跳转 ${formatTime(target)}")
    }

    // ------------------------------------------------------------------ 手势

    private fun setupGestures() {
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
            player?.playbackParameters = PlaybackParameters(LONG_PRESS_SPEED)
            showHud("${LONG_PRESS_SPEED}x 加速中", false)
        }
        binding.gesture.onLongPressEnd = {
            player?.playbackParameters = PlaybackParameters(SPEEDS[speedIndex])
            handler.removeCallbacks(hideHud)
            hideHud.run()
        }
    }

    private fun switchSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        val s = SPEEDS[speedIndex]
        player?.playbackParameters = PlaybackParameters(s)
        binding.tvSpeed.text = if (s == s.toInt().toFloat()) "${s.toInt()}.0x" else "${s}x"
        showHud(binding.tvSpeed.text.toString())
    }

    // ------------------------------------------------------------------ 选集

    private fun setupEpisodes() {
        val eps = PlayQueue.episodes()
        if (eps.isEmpty()) {
            binding.btnEpisodes.visibility = View.GONE
            binding.ivPrev.visibility = View.GONE
            binding.ivNext.visibility = View.GONE
            return
        }
        binding.btnEpisodes.visibility = View.VISIBLE
        binding.tvPanelTitle.text = (PlayQueue.title.ifBlank { "选集" }) + " · 共 ${eps.size} 集"
        binding.rvEpisodes.layoutManager = GridLayoutManager(this, if (isLandscape()) 8 else 5)
        binding.rvEpisodes.adapter = episodeAdapter
        episodeAdapter.submit(eps)
        episodeAdapter.select(PlayQueue.episodeIndex)
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

    private fun stepEpisode(delta: Int) {
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

    private fun playEpisode(index: Int) {
        val ep = PlayQueue.episodes().getOrNull(index) ?: return
        PlayQueue.episodeIndex = index
        episodeAdapter.select(index)
        binding.episodePanel.visibility = View.GONE
        currentTitle = "${PlayQueue.title} ${ep.name}".trim()
        binding.tvTitle.text = currentTitle
        fallbackPage = ep.url

        val site = PlayQueue.siteKey.takeIf { it.isNotBlank() }?.let { Store.find(this, it) }
        if (site == null) {
            playUrl(ep.url)
            return
        }
        lifecycleScope.launch {
            binding.pbBuffering.visibility = View.VISIBLE
            val r = runCatching { AdapterFactory.create(site).resolve(ep) }.getOrNull()
            binding.pbBuffering.visibility = View.GONE
            when (r) {
                is MediaSource.Direct -> {
                    headers = r.headers
                    retried = false
                    playUrl(r.url)
                }
                is MediaSource.Sniff -> {
                    startActivity(
                        SniffActivity.intent(
                            this@PlayerActivity,
                            r.pageUrl,
                            binding.tvTitle.text.toString(),
                            r.headers
                        )
                    )
                    finish()
                }
                is MediaSource.Error -> toast(r.message)
                null -> toast("解析播放地址失败")
            }
        }
    }

    // ------------------------------------------------------------------ 错误诊断

    private fun showDiag(error: PlaybackException) {
        val chain = generateSequence(error.cause) { it.cause }
        val root = chain.last()
        val sb = StringBuilder()
        sb.append("错误码：").append(error.errorCodeName).append(" (").append(error.errorCode).append(")\n")
        generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.let { sb.append("HTTP ").append(it.responseCode).append('\n') }
        val detail = listOfNotNull(root.message, root.javaClass.simpleName)
            .joinToString(" / ")
            .take(220)
        if (detail.isNotBlank()) sb.append("原因：").append(detail).append('\n')
        sb.append("地址：").append(currentUrl.take(140))
        if (retried) sb.append("\n（已重试过一次）")
        binding.tvDiag.text = sb.toString()
        binding.diagPanel.visibility = View.VISIBLE
        binding.ivPlay.setImageResource(R.drawable.ic_play)
        if (!controllerVisible) setBarsVisible(true)

        // 直链播不了（CDN 404 / 超时 / 拒绝）就自动改走网页嗅探 ——
        // 用户不必自己判断"是源挂了还是解析错了"，换条路能把片放出来才是目的。
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

    /**
     * 是否值得自动降级到嗅探。
     * 只对**网络/HTTP 层**错误降级（这些换条路往往能成）；
     * 解码器/格式错误降级也没用，就别折腾用户了。
     */
    private fun shouldAutoSniff(error: PlaybackException): Boolean {
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

    private fun showHud(text: String, autoHideIt: Boolean = true) {
        binding.tvHud.text = text
        binding.tvHud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        if (autoHideIt) handler.postDelayed(hideHud, 900)
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun resumeKey(url: String) = "resume_${url.hashCode()}"

    private fun resumePosition(url: String): Long {
        val v = runCatching { sp.getLong(resumeKey(url), 0L) }.getOrDefault(0L)
        return if (v > 10_000L) v else 0L
    }

    private fun savePosition() {
        val p = player ?: return
        val url = currentUrl
        if (url.isBlank()) return
        val pos = p.currentPosition
        val dur = p.duration
        val key = resumeKey(url)
        if (pos < 15_000L || (dur > 0 && pos > dur - 15_000L)) {
            sp.edit().remove(key).apply()
        } else {
            sp.edit().putLong(key, pos).apply()
        }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (pausedByLifecycle) {
            player?.play()
            pausedByLifecycle = false
        }
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(autoHide)
        player?.let {
            pausedByLifecycle = it.isPlaying
            it.pause()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideHud)
        handler.removeCallbacks(autoHide)
        handler.removeCallbacks(ticker)
        player?.release()
        player = null
        binding.playerView.player = null
        super.onDestroy()
    }
}
