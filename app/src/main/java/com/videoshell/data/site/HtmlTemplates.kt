package com.videoshell.data.site

/** HTML 站点的 URL 模板候选（maccms 系模板大同小异，运行时逐个试，命中的记住） */
object HtmlTemplates {

    fun listCandidates(base: String): List<String> = listOf(
        "$base/vodshow/{id}--------{page}---.html",
        "$base/index.php/vod/show/id/{id}/page/{page}.html",
        "$base/vodshow/{id}/page/{page}.html",
        "$base/vodtype/{id}-{page}.html",
        "$base/vodshow/{id}---{page}.html",
        "$base/index.php/vod/type/id/{id}/page/{page}.html",
        "$base/list/{id}-{page}.html",
        "$base/vod/{id}/page/{page}.html"
    )

    fun detailCandidates(base: String): List<String> = listOf(
        "$base/voddetail/{id}.html",
        "$base/index.php/vod/detail/id/{id}.html",
        "$base/vod/{id}.html",
        "$base/detail/{id}.html",
        "$base/voddetail/{id}/"
    )

    fun searchCandidates(base: String): List<String> = listOf(
        "$base/vodsearch/{kw}-------------.html",
        "$base/index.php/vod/search.html?wd={kw}",
        "$base/vodsearch.html?wd={kw}",
        "$base/index.php/vod/search/wd/{kw}.html",
        "$base/vodsearch/-------------.html?wd={kw}",
        "$base/search.html?wd={kw}"
    )

    private val TYPE_PATTERNS = listOf(
        Regex("vodshow/(\\d+)"),
        Regex("vodtype/(\\d+)"),
        Regex("vod/type/id/(\\d+)"),
        Regex("type/id/(\\d+)"),
        Regex("/list/(\\d+)"),
        Regex("vod/list/(\\d+)"),
        Regex("/type/(\\d+)")
    )

    private val VIDEO_PATTERNS = listOf(
        Regex("voddetail/(\\d+)"),
        Regex("vodplay/(\\d+)"),
        Regex("vod/detail/id/(\\d+)"),
        Regex("/detail/(?:id/)?(\\d+)"),
        Regex("/show/(\\d+)"),
        Regex("vod/(\\d+)\\.html"),
        Regex("/vod/(\\d+)")
    )

    fun typeIdOf(href: String): String? = firstGroup(TYPE_PATTERNS, href)

    fun videoIdOf(href: String): String? = firstGroup(VIDEO_PATTERNS, href)

    private fun firstGroup(patterns: List<Regex>, href: String): String? {
        for (p in patterns) {
            val m = p.find(href) ?: continue
            val g = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (g.isNotBlank()) return g
        }
        return null
    }
}
