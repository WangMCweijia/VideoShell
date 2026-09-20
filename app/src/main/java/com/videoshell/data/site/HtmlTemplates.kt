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
        Regex("/vodplay/[\\w-]*-\\d+-\\d+"),
        // WordPress 系：`/archives/{id}/`、`/index.php/archives/{id}/`（v1.0.25 指纹扩充）
        Regex("/archives/(\\d+)"),
        // 自研站常见的 `/{目录}/{id}/` 详情形态由 tplFromNumericSegment 兜，
        // 这里补两条与分类页不冲突的强形状
        Regex("/video/(\\d+)/?$"),
        Regex("/vod/detail/(\\d+)")
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
        Regex("/episode/"),
        // 「形状」判据：不依赖目录名叫什么。maccms 的播放页永远是 `/{目录}/{id}-{sid}-{nid}.html`，
        // 站点常把目录名改掉（金牌影视用 `/bspvp/`、厂长用 `/v_play/`），
        // 列举目录名永远举不全，不如直接认形状。
        Regex("/[A-Za-z][\\w_\\-]*/\\d+-\\d+-\\d+\\.html")
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
     * 「带后缀的目录式分类」：`/bspvt/dianying.html`、`/vodtype/dongzuo.html`…
     *
     * 和 [isSlugCategory] 是同一件事，区别只是 maccms 后台把「分类别名」配成了带 `.html` 的形式。
     * 实测站点：金牌影视（`bolyship.com`）—— `bspvt` / `bspvd` / `bspvs` / `bspvp`
     * 四个目录名全被改过（分别对应 maccms 的 vodtype / voddetail / vodshow / vodplay）。
     *
     * **形状天然可分**：分类是 `/{目录}/{字母开头的别名}.html`，
     * 详情是 `/{目录}/{纯数字}.html`（数字开头直接不匹配），所以不会把详情页收成分类。
     * 与 [isSlugCategory] 一样，只允许调用方**限制在导航容器内**使用。
     */
    private val SLUG_DIR =
        Regex("^/?([A-Za-z][A-Za-z0-9_\\-]{0,24})/([A-Za-z][A-Za-z0-9_\\-]{0,24})\\.html$")

    fun isSlugDirCategory(href: String): Boolean {
        val h = href.trim()
        if (h.isEmpty() || h.startsWith("http") || h.startsWith("//")) return false
        if (h.startsWith("#") || h.startsWith("javascript") || h.startsWith("mailto")) return false
        if (h.contains('?')) return false
        val m = SLUG_DIR.find(h) ?: return false
        val dir = m.groupValues[1]
        val slug = m.groupValues[2]
        // maccms 的「筛选/列表页」是 `/vodshow/1--------1---.html` 这种连续连字符占位，
        // 别名里出现 3 个以上连字符基本就是它，不是分类标签。
        if (slug.contains("---") || dir.contains("---")) return false
        return slug.any { !it.isDigit() } && dir.any { !it.isDigit() }
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

    /**
     * 从一条真实的播放页链接反推播放页模板：
     * `/bspvp/548165-1-1.html` -> `/bspvp/{id}-1-1.html`。
     *
     * 与 [detailTplFrom] 同理：播放页的目录名也是站点自己起的
     * （金牌影视 `bspvp`、厂长 `/v_play/`），穷举 `playCandidates` 永远举不全，
     * 从页面里学一条最稳。拿到的模板用于「详情页没有分集、只有播放页有」时兜底。
     */
    fun playTplFrom(url: String): String? {
        val u = url.trim()
        if (u.isEmpty()) return null
        val m = PLAY_SHAPE.find(u) ?: return null
        // groupValues: [0]=整段 [1]=目录 [2]=id [3]=sid [4]=nid
        val dirStart = m.range.first + 1          // 目录名的首字符位置
        val dir = m.groupValues[1]
        val sid = m.groupValues[3]
        val nid = m.groupValues[4]
        return u.substring(0, dirStart) + dir + "/{id}-" + sid + "-" + nid + ".html"
    }

    /**
     * maccms 播放页的万能形状 `/{目录}/{id}-{sid}-{nid}.html`，与目录名叫什么无关
     */
    private val PLAY_SHAPE = Regex("/([A-Za-z][\\w_\\-]*)/(\\d+)-(\\d+)-(\\d+)\\.html")

    /**
     * 「尾斜杠目录式分类」：`/tag/AI%E7%9F%AD%E5%89%A7/`、`/drama/rec-hot-drama/`。
     *
     * 自研 SSR 站（Nuxt / Next / Vue Router）的分页路由习惯写成 `/{目录}/{别名}/`：
     * 没有后缀、带尾斜杠，别名还常常是 **URL 编码的中文**。
     * 实测野果短剧（`capable.fzchosdi.cc`）的真分类就是 `/tag/{slug}/`，
     * 而 [isSlugCategory] 只认单段、[isSlugDirCategory] 要求 `.html` —— **两条都认不出**，
     * 于是分类栏里只剩页脚功能页（`/search/`、`/contact/`、`/protocol/`…），
     * 点进任何一个列表都是空的。
     *
     * ⚠️ 这条判据本身**不够**，必须配「同目录下别名足够多」的聚类（见 `HtmlAdapter`）：
     * `/rank/drama/`、`/explore/drama/` 这类功能页形状与它完全一样，
     * 靠白名单永远追不上站点改版，靠数量才能分开。
     */
    private val SLASH_DIR = Regex("^/?([A-Za-z][A-Za-z0-9_\\-]{0,24})/([^/?#]+)/$")

    fun isSlashDirCategory(href: String): Boolean = slashDirOf(href) != null

    /** 尾斜杠分类链接的 `目录 to 别名`；不是该形状返回 null */
    fun slashDirOf(href: String): Pair<String, String>? {
        val h = href.trim()
        if (h.isEmpty() || h.startsWith("http") || h.startsWith("//")) return null
        if (h.startsWith("#") || h.startsWith("javascript") || h.startsWith("mailto")) return null
        if (h.contains('?') || h.contains('#')) return null
        val m = SLASH_DIR.find(h) ?: return null
        val dir = m.groupValues[1]
        val slug = m.groupValues[2]
        if (dir.isBlank() || slug.isBlank()) return null
        if (dir.contains("---") || slug.contains("---")) return null   // maccms 筛选页占位
        if (slug.all { it.isDigit() }) return null                     // 纯数字段是详情 id，不是别名
        return dir to slug
    }

    /** 从一条真实尾斜杠分类链接抽形状：`/tag/AI%E7%9F%AD%E5%89%A7/` -> `/tag/{slug}/` */
    fun slashCatTplFrom(url: String): String? {
        val p = pathOf(url) ?: return null
        if (!p.endsWith("/")) return null
        val d = slashDirOf(p) ?: return null
        return "/${d.first}/{slug}/"
    }

    /** 这条分类形状是不是尾斜杠形态（`/tag/{slug}/`） */
    fun isSlashCatTpl(tpl: String): Boolean {
        val t = tpl.trim()
        return t.endsWith("/") && t.contains("/{slug}/")
    }

    /** 尾斜杠分类形状里的目录名（`/tag/{slug}/` -> `tag`）；不是该形状返回 null */
    fun dirOfSlashCatTpl(tpl: String): String? {
        if (!isSlashCatTpl(tpl)) return null
        return tpl.trim().substringBefore("/{slug}/").trim('/').takeIf { it.isNotBlank() }
    }

    /** [slashCatTplFrom] 的逆运算 */
    fun matchesSlashCatTpl(href: String, tpl: String): Boolean {
        val dir = dirOfSlashCatTpl(tpl) ?: return false
        val p = pathOf(href) ?: return false
        val d = slashDirOf(p) ?: return false
        return d.first.equals(dir, true)
    }

    /**
     * 通用「把 URL 里的数字段换成 `{id}`」，用于自研站的尾斜杠路由：
     * `/drama/video/3381/` -> `/drama/video/{id}/`。
     *
     * 与 [detailTplFrom] / [playTplFrom] 是一回事（**记形状，不记位置**），
     * 但那两条判据是给 maccms 用的（要求 `.html` 结尾 / `{id}-{sid}-{nid}` 形状），
     * 对 Nuxt 系站点一条都匹配不上。
     */
    fun tplFromNumericSegment(url: String): String? {
        val p = pathOf(url) ?: return null
        if (!p.endsWith("/")) return null
        val seg = p.trim('/').split('/').filter { it.isNotBlank() }
        if (seg.size < 2) return null
        val idx = seg.indexOfFirst { s -> s.isNotEmpty() && s.all { it.isDigit() } }
        if (idx <= 0) return null
        val head = seg.take(idx)
        if (head.any { it.isBlank() }) return null
        // 只保留「目录…/{id}/」，丢掉 id 之后的部分（`/drama/video/3381/ep-4/` 也归一到同一形状）
        return "/" + (head + "{id}").joinToString("/") + "/"
    }

    /**
     * 从一条真实分类链接反推「分类页 URL 形状」：
     * `/bspvt/dianying.html` -> `/bspvt/{slug}.html`；`/tag/熟女/` -> `/tag/{slug}/`。
     *
     * 这是调试校准模式第一步的产物 —— 用户点一个分类，我们学到的是**形状**而不是
     * 「他在哪个容器里点的」。
     *
     * 为什么必须是形状：同一个站的分类常分散在主菜单 / 二级面板 / 底部导航里
     * （金牌影视实测：只认点击的那个容器只能拿到 5 个分类，认形状能拿到 40 个）。
     * 形状还附带一个好处 —— 目录名是站点自己配的，按它扫全文档是安全的，
     * 不会像「任意目录」那样把 `/gbook`、`/label` 这类功能页也收进来。
     *
     * 只保留 path、丢掉域名，规则才能在同一站点的 http/https 之间复用。
     */
    fun catTplFrom(url: String): String? {
        val p = pathOf(url) ?: return null
        // 尾斜杠形态（自研 SSR 站）先分流：它没有后缀，下面的 `.html` 判据会直接否掉
        if (p.endsWith("/")) return slashCatTplFrom(p)
        val seg = p.trim('/').split('/')
        if (seg.size != 2) return null                 // 只认 `/{目录}/{别名}.html`
        val dir = seg[0]
        if (!seg[1].endsWith(".html", true)) return null
        val slug = seg[1].removeSuffix("html").removeSuffix(".")
        if (dir.isBlank() || slug.isBlank()) return null
        if (!dir.any { !it.isDigit() }) return null     // 目录名不能是纯数字（那不是别名）
        if (!slug.any { !it.isDigit() }) return null    // 别名不能是纯数字（那是详情页 id）
        if (slug.contains("---") || dir.contains("---")) return null  // maccms 筛选页占位
        return "/$dir/{slug}.html"
    }

    /** [catTplFrom] 的逆运算：这条链接是不是「这条形状」下的分类页 */
    fun matchesCatTpl(href: String, tpl: String): Boolean {
        if (isSlashCatTpl(tpl)) return matchesSlashCatTpl(href, tpl)
        val t = tpl.trim()
        if (!t.endsWith(".html", true) || !t.contains("/{slug}")) return false
        val dir = t.substringBefore("/{slug}").trim('/')
        if (dir.isBlank()) return false
        val p = pathOf(href) ?: return false
        val seg = p.trim('/').split('/')
        if (seg.size != 2) return false
        if (!seg[0].equals(dir, true)) return false
        if (!seg[1].endsWith(".html", true)) return false
        val slug = seg[1].removeSuffix("html").removeSuffix(".")
        if (slug.isBlank() || !slug.any { !it.isDigit() }) return false
        return !slug.contains("---")
    }

    /** 取 URL 的 path 部分；相对地址原样取（去掉 query / fragment） */
    private fun pathOf(url: String): String? {
        val u = url.trim()
        if (u.isEmpty() || u.startsWith("#") || u.startsWith("javascript") || u.startsWith("mailto")) {
            return null
        }
        if (u.startsWith("http") || u.startsWith("//")) {
            val n = if (u.startsWith("//")) "https:$u" else u
            return runCatching { java.net.URI(n).path }.getOrNull()
        }
        val path = u.substringBefore('?').substringBefore('#')
        if (path.isEmpty()) return null
        return if (path.startsWith("/")) path else "/$path"
    }

    /**
     * 列表（分类）页候选。
     *
     * v1.0.25 指纹扩充：把国内主流 CMS 的分类页形状都排进来 ——
     * maccms10（苹果CMS，`vodshow` / `index.php/vod/show`）、海洋 CMS seacms（`/list/{id}-{page}.html`）、
     * 飞飞 / 苹果 CMS 的 `vodtype`、WordPress 视频主题（`/category/{slug}/page/{n}/`）。
     * 命中顺序无所谓（逐个真抓，谁有卡片用谁），关键是**别漏**。
     */
    fun listCandidates(base: String): List<String> = listOf(
        "$base/vodshow/{id}--------{page}---.html",
        "$base/vodshow/{id}---{page}---.html",
        "$base/index.php/vod/show/id/{id}/page/{page}.html",
        "$base/vodshow/id/{id}.html",
        "$base/vodshow/{id}/page/{page}.html",
        "$base/vodtype/{id}-{page}.html",
        "$base/vodshow/{id}---{page}.html",
        "$base/index.php/vod/type/id/{id}/page/{page}.html",
        "$base/index.php/vod/type/id/{id}.html",
        "$base/vod/{id}.html",
        "$base/list/{id}-{page}.html",
        "$base/list/{id}.html",
        "$base/vod/{id}/page/{page}.html",
        "$base/type/{id}-{page}.html",
        "$base/index.php/vod/show/{id}--------{page}---.html",
        "$base/category/{id}/page/{page}/"
    )

    /**
     * 详情页候选（v1.0.25 扩充 WP / seacms / 自研形态）。
     */
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
        "$base/video/{id}.html",
        "$base/archives/{id}/",
        "$base/index.php/archives/{id}/",
        "$base/item/{id}.html"
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

    /**
     * 搜索候选（v1.0.25 指纹扩充）：
     * maccms10 `vodsearch/wd/{kw}`、海洋 CMS `search.php?searchword=`、苹果CMS index.php 形态、
     * WordPress `?s={kw}`、以及自研站的 query 形态。
     */
    fun searchCandidates(base: String): List<String> = listOf(
        "$base/vodsearch/wd/{kw}.html",
        "$base/vodsearch/{kw}-------------.html",
        "$base/vodsearch/{kw}.html",
        "$base/index.php/vod/search/wd/{kw}.html",
        "$base/index.php/vod/search.html?wd={kw}",
        "$base/vodsearch.html?wd={kw}",
        "$base/vodsearch/-------------.html?wd={kw}",
        "$base/s----------.html?wd={kw}",
        "$base/search.html?wd={kw}",
        "$base/search.php?searchword={kw}",
        "$base/index.php?m=vod-search&wd={kw}",
        "$base/vodsearch/{kw}/page/1.html",
        "$base/search?keyword={kw}",
        "$base/?s={kw}",
        "$base/search?q={kw}",
        "$base/search?wd={kw}"
    )

    /**
     * 从首页的搜索表单反推搜索模板（v1.0.20）。
     *
     * 固定候选全是 maccms 系形状，自研站对不上：厂长的表单是
     * `<form action="/nimasile"><input name="q">`，骚火是
     * `<form action="/s----------.html" method="get"><input name="wd">` ——
     * 只有读了表单才知道。约定：
     * - 只认 GET 表单（POST 没法拼 URL）；
     * - 模板 = `action?{输入框name}={kw}`（action 已带 query 就用 `&`）；
     * - 找不到带名字的输入框、或 action 为空 => 返回 null（学不到，走固定候选）。
     */
    fun searchTplFromForm(doc: org.jsoup.nodes.Document, base: String): String? {
        for (form in doc.select("form")) {
            if (!form.attr("method").equals("get", ignoreCase = true) &&
                form.attr("method").isNotBlank()
            ) continue
            var action = form.attr("action").trim()
            if (action.isEmpty() || action.startsWith("javascript")) continue
            if (!action.startsWith("http")) {
                action = base.trimEnd('/') + "/" + action.trimStart('/')
            }
            val input = form.selectFirst("input[name]") ?: continue
            val name = input.attr("name").trim()
            if (name.isEmpty() || name == "{kw}") continue
            val sep = if (action.contains("?")) "&" else "?"
            return "$action${sep}$name={kw}"
        }
        return null
    }

    // ------------------------------------------------------------------ 形状普查（v1.0.34）

    /**
     * 一条「形状」的普查结果：这个 URL 形状在页面上一共被多少条链接命中。
     */
    data class ShapeHit(
        val tpl: String,
        /** 命中该形状的链接数 */
        val links: Int,
        /** **不同别名数**（`/tag/{slug}/` 下的不同 slug）—— 分「分类目录」与「功能页」的关键量 */
        val aliases: Int,
        /** **不同链接文字数** —— 挡掉那一串一模一样的「查看更多」 */
        val names: Int,
        /** 一条样例别名（人话解释用） */
        val sample: String
    )

    /**
     * ## 形状普查（v1.0.34）
     *
     * 「分类形状」以前只有两个来源：**人工校准**（用户点一下）与代码里**写死的家族**
     * （尾斜杠目录 / `.html` 目录 / 导航容器白名单）。新站只要落在这三类之外，
     * 分类栏就是空的 —— 用户唯一的选择是去校准。
     *
     * 而 30 多个版本反复证明的同一件事是：**形状永远列举不完，数量永远分得开**。
     * （金牌影视改目录名、野果的 `/explore/drama/` 与 `/tag/xxx/` 同形 —— 都是这条。）
     *
     * 所以这里把「认形状」升级成**当场测量**：把页面上每条文字链接的路径归纳成形状，
     * 按形状聚类，数两样东西：
     *
     * - [ShapeHit.aliases]：同一形状下有多少个**不同别名**。真分类目录有很多个
     *   （野果 `/tag/` 下 30+ 个），功能页只有一个（`/rank/drama/`、`/explore/drama/` 的别名都是 `drama`）；
     * - [ShapeHit.names]：有多少个**不同链接文字**。栏目页那串「查看更多」会被这条挡掉。
     *
     * **归纳形状直接复用运行时判据**（[slashDirOf] / [catTplFrom]），所以普查出来的 `tpl`
     * 与 [matchesCatTpl]、以及 `HtmlAdapter.collectByCatTpl` 认的是**同一套形状** ——
     * 「当场数出来的数」与「运行时收出来的数」必然相等。判据只能有一份，这是本项目的硬纪律。
     *
     * ⚠️ 调用方通过 [keepName] 决定"哪些文字算分类名"（黑名单 / 功能页词 / 长度限制）。
     * 普查只负责数，不负责判名字的语义。
     */
    fun shapeCensus(
        doc: org.jsoup.nodes.Document,
        keepName: (String) -> Boolean
    ): List<ShapeHit> {
        val byTpl = LinkedHashMap<String, ArrayList<Pair<String, String>>>()
        for (a in doc.select("a[href]")) {
            if (a.selectFirst("img") != null) continue          // 带图的一定不是分类标签
            val href = a.attr("href").trim()
            if (href.isEmpty()) continue
            val stem = stemOf(href) ?: continue
            val name = a.text().replace(Regex("\\s+"), " ").trim()
            if (!keepName(name)) continue
            byTpl.getOrPut(stem.first) { ArrayList() } += (stem.second to name)
        }
        return byTpl.map { (tpl, l) ->
            ShapeHit(
                tpl = tpl,
                links = l.size,
                aliases = l.map { it.first }.distinct().size,
                names = l.map { it.second }.distinct().size,
                sample = l.firstOrNull()?.first.orEmpty()
            )
        }.sortedWith(compareByDescending<ShapeHit> { it.names }.thenByDescending { it.aliases })
    }

    /**
     * 一条链接的「形状 + 别名」。
     *
     * 两条分支**都不是新写的判据**，而是直接复用运行时那两份：
     * 尾斜杠家族走 [slashDirOf]，`.html` 家族走 [catTplFrom]。
     * 这样普查永远不可能提出一个运行时认不出的形状。
     */
    private fun stemOf(href: String): Pair<String, String>? {
        slashDirOf(href)?.let { (dir, slug) -> return "/$dir/{slug}/" to slug }
        val t = catTplFrom(href) ?: return null
        if (!t.contains("{slug}")) return null
        val alias = pathOf(href)?.trimEnd('/')?.substringAfterLast('/')
            ?.removeSuffix("html")?.removeSuffix(".")
            ?.takeIf { it.isNotBlank() } ?: return null
        return t to alias
    }

    private fun firstGroup(patterns: List<Regex>, href: String): String? {
        for (p in patterns) {
            val m = p.find(href) ?: continue
            val g = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (g.isNotBlank()) return g
        }
        return null
    }
}
