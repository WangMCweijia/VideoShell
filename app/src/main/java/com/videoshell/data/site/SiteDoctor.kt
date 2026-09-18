package com.videoshell.data.site

import com.videoshell.App
import com.videoshell.data.model.MediaSource
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
import com.videoshell.player.HlsFixDataSource
import com.videoshell.player.OkHttpDataSource
import com.videoshell.player.PlayLog
import com.videoshell.util.resolveUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 站点自检：把整条链路在当前网络下跑一遍，每步都带上 **HTTP 状态码**。
 *
 * 为什么需要它：App 里所有失败都被 `getOrNull` 吞成空列表，用户看到的就是"空"，
 * 无法判断是 DNS/超时/403 还是解析不出东西。这份报告把判断依据直接摆出来，
 * 用户截个图就能定位 —— 不用再靠"猜哪一环断了"。
 *
 * 跑的顺序：首页 → 分类 → 列表 → 详情 → 选集 → 播放地址 → 媒体请求 → 首个分片。
 */
object SiteDoctor {

    suspend fun run(site: SiteConfig): String {
        NetLog.clear()
        NetLog.verbose = true
        val sb = StringBuilder()
        val L: (String) -> Unit = { sb.append(it).append('\n') }

        L("========== 站点自检 ==========")
        L("版本：v${appVersion()}")
        L("名称：${site.name.ifBlank { "(未命名)" }}")
        L("首页：${site.baseUrl}")
        L("模式：${site.apiMode}" + if (site.apiUrl.isBlank()) "（无采集接口 → HTML 适配）" else "，接口 ${site.apiUrl}")
        L("时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))

        val a = AdapterFactory.create(site)
        L("适配器：${a.javaClass.simpleName}")
        L("")

        // 1) 首页
        val (hst, hinfo) = Http.probe(site.baseUrl, site.baseUrl)
        L("[1] 首页请求")
        L("    HTTP $hst")
        if (hinfo.isNotBlank()) L("    $hinfo")
        if (hst < 200 || hst > 399) {
            L("    → 这一步就没通，后面的分类/列表必然为空；先解决这一环（网络/DNS/WAF）")
        }

        // 2) 分类
        L("")
        L("[2] 分类解析")
        val catRes = runCatching { a.categories() }
        val cats = catRes.getOrElse { emptyList() }
        catRes.exceptionOrNull()?.let {
            L("    异常：${it.javaClass.simpleName}: ${it.message}")
        }
        L("    结果：${cats.size} 个分类")
        cats.take(12).forEach { L("      · ${it.name}   ${it.id}") }
        if (cats.isEmpty()) {
            val d = a.lastDiag
            L("    原因：" + d.ifBlank { "（未给出，可能是解析不出结构）" })
        }

        // 3) 列表
        val firstType = cats.firstOrNull()?.id ?: ""
        L("")
        L("[3] 列表解析" + if (firstType.isBlank()) "（首页/最新）" else "（分类：${cats.first().name}）")
        val listRes = runCatching { a.browse(firstType, 1) }
        val items = listRes.getOrElse { emptyList() }
        listRes.exceptionOrNull()?.let {
            L("    异常：${it.javaClass.simpleName}: ${it.message}")
        }
        L("    结果：${items.size} 条")
        items.take(3).forEach { L("      · ${it.name}  id=${it.id}") }

        if (items.isEmpty()) {
            L("")
            L("→ 列表为空，后续步骤无法继续。请把以上内容截图反馈。")
            appendTail(L, site)
            return sb.toString()
        }

        // [3a] 封面取图实测 —— "列表有数据但没图"时，这里直接给出图床的真实反应
        //（状态码 / DNS 对照），不再停留在"解析正常，图就是不出来"的悬案上。
        L("")
        L("[3a] 封面取图实测（列表首条）")
        val cover = items.firstOrNull { it.pic.isNotBlank() }?.pic
        if (cover.isNullOrBlank()) {
            L("    列表条目本身没带封面地址 —— 这是解析层的问题，不是图片加载问题")
        } else {
            L("    封面：${cover.take(160)}")
            val (cst, cinfo) = Http.probe(cover, site.baseUrl, "bytes=0-1023")
            L("    带 Referer 请求：HTTP $cst" + if (cinfo.isNotBlank()) "   $cinfo" else "")
            val host = runCatching { java.net.URI(cover).host }.getOrNull().orEmpty()
            if (host.isNotBlank()) {
                L("    ${Http.dnsReport(host)}")
                L("    解读：系统 DNS 若为空/异常而 DoH 正常 ⇒ 域名被污染（App 已自动走 DoH 兜底）；")
                L("         两边都正常但 HTTP 非 2xx ⇒ 图床按 UA/Referer/IP 拒绝，把状态码发回定位。")
            }
        }

        // 4) 详情 + 选集
        val id = items.first().id
        L("")
        L("[4] 详情 + 选集（用第一条 id=$id）")
        val dRes = runCatching { a.detail(id) }
        val d = dRes.getOrNull()
        dRes.exceptionOrNull()?.let {
            // 只报异常第一行；详细清单由下面的「解析过程」给出，避免同一份内容出现两遍
            L("    异常：${it.javaClass.simpleName}: " +
                it.message.orEmpty().lineSequence().firstOrNull().orEmpty())
        }
        // 适配器记下的「试过哪些地址、各自什么结果」—— 详情失败时这一节就是答案本身
        val dTrace = a.lastDiag
        if (dTrace.isNotBlank()) {
            L("    解析过程：")
            dTrace.lines().take(24).forEach { line -> L("      $line") }
        }
        if (d == null) {
            L("    结果：失败")
            L("    → 把这份报告整体发回即可定位（上面已列出每个试过的地址与结果）")
            appendTail(L, site)
            return sb.toString()
        }
        L("    名称：${d.name}")
        L("    线路：${d.groups.size} 条")
        d.groups.take(6).forEach { g ->
            L("      · ${g.name}   ${g.episodes.size} 集"
                + (g.episodes.firstOrNull()?.let { "   首集 ${it.name} → ${it.url}" } ?: ""))
        }

        val ep = d.groups.firstOrNull()?.episodes?.firstOrNull()
        if (ep == null) {
            L("")
            L("→ 没有可用剧集，后续步骤无法继续。")
            appendTail(L, site)
            return sb.toString()
        }

        // 5) 播放地址解析
        L("")
        L("[5] 播放地址解析")
        val r = runCatching { a.resolve(ep) }
        r.exceptionOrNull()?.let {
            L("    异常：${it.javaClass.simpleName}: ${it.message}")
        }
        when (val ms = r.getOrNull()) {
            is MediaSource.Direct -> {
                L("    直链：${ms.url}")
                L("    HLS：${ms.isHls}    请求头：${ms.headers.keys.joinToString(" ")}")
                // 把「编码前长什么样」也摆出来：媒体路径常含中文，
                // 一眼就能看出这条地址有没有踩到"播放器不编码"那个坑。
                val rawUrl = percentDecode(ms.url)
                val hasNonAscii = rawUrl != ms.url
                if (hasNonAscii) {
                    L("    编码前：$rawUrl")
                    L("    ⚠️ 原地址含中文等非 ASCII 字符，已自动百分号编码")
                }

                // 6) 媒体请求 —— 直接看 CDN 给什么状态码
                L("")
                L("[6] 媒体请求")
                val (mst, minfo) = Http.probe(ms.url, site.baseUrl, "bytes=0-1023")
                L("    HTTP $mst")
                if (minfo.isNotBlank()) L("    $minfo")

                // 7) 首个分片 —— "能解析却放不出来"的最后一个盲区：
                //    playlist 拿 200 不代表分片也能下（分片常被单独做防盗链、或落在另一个 CDN）。
                //    这里**真的下一段**（512KB）并计时：旧实现只取 `bytes=0-1023`，
                //    1KB 在任何链路上都是秒回，只能证明"地址存在"，证明不了"扛得住播放"。
                L("")
                L("[7] 首个分片（真下 512KB 测速）")
                val seg = runCatching { firstSegment(ms.url, site.baseUrl, 0) }.getOrNull()
                if (seg == null) {
                    L("    没能从 playlist 里解析出分片地址")
                } else {
                    L("    ${seg.take(140)}")
                    val (sst, sbytes, sms) = Http.sample(seg, site.baseUrl, 512L * 1024)
                    val kbps = if (sms > 0) sbytes * 1000.0 / sms / 1024.0 else 0.0
                    L("    HTTP $sst   实际下载 ${sbytes / 1024}KB / ${sms}ms ≈ ${"%.0f".format(kbps)} KB/s")
                    L("    " + speedVerdict(kbps, sbytes))
                }

                // 8) 请求栈对照 —— 本次问题的关键证据。
                //    自检与抓页面走 OkHttp；而 ExoPlayer 的 DefaultHttpDataSource 底层是
                //    HttpURLConnection，**它不会自动对非 ASCII 路径做百分号编码**。
                //    同一个地址、同一个 CDN，两套栈结果可能完全不同 —— 这就是
                //    "自检全绿、播放全挂"唯一说得通的解释，而且能在你的设备上直接复现出来。
                L("")
                L("[8] 请求栈对照（播放器 vs 自检）")
                if (!hasNonAscii) {
                    L("    本地址是纯 ASCII，两套栈发出的字节一致 —— 播放失败不是这个原因")
                } else {
                    val okSt = Http.probe(ms.url, site.baseUrl, "bytes=0-1023").first
                    val hucEnc = withContext(Dispatchers.IO) { hucStatus(ms.url, site.baseUrl) }
                    val hucRaw = withContext(Dispatchers.IO) { hucStatus(rawUrl, site.baseUrl) }
                    L("    OkHttp（自检栈）        : HTTP $okSt")
                    L("    HttpURLConnection 编码后: HTTP $hucEnc")
                    L("    HttpURLConnection 未编码: HTTP $hucRaw   ← 修复前播放器发的就是这个形式")
                    L("    解读：未编码那一行若是 404/-1，说明中文被原样塞进请求行，CDN 找不到资源。")
                }

                // 9) 播放器栈实测 —— 直接用播放器那套 DataSource 跑一遍。
                //    "自检绿、播放挂"本质上是"两条栈不一样"。v1.0.9 起播放器也走 OkHttp，
                //    所以这里的结果**就是**播放器会看到的结果，两边再也不会分叉。
                L("")
                L("[9] 播放器栈实测（与播放器同一个 OkHttpDataSource）")
                val head = ms.headers
                val pl = withContext(Dispatchers.IO) { playStackProbe(ms.url, head, 0L) }
                L("    playlist : ${pl.first}   ${pl.second}")
                if (seg != null) {
                    val sg = withContext(Dispatchers.IO) {
                        playStackProbe(seg, head, 512L * 1024)
                    }
                    L("    分片     : ${sg.first}   ${sg.second}")
                }
                L("    说明：上面两行若是 200/206，说明**播放器那一侧的网络层是通的**；")
                L("         若这样还播不出来，原因就在解码/格式，而不是地址或链路。")
            }
            is MediaSource.Sniff -> {
                L("    HTML 抠不到直链 → 需要网页嗅探（第 6 步跳过）")
                L("    嗅探页：${ms.pageUrl}")
            }
            is MediaSource.Error -> L("    失败：${ms.message}")
            null -> L("    失败：未返回结果")
        }

        appendTail(L, site)
        return sb.toString()
    }

    /** 报告头带上版本号：免得"装的到底是哪个包"来回扯不清 */
    private fun appVersion(): String = runCatching {
        val c = App.instance
        c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()
    }.getOrDefault("?")

    /**
     * 速度判语。
     * 经验阈值：单码率 HLS 的分片约 3~5 秒、300KB~1MB，要跟上播放至少得 ~100KB/s。
     */
    private fun speedVerdict(kbps: Double, bytes: Long): String = when {
        bytes <= 0L -> "→ 一个字节都没下下来：分片 CDN 可能被单独做了防盗链"
        kbps >= 800 -> "→ 链路充裕"
        kbps >= 300 -> "→ 够播（高清单码率约需 150~300KB/s）"
        kbps >= 100 -> "→ 偏慢，高码率片源会卡"
        kbps >= 30 -> "→ 很慢，播放大概率一直转圈"
        else -> "→ 极慢，基本等于连不通"
    }

    /**
     * 用**播放器同一套 DataSource**（`OkHttpDataSource` + `HlsFixDataSource`）真开一次地址。
     *
     * 这一步的意义：以前自检走 OkHttp、播放器走 HttpURLConnection，"自检绿、播放挂"
     * 无法在报告里复现。现在两边同栈，这里的结果就是播放器会看到的结果。
     */
    private fun playStackProbe(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long
    ): Pair<String, String> {
        val ds = HlsFixDataSource(OkHttpDataSource(Http.mediaClient, Http.UA, headers))
        return try {
            val t0 = System.currentTimeMillis()
            val reported = ds.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(url)))
            // playlist 给 64KB 上限就够（够看全整份清单结构）；分片按调用方给的上限
            val cap = if (maxBytes > 0L) maxBytes else 64L * 1024
            val buf = ByteArray(32 * 1024)
            var n = 0L
            while (n < cap) {
                val want = minOf(buf.size.toLong(), cap - n).toInt()
                if (want <= 0) break
                val r = ds.read(buf, 0, want)
                if (r < 0) break
                n += r
            }
            val ms = System.currentTimeMillis() - t0
            runCatching { ds.close() }
            "OK（open 报 ${reported}B）" to "读到 ${n / 1024}KB / ${ms}ms"
        } catch (e: Exception) {
            val code = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<androidx.media3.datasource.HttpDataSource
                    .InvalidResponseCodeException>()
                .firstOrNull()?.responseCode
            val msg = (if (code != null) "HTTP $code " else "") +
                e.javaClass.simpleName + ": " + (e.message ?: "").take(150)
            msg to "（未读到数据）"
        }
    }

    private fun appendNetLog(L: (String) -> Unit) {
        L("")
        L("---------- HTTP 记录 ----------")
        L(NetLog.report())
    }

    /**
     * 报告尾部：HTTP 记录 + 播放记录。
     *
     * 播放记录是这一版新加的通道 —— 播放器/嗅探自己失败的细节以前带不回来
     * （错误面板只能截图，嗅探页报告又复制不出来），现在随自检报告一起走。
     */
    private fun appendTail(L: (String) -> Unit, site: SiteConfig) {
        appendNetLog(L)
        L("")
        L("---------- 站点配方（已固化到本地，跨页面 / 跨启动复用） ----------")
        L(RecipeStore.describe(site.baseUrl))
        L("")
        L("---------- 播放记录（播放器 / 嗅探自己写的） ----------")
        L(PlayLog.report())
    }

    /** 把 URL 里的 `%XX` 还原回字节再按 UTF-8 解释：用于看"这条地址原本长什么样" */
    private fun percentDecode(url: String): String {
        if (!url.contains('%')) return url
        val out = StringBuilder(url.length)
        val buf = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < url.length) {
            val c = url[i]
            if (c == '%' && i + 2 < url.length) {
                val v = url.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    buf.write(v)
                    i += 3
                    continue
                }
            }
            if (buf.size() > 0) {
                out.append(String(buf.toByteArray(), Charsets.UTF_8))
                buf.reset()
            }
            out.append(c)
            i++
        }
        if (buf.size() > 0) out.append(String(buf.toByteArray(), Charsets.UTF_8))
        return out.toString()
    }

    /**
     * 用 `HttpURLConnection` 取状态码 —— 这就是 ExoPlayer `DefaultHttpDataSource` 的底层。
     * 用来在**真机上直接对照**两套请求栈对同一地址的反应，不用再靠推断。
     * 失败（含 URL 非法/连不通）返回 -1。
     */
    private fun hucStatus(url: String, referer: String): Int = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("User-Agent", Http.UA)
        if (referer.isNotBlank()) c.setRequestProperty("Referer", referer)
        c.setRequestProperty("Range", "bytes=0-1023")
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        val st = c.responseCode
        runCatching { c.inputStream?.close() }
        runCatching { c.errorStream?.close() }
        c.disconnect()
        st
    }.getOrDefault(-1)

    /**
     * 从 playlist 里取**第一个分片**地址；遇到 master 清单（`#EXT-X-STREAM-INF`）先下沉一层。
     * 返回 null 表示拿不到（网络失败 / 不是 m3u8 / 只有标签没有分片）。
     */
    private suspend fun firstSegment(url: String, referer: String, depth: Int): String? {
        if (depth > 2) return null
        val text = Http.getOrNull(url, referer) ?: return null
        if (!text.contains("#EXTM3U")) return null
        val first = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?: return null
        val child = resolveUrl(url, first)
        if (child.isBlank()) return null
        return if (text.contains("#EXT-X-STREAM-INF")) firstSegment(child, url, depth + 1) else child
    }
}
