package com.videoshell.player

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
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

        /** 本次播放来自嗅探页 —— 意味着 [SniffQueue] 里有一份候选清单可以换源 */
        private const val EXTRA_FROM_SNIFF = "from_sniff"

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
        /** 「我的」播放设置：记住播放进度 / 自动连播 / 画面自动横竖屏 */
        private const val KEY_RESUME = "setting_resume"
        private const val KEY_AUTO_NEXT = "setting_auto_next"
        private const val KEY_AUTO_ORIENT = "setting_auto_orient"

        /**
         * 「卡住」判定：缓冲进度**连续这么久没推进**就认为这一路播不动了（v1.0.38）。
         *
         * 为什么需要它：ExoPlayer 的默认加载策略遇到取不到的分片会**静默重试**
         * （十次、退避到 5 秒一轮，加起来四十多秒一句话都不说）。
         * 用户那边看到的就是"时长读得到、画面一直转圈、也不报错"。
         * 25 秒足够区分"链路慢但在动"（缓冲会推进）与"这一路根本拿不到数据"。
         */
        private const val STALL_MS = 25_000L

        /**
         * 分片级别的加载重试次数（v1.0.38，原为 10/12）。
         *
         * 调小不是为了更早放弃，而是为了让"这一路真的不行"**快点说出来** ——
         * 重试十次意味着四十多秒的静默，而旁边就摆着「换下一个源」这个更好的选择。
         * 配合 [STALL_MS] 看门狗：慢而能动的流不会被误判，真的取不到数据的会很快暴露。
         */
        private const val MEDIA_RETRIES = 4

        fun intent(
            context: Context,
            url: String,
            title: String,
            headers: Map<String, String>,
            pageUrl: String = "",
            fromSniff: Boolean = false
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_PAGE, pageUrl)
            putExtra(EXTRA_FROM_SNIFF, fromSniff)
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

    /** 本次 playUrl 实际应用的续播点 —— 第一个 READY 时核对它是否落在流末尾（见 STATE_READY 守卫） */
    private var appliedResume: Long = 0L
    /** 已经自动降级到嗅探过一次（防止反复跳转） */
    private var autoSniffTried = false

    /** 本次播放是否来自嗅探页 —— 是的话失败时可以自动换下一个候选源 */
    private var fromSniff = false

    /** 已经为哪个地址换过一次源（同一个源重复报错不能反复切） */
    private var lastSwitchedFrom = ""

    /** 本次播放是否已经记过"就绪"（换源/重试后要允许再记一次） */
    private var readyLogged = false

    /** 「卡住」看门狗：上一次观测到的缓冲进度（-1 = 还没开始观测） */
    private var lastBuffered = -1L

    /** 缓冲进度**最后一次推进**的时刻；长时间不动就说明卡住了 */
    private var stallSince = 0L

    /** 本次播放是否已经因为"卡住"换过源（同一个源别反复切） */
    private var stalledSwitched = false

    /**
     * 本集已经自动「重新解析」过几次（v1.0.39）。
     *
     * 为什么需要它：`stalledSwitched` 每次 `playUrl` 都会被重置，而"重新解析"这条自愈路径
     * **自己会再走一次 `playEpisode` → `playUrl`** —— 拿它当次数上限等于没有上限，
     * 一旦源真的坏了就是一集一集地无限重解析。所以这条计数**只在用户主动换集/换源时清零**
     * （`playEpisode` 的非自愈分支），自愈路径只加不减。
     */
    private var stallReResolveTries = 0

    /** 标题栏分辨率标签（如 "1080P"），取到画面尺寸前为空 */
    private var resLabel = ""

    /**
     * 按画面比例自动横竖屏的开关。用户**手动**点过旋转按钮后就不再自动切
     * （他要的朝向优先）；换集时复位，让新一集继续按内容自动。
     */
    private var orientAuto = true

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
        fromSniff = intent.getBooleanExtra(EXTRA_FROM_SNIFF, false)
        applyTitle()

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
            // ⚠️ 必须在 playUrl 之前定好"这一集是谁" —— playUrl 里要按它去取续播点。
            // DetailActivity 跳转前已把 PlayQueue 填好（title/siteKey/groups/episodeIndex），
            // 这里取到的就是用户点的那一集。
            currentEpKey = PlayQueue.current()?.let {
                episodeKey(PlayQueue.siteKey, PlayQueue.title, it.name, PlayQueue.episodeIndex)
            }.orEmpty()
            playUrl(url)
        }
    }

    // ------------------------------------------------------------------ 系统栏 / 方向

    /**
     * 全屏 + **不避让前置挖孔**。
     *
     * 系统默认（`LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT`）会把整个窗口限制在"安全区"里：
     * 横屏时挖孔所在的那条短边被留出来，表现为**视频旁边一条黑边、画面被挤小**。
     * 用户明确要求用满整屏，所以开 `SHORT_EDGES`（API 28+）——内容可以延伸到短边，
     * 而横屏时挖孔恰好就在短边上。
     *
     * 代价是**控件**可能被挖孔压住，所以画面（PlayerView）继续铺满整屏，
     * 由 [applyOverlayInsets] 单独给覆盖层补上安全距离 —— 两件事分开做，
     * 才能做到"画面用满、按钮不被遮"。
     */
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        applyOverlayInsets()
    }

    /** 覆盖层（顶栏/底栏/选集面板/诊断面板）的**原始** padding，只采样一次 */
    private val overlayPadBase = HashMap<Int, IntArray>()

    /** 靠 margin 让位的控件（固定高的底部面板 + 锁定按钮）的**原始** margin，只采样一次 */
    private val overlayMarginBase = HashMap<Int, IntArray>()

    private fun overlaySpecs(): List<Pair<View, Int>> = listOf(
        binding.topBar to PlayerInsets.TOP,
        binding.bottomBar to PlayerInsets.BOTTOM,
        binding.episodePanel to PlayerInsets.BOTTOM,
        binding.diagPanel to PlayerInsets.BOTTOM
    )

    /** 用 margin（而不是 padding）让位的控件：它们高度固定，吃 padding 会把内容挤扁 */
    private fun marginViews() = listOf(binding.episodePanel, binding.diagPanel, binding.ivUnlock)

    /**
     * 把「挖孔 + 系统栏」的安全距离补给覆盖层，而不是缩画面。
     *
     * 基线 padding / margin 必须**只取一次**：监听器每次收到 inset 都会重设，
     * 在原始值上累加的话会越撑越大（这是这类代码最经典的自伤）。
     *
     * ⚠️ v1.0.28 修的坑：以前是**四条边一起补**。顶栏贴的是上边，却被同时加上
     * top（挖孔/状态栏，真机上测得 ~40dp）与 bottom（导航栏）两笔内边距，
     * 而它是固定 52dp 高 ⇒ 内容区被压成十几 dp，标题与图标只剩一条横带 ——
     * 这就是用户反馈的「竖屏播放时顶部信息栏显示不全」。
     * 现在按方位只补对应的一条边（见 [PlayerInsets]），固定高的底部面板改用 margin 让位，
     * 顶栏在布局里改成 `wrap_content + minHeight`，让 padding 去**撑高**它而不是挤内容。
     */
    private fun applyOverlayInsets() {
        val specs = overlaySpecs()
        if (overlayPadBase.isEmpty()) {
            for ((v, _) in specs) {
                overlayPadBase[v.id] = intArrayOf(
                    v.paddingStart, v.paddingTop, v.paddingEnd, v.paddingBottom
                )
            }
            for (v in marginViews()) {
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                overlayMarginBase[v.id] = intArrayOf(
                    lp.marginStart, lp.topMargin, lp.marginEnd, lp.bottomMargin
                )
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val c = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars()
            )
            val ins = intArrayOf(c.left, c.top, c.right, c.bottom)
            for ((v, edges) in specs) {
                val b = overlayPadBase[v.id] ?: continue
                val p = PlayerInsets.pad(b, ins, edges)
                v.setPadding(p[0], p[1], p[2], p[3])
            }
            for (v in marginViews()) {
                val b = overlayMarginBase[v.id] ?: continue
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                lp.marginStart = b[0] + c.left
                lp.topMargin = b[1] + c.top
                lp.marginEnd = b[2] + c.right
                lp.bottomMargin = b[3] + c.bottom
                v.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
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
        orientAuto = false   // 用户手动指定后，本集内不再按画面比例自动切
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

    // ------------------------------------------------------------------ 标题 / 画面自适应

    /** 标题栏 = 剧名·集名 · 分辨率（分辨率取到前只显示标题） */
    private fun applyTitle() {
        binding.tvTitle.text = when {
            resLabel.isBlank() -> currentTitle
            currentTitle.isBlank() -> resLabel
            else -> "$currentTitle · $resLabel"
        }
    }

    /**
     * 分辨率标签：用宽高里**较小**的一边（横屏视频是高度、竖屏视频是宽度），
     * 与日常"1080P/720P"的叫法一致。尺寸未知（纯音频/还没解出第一帧）不显示。
     */
    private fun applyResLabel(v: VideoSize) {
        if (v.width <= 0 || v.height <= 0) return
        val m = minOf(v.width, v.height)
        resLabel = when {
            m >= 2160 -> "4K"
            m >= 1440 -> "2K"
            m >= 1080 -> "1080P"
            m >= 720 -> "720P"
            m >= 480 -> "480P"
            m >= 360 -> "360P"
            else -> "${m}P"
        }
        applyTitle()
    }

    /**
     * 按画面比例自动横竖屏：横屏视频自动转横屏、竖屏视频回竖屏。
     * 用户手动点过旋转按钮（[toggleOrientation]）后交还控制权，本集内不再自动切。
     */
    private fun applyVideoOrientation(v: VideoSize) {
        if (v.width <= 0 || v.height <= 0) return
        if (!orientAuto) return
        if (!sp.getBoolean(KEY_AUTO_ORIENT, true)) return
        val want =
            if (v.width > v.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        if (requestedOrientation != want) {
            requestedOrientation = want
            showHud(if (v.width > v.height) "画面：横屏" else "画面：竖屏")
        }
    }

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
        // 「复制」：错误面板内容能一键拿走。以前只能长按选中，手机横屏下基本选不全，
        // 结果是每轮回访都缺最关键的那句错 —— 这次把它做成一个按钮。
        binding.btnDiagCopy.setOnClickListener { copyDiag() }
        binding.btnDiagRetry.setOnClickListener {
            binding.diagPanel.visibility = View.GONE
            if (currentUrl.isNotBlank()) {
                retried = true
                playUrl(currentUrl, fromRetry = true)
            }
        }
        // 「换源」：嗅探到的候选不止一个时，手动切到下一个（自动换源失败后还有得选）
        binding.btnDiagNext.visibility = View.GONE
        binding.btnDiagNext.setOnClickListener {
            binding.diagPanel.visibility = View.GONE
            if (!nextSniffSource()) toast("没有更多候选源了")
        }
        binding.btnDiagSniff.setOnClickListener {
            binding.diagPanel.visibility = View.GONE
            if (fromSniff && SniffQueue.candidates.isNotEmpty()) {
                // 嗅探页还在返回栈里活着（候选清单也在），直接回去重挑，不用再嗅一遍
                finish()
                return@setOnClickListener
            }
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
                if (playbackState == Player.STATE_ENDED && PlayQueue.hasNext() &&
                    sp.getBoolean(KEY_AUTO_NEXT, true)
                ) {
                    playEpisode(PlayQueue.episodeIndex + 1)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                applyResLabel(videoSize)
                applyVideoOrientation(videoSize)
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

    private fun playUrl(rawUrl: String, fromRetry: Boolean = false) {
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
        PlayLog.newSession()
        PlayLog.record("▶ 开始播放 ${shorten(url)}" + if (headers.isEmpty()) "" else "  头=${headers.keys.joinToString(",")}")

        // ⚠️ 数据源用 OkHttp，**不用** ExoPlayer 自带的 DefaultHttpDataSource。
        // 后者底层是 HttpURLConnection，与 App 其它部分（自检/解析/嗅探都用 OkHttp）
        // 是两套网络栈：DNS 顺序、连接池、超时重试、Cookie 全不一样 —— 这正是
        // 「自检全绿、播放打不开」唯一说得通的解释。换成一套之后两边不复存在差异。
        val factory = OkHttpDataSourceFactory(Http.mediaClient, Http.UA, browserHeaders())
        val ds = HlsFixDataSourceFactory(factory)
        val policy = DefaultLoadErrorHandlingPolicy(if (fromRetry) MEDIA_RETRIES + 2 else MEDIA_RETRIES)

        val source = if (Media.isHls(url)) {
            HlsMediaSource.Factory(ds)
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(MediaItem.fromUri(url))
        } else {
            DefaultMediaSourceFactory(ds)
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(MediaItem.fromUri(url))
        }

        val resume = if (fromRetry) 0L else resumePosition(activeKey())
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

    /**
     * 播放器实际发出的请求头（v1.0.38）：在调用方给的头之外补上 `Origin`。
     *
     * 为什么补这个：同一个地址"网页端能播、App 里不能播"时，**两边的请求到底差在哪**
     * 是唯一真正要回答的问题。hls.js 走 XHR/fetch ⇒ 浏览器必然带 `Origin: <页面源>`，
     * 而部分 CDN / WAF 拿它做防盗链校验 —— 只带 Referer、不带 Origin 的请求会被挂住，
     * 表现就是"网页端同一个链接能播，App 里一直转圈"。
     *
     * 只补**缺失**的，绝不覆盖调用方已经设好的值（那些值是按站点试出来的）。
     */
    private fun browserHeaders(): Map<String, String> {
        val h = HashMap(headers)
        if (h.keys.none { it.equals("Origin", true) }) {
            originOf(fallbackPage)?.let { h["Origin"] = it }
        }
        if (h.keys.none { it.equals("Referer", true) } && fallbackPage.isNotBlank()) {
            h["Referer"] = fallbackPage
        }
        return h
    }

    /** `https://a.b/c?d` → `https://a.b`。拿不到就返回 null —— 宁可少发一个头，也不发假值 */
    private fun originOf(url: String): String? = runCatching {
        val u = java.net.URI(url)
        if (u.scheme == null || u.authority == null) null else "${u.scheme}://${u.authority}"
    }.getOrNull()

    private fun refreshProgress() {
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

    /** 按当前偏好提交分集列表，并把正在播的那一集标出来 */
    private fun submitEpisodes() {
        episodeAdapter.submit(PlayQueue.episodes(), Store.episodeDesc(this))
        episodeAdapter.select(PlayQueue.episodeIndex)
    }

    private fun toggleEpisodeOrder() {
        val desc = !Store.episodeDesc(this)
        Store.setEpisodeDesc(this, desc)
        binding.tvOrder.text = getString(if (desc) R.string.order_desc else R.string.order_asc)
        submitEpisodes()
        showHud(getString(if (desc) R.string.order_hud_desc else R.string.order_hud_asc))
        // 换了顺序，列表从头看起更顺（选中项可能已经跑到很后面）
        binding.rvEpisodes.scrollToPosition(0)
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

    /**
     * 播放第 [index] 集。
     *
     * @param autoHeal 由「卡住」看门狗发起（v1.0.39）。**必须与用户主动换集区分开**：
     *   自愈走的也是这条路，若在这里清零 [stallReResolveTries]，一次卡住会被无限重解析下去。
     *   所以只有用户发起的（默认 false）才清零，也就是"用户动一下 = 给一次新的自愈预算"。
     */
    private fun playEpisode(index: Int, autoHeal: Boolean = false) {
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
    private fun watchStall() {
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
        if (System.currentTimeMillis() - stallSince < STALL_MS) return
        if (binding.diagPanel.visibility == View.VISIBLE) return   // 面板已在提示，别叠加
        stallSince = System.currentTimeMillis()                     // 重置，免得反复弹
        onStalled(buffered)
    }

    private fun onStalled(buffered: Long) {
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
    private fun showStallPanel(buffered: Long, dur: Long, live: Boolean) {
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

    private fun showDiag(error: PlaybackException) {
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
    private fun copyDiag() {
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

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("?")

    /** 地址太长会撑爆错误面板与记录，中段省略 */
    private fun shorten(u: String): String =
        if (u.length <= 110) u else u.take(70) + "…" + u.takeLast(30)

    // ------------------------------------------------------------------ 换源

    /** 换到下一个嗅探候选源；没有更多候选就返回 false */
    private fun nextSniffSource(): Boolean {
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
    private var currentEpKey: String = ""

    private fun episodeKey(siteKey: String, show: String, epName: String, epIdx: Int): String =
        "resume_" + Media.digest("$siteKey|$show|${epName}#$epIdx")

    /** 无剧集上下文（直链播放 / 嗅探进来）时的兜底：按地址摘要记。digest 取代 32 位 hashCode。 */
    private fun urlKey(url: String): String = "resume_url_" + Media.digest(url)

    /** 有剧集身份就用剧集身份，否则退回地址摘要 */
    private fun activeKey(): String = currentEpKey.ifBlank { urlKey(currentUrl) }

    private fun resumePosition(key: String): Long {
        if (!sp.getBoolean(KEY_RESUME, true)) return 0L
        val v = runCatching { sp.getLong(key, 0L) }.getOrDefault(0L)
        return if (v > 10_000L) v else 0L   // 少于 10 秒不值得续播
    }

    private fun savePosition() {
        if (!sp.getBoolean(KEY_RESUME, true)) return
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
    private fun recordHistory() {
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
        recordHistory()
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
