package com.videoshell.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.videoshell.R
import com.videoshell.data.model.MediaSource
import com.videoshell.data.pan.DriveState
import com.videoshell.data.pan.DriveStore
import com.videoshell.data.pan.PanProviders
import com.videoshell.data.pan.PanResolver
import com.videoshell.data.pan.PanType
import com.videoshell.databinding.ActivityDriveAccountsBinding
import com.videoshell.databinding.ItemDriveAccountBinding
import com.videoshell.util.toast
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ## 网盘账号（v1.0.65）
 *
 * 「登录放在软件里，而不是源里」这句话的落地页。与 TVBox 的做法有本质区别：
 * TVBox 让你自己去电脑浏览器抓 cookie、再贴进站点配置的 `ext` 里（夸父站那种）——
 * 对手机用户门槛太高，而且一份明文 cookie 会跟着源配置到处传。
 *
 * 这里走 **内嵌 WebView 打开官方登录页 → 取一次 cookie 快照 → 存进 App 自己的加密存储**
 * （[DriveStore]）。取一次快照而不是长期依赖系统 `CookieManager`，是因为后者是**全局共享**的：
 * 站点页面用的 WebView 与网盘登录用的 WebView 会互相污染，而且"退出登录"也无从谈起。
 *
 * > v1.0.65 起，**登录本体的实现搬到了 [DriveLoginActivity]**（全屏 + 扫码 + 自动识别）。
 * > 本页只负责"列表 / 状态 / 退出"，点「登录」直接跳过去 —— 同一件事只留一处实现。
 *
 * ## 为什么要说清"为什么必须登录"
 *
 * 实测（2026-09-23）：夸克/UC 的**列目录是匿名的**（所以分享站的集数不需要登录就能显示），
 * 但**转存与取直链必须登录**（`code:31001 require login [guest]`，且不存在任何匿名的
 * 分享取流端点）。这个区别不解释清楚，用户会以为是"以前能看现在让我登录了"。
 * 所以提示语写的是**"集数不需要登录就能看到，但播放直链必须登录"**。
 */
class DriveAccountsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_KEY = "drive_key"

        fun intent(ctx: Context, driveKey: String = ""): Intent =
            Intent(ctx, DriveAccountsActivity::class.java).putExtra(EXTRA_KEY, driveKey)
    }

    private lateinit var binding: ActivityDriveAccountsBinding
    private val rows = HashMap<String, ItemDriveAccountBinding>()

    /** 从播放页「去登录」跳过来时定位的那个盘 */
    private var focusKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriveAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        focusKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        binding.tvHint.text = getString(R.string.drive_hint)
        binding.btnBack.setOnClickListener { finish() }

        buildRows()
        refresh()
        verifyAll()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ 行

    private fun buildRows() {
        for (p in PanProviders.ordered()) {
            val row = ItemDriveAccountBinding.inflate(layoutInflater, binding.driveList, false)
            row.tvName.text = p.type.label
            rows[p.type.key] = row

            if (!p.supported) {
                // 未实现的盘**如实说"暂不支持"**，而不是给一个点不动的"登录"。
                // 让它出现在列表里是有意义的：用户能看出"这个盘还没做"，
                // 而不是"我明明登录了却播不了"。
                row.tvState.text = getString(R.string.drive_unsupported)
                row.btnLogin.visibility = View.GONE
                row.btnLogout.visibility = View.GONE
            } else {
                row.btnLogin.setOnClickListener { openLogin(p.type) }
                row.btnLogout.setOnClickListener { logout(p.type) }
            }
            if (binding.driveList.childCount > 0) binding.driveList.addView(divider())
            binding.driveList.addView(row.root)
        }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
        )
        setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.line))
    }

    private fun refresh() {
        for (t in PanType.values()) {
            val row = rows[t.key] ?: continue
            val p = PanProviders.of(t)
            if (!p.supported) continue
            val st = DriveStore.state(t)
            val updated = DriveStore.updatedAt(t)
            row.tvState.text = buildList {
                add(st.label)
                if (updated > 0L) add(getString(R.string.drive_updated, fmtDate(updated)))
            }.joinToString(" · ")
            row.btnLogin.text = getString(
                if (st == DriveState.None) R.string.drive_login else R.string.drive_relogin
            )
            row.btnLogout.visibility = if (st == DriveState.None) View.GONE else View.VISIBLE
        }
    }

    private fun verifyAll() {
        lifecycleScope.launch {
            var expired = ""
            for (t in PanType.values()) {
                val p = PanProviders.of(t)
                if (!p.supported) continue
                if (DriveStore.state(t) == DriveState.None) continue
                if (p.verify() == DriveState.Expired) expired = t.label
            }
            refresh()
            if (expired.isNotBlank()) toast(getString(R.string.drive_login_bad, expired))
            // 从播放页「去登录」跳过来：明确告诉他现在该登哪一个
            if (focusKey.isNotBlank()) {
                val fk = PanType.ofKey(focusKey) ?: return@launch
                if (DriveStore.state(fk) == DriveState.None) {
                    toast(getString(R.string.drive_go_login) + "：" + fk.label)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 登录 / 退出

    /**
     * 打开**全屏扫码登录页**（v1.0.65 改）。
     *
     * 原来是「AlertDialog 里塞一个 WebView + 手点『我已完成登录』」，两个硬伤：
     * ① 网盘网页版的登录默认就是**扫码**，二维码被塞进对话框后压成一个小方块，扫不动；
     * ② 手点按钮的时机用户拿不准 —— 点早了 cookie 还没落下来，落盘的是一份**空壳**，
     *    表现成「页面显示已登录、一播就 401」（E37 那种）。
     * 现在整块交给 [DriveLoginActivity]：全屏 + 轮询 cookie 自动识别 + 落盘前体检。
     */
    private fun openLogin(t: PanType) {
        if (!PanProviders.of(t).supported) return
        startActivity(DriveLoginActivity.intent(this, t.key))
    }

    private fun logout(t: PanType) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.drive_logout) + " " + t.label + "？")
            .setMessage(R.string.drive_logout_msg)
            .setPositiveButton(R.string.drive_logout) { _, _ ->
                DriveStore.clear(t)
                PanResolver.invalidate()
                // 顺手清掉 WebView 那边的会话，否则"点了退出、再登录还是原来那个号"
                runCatching {
                    val cm = CookieManager.getInstance()
                    for (h in hostsOf(t)) cm.setCookie(h, "x=; Max-Age=0")
                    cm.flush()
                }
                toast(getString(R.string.drive_logout_ok, t.label))
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 该盘相关的域名（取 cookie / 清会话时逐个处理、按名去重）。
     *
     * **定义在 [DriveLoginActivity]**：退出登录清会话与登录时取 cookie 必须看**同一张表**，
     * 两处各写一份必然漂移 —— 典型症状是「点了退出，再登录还是原来那个号」
     * （清掉的域和取值的域不是同一批）。这里只做转发，不再自己维护一份。
     */
    private fun hostsOf(t: PanType): List<String> = DriveLoginActivity.hostsOf(t)

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}

/**
 * 「需要登录网盘」的统一处置。
 *
 * 放在 [Activity] 上而不是某个页面里：播放页、详情页、校准页都会走到这里，
 * 三个地方各写一遍必然漂移（一处能跳转、一处只能看 toast）。
 */
fun Activity.askDriveLogin(need: MediaSource.NeedLogin) {
    AlertDialog.Builder(this)
        .setTitle(R.string.drive_need_login_title)
        .setMessage(need.message)
        .setPositiveButton(R.string.drive_go_login) { _, _ ->
            startActivity(DriveAccountsActivity.intent(this, need.driveKey))
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
