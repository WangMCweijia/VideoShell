package com.videoshell.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.videoshell.R
import com.videoshell.data.pan.DriveState
import com.videoshell.data.pan.DriveStore
import com.videoshell.data.pan.PanProviders
import com.videoshell.data.pan.PanResolver
import com.videoshell.data.pan.PanType
import com.videoshell.databinding.ActivityDriveLoginBinding
import com.videoshell.util.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ## 网盘登录页（v1.0.65）：全屏 WebView + **扫码登录**
 *
 * ### 为什么必须是全屏
 *
 * 网盘网页版的默认登录方式就是**扫码**（夸克 PC 版点登录直接出二维码）。
 * 二维码要能被手机扫，尺寸就不能太小 —— 原来把它塞进 `AlertDialog` 里，
 * 二维码被压成一个小方块，扫不动。这里是全屏，扫码才是可用状态。
 *
 * ### 扫码登录比"贴 cookie"强在哪
 *
 * TVBox 那一路（`quark_cookie` / `uc_cookie` 填在站点 `ext` 里）要求用户去**电脑浏览器**
 * 抓一份 cookie 再手抄进来 —— 手机用户做不到，而且**一份明文 cookie 会跟着源配置到处传**。
 * 扫一下码，凭据直接进本机加密存储（[DriveStore]），不出设备、不进配置。这就是
 * "登录放在软件里，而不是源里"的具体形状。
 *
 * ### 三个必须显式打开、否则扫码一定失败的开关
 *
 * 1. **第三方 Cookie**（[CookieManager.setAcceptThirdPartyCookies]）：登录态是
 *    `passport.*` / `open-api.*` 这些**另一个域**发下来的。只开主域 cookie，扫码后
 *    回到 `pan.*` 仍是未登录 —— 症状是"扫了没反应"。
 * 2. **弹窗内联**（`setSupportMultipleWindows(false)`）：登录/授权页会用 `window.open`，
 *    默认在 WebView 里打不开（没有 `WebChromeClient` 就静默丢弃）⇒ 卡在白页。
 * 3. **DOM Storage**：登录页是 SPA，不开关了 JS 就直接不跳转。
 *
 * ### 自动识别登录成功
 *
 * 人手点"我已完成登录"容易点早了（cookie 还没落）或点漏。这里轮询 cookie：
 * 出现该盘的**登录标记键**（夸克/UC 是 `__pus` / `__puus`，实测 PC 网页登录后必然出现）
 * 就自动保存并返回。手动按钮仍然保留 —— 标记键万一改名，用户还有路可走。
 */
class DriveLoginActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_KEY = "drive_key"

        /**
         * 桌面 UA。理由与 [com.videoshell.data.pan.PanCloudDrive] 一致：我们用的是
         * **PC 接口**（`pr=ucpro&fr=pc`），WebView 若拿手机 UA 去登录，拿回来的 cookie
         * 可能对应移动端会话，回到接口那一侧身份就是矛盾的。
         * 而且 PC 版网页才有扫码登录（移动版会直接往 App 里跳）。
         */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 轮询 cookie 的间隔：太密会拖慢登录页自己的 XHR，太疏则"扫完等半天" */
        private const val POLL_MS = 1200L

        fun intent(ctx: Context, driveKey: String): Intent =
            Intent(ctx, DriveLoginActivity::class.java).putExtra(EXTRA_KEY, driveKey)

        fun loginPage(t: PanType): String = when (t) {
            PanType.QUARK -> "https://pan.quark.cn/"
            PanType.UC -> "https://drive.uc.cn/"
            PanType.ALI -> "https://www.alipan.com/"
            PanType.CLOUD123 -> "https://www.123pan.com/"
            PanType.CLOUD189 -> "https://cloud.189.cn/web/login.html"
            PanType.XUNLEI -> "https://pan.xunlei.com/"
            PanType.GUANGYA -> "https://www.guangyapan.com/"
            PanType.BAIDU -> "https://pan.baidu.com/"
            PanType.MOBILE -> "https://caiyun.139.com/"
        }

        /** 该盘相关的域名（取 cookie 时逐个取、按名去重） */
        fun hostsOf(t: PanType): List<String> = when (t) {
            PanType.QUARK -> listOf("https://pan.quark.cn/", "https://drive-pc.quark.cn/")
            PanType.UC -> listOf("https://drive.uc.cn/", "https://pc-api.uc.cn/")
            PanType.ALI -> listOf("https://www.alipan.com/", "https://openapi.alipan.com/")
            PanType.CLOUD123 -> listOf("https://www.123pan.com/")
            PanType.CLOUD189 -> listOf("https://cloud.189.cn/")
            PanType.XUNLEI -> listOf("https://pan.xunlei.com/")
            PanType.GUANGYA -> listOf("https://www.guangyapan.com/", "https://api.guangyapan.com/")
            PanType.BAIDU -> listOf("https://pan.baidu.com/")
            PanType.MOBILE -> listOf("https://caiyun.139.com/")
        }

        /**
         * 「登录成功」的 cookie 标记键 —— 出现任意一个即判定已登录。
         *
         * 夸克/UC 的判据是**实测**来的：PC 网页登录后 `__pus` / `__puus` / `__uid` 必然出现；
         * 而一份"看着很长却是纯埋点"的 cookie 里这三个一个都没有（见 PITFALLS E37）。
         * 其余盘还没做（`UnsupportedPan`），返回空表 ⇒ 只走手动保存。
         */
        fun loginMarkers(t: PanType): List<String> = when (t) {
            PanType.QUARK, PanType.UC -> listOf("__puus", "__pus", "__uid")
            else -> emptyList()
        }

        /** 打开默认浏览器（登录页里那些"在 App 中打开"的深链，我们放给系统处理） */
        private val EXTERNAL_SCHEMES = listOf(
            "quark", "uc", "alipays", "alipay", "baiduyun", "baidu", "thunder",
            "xunlei", "weixin", "market", "intent", "mailto", "tel"
        )
    }

    private lateinit var binding: ActivityDriveLoginBinding
    private var type: PanType = PanType.QUARK
    private var poll: Job? = null
    private var saved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriveLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        type = PanType.ofKey(intent.getStringExtra(EXTRA_KEY).orEmpty()) ?: PanType.QUARK
        binding.tvTitle.text = getString(R.string.drive_login_title, type.label)
        binding.tvHint.text =
            if (loginMarkers(type).isEmpty()) getString(R.string.drive_login_hint_manual)
            else getString(R.string.drive_login_hint_qr, type.label)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnReload.setOnClickListener { runCatching { binding.web.reload() } }
        binding.btnDone.setOnClickListener { captureAndFinish(manual = true) }

        setupWeb()
        runCatching { binding.web.loadUrl(loginPage(type)) }
        startWatching()
    }

    override fun onDestroy() {
        poll?.cancel()
        // WebView 必须显式销毁：它持有 Activity 的 Context，泄漏的话整个页面都回收不掉
        runCatching { binding.web.stopLoading() }
        runCatching { binding.web.destroy() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ WebView

    private fun setupWeb() {
        val w = binding.web
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // ① 第三方 Cookie —— 没这一行，扫码之后回到主域仍是未登录（见类文档）
        runCatching { cm.setAcceptThirdPartyCookies(w, true) }
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 登录页是 SPA；老设备上 databaseEnabled 关着会让 localStorage 写不进去
            @Suppress("DEPRECATION")
            runCatching { databaseEnabled = true }
            javaScriptCanOpenWindowsAutomatically = true
            // ② 弹窗内联：登录/授权用 window.open，默认会被静默丢弃 ⇒ 白页
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            loadsImagesAutomatically = true
            // 少数登录页仍混着 http 资源，挡掉会白页
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = DESKTOP_UA
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        w.isVerticalScrollBarEnabled = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                // 深链（quark:// 等）交给系统 —— 在我们这里只会报"不能加载"
                if (EXTERNAL_SCHEMES.any { u.startsWith("$it:") }) return true
                return false
            }

            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) {
                binding.progress.progress = p
                if (p >= 100) binding.progress.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------------ 识别

    /**
     * 轮询 cookie，出现登录标记键就自动保存。
     *
     * 只读 `CookieManager`（不注入任何脚本、不碰页面 DOM）：登录流程里我们**不参与**，
     * 只是旁观它有没有成功。这样站点改版也不会把我们搞坏。
     */
    private fun startWatching() {
        val markers = loginMarkers(type)
        if (markers.isEmpty()) return
        poll = lifecycleScope.launch {
            while (isActive) {
                delay(POLL_MS)
                if (saved) return@launch
                val c = readCookies()
                if (markers.any { c.contains("$it=") }) {
                    captureAndFinish(manual = false)
                    return@launch
                }
            }
        }
    }

    /** 合并多个 host 的 cookie（登录态常挂在 `Domain=.xxx` 上，逐 host 取再按名去重最稳） */
    private fun readCookies(): String {
        val cm = CookieManager.getInstance()
        val merged = LinkedHashMap<String, String>()
        for (h in hostsOf(type) + loginPage(type)) {
            val c = runCatching { cm.getCookie(h) }.getOrNull().orEmpty()
            if (c.isBlank()) continue
            for (kv in c.split(';')) {
                val s = kv.trim()
                val i = s.indexOf('=')
                if (i > 0) merged[s.substring(0, i)] = s.substring(i + 1)
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun captureAndFinish(manual: Boolean) {
        if (saved) return
        val c = readCookies()
        val markers = loginMarkers(type)
        // 手动保存时也做一次体检：没有标记键基本等于"还没登录成功"，
        // 直接落盘会变成一个"看着登录了、一播就 401"的状态（E37 那种）
        if (markers.isNotEmpty() && markers.none { c.contains("$it=") }) {
            toast(getString(R.string.drive_login_empty))
            return
        }
        if (c.isBlank()) {
            toast(getString(R.string.drive_login_empty))
            return
        }
        saved = true
        poll?.cancel()
        DriveStore.setCookie(type, c)
        // 换了账号 ⇒ 目录权限可能就不同了，缓存的目录树必须作废
        PanResolver.invalidate()
        runCatching { CookieManager.getInstance().flush() }
        if (!manual) toast(getString(R.string.drive_login_ok, type.label))
        lifecycleScope.launch {
            val st = PanProviders.of(type).verify()
            if (st == DriveState.Expired) {
                saved = false
                toast(getString(R.string.drive_login_bad, type.label))
            } else {
                if (manual) toast(getString(R.string.drive_login_ok, type.label))
                finish()
            }
        }
    }
}
