package com.videoshell.data.site

import com.videoshell.data.model.MediaSource
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
import com.videoshell.util.resolveUrl
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
            appendNetLog(L)
            return sb.toString()
        }

        // 4) 详情 + 选集
        val id = items.first().id
        L("")
        L("[4] 详情 + 选集（用第一条 id=$id）")
        val dRes = runCatching { a.detail(id) }
        val d = dRes.getOrNull()
        dRes.exceptionOrNull()?.let {
            L("    异常：${it.javaClass.simpleName}: ${it.message}")
        }
        if (d == null) {
            L("    结果：失败")
            appendNetLog(L)
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
            appendNetLog(L)
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
                // 6) 媒体请求 —— 直接看 CDN 给什么状态码
                L("")
                L("[6] 媒体请求")
                val (mst, minfo) = Http.probe(ms.url, site.baseUrl, "bytes=0-1023")
                L("    HTTP $mst")
                if (minfo.isNotBlank()) L("    $minfo")

                // 7) 首个分片 —— "能解析却放不出来"的最后一个盲区：
                //    playlist 拿 200 不代表分片也能下（分片常被单独做防盗链、或落在另一个 CDN）。
                L("")
                L("[7] 首个分片")
                val seg = runCatching { firstSegment(ms.url, site.baseUrl, 0) }.getOrNull()
                if (seg == null) {
                    L("    没能从 playlist 里解析出分片地址")
                } else {
                    val (sst, sinfo) = Http.probe(seg, site.baseUrl, "bytes=0-1023")
                    L("    HTTP $sst")
                    L("    ${seg.take(140)}")
                    if (sinfo.isNotBlank()) L("    $sinfo")
                }
            }
            is MediaSource.Sniff -> {
                L("    HTML 抠不到直链 → 需要网页嗅探（第 6 步跳过）")
                L("    嗅探页：${ms.pageUrl}")
            }
            is MediaSource.Error -> L("    失败：${ms.message}")
            null -> L("    失败：未返回结果")
        }

        appendNetLog(L)
        return sb.toString()
    }

    private fun appendNetLog(L: (String) -> Unit) {
        L("")
        L("---------- HTTP 记录 ----------")
        L(NetLog.report())
    }

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
