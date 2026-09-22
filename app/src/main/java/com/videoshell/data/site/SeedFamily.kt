package com.videoshell.data.site

import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 「签名种子配置」族的**运行时自证**（v1.0.53）。
 *
 * 判据全在纯逻辑的 [SeedConfig] 里，这里只做三件事：**发一次请求、按 host 缓存结论、留痕**。
 *
 * ## 为什么结论要按 host 缓存
 *
 * 一个站从"建适配器"到"真正开始分类/搜索"中间会经过好几处（`SiteBrowser` 建一次、
 * `SiteDoctor` 自检再建一次、`AggSearch` 扇出时每个站又建一次）——
 * 不缓存就是每个入口都白打一次 `/config.json`。
 * 缓存到 `ConcurrentHashMap`（进程内）就够了：这一族的探测是**一个几百字节的 GET**，
 * 没必要像加密接口族那样落盘（那族的探测要 POST 并解密，代价高得多）。
 *
 * ## 否定的结论同样缓存
 *
 * 判"不是本族"之后**不再重试**（记空串）。否则每建一次适配器就对一个普通站发一次
 * 无意义的 `/config.json` 请求 —— 那是把"通用机制"变成"对全网站加负担"。
 * 代价是：站点**后来**才换上这一族前端时要重启 App 才会认出来（可以接受，
 * 那时用户也会重新添加站源）。
 */
object SeedFamily {

    private const val TAG = "种子族"

    /** host -> api 基地址；**空串 = 已判定"不是本族"**（不是"没查过"） */
    private val cache = ConcurrentHashMap<String, String>()

    private val lock = Mutex()

    /** 最近一次判定过程的一句话说明（进自检报告） */
    @Volatile
    var lastNote: String = ""
        private set

    private val ORIGIN = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE)

    /**
     * 自证并返回 api 基地址；不是这一族返回 null。
     *
     * `{origin}/config.json` 与站点同源 —— 所以不需要任何域名知识，
     * 站点换域名时这条路径自动跟着走。
     */
    suspend fun resolve(baseUrl: String, referer: String? = null): String? {
        val host = RecipeStore.hostOf(baseUrl)
        if (host.isBlank()) return null
        cache[host]?.let { return it.ifBlank { null } }
        return lock.withLock {
            cache[host]?.let { return@withLock it.ifBlank { null } }
            val api = probe(baseUrl, referer)
            cache[host] = api.orEmpty()
            api
        }
    }

    /** 已经判定过就直接给结论（不再发请求）；没查过返回 null */
    fun cached(baseUrl: String): String? {
        val h = RecipeStore.hostOf(baseUrl)
        if (h.isBlank()) return null
        val v = cache[h] ?: return null
        return v.ifBlank { null }
    }

    /** 这个 host 是不是已经判定过（含否定） */
    fun decided(baseUrl: String): Boolean = cache.containsKey(RecipeStore.hostOf(baseUrl))

    private suspend fun probe(baseUrl: String, referer: String?): String? {
        val origin = ORIGIN.find(baseUrl.trim())?.groupValues?.get(1) ?: return null
        val url = "$origin/config.json"
        val body = Http.getOrNull(url, referer = referer ?: baseUrl)
        if (body == null) {
            lastNote = "没取到 $url（网络或站点不提供该文件）"
            return null
        }
        val api = SeedConfig.apiBase(body)
        lastNote = if (api != null) {
            // 请求本身已经由 Http 记进 NetLog 了；这一条是**判定结论**，给它一个可读的注脚
            NetLog.record(url, 0, 0, "自证命中：$api", TAG)
            "本站自证为**签名种子配置族**（api=$api）"
        } else {
            // 不重复记一条 HTTP 状态（那由 Http 负责，这里编一个就是撒谎）。
            // 报告里靠 lastNote 分清「发了请求但没认出来」与「压根没发（已缓存否定）」。
            "$url 的响应不是种子配置信封"
        }
        return api
    }
}
