package com.videoshell.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.videoshell.R
import com.videoshell.data.net.UpdateChecker
import com.videoshell.data.net.UpdateDownloader
import com.videoshell.util.toast
import kotlinx.coroutines.launch

/**
 * 「我的 → 检查更新」的整个交互流程（v1.0.54）。
 *
 * ## 为什么单独一个文件
 *
 * 它有三个**串行**阶段（检查 → 下载 → 安装），每阶段各有自己的失败出口，还夹着
 * 两种进度弹窗和一次系统授权引导。塞进 MainActivity 会把"我的"页的接线淹掉 ——
 * 而 MainActivity 本来就已经在当 pages 的宿主用了。
 *
 * ## 三条容易写错的
 *
 * 1. **versionCode 要从 PackageManager 读，不能用 BuildConfig。**
 *    界面上"本机版本"与"再决定要不要更新"必须是同一个数；`BuildConfig.VERSION_CODE`
 *    在不用 flavor 时也等于它，但一旦哪天加了 flavor/多渠道就会分叉，届时会出现
 *    "提示有新版本 → 下载完安装时又说已安装更高版本"。
 *
 * 2. **进度弹窗的取消要真的生效。** ProgressDialog 默认按返回键就关掉，但协程还在跑、
 *    网还在下。关掉后既没有反馈也停不下来，用户会以为卡死了。所以 setCancelable(false)，
 *    只在**明确的**失败/成功出口里 dismiss。
 *
 * 3. **「已是最新」不能只靠 toast。** 用户点这一下就是为了得到一个答案，
 *    toast 一闪而过（尤其在下拉通知栏时），他只会再点一遍。所以结果落在
 *    「检查更新」那一行的右侧文案上（[setHint]），toast 只做补充。
 */
object UpdateFlow {

    /** 当前 APK 的 versionCode。 */
    fun versionCodeOf(ctx: Context): Int = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        PackageInfoCompat.getLongVersionCode(pi).toInt()
    }.getOrDefault(0)

    /** 当前 APK 的 versionName（失败时给一个占位，不让界面出现空白）。 */
    fun versionNameOf(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    }.getOrNull().orEmpty().ifBlank { "?" }

    /**
     * 跑一遍检查更新。
     *
     * @param onHint 把结果写到「检查更新」那一行右侧（见类注释第 3 条）
     */
    fun start(act: AppCompatActivity, onHint: (String) -> Unit = {}) {
        val sheet = Sheet.indeterminate(act, act.getString(R.string.update_checking))
        act.lifecycleScope.launch {
            val state = UpdateChecker.check(versionCodeOf(act))
            sheet.dismiss()
            when (state) {
                is UpdateChecker.State.Latest -> {
                    val txt = act.getString(R.string.update_latest, versionNameOf(act))
                    onHint(txt)
                    act.toast(txt)
                }

                is UpdateChecker.State.Failed -> {
                    onHint(act.getString(R.string.update_check_failed_short))
                    // 失败原因**原文**照给：这里每一条对应的下一步都不同，
                    // 概括成"检查更新失败"等于让用户唯一能做的事变成"再点一遍"。
                    AlertDialog.Builder(act)
                        .setTitle(R.string.update_check_failed_title)
                        .setMessage(state.reason)
                        .setPositiveButton(R.string.site_doctor_close, null)
                        .show()
                }

                is UpdateChecker.State.Newer -> {
                    onHint(act.getString(R.string.update_found_short, state.info.versionName))
                    confirm(act, state.info)
                }
            }
        }
    }

    /**
     * 「发现新版本」确认弹层（v1.0.60 重做）。
     * 旧版是系统 AlertDialog —— 标题小、说明挤、主次按钮不分权重。
     * 新版：标题放大、说明独立段、元信息（体积/线路）弱化、主按钮琥珀实底。
     */
    private fun confirm(act: AppCompatActivity, info: UpdateChecker.UpdateInfo) {
        val v = LayoutInflater.from(act).inflate(R.layout.dialog_update_confirm, null)
        v.findViewById<TextView>(R.id.tvUpdCfTitle).text =
            act.getString(R.string.update_found_title, info.versionName)
        v.findViewById<TextView>(R.id.tvUpdNotes).text =
            info.notes.ifBlank { act.getString(R.string.update_no_notes) }
        val meta = buildString {
            if (info.size > 0) append(act.getString(R.string.update_size, info.size / 1024 / 1024))
            // 线路要可见：镜像线路意味着"清单出自两个镜像的一致答案"而非 GitHub 本尊（安全语义不同）
            if (info.via.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(act.getString(R.string.update_via, info.via))
            }
        }
        v.findViewById<TextView>(R.id.tvUpdMeta).apply {
            text = meta
            visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
        }
        val dlg = AlertDialog.Builder(act).setView(v).create()
        dlg.show()
        roundedWindow(dlg)
        v.findViewById<MaterialButton>(R.id.btnUpdGo).setOnClickListener {
            runCatching { dlg.dismiss() }
            download(act, info)
        }
        v.findViewById<MaterialButton>(R.id.btnUpdLater).setOnClickListener {
            runCatching { dlg.dismiss() }
        }
    }

    private fun download(act: AppCompatActivity, info: UpdateChecker.UpdateInfo) {
        val sheet = Sheet.determinate(act, act.getString(R.string.update_dl_title))
        act.lifecycleScope.launch {
            val r = UpdateDownloader.download(act, info) { pct, done ->
                if (pct >= 0) act.runOnUiThread {
                    runCatching {
                        // ★ 百分比与字节数都来自回调（旧版只喂 ProgressBar，
                        //   message 里的"0%"是构造时拼死的 —— 用户报的 bug 根因）
                        sheet.progress(pct, done, info.size)
                    }
                }
            }
            sheet.dismiss()
            r.onSuccess { apk ->
                // 授权缺失时**不能**只报"安装失败"：Android 8+ 的这道闸每个应用单独授权，
                // 用户不知道去哪开。这里直接把他送到本应用那一项。
                if (!UpdateDownloader.canInstall(act)) {
                    AlertDialog.Builder(act)
                        .setTitle(R.string.update_need_permission_title)
                        .setMessage(R.string.update_need_permission_msg)
                        .setPositiveButton(R.string.update_need_permission_go) { _, _ ->
                            UpdateDownloader.openInstallPermission(act)
                        }
                        .setNegativeButton(R.string.update_later, null)
                        .show()
                    return@onSuccess
                }
                UpdateDownloader.install(act, apk)
                    .onSuccess { act.toast(act.getString(R.string.update_installing)) }
                    .onFailure { act.toast(act.getString(R.string.update_install_failed, it.message.orEmpty())) }
            }.onFailure {
                AlertDialog.Builder(act)
                    .setTitle(R.string.update_download_failed_title)
                    .setMessage(it.message.orEmpty())
                    .setPositiveButton(R.string.site_doctor_close, null)
                    .show()
            }
        }
    }

    /**
     * 进度弹层（v1.0.60 重做）。**不可取消** —— 见类注释第 2 条：
     * 允许取消只会得到一个"关了但还在下"的界面，既没反馈也停不下来。
     *
     * 不用 `ProgressDialog`：API 26 废弃，且部分 ROM 走系统圆环样式。
     * 自定义布局四件套（标题/百分比/进度条/字节明细）各自独立更新；
     * 窗口背景置透明 + 布局自带 bg_dialog（e1 实底 + 发丝线 + 圆角），
     * 否则系统 window 背景会出现双层圆角。
     */
    private class Sheet private constructor(
        act: AppCompatActivity,
        title: String,
        indeterminate: Boolean
    ) {
        private val bar: ProgressBar
        private val tvPercent: TextView
        private val tvDetail: TextView
        private val dlg: AlertDialog

        /** 检查中：无确数 → 走 indeterminate，隐藏百分比与明细 */
        constructor(act: AppCompatActivity, title: String) : this(act, title, true)

        init {
            val v = LayoutInflater.from(act).inflate(R.layout.dialog_update_progress, null)
            v.findViewById<TextView>(R.id.tvUpdTitle).text = title
            bar = v.findViewById(R.id.pbUpd)
            tvPercent = v.findViewById(R.id.tvUpdPercent)
            tvDetail = v.findViewById(R.id.tvUpdDetail)
            bar.isIndeterminate = indeterminate
            if (indeterminate) {
                tvPercent.visibility = View.GONE
                tvDetail.visibility = View.GONE
            }
            dlg = AlertDialog.Builder(act).setView(v).setCancelable(false).create()
            dlg.setCanceledOnTouchOutside(false)
            dlg.show()
            roundedWindow(dlg)
        }

        companion object {
            fun indeterminate(act: AppCompatActivity, title: String) = Sheet(act, title, true)
            fun determinate(act: AppCompatActivity, title: String) = Sheet(act, title, false)
        }

        /**
         * 下载有确数，切成确定的横向进度；`pct` 越界一律夹住（乱填进度比不动更让人疑心）。
         * 百分比、进度条、字节数**三处一起更新** —— 旧版只更新进度条，文字停在 0%。
         */
        fun progress(pct: Int, doneBytes: Long, totalBytes: Long) {
            runCatching {
                if (bar.isIndeterminate) bar.isIndeterminate = false
                val p = pct.coerceIn(0, 100)
                bar.progress = p
                tvPercent.text = "$p%"
                tvDetail.text = detailText(doneBytes, totalBytes)
            }
        }

        private fun detailText(done: Long, total: Long): String {
            val mb = done / 1024.0 / 1024.0
            return if (total > 0) {
                String.format("%.1f / %.1f MB", mb, total / 1024.0 / 1024.0)
            } else {
                String.format("%.1f MB", mb)
            }
        }

        fun dismiss() = runCatching { dlg.dismiss() }
    }

    /** 权限判断的转发（供 Activity 外部调用，避免直接依赖 UpdateDownloader） */
    fun canInstall(ctx: Context): Boolean = UpdateDownloader.canInstall(ctx)

    /** 弹层窗口背景置透明：布局自带 bg_dialog（e1 实底 + 发丝线 + 圆角），不置透明会双层圆角 */
    private fun roundedWindow(dlg: AlertDialog) {
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    /** 供「我的」页初始化时给一个中性文案，避免那行右侧长期空白 */
    fun idleHint(ctx: Context): String =
        ctx.getString(R.string.update_idle, versionNameOf(ctx))
}
