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

        internal val SPEEDS = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f, 0.5f)
        internal const val LONG_PRESS_SPEED = 2.5f

        /** 屏幕方向偏好：默认横屏 */
        private const val ORIENT_LANDSCAPE = 0
        private const val ORIENT_PORTRAIT = 1
        private const val ORIENT_AUTO = 2
        private const val SEEK_STEP_MS = 10_000L
        private const val AUTO_HIDE_MS = 4_000L
        private const val SP = "videoshell"
        private const val KEY_ORIENT = "player_orientation"
        /** 「我的」播放设置：记住播放进度 / 自动连播 / 画面自动横竖屏 */
        internal const val KEY_RESUME = "setting_resume"
        internal const val KEY_AUTO_NEXT = "setting_auto_next"
        private const val KEY_AUTO_ORIENT = "setting_auto_orient"

        /**
         * 「卡住」判定：缓冲进度**连续这么久没推进**就认为这一路播不动了（v1.0.38）。
         *
         * 为什么需要它：ExoPlayer 的默认加载策略遇到取不到的分片会**静默重试**
         * （十次、退避到 5 秒一轮，加起来四十多秒一句话都不说）。
         * 用户那边看到的就是"时长读得到、画面一直转圈、也不报错"。
         * 25 秒足够区分"链路慢但在动"（缓冲会推进）与"这一路根本拿不到数据"。
         */
        internal const val STALL_MS = 25_000L

        /**
         * 分片级别的加载重试次数（v1.0.38，原为 10/12）。
         *
         * 调小不是为了更早放弃，而是为了让"这一路真的不行"**快点说出来** ——
         * 重试十次意味着四十多秒的静默，而旁边就摆着「换下一个源」这个更好的选择。
         * 配合 [STALL_MS] 看门狗：慢而能动的流不会被误判，真的取不到数据的会很快暴露。
         */
        internal const val MEDIA_RETRIES = 4

        /**
         * 画中画窗口的纵横比硬边界（FN-3）。
         *
         * 系统对 PiP 窗口有硬性区间（约 9:21.5 ~ 21.5:9），超出会直接抛
         * `enterPictureInPictureMode: Aspect ratio is too extreme (must be between
         * 0.418410 and 2.390000)` —— 整块功能就废了。这里取比系统边界再内收一点的
         * 值做夹取：竖屏短剧、超宽电影、带旋转角度的视频都可能落到区间外，
         * 宁可口径略有出入，也不要让功能直接失败。
         */
        internal const val PIP_MIN_RATIO = 0.42f
        internal const val PIP_MAX_RATIO = 2.38f

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

    internal lateinit var binding: ActivityPlayerBinding
    internal var player: ExoPlayer? = null
    internal var headers: Map<String, String> = emptyMap()
    internal var speedIndex = 0
    internal var controllerVisible = true
    private var locked = false
    private var pausedByLifecycle = false
    internal var bright = 0.5f
    internal var volFloat = 0.5f
    internal var muted = false
    internal var dragging = false

    internal var currentUrl = ""
    internal var currentTitle = ""
    internal var fallbackPage = ""
    internal var retried = false

    /** 本次 playUrl 实际应用的续播点 —— 第一个 READY 时核对它是否落在流末尾（见 STATE_READY 守卫） */
    internal var appliedResume: Long = 0L
    /** 已经自动降级到嗅探过一次（防止反复跳转） */
    internal var autoSniffTried = false

    /** 本次播放是否来自嗅探页 —— 是的话失败时可以自动换下一个候选源 */
    internal var fromSniff = false

    /** 已经为哪个地址换过一次源（同一个源重复报错不能反复切） */
    internal var lastSwitchedFrom = ""

    /** 本次播放是否已经记过"就绪"（换源/重试后要允许再记一次） */
    internal var readyLogged = false

    /** 「卡住」看门狗：上一次观测到的缓冲进度（-1 = 还没开始观测） */
    internal var lastBuffered = -1L

    /** 缓冲进度**最后一次推进**的时刻；长时间不动就说明卡住了 */
    internal var stallSince = 0L

    /** 本次播放是否已经因为"卡住"换过源（同一个源别反复切） */
    internal var stalledSwitched = false

    /**
     * 本集已经自动「重新解析」过几次（v1.0.39）。
     *
     * 为什么需要它：`stalledSwitched` 每次 `playUrl` 都会被重置，而"重新解析"这条自愈路径
     * **自己会再走一次 `playEpisode` → `playUrl`** —— 拿它当次数上限等于没有上限，
     * 一旦源真的坏了就是一集一集地无限重解析。所以这条计数**只在用户主动换集/换源时清零**
     * （`playEpisode` 的非自愈分支），自愈路径只加不减。
     */
    internal var stallReResolveTries = 0

    /** 标题栏分辨率标签（如 "1080P"），取到画面尺寸前为空 */
    internal var resLabel = ""

    /**
     * 当前这一集的**身份键**（`siteKey|show|epName#epIdx` 的摘要），没进剧集上下文时为空。
     *
     * 它决定"续播点记在谁名下"：为空就退回按播放地址记（[urlKey]）。
     * 原来它躺在 PlayerActivity 的字段区，v1.0.54 拆分时和续播那一族一起搬到了
     * PlayerActivity_Diag.kt —— 但它必须留在**类里**（三个文件都要写它，见 PITFALLS §4.42）。
     */
    internal var currentEpKey: String = ""

    /**
     * 按画面比例自动横竖屏的开关。用户**手动**点过旋转按钮后就不再自动切
     * （他要的朝向优先）；换集时复位，让新一集继续按内容自动。
     */
    internal var orientAuto = true

    internal val sp: SharedPreferences by lazy { getSharedPreferences(SP, Context.MODE_PRIVATE) }
    internal val handler = Handler(Looper.getMainLooper())
    internal val hideHud = Runnable { binding.tvHud.visibility = View.GONE }
    internal val autoHide = Runnable { if (!locked) setBarsVisible(false) }

    /**
     * 解锁键在锁屏后的停留时长。
     *
     * 锁屏是为了"防误触"，而解锁键贴在右中 —— 它常驻就等于在这个位置又开了一个
     * 44dp 的误触区，还压住画面。所以给个短停留，之后自己退场；想看就点一下屏幕。
     */
    private val unlockStayMs = 3_000L

    /** 解锁键的自动退场任务（保持单实例，便于 removeCallbacks 取消） */
    private val hideUnlock = Runnable { if (locked) binding.ivUnlock.visibility = View.GONE }

    /** 画面比例档位 */
    private val ratioModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "自适应",
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "16:9",
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT to "4:3",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "铺满",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁剪铺满"
    )
    private var ratioIndex = 0

    internal val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    internal val episodeAdapter = EpisodeAdapter { index, _ -> playEpisode(index) }

    /** 当前已加载的外挂字幕（content://，FN-5）；null = 无 */
    internal var subtitleUri: Uri? = null

    /** 是否需要保留媒体通知：播放自然结束且没下一集时置否，及时撤掉通知（FN-4） */
    internal var keepNotification = true

    /** 最近一次画面尺寸，用于画中画窗口比例（FN-3） */
    internal var lastVideoSize: VideoSize? = null

    /** 字幕文件选择器（FN-5）：选 .srt / .vtt 后加载为外挂字幕轨 */
    internal val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                applySubtitle(uri)
            }
        }

    /** 投屏设备选择（新增功能）：投成功后本机必须停下，否则手机和电视会同时出声 */
    private val castLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                player?.pause()
                showHud("已投屏到电视，本机已暂停")
            }
        }

    /** 监听媒体通知上的「播放 / 暂停 / 停止」（FN-4），由 [MediaNotificationService] 发出来 */
    internal val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaNotificationService.ACTION_PLAY -> {
                    player?.play()
                    scheduleHide()
                }
                MediaNotificationService.ACTION_PAUSE -> player?.pause()
                MediaNotificationService.ACTION_STOP -> {
                    player?.pause()
                    keepNotification = false
                    MediaNotificationService.stop(this@PlayerActivity)
                    savePosition()
                    recordHistory()
                    finish()
                }
            }
        }
    }

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
        registerNotifReceiver()

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
    private fun marginViews() =
        listOf(binding.episodePanel, binding.diagPanel, binding.ivLock, binding.ivUnlock)

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
    internal fun applyTitle() {
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
    internal fun applyResLabel(v: VideoSize) {
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
    internal fun applyVideoOrientation(v: VideoSize) {
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
        // 锁定时唯一还活着的手势：点屏幕唤出/收起解锁键（解锁键会自己退场，见 showUnlockBriefly）
        binding.gesture.onLockedTap = { toggleUnlockBriefly() }
        binding.ivMute.setOnClickListener { toggleMute() }
        binding.ivResize.setOnClickListener { cycleRatio() }
        binding.ivPrev.setOnClickListener { stepEpisode(-1) }
        binding.ivNext.setOnClickListener { stepEpisode(1) }
        binding.ivRew.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        binding.ivFf.setOnClickListener { seekBy(SEEK_STEP_MS) }
        binding.ivPlay.setOnClickListener { togglePlay() }
        binding.tvSpeed.text = "1.0x"
        binding.tvSpeed.setOnClickListener { switchSpeed() }

        // ---- 新增入口：画中画 / 字幕 / 投屏 / 分享（FN-3 / FN-5 / 投屏 / FN-9）----
        binding.ivPip.setOnClickListener { enterPip() }
        binding.ivSubtitle.setOnClickListener { pickSubtitle() }
        binding.ivCast.setOnClickListener {
            if (currentUrl.isBlank()) {
                toast("还没有可投屏的播放地址")
                return@setOnClickListener
            }
            castLauncher.launch(CastActivity.intent(this, currentUrl, currentTitle, headers))
        }
        binding.ivShare.setOnClickListener { showShare() }

        // 底栏按钮行：横屏合并成一行 / 竖屏拆成两行。
        // 这里排一次决定首屏；旋转后由 onConfigurationChanged 再排（Activity 不重建）。
        applyBarRows()

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

    /**
     * 竖屏时位于底栏「工具行」的成员；横屏会被并进主行。
     *
     * ⚠️ 改 `activity_player.xml` 里 `btnRowTool` 的成员时**必须同步这里** ——
     * 否则旋转回竖屏时，它不会被搬回第二行（表现为横屏布局残留）。
     */
    private val TOOL_ROW_IDS = intArrayOf(
        R.id.tvSpeed, R.id.btnEpisodes, R.id.ivMute, R.id.ivResize, R.id.ivRotate
    )

    /**
     * 按屏幕方向重排底栏按钮行：**横屏一行 / 竖屏两行**。
     *
     * 为什么不一律一行：竖屏 360dp 去掉左右 padding 只剩 340dp，而 10 枚按钮
     * （44dp 起）并排要 440dp+ —— 物理上塞不下。硬挤会把点击区压到 34dp 以下，
     * 比"多一行"严重得多。横屏 640dp+ 才放得开，所以只在横屏合并。
     *
     * ⚠️ PlayerActivity 在 manifest 里声明了 `configChanges=orientation|screenSize`
     * ⇒ 旋转**不会重建 Activity**，只在 onCreate 里排一次的话转一次屏就错位，
     * 必须靠 [onConfigurationChanged] 再排。
     */
    private fun applyBarRows() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val main = binding.btnRowMain
        val tool = binding.btnRowTool
        if (land) {
            if (tool.childCount > 0) {
                val kids = (0 until tool.childCount).map { tool.getChildAt(it) }
                tool.removeAllViews()
                kids.forEach { main.addView(it) }
            }
            tool.visibility = View.GONE
        } else {
            // 复原：按原顺序把它们搬回第二行（本来就在第二行时是空操作）
            val back = (0 until main.childCount)
                .map { main.getChildAt(it) }
                .filter { it.id in TOOL_ROW_IDS }
            back.forEach {
                main.removeView(it)
                tool.addView(it)
            }
            tool.visibility = View.VISIBLE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyBarRows()
    }

    internal fun setBarsVisible(visible: Boolean) {
        controllerVisible = visible
        binding.topBar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        // 悬浮锁定键跟着控制条一起显隐。它是独立图层、贴在右中，而那块区域正好是
        // 「右半屏上下滑调音量」的手势区 —— 常显会把它从手势层上面挡掉，用户只会
        // 觉得"这一段划不动"，联想不到是按钮在挡。锁定后本键隐去、ivUnlock 顶上（同位同形）。
        binding.ivLock.visibility = if (visible && !locked) View.VISIBLE else View.GONE
        binding.gesture.bottomBlockHeight =
            if (visible && binding.bottomBar.height > 0) binding.bottomBar.height else 0
        if (!visible) {
            binding.episodePanel.visibility = View.GONE
            binding.diagPanel.visibility = View.GONE
        }
    }

    internal fun scheduleHide() {
        handler.removeCallbacks(autoHide)
        if (player?.isPlaying == true && !locked) handler.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    /**
     * 解锁键显隐（锁定状态下才用）。
     *
     * 旧实现（v1.0.46 及以前）锁屏后把 ivUnlock 设为 VISIBLE 就再没人管它了 ——
     * 结果是解锁键**永久压在画面右侧正中**，既碍眼又在那块位置留了个误触区。
     * 现在的规则：出现 → 停 [unlockStayMs] → 自己退场；点屏幕可再唤出。
     */
    private fun showUnlockBriefly() {
        binding.ivUnlock.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, unlockStayMs)
    }

    /** 锁定时的单击：解锁键在场就收起，不在场就唤出 */
    private fun toggleUnlockBriefly() {
        if (binding.ivUnlock.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideUnlock)
            binding.ivUnlock.visibility = View.GONE
        } else {
            showUnlockBriefly()
        }
    }

    private fun setLocked(v: Boolean) {
        locked = v
        binding.gesture.locked = v
        handler.removeCallbacks(hideUnlock)
        if (v) {
            setBarsVisible(false)
            // 顺序要紧：setBarsVisible(false) 会把 ivLock 收掉，之后才轮到 ivUnlock 登场
            showUnlockBriefly()
            showHud(getString(R.string.hud_locked))
        } else {
            binding.ivUnlock.visibility = View.GONE
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

    internal fun togglePlay() {
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

    /** 用户主动离开（按 Home / 切到多任务）且正在播放、未开后台播放 ⇒ 自动进入画中画 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (bgPlayEnabled()) return   // 后台播放场景交给媒体通知 + 音频续播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            player?.isPlaying == true &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            enterPip()
        }
    }

    /** 进出画中画：小窗里不该显示这套自定义控制条 */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        setBarsVisible(!isInPictureInPictureMode)
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
        if (bgPlayEnabled()) {
            // 后台播放：不暂停，继续放；确保媒体通知在场（通知栏可控制、系统不易杀）
            syncMediaNotification()
        } else {
            player?.let {
                pausedByLifecycle = it.isPlaying
                it.pause()
            }
            MediaNotificationService.stop(this)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideHud)
        handler.removeCallbacks(autoHide)
        handler.removeCallbacks(hideUnlock)
        handler.removeCallbacks(ticker)
        runCatching { unregisterReceiver(notifReceiver) }
        MediaNotificationService.stop(this)
        player?.release()
        player = null
        binding.playerView.player = null
        super.onDestroy()
    }
}
