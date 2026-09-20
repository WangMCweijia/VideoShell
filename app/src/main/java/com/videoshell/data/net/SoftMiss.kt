package com.videoshell.data.net

import java.security.MessageDigest

/**
 * ## 通用「软 404 / 首页回退」守卫（v1.0.34）
 *
 * ### 这一类故障长什么样
 *
 * 站点的搜索路由被关掉（或被 WAF 挡掉）时，很多站**不返回 404**，而是**把首页原样吐回来**：
 * 状态码 200、HTML 完整、页面里甚至真有几十条影片链接 —— 因为那是首页自己的推荐位。
 *
 * 实测（野果短剧 `agenda.fzchosdi.cc`，2026-09-19）：
 *
 * | 请求 | HTTP | 字节 | 与首页 sha256 |
 * |---|---|---|---|
 * | `/?s=庆余年` | 200 | 246747 | **完全相同** |
 * | `/?s=zzzq不存在的词` | 200 | 246747 | **完全相同** |
 * | `/search/drama/庆余年/` | 200 | 5801 | 不同（JS 空壳） |
 *
 * ### 为什么它比"解析不对"难查得多
 *
 * 解析器一切正常 —— 它确实从一份真 HTML 里抠出了一份真列表。壳子于是把这份**首页推荐位**
 * 当成"搜索结果"存下来，还把 `/?s={kw}` **固化进配方**。用户看到的是「**搜什么都一样**」，
 * 而所有常规判据（状态码、条数、详情链接数）**全是绿的**。
 *
 * ### 判据：对照组的思路
 *
 * 「能出结果」不是证据 —— **错误的输入也应该出零结果**才是。所以这里不数结果，
 * 只看**这份响应跟首页是不是同一个页面**：
 *
 * - 字节级等同（长度 + 链接集合哈希都一样）⇒ 确定是首页副本；
 * - 近乎等同（标题一致 + 唯一链接数一致 + 长度差 <1%）⇒ 疑似首页副本（随机广告位/时间戳会差一点）。
 *
 * ### 为什么是通用机制而不是给野果写个补丁
 *
 * 「软 404 回首页」是**一整类**站点行为（凡是把 404 交给首页兜底的站都会这样），
 * 与站点用什么 CMS、目录名叫什么完全无关。30 多个版本的教训反复指向同一条：
 * **能测量的就别去猜形状**。所以这里只依赖「首页」这一个所有站都有的参照物。
 *
 * ⚠️ **拿不到首页时一律放行**（返回 false）。守卫的职责是拦下"确定的错"，
 * 不是"猜着拦" —— 宁可漏收，不可错收（本项目的白纸黑字纪律）。
 */
object SoftMiss {

    /** 一份页面的「身份」：长度 + 标题 + 唯一链接集合 —— 三个都极易取，又能把整页概括住 */
    data class Sig(
        val len: Int,
        val title: String,
        val links: Int,
        val linkHash: String
    )

    /** host -> 首页原 HTML（进程内缓存，一个站只抓一次首页） */
    private val homes = HashMap<String, String>()

    /** host -> 一句话结论（自检报告用） */
    private val suspect = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun hostOf(url: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE)
            .find(url.trim())?.groupValues?.get(1)?.lowercase().orEmpty()

    /**
     * 把一份 HTML 概括成 [Sig]。
     *
     * 链接集合用**排序去重的 href 全集** —— 它比整页字节哈希稳（同一页面两次抓取，
     * 随机广告位/轮播顺序会变），又比"数链接个数"分辨力强得多。
     */
    fun sigOf(html: String): Sig {
        val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()?.lowercase()?.take(80).orEmpty()
        val hrefs = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
        return Sig(html.length, title, hrefs.size, sha16(hrefs.joinToString("\n")))
    }

    private fun sha16(s: String): String = runCatching {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        d.take(8).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /**
     * 记下首页。
     *
     * **调用方有机会时务必顺手喂一次**（例如 `categories()` 本来就抓了首页）——
     * 这样 [isHomeCopy] 不必额外发请求，守卫就是纯零成本。
     */
    fun rememberHome(baseUrl: String, html: String) {
        val h = hostOf(baseUrl)
        if (h.isBlank() || html.isBlank()) return
        runCatching { homes[h] = html }
    }

    /** 首页原 HTML；没缓存就抓一次（一个站每个进程只抓一次）。拿不到返回 null ⇒ 守卫放行 */
    suspend fun homeHtml(baseUrl: String): String? {
        val h = hostOf(baseUrl)
        if (h.isBlank()) return null
        homes[h]?.let { return it }
        val html = runCatching { Http.getOrNull(baseUrl, referer = baseUrl) }.getOrNull()
        if (html.isNullOrBlank()) return null
        homes[h] = html
        return html
    }

    /**
     * **纯函数版**判据（不发请求）：给定首页 HTML，判这份 HTML 是不是它的副本；
     * 是则返回一句人话结论，不是返回 null。
     *
     * 抽成纯函数是为了**可离线断言** —— 一个判据如果只能在真机 + 真网络下验，
     * 它就会在回归里失效（本项目的硬纪律）。[isHomeCopy] 只是"先取首页、再调它"。
     */
    fun whyCopyOf(homeHtml: String, html: String): String? {
        if (homeHtml.isBlank() || html.isBlank()) return null
        val home = sigOf(homeHtml)
        val now = sigOf(html)

        // ① 字节级等同：长度一致 + 链接集合哈希一致（野果 `/?s=` 就是这一档）
        if (home.linkHash.isNotEmpty() && home.len == now.len && home.linkHash == now.linkHash) {
            return "与**首页完全相同**（${now.len} B，链接集合一致）"
        }

        // ② 近乎等同：标题一致 + 唯一链接数一致 + 长度差 <1%。
        //    随机广告位、时间戳这类动态段会让字节级判据失手，这一档兜住。
        if (home.title.isNotEmpty() && home.title == now.title && home.links == now.links &&
            kotlin.math.abs(home.len - now.len) <= (home.len / 100).coerceAtLeast(64)
        ) {
            return "与首页**近乎完全相同**（${now.len} vs ${home.len} B，标题相同、唯一链接数相同）"
        }
        return null
    }

    fun isCopyOf(homeHtml: String, html: String): Boolean = whyCopyOf(homeHtml, html) != null

    /** 这份 HTML 是不是「首页的回退副本」；命中会写进自检报告 */
    suspend fun isHomeCopy(baseUrl: String, html: String): Boolean {
        val home = homeHtml(baseUrl) ?: return false
        val why = whyCopyOf(home, html) ?: return false
        note(baseUrl, "该地址返回的页面$why ⇒ 软 404 回首页，结果与关键词无关")
        return true
    }

    private fun note(baseUrl: String, s: String) {
        val h = hostOf(baseUrl)
        if (h.isNotBlank()) suspect.putIfAbsent(h, s)
    }

    /** 直接读结论（自检报告用）；没发现过返回空串 */
    fun describe(baseUrl: String): String = suspect[hostOf(baseUrl)].orEmpty()

    /** 离线断言用：清掉进程内缓存（用例之间互相隔离） */
    fun reset() {
        homes.clear()
        suspect.clear()
    }
}
