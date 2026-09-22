package com.videoshell.data.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
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

    /** 探测专用：Range 取前 128KB 量速，短超时、不重试（探测卡住就放弃那条候选） */
    private val probeClient by lazy {
        Http.fastClient
    }

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

            // 候选地址：直连两条在前（api 资产 / 裸链），镜像包一层在后（UpdateMirror.candidates）。
            // 排序依据是真探测：各候选并发量一次速（见 [probeAll]），**快的在前**。
            // v1.0.55 的"api 在前"只是静态顺序，v1.0.56 起由实测吞吐决定 ——
            // 实测直连 74 KB/s vs 镜像 1860 KB/s，顺序本身就是十几倍的下载时长。
            val cands = UpdateMirror.candidates(info.apkUrl, info.apkApiUrl)
            val speed = probeAll(cands)
            val ordered = UpdateMirror.rank(cands, speed)

            var lastErr: Exception? = null
            var ok = false
            for (u in ordered) {
                try {
                    transfer(u, target, info, onProgress)
                    // ★ sha256 校验必须在**候选循环之内**（v1.0.56 修正）：
                    // 旧版放在循环外，"200 但给的是 HTML"的说谎镜像会把唯一一次
                    // 摘要校验机会用在废数据上，然后整个更新直接失败 ——
                    // 正确行为是"这个候选作废，换下一个"。
                    if (info.sha256.isNotBlank()) {
                        val got = sha256(target)
                        if (!got.equals(info.sha256, ignoreCase = true)) {
                            target.delete()
                            throw IOException(
                                "摘要不符（期望 ${info.sha256.take(12)}…，实得 ${got.take(12)}…）" +
                                        "—— 该线路给的不是这个包，换线路"
                            )
                        }
                    }
                    ok = true
                    break
                } catch (e: Exception) {
                    // 换下一条前**必须**清掉半截文件：留着它，第二条又失败时
                    // 会拿第一条的残骸去过摘要，报出来的是"摘要校验失败"而不是真实原因。
                    target.delete()
                    lastErr = e
                }
            }
            if (!ok) {
                throw lastErr ?: IOException("没有可用的下载地址")
            }

            if (target.length() <= 0L) throw IOException("下载到 0 字节")
            target
        }
    }

    /**
     * 并发探测每个候选：`Range: bytes=0-131071` 取前 128KB，算 KB/s。
     *
     * 两道门槛，都是被实测逼出来的：
     * 1. **响应必须以 ZIP 魔数开头**（[UpdateMirror.looksLikeApk]）——
     *    `ghps.cc` / `gh-proxy.net` 对任何地址都返回 `200 + HTML`，只看状态码
     *    它们反而是"最快的线路"；不验魔数，整个测速就是在给说谎者排名。
     * 2. 探测失败（超时/非 200/非 ZIP）= 该候选**不进测速表**，而不是淘汰：
     *    探测只是一次采样，直连在慢速网络上探测超时、整包却可能下得动。
     *
     * 全部并发执行（约 1~3 秒），随后按 [UpdateMirror.rank] 排序。
     */
    private suspend fun probeAll(cands: List<String>): Map<String, Double> =
        coroutineScope {
            cands.map { u -> async { u to probeOnce(u) } }
                .awaitAll()
                .mapNotNull { (u, s) -> s?.let { u to it } }
                .toMap()
        }

    /** 单条探测。返回 KB/s；失败返回 null（字节不足以判 ZIP / 网络 / 非 200 都算） */
    private suspend fun probeOnce(url: String): Double? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url)
                    .header("Accept", "application/octet-stream")
                    .header("Range", "bytes=0-131071")
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body ?: return@withContext null
                    val src = body.byteStream()
                    val head = ByteArray(4)
                    var n = 0
                    while (n < 4) {
                        val r = src.read(head, n, 4 - n)
                        if (r < 0) break
                        n += r
                    }
                    if (!UpdateMirror.looksLikeApk(head.copyOf(n))) return@withContext null
                    val t0 = System.currentTimeMillis()
                    var total = n.toLong()
                    val buf = ByteArray(32 * 1024)
                    while (total < 128 * 1024) {
                        val r = src.read(buf, 0, buf.size)
                        if (r < 0) break
                        total += r
                    }
                    val ms = System.currentTimeMillis() - t0
                    if (ms <= 0 || total < 64 * 1024) return@withContext null
                    total / 1024.0 / (ms / 1000.0)
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * 从一个地址把包拖到 `target`。
     *
     * ⚠️ `Accept: application/octet-stream` **不能省**：api 的资产地址不带它返回的是
     * 那段资产的 JSON 元数据（几百字节），而不是包本身 —— 于是 sha256 校验会失败，
     * 报出来的原因却是"摘要对不上"，排查方向直接跑偏。对裸链它无害（照样 302 到 CDN）。
     */
    private fun transfer(
        url: String,
        target: File,
        info: UpdateChecker.UpdateInfo,
        onProgress: (Int, Long) -> Unit
    ) {
        val req = Request.Builder().url(url)
            .header("Accept", "application/octet-stream")
            .build()
        dlClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} @ $url")
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
                        onProgress(if (total > 0) ((done * 100) / total).toInt() else -1, done)
                    }
                }
            }
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
