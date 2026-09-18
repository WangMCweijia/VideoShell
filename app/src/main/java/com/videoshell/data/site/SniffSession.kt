package com.videoshell.data.site

/**
 * 会话级「请求姿态」记忆（v1.0.25）。
 *
 * 一批站点（尤其走解析接口 / 带防盗链的 CDN）只有在**带着正确 Referer/Origin** 时才下发流。
 * 嗅探页里我们本来就知道正确的 Referer（就是那个播放页），但那个信息以前只活在
 * SniffActivity 的局部变量里 —— 换成同一站的其它集、或者从历史记录再次进入播放，
 * 请求姿态又空了，于是"同一部剧昨天能播今天不能"。
 *
 * 这里按 host 记住最近一次嗅探/解析成功时的 Referer 与 UA，播放器与嗅探共用。
 */
object SniffSession {

    private val referers = LinkedHashMap<String, String>()
    private val agents = LinkedHashMap<String, String>()

    private const val MAX = 32

    @Synchronized
    fun remember(pageUrl: String, referer: String, ua: String = "") {
        val host = hostOf(pageUrl) ?: return
        if (host.isBlank()) return
        if (referer.isNotBlank()) referers[host] = referer
        if (ua.isNotBlank()) agents[host] = ua
        while (referers.size > MAX) referers.remove(referers.keys.first())
    }

    @Synchronized
    fun refererFor(url: String): String? {
        val host = hostOf(url) ?: return null
        referers[host]?.let { return it }
        // 退回同域：页面与流常常不同子域（play.a.com 与 cdn.a.com）
        val root = rootDomain(host)
        return referers.entries.firstOrNull { rootDomain(it.key) == root }?.value
    }

    @Synchronized
    fun uaFor(url: String): String? {
        val host = hostOf(url) ?: return null
        return agents[host]
    }

    /** 给一组请求头补上会话记忆里的 Referer/UA（缺失才补，不覆盖调用方显式给的值） */
    fun enrich(url: String, headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(headers)
        if (out.keys.none { it.equals("Referer", true) }) {
            refererFor(url)?.let { out["Referer"] = it }
        }
        if (out.keys.none { it.equals("User-Agent", true) }) {
            uaFor(url)?.let { out["User-Agent"] = it }
        }
        return out
    }

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url.trim()).host?.lowercase()
    }.getOrNull()

    private fun rootDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }
}
