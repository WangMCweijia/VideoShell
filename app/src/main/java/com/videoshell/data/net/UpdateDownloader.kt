package com.videoshell.data.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 自更新的**下载与安装**（[UpdateChecker] 的后半程）。
 *
 * ## 三个必须分开说的坑
 *
 * 1. **读超时不能用默认值。** 普通请求走 `Http.client`（读超时 10 秒），而安装包有十几 MB ——
 *    照搬那个 client 的结果是"下到 40% 就断"，而且报的是超时，看起来像网络问题。
 *    这里派生一个长超时的 client（共享连接池/DNS/CookieJar，只放宽超时）。
 *
 * 2. **每次先清掉旧包。** 目录里留着上一版的 APK，安装时点错文件是"装了却没变化"的经典成因
 *    —— 与「同一版本号两代包」是同一类故障（PITFALLS §4.38）。
 *
 * 3. **必须校验 sha256 再安装。** 清单里有摘要，下完先算再比。这是唯一能区分
 *    "下全了"与"下了一半但 HTTP 仍返回成功"的判据 —— 不校验的话，残缺包的报错会出现在
 *    安装器里（"解析包时出现问题"），排查方向直接跑偏。
 *
 * 安装走 [FileProvider]（Android 7.0 起禁止 `file://` 外泄），Android 8.0 起还需要
 * 「允许安装未知来源应用」这一用户授权 —— 没授权时要**引导过去**，不能只说一句"安装失败"。
 */
object UpdateDownloader {

    private val dlClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 下载目录：应用外部私有目录（不需要存储权限，卸载随应用一起清掉） */
    private fun dirOf(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "update").apply { mkdirs() }

    /**
     * 下载并校验。
     *
     * @param onProgress (百分比（-1 = 总长未知）, 已下载字节)
     */
    suspend fun download(
        ctx: Context,
        info: UpdateChecker.UpdateInfo,
        onProgress: (Int, Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = dirOf(ctx)
            // 坑 2：先清旧的，免得"装上的还是上一版"
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".apk", true)) f.delete()
            }
            val target = File(dir, "videoshell-${info.versionName}.apk")

            val req = okhttp3.Request.Builder().url(info.apkUrl).build()
            dlClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("响应体为空")
                val total = body.contentLength().takeIf { it > 0 } ?: info.size
                body.byteStream().use { input ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(
                                if (total > 0) ((done * 100) / total).toInt() else -1, done
                            )
                        }
                    }
                }
            }

            if (target.length() <= 0L) throw IOException("下载到 0 字节")

            // 坑 3：摘要对不上就丢弃 —— 宁可不装，也不能装一个半残的
            if (info.sha256.isNotBlank()) {
                val got = sha256(target)
                if (!got.equals(info.sha256, ignoreCase = true)) {
                    target.delete()
                    throw IOException(
                        "摘要校验失败（期望 ${info.sha256.take(12)}…，实得 ${got.take(12)}…）——" +
                                "包可能没下全或被改动，已丢弃"
                    )
                }
            }
            target
        }
    }

    internal fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Android 8.0+ 是否已允许本应用安装未知来源；低版本恒为 true（没有这道闸） */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** 跳到「允许安装未知来源应用」的系统设置页（带 package 参数，直接落到本应用那一项） */
    fun openInstallPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 拉起系统安装器。授权缺失时返回失败原因，交给调用方提示并引导授权。 */
    fun install(ctx: Context, apk: File): Result<Unit> = runCatching {
        if (!apk.exists()) throw IOException("安装包不存在：${apk.name}")
        if (!canInstall(ctx)) throw SecurityException("还没有「允许安装未知来源应用」的授权")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}
