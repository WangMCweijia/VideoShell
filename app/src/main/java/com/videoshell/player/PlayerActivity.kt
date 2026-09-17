package com.videoshell.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
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

/** 内置播放器（ExoPlayer / Media3），带手势、倍速、选集 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADERS = "headers"

        private val SPEEDS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f, 0.5f)

        fun intent(context: Context, url: String, title: String, headers: Map<String, String>): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
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
    private var pausedByLifecycle = false
    private var bright = 0.5f
    private var volFloat = 0.5f

    private val handler = Handler(Looper.getMainLooper())
    private val hideHud = Runnable { binding.tvHud.visibility = View.GONE }

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val episodeAdapter = EpisodeAdapter { index, _ -> playEpisode(index) }

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

        binding.tvTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        bright = window.attributes.screenBrightness.let { if (it > 0f) it else 0.5f }
        volFloat = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1).toFloat()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRotate.setOnClickListener { toggleOrientation() }
        binding.tvSpeed.text = "1.0x"
        binding.tvSpeed.setOnClickListener { switchSpeed() }

        setupPlayer()
        setupGestures()
        setupEpisodes()

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isNotBlank()) playUrl(url)
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupPlayer() {
        val p = ExoPlayer.Builder(this).build()
        player = p
        binding.playerView.player = p
        binding.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            val visible = visibility == View.VISIBLE
            controllerVisible = visible
            binding.topBar.visibility = if (visible) View.VISIBLE else View.GONE
            binding.gesture.bottomBlockHeight = if (visible) dp(150) else 0
            if (!visible) binding.episodePanel.visibility = View.GONE
        })

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                binding.pbBuffering.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (playbackState == Player.STATE_ENDED) {
                    if (PlayQueue.hasNext()) playEpisode(PlayQueue.episodeIndex + 1)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.pbBuffering.visibility = View.GONE
                toast("播放失败：${error.errorCodeName}")
            }
        })
    }

    private fun playUrl(url: String) {
        val p = player ?: return
        if (url.isBlank()) return
        val ds = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val source = if (Media.isHls(url)) {
            HlsMediaSource.Factory(ds)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(url))
        } else {
            DefaultMediaSourceFactory(ds).createMediaSource(MediaItem.fromUri(url))
        }
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    private fun setupGestures() {
        binding.gesture.onSingleTap = {
            if (controllerVisible) binding.playerView.hideController()
            else binding.playerView.showController()
        }
        binding.gesture.onDoubleTap = {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            binding.playerView.showController()
        }
        binding.gesture.onSeekPreview = seekPreview@{ delta ->
            val p = player ?: return@seekPreview
            val dur = p.duration.let { if (it > 0) it else 0L }
            val target = (p.currentPosition + delta).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
            showHud("${formatTime(target)} / ${formatTime(dur)}", false)
        }
        binding.gesture.onSeekCommit = seekCommit@{ delta ->
            val p = player ?: return@seekCommit
            val dur = p.duration.let { if (it > 0) it else 0L }
            val target = (p.currentPosition + delta).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
            p.seekTo(target)
            showHud("已跳转 ${formatTime(target)}")
        }
        binding.gesture.onBrightnessDelta = { d ->
            val attrs = window.attributes
            bright = (bright + d).coerceIn(0.02f, 1f)
            attrs.screenBrightness = bright
            window.attributes = attrs
            showHud("亮度 ${(bright * 100).roundToInt()}%")
        }
        binding.gesture.onVolumeDelta = { d ->
            volFloat = (volFloat + d).coerceIn(0f, 1f)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volFloat * max).roundToInt(), 0)
            showHud("音量 ${(volFloat * 100).roundToInt()}%")
        }
        binding.gesture.onGestureEnd = { handler.removeCallbacks(hideHud); hideHud.run() }
    }

    private fun setupEpisodes() {
        val eps = PlayQueue.episodes()
        if (eps.isEmpty()) {
            binding.btnEpisodes.visibility = View.GONE
            return
        }
        binding.btnEpisodes.visibility = View.VISIBLE
        binding.tvPanelTitle.text =
            (PlayQueue.title.ifBlank { "选集" }) + " · 共 ${eps.size} 集"
        binding.rvEpisodes.layoutManager = GridLayoutManager(this, 5)
        binding.rvEpisodes.adapter = episodeAdapter
        episodeAdapter.submit(eps)
        episodeAdapter.select(PlayQueue.episodeIndex)
        binding.btnEpisodes.setOnClickListener {
            val show = binding.episodePanel.visibility != View.VISIBLE
            binding.episodePanel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                binding.playerView.showController()
                binding.topBar.visibility = View.VISIBLE
            }
        }
    }

    private fun playEpisode(index: Int) {
        val ep = PlayQueue.episodes().getOrNull(index) ?: return
        PlayQueue.episodeIndex = index
        episodeAdapter.select(index)
        binding.episodePanel.visibility = View.GONE
        binding.tvTitle.text = "${PlayQueue.title} ${ep.name}".trim()

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

    private fun switchSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        val s = SPEEDS[speedIndex]
        player?.setPlaybackParameters(PlaybackParameters(s))
        binding.tvSpeed.text = when {
            s == s.toInt().toFloat() -> "${s.toInt()}.0x"
            else -> "${s}x"
        }
        showHud(binding.tvSpeed.text.toString())
    }

    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun showHud(text: String, autoHide: Boolean = true) {
        binding.tvHud.text = text
        binding.tvHud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        if (autoHide) handler.postDelayed(hideHud, 900)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onStart() {
        super.onStart()
        if (pausedByLifecycle) {
            player?.play()
            pausedByLifecycle = false
        }
    }

    override fun onStop() {
        super.onStop()
        player?.let {
            pausedByLifecycle = it.isPlaying
            it.pause()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideHud)
        player?.release()
        player = null
        binding.playerView.player = null
        super.onDestroy()
    }
}
