package com.videoshell.data.site

/**
 * HTML 站点的「链接语义识别」+ URL 模板候选。
 *
 * 核心难点：maccms 系主题对分类页 / 详情页的 URL 约定各不相同，同一个 `/vod/{id}.html`
 * 在 A 主题里是分类页、在 B 主题里是详情页。这里用「该站是否还存在强详情链接」
 * （`/detail/`、`/voddetail/`、`/play/x-y-z.html`）来消歧：
 *
 * - 有强详情链接 -> `/vod/{id}.html` 判为**分类页**
 * - 没有        -> `/vod/{id}.html` 判为**详情页**
 */
object HtmlTemplates {

    /** 强详情：一眼就是影片详情 / 播放页，与分类页不可能混淆 */
    private val STRONG_DETAIL = listOf(
        Regex("/detail/(?:id/)?(\\d+)"),
        Regex("/detail/([\\w-]+)\\.html"),
        Regex("voddetail/(\\d+)"),
        Regex("vod/detail/id/(\\d+)"),
        Regex("vodplay/(\\d+)"),
        Regex("/show/(\\d+)"),
        Regex("/vodplay/[\\w-]*-\\d+-\\d+")
    )

    /**
     * 播放页链接：用于判断站点是否走「详情页 + 播放页」结构。
     * `(?:^|[/_\-])play[/_\-]` 覆盖 `/play/`、`/v_play/`、`-play-`、`_play/` 等各家写法
     * （厂长资源用的是 `/v_play/{base64}.html`）。
     */
    private val PLAY = listOf(
        Regex("/play/"),
        Regex("/v_play/"),
        Regex("vplay/"),
        Regex("(?:^|[/_\\-])play[/_\\-]"),
        Regex("vodplay/"),
        Regex("/vod/play"),
        Regex("play/id/"),
        Regex("/watch/"),
        Regex("/episode/")
    )

    /**
     * 分集链接的宽松判据：只在"候选容器内部"用。
     * 容器里的链接本来就该是分集，判太严会把 `{id}-{sid}-{nid}.html` 这类漏掉。
     */
    private val EPISODE_HINT = Regex(
        "(play|watch|episode|vod/\\d+-\\d+|/v/\\d+|-\\d+-\\d+\\.html|/vod/\\d+/\\d+/\\d+|/vod/\\d+\\.html\\?sid=)",
        RegexOption.IGNORE_CASE
    )

    /** 弱详情：可能是详情页，也可能是分类页，取决于站点主题 */
    private val WEAK_DETAIL = listOf(
        Regex("vod/(\\d+)\\.html"),
        Regex("/vod/(\\d+)$")
    )

    /**
     * 通用「单段路径 + 数字 + .html」详情页：`/movie/23804.html`、`/film/123.html`、`/tv/45.html`…
     *
     * 与 [WEAK_DETAIL] 的区别：这类路径**和分类页形状不冲突**（分类页是 `/vodshow/id/6.html`
     * 那种多段带前缀的），所以不依赖 `vodIsCategory` 的判断，任何时候都能认。
     * 唯一的重叠是 `/vod/{id}.html`，但它作为分类页时是**导航链接、没有海报**，
     * 会被抽取器的「卡片必须自带图片」挡掉。
     */
    private val GENERIC_DETAIL = Regex("/[A-Za-z][A-Za-z0-9_\\-]{1,20}/(\\d+)\\.html")

    /** 分类（列表）页链接 */
    private val CATEGORY = listOf(
        Regex("vodshow/class/[^/]+/id/(\\d+)"),
        Regex("vodshow/id/(\\d+)"),
        Regex("vodshow/(\\d+)"),
        Regex("vodtype/(\\d+)"),
        Regex("vod/type/id/(\\d+)"),
        Regex("type/id/(\\d+)"),
        Regex("vod/list/(\\d+)"),
        Regex("/list/(\\d+)"),
        Regex("/type/(\\d+)"),
        Regex("/vod/(\\d+)\\.html")
    )

    private val CATEGORY_AMBIGUOUS = "/vod/(\\d+)\\.html"

    fun isStrongDetail(href: String): Boolean = STRONG_DETAIL.any { it.containsMatchIn(href) }

    fun isPlayLink(href: String): Boolean = PLAY.any { it.containsMatchIn(href) }

    /** 分集链接（容器内使用的宽松判据） */
    fun isEpisodeLink(href: String): Boolean {
        val h = href.trim()
        if (h.isEmpty()) return false
        if (h.startsWith("javascript") || h.startsWith("#") || h.startsWith("mailto")) return false
        return isPlayLink(h) || EPISODE_HINT.containsMatchIn(h)
    }

    /** 说明本站走「独立详情页 + 独立播放页」结构 —— 此时 `/vod/{id}.html` 属于分类页 */
    fun isDetailSignal(href: String): Boolean = isStrongDetail(href) || isPlayLink(href)

    /** 影片 id（拿不到返回 null） */
    fun videoIdOf(href: String, vodIsCategory: Boolean = false): String? {
        firstGroup(STRONG_DETAIL, href)?.let { return it }
        firstGroup(listOf(GENERIC_DETAIL), href)?.let { return it }
        if (!vodIsCategory) firstGroup(WEAK_DETAIL, href)?.let { return it }
        return null
    }

    /** 分类 id（不是分类链接返回 null） */
    fun typeIdOf(href: String, vodIsCategory: Boolean = false): String? {
        if (isDetailSignal(href)) return null
        val pats = if (vodIsCategory) CATEGORY else CATEGORY.filter { it.pattern != CATEGORY_AMBIGUOUS }
        return firstGroup(pats, href)
    }

    fun isCategoryHref(href: String, vodIsCategory: Boolean): Boolean =
        typeIdOf(href, vodIsCategory) != null

    /**
     * 「目录式分类」：`/meijutt`、`/riju`、`/gcj`、`/zuixindianying` 这类**单段路径**。
     *
     * WordPress 系（厂长资源那类）导航用自定义分类别名做分类页，URL 里既没有 `vodshow`
     * 也没有数字，靠 [CATEGORY] 那套模板一条都认不出来。所以这里单独放一条判据，
     * 由调用方**限制在导航容器内**使用（不然会误收 `/gbook`、`/label` 这类功能页）。
     */
    private val SLUG_CATEGORY = Regex("^/?([A-Za-z][A-Za-z0-9_\\-]{1,24})/?$")

    fun isSlugCategory(href: String): Boolean {
        val h = href.trim()
        if (h.isEmpty() || h.startsWith("http") || h.startsWith("//")) return false
        if (h.startsWith("#") || h.startsWith("javascript") || h.startsWith("mailto")) return false
        if (h.contains('.') || h.contains('?') || h.contains('=')) return false
        val g = SLUG_CATEGORY.find(h)?.groupValues?.getOrNull(1) ?: return false
        // 纯数字段不是分类（多半是分页）
        return g.any { !it.isDigit() }
    }

    /**
     * 从一条真实的卡片详情链接反推详情页模板：`/movie/23804.html` + id=`23804`
     * -> `/movie/{id}.html`。
     *
     * 站点用什么路径前缀（`/detail/`、`/movie/`、`/watch/`…）只有它的列表页知道，
     * 与其穷举模板，不如从列表里学一条。
     */
    fun detailTplFrom(url: String, id: String): String? {
        val u = url.trim()
        val k = id.trim()
        if (u.isEmpty() || k.isEmpty()) return null
        val idx = u.lastIndexOf(k)
        if (idx <= 0) return null
        // 只认「数字紧跟在 `/` 或 `-` 后」的位置，避免把域名/目录里的数字也换掉
        val prev = u[idx - 1]
        if (prev != '/' && prev != '-') return null
        return u.substring(0, idx) + "{id}" + u.substring(idx + k.length)
    }

    fun listCandidates(base: String): List<String> = listOf(
        "$base/vodshow/{id}--------{page}---.html",
        "$base/index.php/vod/show/id/{id}/page/{page}.html",
        "$base/vodshow/id/{id}.html",
        "$base/vodshow/{id}/page/{page}.html",
        "$base/vodtype/{id}-{page}.html",
        "$base/vodshow/{id}---{page}.html",
        "$base/index.php/vod/type/id/{id}/page/{page}.html",
        "$base/vod/{id}.html",
        "$base/list/{id}-{page}.html",
        "$base/vod/{id}/page/{page}.html"
    )

    fun detailCandidates(base: String): List<String> = listOf(
        "$base/voddetail/{id}.html",
        "$base/detail/{id}.html",
        "$base/index.php/vod/detail/id/{id}.html",
        "$base/voddetail/{id}/",
        "$base/index.php/vod/detail/{id}.html",
        "$base/vod/{id}.html",
        // WordPress 系常见路径（列表页学不到模板时的兜底）
        "$base/movie/{id}.html",
        "$base/film/{id}.html",
        "$base/video/{id}.html"
    )

    /**
     * 播放页模板：**详情页解析不到分集时的兜底**。
     * 很多主题的分集列表只在播放页里，详情页只有海报和简介。
     */
    fun playCandidates(base: String): List<String> = listOf(
        "$base/play/{id}-1-1.html",
        "$base/vodplay/{id}-1-1.html",
        "$base/index.php/vod/play/id/{id}/sid/1/nid/1.html",
        "$base/vod/play/id/{id}/sid/1/nid/1.html",
        "$base/index.php/vod/play/{id}-1-1.html",
        "$base/v/{id}-1-1.html",
        "$base/watch/{id}-1-1.html",
        "$base/play/{id}-1-1/"
    )

    fun searchCandidates(base: String): List<String> = listOf(
        "$base/vodsearch/wd/{kw}.html",
        "$base/vodsearch/{kw}-------------.html",
        "$base/vodsearch/{kw}.html",
        "$base/index.php/vod/search/wd/{kw}.html",
        "$base/index.php/vod/search.html?wd={kw}",
        "$base/vodsearch.html?wd={kw}",
        "$base/vodsearch/-------------.html?wd={kw}",
        "$base/search.html?wd={kw}",
        "$base/search.php?searchword={kw}"
    )

    private fun firstGroup(patterns: List<Regex>, href: String): String? {
        for (p in patterns) {
            val m = p.find(href) ?: continue
            val g = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (g.isNotBlank()) return g
        }
        return null
    }
}
