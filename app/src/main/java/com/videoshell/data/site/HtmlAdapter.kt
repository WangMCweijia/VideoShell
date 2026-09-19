package com.videoshell.data.site

import com.videoshell.data.model.Category
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.util.resolveUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import java.io.IOException
import java.net.URLEncoder

/**
 * 通用 HTML 适配器：没有标准采集接口的视频站兜底用。
 *
 * 与旧实现的区别：
 * 1) 分类项携带站点自己的真实 URL，浏览时**直接用该 URL**，不再靠猜模板 —— 命中率与首屏体验都稳得多；
 * 2) 卡片必须有图，导航链接（电影/电视剧…）不再被当成影片；
 * 3) 分页用「已见 id 集合」去重，站点忽略 page 参数时自动收尾，不会无限翻页。
 */
class HtmlAdapter(site: SiteConfig) : SiteAdapter(site) {

    private var listTpl: String? = null
    private var detailTpl: String? = null
    private var searchTpl: String? = null
    private var playTpl: String? = null

    /**
     * 本站是否走「独立详情页 + 独立播放页」结构（决定 `/vod/{id}.html` 是分类还是详情）
     */
    @Volatile
    private var vodIsCategory = false

    /** 分类解析结果缓存 */
    private var cachedCats: List<Category> = emptyList()

    /**
     * 从列表页学到的详情页模板（`/movie/{id}.html` 之类）。
     * 比穷举 [HtmlTemplates.detailCandidates] 可靠得多，优先级也更高。
     */
    private var learnedDetailTpl: String? = null

    /** 已回到首页现学过一次（避免每次详情失败都重抓首页） */
    private var homeLearned = false

    /**
     * 最近一次成功抓到的详情页 DOM。
     *
     * 留着它是为了让「播放页兜底」不必再猜 URL：详情页的分集按钮里就摆着真实的播放页链接
     * （自研站 `/drama/video/{id}/` 这种站点私有目录名，穷举模板永远猜不到）。
     */
    private var lastDetailDoc: Document? = null

    /**
     * 详情页 HTML 里拿到的封面。
     *
     * 留着它是为了「分集只能去播放页拿」的那条路径：详情页的 `og:image` 是真海报，
     * 而**播放页的 `og:image` 往往是站点级默认分享图**（野果实测：详情页
     * `…2026091721151270131.jpeg` vs 播放页 `images/social-default.png`）。
     * 详情页抓到时就先把封面存下来，播放页兜底构造 VideoDetail 时接着用 ——
     * 零额外请求，也不必回头再抓一次详情页。
     */
    private var detailPicHint: String = ""

    /** 校准固化的分类页 URL 形状（`/bspvt/{slug}.html`） */
    @Volatile
    private var manualCatTpl: String? = null

    /** 调试校准模式固化下来的分类容器（形状认不出来时的退路） */
    @Volatile
    private var manualNavSel: String? = null

    /** 本站是否经过人工校准（报告里要能看出来） */
    @Volatile
    private var calibrated = false

    /**
     * ## 「这次校准到底生效了没有」的三个现场变量（v1.0.32）
     *
     * 用户的原始反馈是**无法证伪**的：「校准走完了，但这个站还是按原来的规则显示」。
     * 这句话背后至少有四种完全不同的成因（见 [SiteAdapter.calibDiag]），
     * 而界面"看着没变"是分不出来的 —— 所以把适配器这一步的**真实决策**原样记下来，
     * 由 `SiteDoctor` 打进报告。这是从"应该没生效"到"确定是哪一种"的桥。
     */
    @Volatile
    private var loadedRecipe = false

    /** 配方的结论（本次页面解析后写入）：形状/容器命中了没有、收到几个 */
    @Volatile
    private var calibOutcome = ""

    @Volatile
    private var calibAppliedFlag = false

    /**
     * 「最新」tab（`browse("")`）实际改用哪个分类页。
     *
     * 见 [substituteHome]：首页是 JS 渲染的站，SSR 里根本没有剧集数据。
     */
    private var homeCat: String? = null

    /**
     * 站点配方：跨 Activity / 跨启动复用。
     *
     * **这是 v1.0.11 的关键修复**：详情页在独立的 `DetailActivity` 里，会新建 Adapter 实例，
     * 实例内存的学习结果全丢 —— 不落盘就只能靠穷举模板，站点改过目录名必然失败。
     *
     * ⚠️ **这个 init 必须放在所有「会被它赋值的属性」之后。** Kotlin 把属性初始化器与 init 块
     * 按**源码出现顺序**编进构造函数：init 写在前面时，后面那几行的 `= null` 会把刚读出来的
     * 配方**当场覆写掉**。v1.0.18 前 `manualCatTpl` / `manualNavSel` / `calibrated` 就踩在这上面 ——
     * 表现是「校准明明写盘了，进页面却还是没生效」，而且**不报错、不崩溃**。
     */
    init {
        RecipeStore.load(site.baseUrl)?.let { r ->
            loadedRecipe = true
            // 成功命中过的模板 > 从列表页推测的模板，但两者都比穷举可信
            detailTpl = r.detailTpl
            learnedDetailTpl = r.detailTpl
            playTpl = r.playTpl
            listTpl = r.listTpl
            searchTpl = r.searchTpl
            if (r.vodIsCategory) vodIsCategory = true
            // 校准模式固化的分类规则：形状 + 容器（人手点出来的）
            manualCatTpl = r.catTpl?.takeIf { it.isNotBlank() }
            manualNavSel = r.navSel?.takeIf { it.isNotBlank() }
            calibrated = r.calibAt > 0
            homeCat = r.homeCat?.takeIf { it.isNotBlank() }
        }
    }

    /** 分类为空时把原因带出去，让 UI 能显示出来（不然只能靠猜） */
    private var diag: String = ""

    /**
     * 详情解析的「试过什么」记录（v1.0.15）。
     *
     * 为什么需要：用户报「校准后拿不到分集列表」时，`detail()` 只会抛一句
     * "HTML 模板不匹配" —— 试了哪些地址、每个地址是连不上、404、还是拿到了页面但
     * 一个分集锚点都没有，全被吞掉了。没有这层记录就只能靠猜，而这一版已经证明了
     * 「猜」是最慢的排查方式（同一份配方在别的机器上明明跑得通）。
     */
    private var detailTrace: String = ""

    private val traceBuf = ArrayList<String>()

    private fun tr(s: String) {
        if (traceBuf.size < 24) traceBuf += s
    }

    /**
     * 分类为空时是分类的原因；分类没问题但详情失败时给出**详情试过的地址清单**。
     * 两条都为空才算"没话说"。
     */
    override val lastDiag: String
        get() = diag.ifBlank { detailTrace }

    /**
     * 「这次校准生效了没有」—— `SiteDoctor` 原样打印（见 [SiteAdapter.calibDiag]）。
     *
     * 三段拼起来：**配方有没有读到** → **哪来的、学到什么** → **本次页面用上了没有**。
     * 第三段是以前完全没有的：它是唯一能区分"校准没写盘"和"写了但被静默忽略"的信息。
     */
    override val calibDiag: String
        get() {
            val head = when {
                !loadedRecipe -> "无配方（既没校准过，也没自动学到模板）"
                calibrated -> "调试校准（人工）"
                else -> "自动学习（**没有人工校准记录** —— 说明校准那一步没写盘，或者本站命中了加密白名单被跳过）"
            }
            val shape = "分类形状=" + (manualCatTpl ?: "（无）") +
                    "｜分类容器=" + (manualNavSel ?: "（无）")
            val tail = calibOutcome.ifBlank { "（还没解析过分类页）" }
            return "配方来源：$head｜$shape\n      $tail"
        }

    /** 校准规则**真的用上了**：形状/容器命中了本次页面（而不是静默退回默认判据） */
    override val calibApplied: Boolean get() = calibAppliedFlag

    /** 最近一次抓取失败的具体原因（异常文本），分类为空时并进 [diag] 一起展示 */
    private var lastFetchErr: String = ""

    /** 当前浏览目标的已见影片 id，用于分页去重 */
    private val seenIds = HashSet<String>()
    private var seenKey = ""

    private val root: String get() = site.baseUrl.trimEnd('/')

    // ------------------------------------------------------------------ 分类

    /** 导航语义容器（越靠前越像"分类标签"） */
    private val navSelectors = listOf(
        ".main_nav a", ".tab_head a", ".nav-list a", "header nav a", "nav a",
        ".navbar a", ".nav_bar a", ".menu a", "#nav a", ".header-nav a", "header .nav a", "header a"
    )

    private val catBlacklist = setOf(
        "首页", "全部", "更多", "排行", "排行榜", "登录", "注册", "求片", "留言",
        "历史", "专题", "关于", "反馈", "APP", "手机版", "换一换", "最近更新",
        // 站点功能页的**完整标题**（精确匹配，不会误伤名字里恰好含这些字的内容分类）。
        // 实测野果短剧的页脚/头部功能页就是这几条，它们和「目录式分类」形状完全一样，
        // 收进分类栏后用户点进去只会看到空列表。
        "联系我们", "常见问题", "使用条款", "隐私声明", "用户协议", "服务协议",
        "免责声明", "版权声明", "关于我们", "帮助中心", "搜索", "搜索剧集"
    )

    /**
     * 站点功能页的**英文段名**。
     *
     * 与分类名不同，功能页词汇是一个**封闭集合** —— 全网都在用这几个词，
     * 所以词表在这里是可靠工具（而给分类名写词表则永远追不上）。判据作用在 URL 上：
     * 根级单段路径 `/contact/`、`/search/`、`/privacy/` 形状上与「目录式分类」无法区分，
     * 但它们是站点功能页。
     *
     * 不加这一层会出人命：`collectSlugCategories` 在 [categoriesFrom] 里排在尾斜杠分类之前，
     * 一旦它凑够 2 条就**提前返回**，把真分类整批挡在门外 ——
     * 这正是 v1.0.15 自检报告里「分类 5 个全是页脚功能页」的直接成因。
     */
    private val funcSlug = setOf(
        "about", "contact", "contactus", "contact-us", "search", "question", "questions",
        "faq", "faqs", "protocol", "privacy", "terms", "term", "agreement", "help",
        "support", "feedback", "login", "logout", "register", "signup", "signin",
        "user", "users", "account", "profile", "app", "download", "downloads",
        "link", "links", "friendlink", "sitemap", "rss", "notice", "announce",
        "announcement", "gbook", "label", "labels", "tags", "index", "member",
        "vip", "pay", "order", "cart", "setting", "settings", "history", "favorite",
        "favorites", "sponsor", "disclaimer", "copyright"
    )

    /** 名称里带这些字的一律不算分类（公告/求片/备用站点之类的功能页） */
    private val catBadWords = listOf(
        "公告", "须知", "关于", "求片", "留言", "备用", "加入", "Telegram",
        "客服", "声明", "版权", "筛选"
    )

    /**
     * 目录式分类（`/meijutt`、`/riju`…）**只在这些容器里认**。
     * 放到全文档扫会把 `/gbook`、`/label`、`/user` 这类功能页也收进来。
     *
     * 注意：一个站往往有**好几套**导航（顶部主菜单 / 二级"分类"面板 / 底部导航），
     * 分类会分散在里面 —— 厂长资源的「日剧」就只存在于 `ul.submenu_mi` 这套里，
     * 所以下面这些容器要**全部**扫一遍，不能命中一个就收工。
     */
    private val slugNavContainers = listOf(
        ".navlist", ".navtop", ".nav-list", ".navlist-content", ".nav_menu", ".nav-menu",
        ".navbar", ".nav_bar", ".nav-bar", ".top-nav", ".head-nav", ".header-nav",
        ".submenu_mi", ".v-sort-nav", ".sort-nav", ".cate-nav", ".category-nav",
        ".footnav", ".footer_nav",
        "#nav", "nav", ".nav", ".menu", "header"
    )

    /**
     * 「分集容器」——里面放的锚点就是分集 / 播放页链接。
     *
     * 为什么按容器认：自研站（Nuxt/Vue）的分集锚点 href 五花八门，
     * 拿形状判据永远追不上；但**容器名是全球通用的英文词**（episode / playlist），
     * 而且「在分集容器里」这件事本身就是最强的语义信号。
     * 实测野果短剧：`episode-list` 容器里就是 `/drama/video/{id}/`。
     */
    private val episodeContainers = listOf(
        "[class*=episode]", "[class*=Episode]", "[class*=playlist]", "[class*=play-list]",
        "[class*=paly_list]", "[class*=play_list]", "[id*=episode]", "[id*=playlist]",
        "[class*=选集]", "[class*=剧集]"
    )

    override suspend fun categories(): List<Category> {
        if (cachedCats.isNotEmpty()) return cachedCats
        diag = ""

        // 1) 首页导航 —— 最理想，直接就是站点自己的分类标签
        //    多地址重试：http/https、www/裸域 都试一遍（有的站在某些网络上只认其中一种）
        var lastErr = ""
        var sawHome = 0
        for (base in baseCandidates()) {
            val home = fetch(base)
            if (home == null) continue
            sawHome = home.length
            val list = try {
                categoriesFrom(Jsoup.parse(home, base))
            } catch (e: Exception) {
                lastErr = e.javaClass.simpleName + ": " + e.message
                emptyList()
            }
            if (list.size >= 2) {
                cachedCats = list
                return list
            }
            diag = "首页 ${home.length} 字，分类 ${list.size} 个"
        }
        if (sawHome == 0) {
            val why = lastErr.ifBlank { lastFetchErr }
            diag = "首页请求失败：" + site.baseUrl + if (why.isBlank()) "" else "（$why）"
        }

        // 2) 兜底：有的站首页是纯 JS 渲染（导航藏在脚本里），但列表页有静态导航。
        //    用列表模板探一遍，能拿到就用，拿不到也不影响"最新"浏览。
        for (tpl in HtmlTemplates.listCandidates(root).take(5)) {
            val url = build(tpl, id = "1", page = 1)
            val html = fetch(url) ?: continue
            val doc = Jsoup.parse(html, site.baseUrl)
            val list = categoriesFrom(doc)
            if (list.size >= 2) {
                cachedCats = list
                return list
            }
        }
        if (diag.isBlank()) diag = "首页与候选列表页都没解析到分类"
        return emptyList()
    }

    /**
     * 首页地址候选：原地址 → 换 www/裸域 → 换协议。
     * 有些站在特定网络下只认其中一种，多试一次成本很低，却能救回整个站。
     */
    private fun baseCandidates(): List<String> {
        val b = site.baseUrl.trimEnd('/')
        val out = LinkedHashSet<String>()
        out.add(b)
        val host = hostOf(b)
        if (host.isNotBlank()) {
            val alt = when {
                host.startsWith("www.") -> host.removePrefix("www.")
                else -> "www.$host"
            }
            out.add(b.replaceFirst(host, alt))
        }
        val swapped = if (b.startsWith("https://")) b.replaceFirst("https://", "http://")
        else b.replaceFirst("http://", "https://")
        out.add(swapped)
        return out.filter { it.length > 8 }.toList()
    }

    /**
     * 抓取页面。
     *
     * [Http.get] 内部已带重试与退避（手机网络下"新域名首次请求"很容易抖一下），
     * 这里只负责把失败原因留下来 —— 分类为空时提示条要能说清是**哪个地址、什么错**。
     */
    private suspend fun fetch(url: String): String? {
        val r = runCatching { Http.get(url, referer = site.baseUrl) }
        r.getOrNull()?.let { return it }
        lastFetchErr = r.exceptionOrNull()?.let { e ->
            e.javaClass.simpleName + ": " + (e.message ?: "").take(100)
        }.orEmpty()
        return null
    }

    /** 从首页 DOM 里解析分类标签（独立成函数便于离线校验） */
    fun categoriesFrom(doc: Document): List<Category> {
        vodIsCategory = detectVodShape(doc)
        // 每次重解析都从头记录「校准规则这次到底用上了没有」（见 calibOutcome 的注释）。
        // 不累加：categories() 会按 baseCandidates() 试多个首页地址，累加会变成一串重复结论。
        calibOutcome = ""
        calibAppliedFlag = false

        val out = LinkedHashMap<String, Category>()

        // 0) 校准模式固化的规则，优先于任何形状猜测。
        //
        //    顺序有讲究：**先认形状，再认容器**。
        //    形状（`/bspvt/{slug}.html`）是"分类逻辑"本身 —— 分类会散落在主菜单、二级面板、
        //    底部导航里，认形状才能一次全收；只用容器的话，金牌影视实测 40 个分类会掉到 5 个。
        //    容器留给"形状认不出来"的站点（比如 `/meijutt` 这种无后缀别名，形状上等同于默认判据）。
        //
        //    两条都认不出来时不硬撑，继续走默认逻辑 —— 站点改版后不能让用户卡死。
        manualCatTpl?.let { tpl ->
            if (HtmlTemplates.isSlashCatTpl(tpl)) {
                // 尾斜杠形状（`/tag/{slug}/`）走聚类收集：目录是用户自己点的，只认这一个目录
                collectSlashDirCategories(doc, out, expectDir = HtmlTemplates.dirOfSlashCatTpl(tpl))
            } else {
                collectByCatTpl(doc, out, tpl)
            }
            if (out.size >= 2) {
                calibAppliedFlag = true
                calibOutcome = "校准形状已生效：$tpl 在本次页面命中 ${out.size} 个分类（判据要求 ≥2）"
                return out.values.take(40).toList()
            }
            // 这一条是"校准明明写盘了、界面却没变"的第一号成因：
            // 校准规则**命中了但不够 2 个** ⇒ 静默退回默认逻辑，用户看不出任何差别。
            calibOutcome = "⚠️ 校准形状 $tpl 在本页只命中 ${out.size} 个（判据要求 ≥2）" +
                    " ⇒ **已静默退回默认逻辑**（这就是「校准了但显示没变」的常见原因）"
            out.clear()
        }
        manualNavSel?.let { sel ->
            if (doc.select(sel).isNotEmpty()) {
                collectSlugCategories(doc, out, only = listOf(sel))
                collectCategories(doc.select("$sel a"), out)
                if (out.size >= 2) {
                    calibAppliedFlag = true
                    calibOutcome = "校准容器已生效：$sel 命中 ${out.size} 个分类"
                    return out.values.take(40).toList()
                }
                if (calibOutcome.isBlank()) {
                    calibOutcome = "⚠️ 校准容器 $sel 在本页只命中 ${out.size} 个（判据要求 ≥2）⇒ 退回默认逻辑"
                }
                out.clear()
            } else if (calibOutcome.isBlank()) {
                calibOutcome = "⚠️ 校准容器 $sel 在本页选不中任何元素（站点改版了？）⇒ 退回默认逻辑"
            }
        }
        if (calibOutcome.isBlank()) {
            calibOutcome = when {
                calibrated && manualCatTpl == null && manualNavSel == null ->
                    "⚠️ **这次校准没有学到分类规则**（第 1 步可能被跳过、或没点中分类）" +
                            " ⇒ 分类栏用的仍是默认判据"
                else -> "本次解析没走到校准规则（无配方或已退回默认判据）"
            }
        }

        // 1) 优先在导航容器里找 —— 最贴近站点自身的分类标签
        for (sel in navSelectors) {
            val anchors = doc.select(sel)
            if (anchors.isEmpty()) continue
            collectCategories(anchors, out)
            if (out.size >= 2) return out.values.take(40).toList()
        }

        // 2) 目录式分类：WordPress 系（厂长资源那类）用自定义分类别名做分类页，
        //    URL 既没有 vodshow 也没有数字，第 1 步一条都认不出来。
        collectSlugCategories(doc, out)
        if (out.size >= 2) return out.values.take(40).toList()

        // 2.5) 尾斜杠目录式分类：自研 SSR 站（Nuxt/Vue Router）的 `/tag/{slug}/`。
        //      **必须全文档扫 + 按目录聚类**，不能靠容器，也不能靠白名单：
        //      实测野果短剧首页 nav 里只有「推荐 / 探索分类 / 排行榜 / 回家的路」4 条，
        //      真分类（30+ 个 tag）散落在页面各处；而 `/rank/drama/`、`/explore/drama/`
        //      形状与分类完全相同 —— 唯一分得开的是「同一个目录下有多少个不同别名」。
        out.clear()
        collectSlashDirCategories(doc, out)
        if (out.size >= 2) return out.values.take(40).toList()
        out.clear()

        // 3) 兜底：全文档扫（仍要求是无图纯文字链接）
        out.clear()
        collectCategories(doc.select("a[href]"), out)
        if (out.size < 2) collectSlugCategories(doc, out)
        if (out.size >= 2) return out.values.take(40).toList()

        // 4) 最后兜底：强行把 `/vod/{id}.html` 当分类再扫一遍。
        //    `/vod/{id}.html` 是分类还是详情，全靠"站点有没有独立详情页"来消歧；
        //    详情 URL 五花八门，判据有认不出的时候 —— 那时分类栏就会空着。
        //    这里加「名字短且不含数字」的限制，避免把一屏影片名当成分类标签。
        val alt = LinkedHashMap<String, Category>()
        collectCategories(doc.select("a[href]"), alt, forceCategory = true)
        val clean = alt.filterValues { it.name.length <= 6 && it.name.none { ch -> ch.isDigit() } }
        return clean.values.take(40).toList()
    }

    /**
     * 目录式分类（`/meijutt`、`/riju`…）：只在导航容器内认，避免收进 `/gbook` 这类功能页。
     *
     * [only] 非空时只扫这些容器 —— 「调试校准模式」固化的选择器就走这条路。
     */
    private fun collectSlugCategories(
        doc: Document,
        out: LinkedHashMap<String, Category>,
        only: List<String>? = null
    ) {
        val host = hostOf(site.baseUrl)
        // 遍历**全部**导航容器：同一个站的分类会分散在主导航/顶部栏/底部导航里，只取第一个必然漏
        for (sel in (only ?: slugNavContainers)) {
            for (box in doc.select(sel)) {
                for (a in box.select("a[href]")) {
                    if (a.selectFirst("img") != null) continue
                    val href = a.attr("href").trim()
                    // 两种写法都认：`/riju`（无后缀）与 `/bspvt/dianying.html`（带 .html）。
                    // maccms 后台的「分类别名」两种写法都常见，只认一种就会整站没有分类。
                    if (!HtmlTemplates.isSlugCategory(href) && !HtmlTemplates.isSlugDirCategory(href)) continue
                    if (isFuncSlug(href)) continue

                    val name = a.text().replace(Regex("\\s+"), " ").trim()
                    if (name.isBlank() || name.length > 10) continue
                    if (name in catBlacklist) continue
                    if (catBadWords.any { name.contains(it) }) continue
                    // 同名去重：同一个分类常同时出现在主菜单、二级面板与底部导航里，
                    // URL 可能各不相同（`/bspvt/dianshiju.html` vs `/bspvs/dianshiju-----.html`），
                    // 只按 URL 去重会在分类栏里出现一串重复标签。
                    if (out.values.any { it.name == name }) continue

                    val url = abs(href)
                    if (host.isNotBlank() && !hostOf(url).equals(host, true)) continue
                    if (out.containsKey(url)) continue
                    out[url] = Category(url, name, "0")
                }
            }
        }
    }

    /**
     * URL 的最后一段是不是「站点功能页」词汇（`/contact/`、`/search.html`、`/privacy`…）。
     * 见 [funcSlug] 的说明：功能页词汇是封闭集合，写词表是可靠的。
     */
    private fun isFuncSlug(href: String): Boolean {
        val p = href.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        if (p.isBlank()) return false
        val seg = p.substringAfterLast('/').substringBeforeLast('.').lowercase()
        return seg.isNotBlank() && seg in funcSlug
    }

    /**
     * 「尾斜杠目录式分类」：`/tag/AI%E7%9F%AD%E5%89%A7/`、`/drama/rec-hot-drama/`。
     *
     * ## 为什么必须全文档扫
     *
     * 实测野果短剧（Nuxt3 SSR 站）首页 `<nav>` 里只有 4 条链接
     * （推荐 / 探索分类 / 排行榜 / 回家的路），而真分类是 `/tag/` 下的 30+ 个标签 ——
     * 它们散落在页面的各个推荐区块里。靠容器收集只能拿到 4 条功能页，
     * 这正是 v1.0.12 那条教训的又一次重演（认形状 > 认容器）。
     *
     * ## 为什么必须聚类
     *
     * 全文档扫会顺带收进 `/rank/drama/`、`/explore/drama/` 这类**功能页** ——
     * 它们的 URL 形状和分类一模一样（都是 `/{目录}/{别名}/`），
     * 靠白名单（`rank`/`explore`/`search`…）永远追不上站点改版。
     *
     * 真正的分界线是**数量**：分类目录下面会有很多个**不同的别名**
     * （实测 `/tag/` 有 30+ 个不同 slug），功能页只有一个（`/rank/drama/`、`/explore/drama/`
     * 的别名都是 `drama`）。所以：**同一目录下不同别名 ≥ 2 且不同名称 ≥ 2** 才算分类目录。
     *
     * 名称那一半同样必要：`/drama/rec-hot-drama/` 这类栏目页链接在首页上文字全是
     * 「查看更多」，名字去重后只剩 1 个 —— 按名字判它就不是分类，自然被排除，
     * 不会在分类栏里摆出 6 个一模一样的「查看更多」。
     *
     * [expectDir] 非空 = 用户在校准模式亲手点过这个目录，此时只认它、且不再要求聚类
     * （用户的主权高于启发式，这是 v1.0.14 定下的分工）。
     */
    private fun collectSlashDirCategories(
        doc: Document,
        out: LinkedHashMap<String, Category>,
        expectDir: String? = null
    ) {
        val host = hostOf(site.baseUrl)

        // dir -> (slug -> (name, url))，LinkedHashMap 保证分类栏顺序与页面出现顺序一致
        val byDir = LinkedHashMap<String, LinkedHashMap<String, Pair<String, String>>>()

        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            val ds = HtmlTemplates.slashDirOf(href) ?: continue
            // 分集/播放按钮同样长这样（`/drama/video/3381/`），别名是纯数字已被判据挡掉，
            // 但保险起见：带图片的锚点一律不是分类
            if (a.selectFirst("img") != null) continue
            if (isFuncSlug(href)) continue
            // 目录名本身是功能页词汇的（`/search/xxx/`）也排除
            if (ds.first.lowercase() in funcSlug) continue
            // 分页段（`/tag/x/page/2/` 是三段，不会走到这里；防 `/tag/page/` 这类）
            if (ds.second.equals("page", true)) continue

            val name = a.text().replace(Regex("\\s+"), " ").trim()
            if (name.isBlank() || name.length > 10) continue
            if (name in catBlacklist) continue
            if (catBadWords.any { name.contains(it) }) continue

            val url = abs(href)
            if (host.isNotBlank() && !hostOf(url).equals(host, true)) continue

            val dir = ds.first
            if (expectDir != null && !dir.equals(expectDir, true)) continue
            byDir.getOrPut(dir) { LinkedHashMap() }.putIfAbsent(ds.second, name to url)
        }

        for (slugs in byDir.values) {
            if (expectDir == null) {
                // 聚类判据：同一目录下至少 2 个不同别名、且至少 2 个不同名字
                if (slugs.size < 2) continue
                if (slugs.values.map { it.first }.distinct().size < 2) continue
            }
            for (nv in slugs.values) {
                val (name, url) = nv
                if (out.values.any { it.name == name }) continue   // 同名去重（同一分类常多处出现）
                if (out.containsKey(url)) continue
                out[url] = Category(url, name, "0")
            }
        }
    }

    /**
     * 按「校准固化的分类形状」扫全文档。
     *
     * 这是**唯一**允许全文档认分类的地方 —— 因为目录名是站点自己配的（金牌影视 `/bspvt/`），
     * 比"任意目录"精确得多，不会像 [collectSlugCategories] 那样必须靠容器白名单兜着，
     * 也就不会把 `/gbook`、`/label` 这类功能页收进来。
     */
    private fun collectByCatTpl(
        doc: Document,
        out: LinkedHashMap<String, Category>,
        tpl: String
    ) {
        val host = hostOf(site.baseUrl)
        for (a in doc.select("a[href]")) {
            if (a.selectFirst("img") != null) continue
            val href = a.attr("href").trim()
            if (!HtmlTemplates.matchesCatTpl(href, tpl)) continue

            val name = a.text().replace(Regex("\\s+"), " ").trim()
            if (name.isBlank() || name.length > 10) continue
            if (name in catBlacklist) continue
            if (catBadWords.any { name.contains(it) }) continue
            // 同一分类常在多处出现且 URL 各不相同，按名字去重（同 collectSlugCategories）
            if (out.values.any { it.name == name }) continue

            val url = abs(href)
            if (host.isNotBlank() && !hostOf(url).equals(host, true)) continue
            if (out.containsKey(url)) continue
            out[url] = Category(url, name, "0")
        }
    }

    private fun collectCategories(
        anchors: Elements,
        out: LinkedHashMap<String, Category>,
        forceCategory: Boolean = false
    ) {
        for (a in anchors) {
            if (a.selectFirst("img") != null) continue
            val href = a.attr("href").trim()
            if (href.isEmpty()) continue
            if (href.startsWith("javascript") || href.startsWith("#") || href.startsWith("mailto")) continue
            if (!HtmlTemplates.isCategoryHref(href, vodIsCategory || forceCategory)) continue

            val name = a.text().replace(Regex("\\s+"), " ").trim()
            if (name.isBlank() || name.length > 10) continue
            if (name in catBlacklist) continue
            if (name.endsWith(":") || name.endsWith("：")) continue

            val url = abs(href)
            if (out.containsKey(url)) continue
            out[url] = Category(url, name, "0")
        }
    }

    // ------------------------------------------------------------------ 浏览

    override suspend fun browse(typeId: String, page: Int): List<VideoItem> {
        val ref = typeId.trim()
        // 「最新」tab：已经确认过本站首页不可用时，直接走固化的分类页（省掉一次首页请求）
        if (ref.isEmpty()) homeCat?.let { return browseCat(it, page) }

        val urls = browseUrls(ref, page)
        if (urls.isEmpty()) return emptyList()

        val k = "browse|$ref"
        if (k != seenKey) {
            seenKey = k
            seenIds.clear()
        }
        val list = fetchList(urls, page)
        // ⑤ 失败自动校准（v1.0.25）：首屏一条都解析不出来时，自己在站内探一遍 ——
        // 学详情/分类形状、真抓几个分类页，谁有卡片就把列表模板固化下来。
        // 用户端表现：以前要点「重学本站」或手动进校准，现在第一次进站自愈。
        if (list.isEmpty() && page <= 1 && !autoHealed) {
            autoHealed = true
            autoHeal()?.let { healed ->
                diag = "自动校准生效：${diag}".trim()
                return healed
            }
        }
        // 空 id = 「最新」= 站点首页。首页拿不到封面时改用真分类页，见 substituteHome。
        if (ref.isEmpty() && page <= 1) substituteHome(list)?.let { return it }
        return list
    }

    // ------------------------------------------------------------------ v1.0.25：自愈 + 预渲染

    /** 自动校准只跑一次（同一实例内），避免每次翻页都探测一遍 */
    private var autoHealed = false

    /**
     * ⑤ 失败自动校准：解析拿不到东西时，站内自己探一轮。
     * @return 探到的列表；什么都没探到返回 null（调用方照常报错）
     */
    private suspend fun autoHeal(): List<VideoItem>? {
        // 1) 首页：能学的形状（详情模板 / 分类形状 / 结构判定）全学一遍
        val homeHtml = runCatching { fetch(site.baseUrl) }.getOrNull() ?: return null
        val home = Jsoup.parse(homeHtml, site.baseUrl)
        rememberShape(home)
        learnDetailTpl(home)

        // 2) 候选分类页：先问解析出来的分类，没有就退回模板候选
        val navCands = runCatching { categories() }.getOrDefault(emptyList())
            .map { abs(it.id) }
            .filter { it.startsWith("http") }
            .distinct()
        val cands = (navCands.ifEmpty {
            HtmlTemplates.listCandidates(root).map { build(it, id = "1", page = 1) }
        }).take(4)

        for (c in cands) {
            val html = runCatching { fetch(c) }.getOrNull() ?: continue
            val doc = Jsoup.parse(html, site.baseUrl)
            val items = HtmlExtractor.parseList(doc, site.baseUrl, vodIsCategory, html)
            if (items.size < 5) continue
            rememberShape(doc)
            learnDetailTpl(doc)
            val tpl = guessTpl(c, 1)
            if (tpl != null && tpl != listTpl) {
                listTpl = tpl
                RecipeStore.update(site.baseUrl) { it.copy(listTpl = tpl) }
            }
            diag = "自动校准：改用 $c（${items.size} 条）"
            return accept(items, 1)
        }
        return null
    }

    // ------------------------------------------------------------------ 预渲染兜底（供 UI 层回调）

    /** HTML 解析器才吃「预渲染兜底」：数据本来就该在 DOM 里，只是需要 JS 跑一遍 */
    override val supportsWebRender: Boolean get() = true

    /** 用已渲染好的 HTML 解析列表（WebView 预渲染兜底） */
    override fun parseListFromHtml(html: String, page: Int): List<VideoItem> {
        val doc = Jsoup.parse(html, site.baseUrl)
        rememberShape(doc)
        learnDetailTpl(doc)
        val items = HtmlExtractor.parseList(doc, site.baseUrl, vodIsCategory, html)
        if (items.isEmpty()) return emptyList()
        diag = "预渲染兜底解析到 ${items.size} 条"
        return accept(items, page) ?: emptyList()
    }

    /** 用已渲染好的 HTML 解析详情（WebView 预渲染兜底） */
    override fun parseDetailFromHtml(html: String): VideoDetail? {
        val doc = Jsoup.parse(html, site.baseUrl)
        lastDetailDoc = doc
        val groups = HtmlExtractor.parseGroups(doc, site.baseUrl)
        if (groups.isEmpty()) return null
        tr("预渲染兜底：解析到 ${groups.size} 条线路")
        return buildDetail("", doc, groups)
    }

    override fun searchUrlFor(keyword: String, page: Int): String? =
        ordered(searchTpl, HtmlTemplates.searchCandidates(root))
            .firstOrNull()?.let { build(it, kw = keyword, page = page) }

    override fun browseUrlFor(typeId: String, page: Int): String? =
        browseUrls(typeId, page).firstOrNull()

    /** 详情页地址：配方/学到的模板优先，其次候选模板里最像的那条 */
    override fun detailUrlFor(id: String): String? {
        if (id.isBlank()) return null
        val tpl = detailTpl ?: learnedDetailTpl
        if (!tpl.isNullOrBlank()) return build(tpl, id = id)
        return HtmlTemplates.detailCandidates(root).firstOrNull()?.let { build(it, id = id) }
    }

    private suspend fun browseCat(cat: String, page: Int): List<VideoItem> {
        val k = "browse|$cat"
        if (k != seenKey) {
            seenKey = k
            seenIds.clear()
        }
        return fetchList(browseUrls(cat, page), page)
    }

    /**
     * 「最新」tab 的兜底：**首页列表一条封面都没有**时，改用探到的真分类页。
     *
     * 为什么要做这件事（v1.0.18，野果短剧线上实测）：
     *
     * | | App 默认入口 `browse("")`（首页） | 真分类页 |
     * |---|---|---|
     * | 条数 | 53 | 30 |
     * | 有封面 | **0** | **30** |
     * | 名字 | 全是「查看剧集」 | 正常剧名 |
     * | HTML 里的图床地址 | **0 个** | 31 个 |
     *
     * 原因不是"解析没写好"，而是**首页的数据根本不在 SSR 里**：`__NUXT_DATA__` 只有 SEO 配置
     * （`cover` 值为空、页面上 56 张图全是 `data:` 占位 GIF），剧集列表由客户端 JS 再拉一次接口
     * 才填上。**这类页面无论怎么改选择器都抠不出封面**，只能换数据源。
     *
     * 保守起见必须**探到"分类页确实有封面"才替换**：只试 1~3 个分类，都没有就原样返回首页结果，
     * 不做任何"猜"的替换。探到之后固化进配方（[SiteRecipe.homeCat]），此后只探一次。
     */
    private suspend fun substituteHome(home: List<VideoItem>): List<VideoItem>? {
        if (home.isEmpty() || home.any { it.pic.isNotBlank() }) return null
        val cat = probeCoveredCategory()
        if (cat == null) {
            // v1.0.20：替换失败要留痕。用户反馈「仍然无封面」时，自检报告 / 诊断
            // 必须能区分「探到了但没替换」与「分类都探了但全没封面」。
            val n = runCatching { categories() }.getOrDefault(emptyList()).size
            diag = "首页无封面（客户端渲染），已试探 $n 个分类页均未探到封面 —— " +
                    "若分类 tab 里有封面，请把站点自检报告发出来"
            return null
        }
        homeCat = cat
        RecipeStore.update(site.baseUrl) { it.copy(homeCat = cat) }
        diag = "首页无封面数据（客户端渲染），已改用分类页 $cat"
        return browseCat(cat, 1)
    }

    /** 探前 6 个真分类页，返回**确实带封面**的那一个；都没有则 null（不替换） */
    private suspend fun probeCoveredCategory(): String? {
        val cands = runCatching { categories() }.getOrDefault(emptyList())
            .map { abs(it.id) }
            .filter { it.startsWith("http") }
            .distinct()
            .take(6)
        for (c in cands) {
            if (fetchList(listOf(c), 1).any { it.pic.isNotBlank() }) return c
        }
        return null
    }

    private fun browseUrls(ref: String, page: Int): List<String> {
        if (ref.startsWith("http") || ref.startsWith("/")) {
            val u = abs(ref)
            if (page <= 1) return listOf(u)
            // 分页：先试模板（能从 URL 里抠出分类 id 时），再试 ?page=N，最后回落原页（靠去重收尾）
            val id = HtmlTemplates.typeIdOf(u, vodIsCategory)
            val tpls = if (id == null) emptyList()
            else ordered(listTpl, HtmlTemplates.listCandidates(root)).map { build(it, id = id, page = page) }
            // 目录式分类（/meijutt）没有数字 id，WordPress 系的分页是 /page/N
            val slugPage = if (id == null) listOf("$u/page/$page", "$u/page/$page/") else emptyList()
            return tpls + slugPage + listOf(withQueryPage(u, page), u)
        }
        if (ref.isBlank()) {
            // 空 id = 首页"最新"，站点首页没有分页
            return if (page <= 1) listOf(site.baseUrl) else emptyList()
        }
        return ordered(listTpl, HtmlTemplates.listCandidates(root))
            .map { build(it, id = ref, page = page) }
    }

    // ------------------------------------------------------------------ 搜索

    override suspend fun search(keyword: String, page: Int): List<VideoItem> {
        val k = "search|$keyword"
        if (k != seenKey) {
            seenKey = k
            seenIds.clear()
        }
        // v1.0.20：固定候选全是 maccms 形状，自研站对不上（厂长 action=/nimasile name=q、
        // 骚火 action=/s----------.html name=wd）。站点自己的搜索表单就是标准答案 —— 学一次固化。
        if (searchTpl.isNullOrBlank()) learnSearchTplFromForm()
        // 两遍式：
        // ① 严格——要求结果里至少一条标题带关键词。候选打歪时（404 软跳首页 / 无关词的
        //    搜索总览页）parseList 仍能从推荐位抠出一份列表，表现为「搜什么都出同一批内容」
        //    （金牌影院）。严格遍修掉它，并固化打中的模板。
        // ② 宽松——严格遍全军覆没再退回旧行为。保住「按演员名搜索」这类
        //    结果标题不含关键词的真搜索（搜「吴京」出「战狼」）；宽松遍**不固化**模板，
        //    免得把打歪的候选学成永久配方。
        searchPass(keyword, page, strict = true)?.let { return it }
        return searchPass(keyword, page, strict = false) ?: emptyList()
    }

    private suspend fun searchPass(keyword: String, page: Int, strict: Boolean): List<VideoItem>? {
        for (tpl in ordered(searchTpl, HtmlTemplates.searchCandidates(root))) {
            val html = Http.getOrNull(build(tpl, kw = keyword, page = page), referer = site.baseUrl) ?: continue
            val doc = Jsoup.parse(html, site.baseUrl)
            rememberShape(doc)
            val fresh = accept(HtmlExtractor.parseList(doc, site.baseUrl, vodIsCategory, html), page)
            if (fresh == null) continue
            learnDetailTpl(doc)
            if (strict && fresh.isNotEmpty() &&
                fresh.none { it.name.contains(keyword, ignoreCase = true) }
            ) continue
            if (strict && searchTpl != tpl) {
                searchTpl = tpl
                RecipeStore.update(site.baseUrl) { it.copy(searchTpl = tpl) }
            }
            return fresh
        }
        return null
    }

    /**
     * 从首页搜索表单学 `searchTpl`。
     *
     * [force] = false：只在还没有模板时学（首次搜索）。
     * [force] = true：无条件重学 —— 严格遍全败后的自愈路径。**返回是否学到了与原先不同的
     * 模板**：学不到/没变化都返回 false，调用方就不必白再试一轮。
     */
    private suspend fun learnSearchTplFromForm(force: Boolean = false): Boolean {
        if (!force && !searchTpl.isNullOrBlank()) return false
        val html = runCatching { Http.getOrNull(site.baseUrl, referer = site.baseUrl) }.getOrNull() ?: return false
        val doc = Jsoup.parse(html, site.baseUrl)
        val tpl = HtmlTemplates.searchTplFromForm(doc, root) ?: return false
        if (tpl == searchTpl) return false
        searchTpl = tpl
        RecipeStore.update(site.baseUrl) { it.copy(searchTpl = tpl) }
        diag = "搜索模板来自站点表单：$tpl"
        return true
    }

    // ------------------------------------------------------------------ 详情

    override suspend fun detail(id: String): VideoDetail {
        // 记录这一轮试过什么 —— 失败时随异常一起抛给 UI，自检报告里也会列出
        traceBuf.clear()
        detailTrace = ""
        detailPicHint = ""                      // 每部影片各算各的，别串上一部的封面
        tr("影片 id=$id")
        tr("配方：详情模板=" + (detailTpl ?: "—") + "　播放模板=" + (playTpl ?: "—"))

        // 1) 配方 / 学到的模板优先。**关键**：它们来自磁盘，所以进详情页那个新 Activity
        //    也能直接用 —— 这正是修掉「分类列表都能出、一点详情就失败」的地方。
        for (tpl in listOfNotNull(detailTpl, learnedDetailTpl).distinct()) {
            hitDetail(tpl, id)?.let { return it }
        }

        // 1.5) 详情页**抓到了**、分集却只在播放页 —— 直接去播放页，不必再回首页现学、
        //      也不必盲试 8 个候选地址。判据是详情页 DOM 里现成的分集链接：
        //      自研站（Nuxt）的播放页目录名是站点私有的，穷举模板一条都匹配不上，
        //      但详情页的分集按钮里就摆着真链接。
        if (domPlayUrls().isNotEmpty()) {
            detailFromPlayPage(id)?.let { return it }
        }

        // 2) 手上还没有模板 -> 回首页现学一次（首次冷启动、配方被清掉、站点改路径时自愈）。
        //    放在穷举**之前**：盲试 8 个必然 404 的候选要发 8 次请求，学一条只要 1 次。
        if (learnFromHome()) {
            learnedDetailTpl?.let { tpl -> hitDetail(tpl, id)?.let { return it } }
        }

        // 3) 穷举候选兜底
        for (tpl in HtmlTemplates.detailCandidates(root)) {
            if (tpl == detailTpl || tpl == learnedDetailTpl) continue
            hitDetail(tpl, id)?.let { return it }
        }

        // 4) 兜底：不少主题的**详情页只有海报和简介，分集列表只在播放页**。
        //    用 play 模板探一次，从中把分集捞出来。
        detailFromPlayPage(id)?.let { return it }

        detailTrace = traceBuf.joinToString("\n")
        throw IOException(
            "未能解析该影片详情（HTML 模板不匹配，可尝试网页嗅探播放）\n" +
                "试过这些地址：\n" + traceBuf.takeLast(9).joinToString("\n")
        )
    }

    /** 用一条模板真抓详情页并解析分集；成功就把模板固化进配方 */
    private suspend fun hitDetail(tpl: String, id: String): VideoDetail? {
        val url = build(tpl, id = id)
        val html = fetch(url)
        if (html == null) {
            tr("✗ $url → " + lastFetchErr.ifBlank { "请求失败" })
            return null
        }
        val doc = Jsoup.parse(html, site.baseUrl)
        lastDetailDoc = doc
        rememberShape(doc)
        // 封面与「分集解析成功与否」**无关**：先把详情页这张真海报存下来，
        // 供后面「分集只能去播放页拿」的路径使用（播放页的 og:image 常是默认图）
        resolveUrl(site.baseUrl, HtmlExtractor.parsePic(doc))
            .takeIf { it.isNotBlank() }?.let { detailPicHint = it }
        // 播放页模板在详情页上就能学到（分集按钮的 href），**与分集解析成功与否无关** ——
        // 所以放在 groups 判断之前，失败路径上也能积累这次学习成果
        learnPlayTpl(doc)
        var groups = HtmlExtractor.parseGroups(doc, site.baseUrl)
        if (groups.isEmpty()) {
            // 自研 SSR 站（Nuxt/Vue）的分集不在 DOM 里 —— 锚点是前端路由，服务端只渲染出
            // 一个"当前集"的链接（甚至全是同一个 href）。真数据在页面内嵌 JSON 里，去那儿取。
            groups = SsrPayload.groups(html)
            if (groups.isNotEmpty()) {
                tr("· $url → DOM 无分集锚点，改从页面内嵌 JSON 取到 " +
                        "${groups.sumOf { it.episodes.size }} 集")
            }
        }
        if (groups.isEmpty()) {
            tr("✗ $url → 页面 ${html.length} 字，但一个分集锚点都没认出来")
            return null
        }
        tr("✓ $url → ${groups.sumOf { it.episodes.size }} 集")
        if (detailTpl != tpl) {
            detailTpl = tpl
            RecipeStore.update(site.baseUrl) { it.copy(detailTpl = tpl) }
        }
        return buildDetail(id, doc, groups)
    }

    /** 回首页抓一次，从卡片链接现学详情页模板（整个 Adapter 生命周期内只做一次） */
    private suspend fun learnFromHome(): Boolean {
        if (homeLearned) return learnedDetailTpl != null
        homeLearned = true
        val html = Http.getOrNull(root, referer = root)
        if (html == null) {
            tr("✗ 回首页现学：首页抓不到（$root）")
            return false
        }
        val doc = Jsoup.parse(html, root)
        rememberShape(doc)
        learnDetailTpl(doc)
        val t = learnedDetailTpl
        if (t == null) tr("✗ 回首页现学：首页 ${html.length} 字，但没找到可学的详情链接")
        else tr("· 回首页现学：学到详情模板 $t")
        return t != null
    }

    private fun buildDetail(id: String, doc: Document, groups: List<PlayGroup>): VideoDetail {
        val title = HtmlExtractor.parseTitle(doc)
        return VideoDetail(
            id = id,
            name = title,
            // 播放页兜底时 og:image 常是站点默认图（已被 parsePic 挡掉）⇒ 回落到详情页那张
            pic = resolveUrl(site.baseUrl, HtmlExtractor.parsePic(doc)).ifBlank { detailPicHint },
            summary = HtmlExtractor.parseSummary(doc),
            // 分集名里若带着剧名前缀（`兰香如故第01集`），按剧名削掉 —— 一屏几十集都重复剧名
            // 既挤又难扫，用户看到的就是「兰香如故 第01集」重复十几遍
            groups = HtmlExtractor.stripTitlePrefix(groups, title)
        )
    }

    private suspend fun detailFromPlayPage(id: String): VideoDetail? {
        if (playTpl.isNullOrBlank()) tr("· 播放页兜底：没有播放模板，只能盲试通用候选")
        // 顺序有讲究：**先从详情页 DOM 里真的找到的播放页链接**，再退到猜模板。
        // 自研站（Nuxt）的播放页目录名是站点私有的（`/drama/video/{id}/`），
        // 穷举 `playCandidates` 一条也匹配不上；而详情页的分集按钮里就摆着真链接，
        // 直接抓它一次，比盲试 8 个候选地址靠谱得多 —— 也快得多。
        val urls = domPlayUrls() + orderPlay(HtmlTemplates.playCandidates(root)).map { build(it, id = id) }
        for (url in urls.distinct()) {
            val html = fetch(url)
            if (html == null) {
                tr("✗ $url → " + lastFetchErr.ifBlank { "请求失败" })
                continue
            }
            val doc = Jsoup.parse(html, site.baseUrl)
            var groups = HtmlExtractor.parseGroups(doc, site.baseUrl)
            if (groups.isEmpty()) {
                // 播放页才是真数据源：SSR 把整条 `episodeAll`（每集自带播放地址）塞在页内 JSON 里
                groups = SsrPayload.groups(html)
                if (groups.isNotEmpty()) {
                    tr("· $url（播放页）→ DOM 无分集，改从页内 JSON 取到 " +
                            "${groups.sumOf { it.episodes.size }} 集")
                }
            }
            if (groups.isEmpty()) {
                tr("✗ $url → 页面 ${html.length} 字，但一个分集锚点都没认出来")
                continue
            }
            tr("✓ $url（播放页兜底）→ ${groups.sumOf { it.episodes.size }} 集")
            learnPlayTpl(doc)
            return buildDetail(id, doc, groups)
        }
        return null
    }

    /**
     * 详情页 DOM 里指向播放页的链接（分集按钮）。
     *
     * 只在**分集容器内部**取 —— 这是安全边界：容器名（episode / playlist）全球通用，
     * 而"在分集容器里"本身就说明它是分集链接，不需要再猜 URL 形状。
     * 拿到的链接直接可用，因此自研站也不必先学会播放页模板。
     */
    private fun domPlayUrls(): List<String> {
        val doc = lastDetailDoc ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (sel in episodeContainers) {
            for (a in doc.select("$sel a[href]")) {
                val href = a.attr("href").trim()
                if (href.isEmpty() || href.startsWith("javascript") || href.startsWith("#")) continue
                val abs = abs(href)
                if (!abs.startsWith("http")) continue
                if (hostOf(abs) != hostOf(site.baseUrl) && !hostOf(abs).endsWith("." + hostOf(site.baseUrl))) {
                    continue
                }
                out.add(abs)
            }
            if (out.isNotEmpty()) break
        }
        return out.toList()
    }

    private fun orderPlay(all: List<String>): List<String> =
        if (playTpl.isNullOrBlank()) all else listOf(playTpl!!) + all.filter { it != playTpl }

    // ------------------------------------------------------------------ 内部

    /**
     * 过滤分页结果：
     * - 返回 null 表示"这个 URL 不适合当前页"，去试下一个候选；
     * - 返回空列表表示"没有新内容了"，前端据此收尾。
     */
    private fun accept(items: List<VideoItem>, page: Int): List<VideoItem>? {
        if (items.size < 2) return null
        if (page <= 1) {
            seenIds.clear()
            seenIds.addAll(items.map { it.id })
            return items
        }
        val fresh = items.filter { it.id !in seenIds }
        if (fresh.isEmpty()) return emptyList()
        seenIds.addAll(fresh.map { it.id })
        return fresh
    }

    private suspend fun fetchList(urls: List<String>, page: Int): List<VideoItem> {
        for (u in urls) {
            val html = Http.getOrNull(u, referer = site.baseUrl) ?: continue
            val doc = Jsoup.parse(html, site.baseUrl)
            rememberShape(doc)
            val fresh = accept(HtmlExtractor.parseList(doc, site.baseUrl, vodIsCategory, html), page)
            if (fresh == null) continue
            learnDetailTpl(doc)
            if (fresh.isEmpty()) return emptyList()
            if (urls.size > 1 && u == urls.first()) {
                val g = guessTpl(u, page)
                if (g != null && g != listTpl) {
                    listTpl = g
                    RecipeStore.update(site.baseUrl) { it.copy(listTpl = g) }
                }
            }
            return fresh
        }
        return emptyList()
    }

    /** 从列表页学一条详情页模板（只学一次），学到就写进配方 */
    private fun learnDetailTpl(doc: Document) {
        if (learnedDetailTpl != null) return
        val tpl = HtmlExtractor.detailTplHint(doc, site.baseUrl, vodIsCategory) ?: return
        if (!sameHost(tpl)) return
        learnedDetailTpl = tpl
        RecipeStore.update(site.baseUrl) { it.copy(detailTpl = it.detailTpl ?: tpl) }
    }

    /**
     * 从详情页学一条**播放页**模板：`/bspvp/548165-1-1.html` -> `/bspvp/{id}-1-1.html`；
     * 自研站则学 `/drama/video/3381/` -> `/drama/video/{id}/`。
     *
     * 播放页的目录名同样是站点自己起的（金牌影视 `bspvp`、厂长 `/v_play/`、野果 `/drama/video/`），
     * 穷举 [HtmlTemplates.playCandidates] 举不全。用于「详情页不给分集、只有播放页有」的兜底。
     *
     * 学习顺序：
     * 1. **分集容器内**的锚点最可信 —— 容器名（episode / playlist）是通用语义，
     *    不需要猜 URL 形状，自研站也能学到；
     * 2. 整页 [HtmlTemplates.isPlayLink] 命中的锚点（覆盖 maccms 系）。
     */
    private fun learnPlayTpl(doc: Document) {
        if (!playTpl.isNullOrBlank()) return

        // 1) 分集容器优先：里面的锚点必然是「去播放」的链接
        for (sel in episodeContainers) {
            for (a in doc.select("$sel a[href]")) {
                val href = a.attr("href").trim()
                if (href.isEmpty() || href.startsWith("javascript") || href.startsWith("#")) continue
                val t = HtmlTemplates.tplFromNumericSegment(abs(href)) ?: continue
                if (!sameHost(t)) continue
                // 学到的必须是**播放页**：详情页自己的模板不该从这条路上回来
                if (t == detailTpl || t == learnedDetailTpl) continue
                playTpl = t
                RecipeStore.update(site.baseUrl) { it.copy(playTpl = t) }
                return
            }
        }

        // 2) 整页找：maccms 系的播放页链接
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!HtmlTemplates.isPlayLink(href)) continue
            val t = HtmlTemplates.playTplFrom(abs(href)) ?: continue
            if (!sameHost(t)) continue
            playTpl = t
            RecipeStore.update(site.baseUrl) { it.copy(playTpl = t) }
            return
        }
    }

    /** 学到的模板必须属于本站：列表页里混进广告 / 外链时会学到别家的模板 */
    private fun sameHost(tpl: String): Boolean {
        val a = hostIn(tpl)
        val b = hostIn(root)
        if (a.isBlank() || b.isBlank()) return false
        return a == b || a.endsWith(".$b") || b.endsWith(".$a")
    }

    private fun hostIn(url: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase().orEmpty()

    /** 首页/列表页第一次解析时确定站点形态，之后沿用；学到就写进配方 */
    private fun rememberShape(doc: Document) {
        if (vodIsCategory) return
        if (!detectVodShape(doc)) return
        vodIsCategory = true
        RecipeStore.update(site.baseUrl) { it.copy(vodIsCategory = true) }
    }

    private fun detectVodShape(doc: Document): Boolean {
        for (a in doc.select("a[href]")) {
            if (HtmlTemplates.isDetailSignal(a.attr("href"))) return true
        }
        return false
    }

    /** 值不大，够用：命中过就记住，避免每页重复试错 */
    private fun guessTpl(url: String, page: Int): String? {
        val id = HtmlTemplates.typeIdOf(url, vodIsCategory) ?: return null
        for (tpl in HtmlTemplates.listCandidates(root)) {
            if (build(tpl, id = id, page = page) == url) return tpl
        }
        return null
    }

    private fun abs(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return root
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:$u"
        return "$root/${u.trimStart('/')}"
    }

    private fun hostOf(url: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1).orEmpty()

    private fun withQueryPage(url: String, page: Int): String =
        url + (if (url.contains("?")) "&" else "?") + "page=$page"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun build(tpl: String, id: String = "", page: Int = 1, kw: String = ""): String {
        val hasPageToken = tpl.contains("{page}")
        var u = tpl
            .replace("{id}", id)
            .replace("{page}", "$page")
            .replace("{kw}", enc(kw))
        if (!u.startsWith("http")) u = "$root/${u.trimStart('/')}"
        if (page > 1 && !hasPageToken) u = withQueryPage(u, page)
        return u
    }

    private fun ordered(pref: String?, all: List<String>): List<String> =
        if (pref.isNullOrBlank()) all else listOf(pref) + all.filter { it != pref }
}
