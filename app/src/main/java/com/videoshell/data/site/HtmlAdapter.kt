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

    internal var listTpl: String? = null
    internal var detailTpl: String? = null
    private var searchTpl: String? = null
    internal var playTpl: String? = null

    /**
     * 本站是否走「独立详情页 + 独立播放页」结构（决定 `/vod/{id}.html` 是分类还是详情）
     */
    @Volatile
    internal var vodIsCategory = false

    /** 分类解析结果缓存 */
    private var cachedCats: List<Category> = emptyList()

    /**
     * 从列表页学到的详情页模板（`/movie/{id}.html` 之类）。
     * 比穷举 [HtmlTemplates.detailCandidates] 可靠得多，优先级也更高。
     */
    internal var learnedDetailTpl: String? = null

    /**
     * ## 从**形状普查**学到的分类形状（v1.0.34 提出 → v1.0.35 固化）
     *
     * 与 [learnedDetailTpl] 同一个思路：能从页面当场测出来的东西，就别指望用户去校准。
     *
     * v1.0.34 只让它活在实例内存里（"先测量，再固化"）；现在判据已被证明可靠
     * （两道阈值 + 归纳与接收复用同一套收集器），于是**收出 ≥2 个分类就写盘**
     * （[SiteRecipe.learnedCatTpl]）。固化后冷启动不必再扫全文档，
     * 而且**活证据优先** —— 现场普查的形状排在固化值前面，站点改版能立刻自愈。
     */
    private var learnedCatTpl: String? = null

    /** 磁盘上那份普查形状（用来避免重复写盘；与 [learnedCatTpl] 可能暂时不同） */
    private var savedCatTpl: String? = null

    /** 最近一次分类栏是靠哪个规则得到的（普查 / 校准 / 默认），自检报告里展示 */
    @Volatile
    private var censusDiag: String = ""

    /** 已回到首页现学过一次（避免每次详情失败都重抓首页） */
    internal var homeLearned = false

    /**
     * 最近一次成功抓到的详情页 DOM。
     *
     * 留着它是为了让「播放页兜底」不必再猜 URL：详情页的分集按钮里就摆着真实的播放页链接
     * （自研站 `/drama/video/{id}/` 这种站点私有目录名，穷举模板永远猜不到）。
     */
    internal var lastDetailDoc: Document? = null

    /**
     * 详情页 HTML 里拿到的封面。
     *
     * 留着它是为了「分集只能去播放页拿」的那条路径：详情页的 `og:image` 是真海报，
     * 而**播放页的 `og:image` 往往是站点级默认分享图**（野果实测：详情页
     * `…2026091721151270131.jpeg` vs 播放页 `images/social-default.png`）。
     * 详情页抓到时就先把封面存下来，播放页兜底构造 VideoDetail 时接着用 ——
     * 零额外请求，也不必回头再抓一次详情页。
     */
    internal var detailPicHint: String = ""

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
    internal var homeCat: String? = null

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
            // 形状普查的固化值（v1.0.35）：冷启动直接排进候选，不必再扫一遍全文档
            learnedCatTpl = r.learnedCatTpl?.takeIf { it.isNotBlank() }
            savedCatTpl = learnedCatTpl
        }
    }

    /** 分类为空时把原因带出去，让 UI 能显示出来（不然只能靠猜） */
    internal var diag: String = ""

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

    internal fun tr(s: String) {
        if (traceBuf.size < 24) traceBuf += s
    }

    /**
     * 分类为空时是分类的原因；分类没问题但详情失败时给出**详情试过的地址清单**。
     * 两条都为空才算"没话说"。
     */
    override val lastDiag: String
        get() = diag.ifBlank { detailTrace }

    /**
     * 人工校准的 `searchTpl` 被自动学习顶掉时的**留痕**（v1.0.33）。
     *
     * 搜索的两遍式是"谁有结果用谁"，并把打中的候选**固化**进配方（见 [searchPass]）。
     * 这对自愈是必要的 —— 野果实测就是这样救回来的：人工校准存下的
     * `/search/drama/{kw}/` 是**200 空壳（0 条结果）**，而自动学到的 `/?s={kw}` 出 58 条。
     *
     * 但**静默**覆盖掉用户亲手校准的那一条是不可接受的：配方从此与它的 `calibNote`
     * 各说各话（实测：note 写着 `/search/drama/{kw}/`，配方里存的却是 `/?s={kw}`），
     * 让下一次排查变成猜谜。所以这一条必须留痕、并进 [calibDiag]。
     */
    @Volatile
    private var searchTplSwap: String? = null

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
            val swap = searchTplSwap?.let { "\n      ⚠️ $it" } ?: ""
            // 分类栏最终是**靠哪个规则**得到的（人工校准 / 形状普查 / 默认判据）——
            // 没有这一行时，用户只能看到"分类有 40 个"，看不出它到底是谁收的（v1.0.34）。
            val census = censusDiag.takeIf { it.isNotBlank() }?.let { "\n      $it" } ?: ""
            return "配方来源：$head｜$shape\n      $tail$swap$census"
        }

    /** 校准规则**真的用上了**：形状/容器命中了本次页面（而不是静默退回默认判据） */
    override val calibApplied: Boolean get() = calibAppliedFlag

    /** 最近一次抓取失败的具体原因（异常文本），分类为空时并进 [diag] 一起展示 */
    internal var lastFetchErr: String = ""

    /** 当前浏览目标的已见影片 id，用于分页去重 */
    internal val seenIds = HashSet<String>()
    internal var seenKey = ""

    internal val root: String get() = site.baseUrl.trimEnd('/')

    // ------------------------------------------------------------------ 分类

    /** 导航语义容器（越靠前越像"分类标签"） */
    private val navSelectors = listOf(
        ".main_nav a", ".tab_head a", ".nav-list a", "header nav a", "nav a",
        ".navbar a", ".nav_bar a", ".menu a", "#nav a", ".header-nav a", "header .nav a", "header a"
    )

    internal val catBlacklist = setOf(
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
    internal val funcSlug = setOf(
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
    internal val catBadWords = listOf(
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
    internal val slugNavContainers = listOf(
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
    internal val episodeContainers = listOf(
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
            // 顺手把首页签名喂给软 404 守卫（v1.0.34）——
            // 这样搜索时判"这份结果页是不是首页副本"**不必额外再抓一次首页**，守卫就是纯零成本。
            com.videoshell.data.net.SoftMiss.rememberHome(site.baseUrl, home)
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
    internal suspend fun fetch(url: String): String? {
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

        // 2.6) 形状普查（v1.0.34）：**免校准**的通用分类形状发现。
        //
        //      前面 1~2.5 步都是在「已知的形状家族」里找：导航容器、无后缀别名、
        //      尾斜杠目录。新站的分类形状只要不落在这几类里，分类栏就是空的 ——
        //      用户唯一的选择是去手工校准。
        //
        //      而 30 多个版本反复证明：**形状列举不完，数量分得开**。所以这里不猜形状，
        //      而是当场把页面上每条文字链接归纳成形状、按形状聚类，然后只看两个量：
        //        · 不同别名数 ≥2（真分类目录有很多别名，`/explore/drama/` 这类功能页只有 1 个）
        //        · 不同文字数 ≥2（挡掉栏目页那一串「查看更多」）
        //      归纳形状与接收形状复用的是**同一套运行时判据**（`HtmlTemplates.stemOf` → `slashDirOf`/`catTplFrom`，
        //      接收用 `collectByCatTpl` / `collectSlashDirCategories`），所以普查提议的形状
        //      运行时一定认；而"够不够 2 个"这个终判仍然交给真收集器 —— 判据只有一份。
        //
        //      代价：一次全文档扫描，且只在前面所有形状家族都失败时才走到这里（极少）。
        //
        //      固化（v1.0.35）：一旦某个形状**真的收出 ≥2 个分类**，就写盘
        //      （[SiteRecipe.learnedCatTpl]）—— 写盘的形状因此一定是"用过且有效"的，不是提议。
        //      顺序上**活证据优先**：本次现场普查出来的排在固化值前面，
        //      站点改版后新形状立刻顶掉旧的，旧值只在现场全部不成立时当退路。
        val fresh = LinkedHashSet<String>()
        runCatching {
            HtmlTemplates.shapeCensus(doc) { n ->
                n.isNotBlank() && n.length <= 10 &&
                        n !in catBlacklist && catBadWords.none { w -> n.contains(w) }
            }
        }.getOrDefault(emptyList())
            .filter { it.aliases >= 2 && it.names >= 2 }
            .forEach { fresh += it.tpl }

        val censusTries = LinkedHashSet<String>()
        censusTries += fresh
        learnedCatTpl?.takeIf { it.isNotBlank() }?.let { censusTries += it }   // 固化值垫底

        for (tpl in censusTries) {
            val tmp = LinkedHashMap<String, Category>()
            runCatching {
                if (HtmlTemplates.isSlashCatTpl(tpl)) {
                    collectSlashDirCategories(doc, tmp, expectDir = HtmlTemplates.dirOfSlashCatTpl(tpl))
                } else {
                    collectByCatTpl(doc, tmp, tpl)
                }
            }
            if (tmp.size >= 2) {
                learnedCatTpl = tpl
                if (savedCatTpl != tpl) {
                    savedCatTpl = tpl
                    RecipeStore.update(site.baseUrl) {
                        it.copy(learnedCatTpl = tpl, learnedCatAt = System.currentTimeMillis())
                    }
                }
                censusDiag = "分类形状来自**形状普查**（免校准）：$tpl 命中 ${tmp.size} 个" +
                        if (tpl in fresh) "（本次现场普查）" else "（沿用上次固化，本次现场无新结论）"
                return tmp.values.take(40).toList()
            }
        }

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
     * 校准「当场自证」：这条分类形状在这份首页 HTML 上**真能收到几个分类**。
     *
     * 关键在"复用"：这里调用的就是 [categoriesFrom] 第 0 步那两个收集器
     * （[collectSlashDirCategories] / [collectByCatTpl]），所以"校准当场数出来的数"
     * 与"运行时判 ≥2 的那个数"**必然相等**。判据只能有一份 —— 否则就是
     * 「校准界面说学到了、运行时另一套判据不认」这类最难查的不一致。
     *
     * 为什么需要它（v1.0.33 野果实测）：
     * 用户在校准第 1 步点的是**侧栏导航项** `<a href="/explore/drama/">探索分类</a>`。
     * [HtmlTemplates.catTplFrom] 会把它的末段当成可变别名，老老实实泛化成
     * `/explore/{slug}/` —— 而站点真分类是 `/tag/{slug}/`，两者**形状完全一样**。
     * 形状永远分不开，只有数量能分开：首页 330 个 `<a>` 里 `/explore/{slug}/` 只有 **1 条**，
     * `/tag/{slug}/` 有 **252 条**。
     *
     * 旧行为：这一条照样写进配方 ⇒ 运行时命中 1 个（判据要求 ≥2）⇒ 静默退回默认逻辑
     * ⇒ 用户看到「校准走完了，但用起来跟没校准一样」，而且**永远查不出为什么**。
     * 现在交给校准界面当场拦下，并告诉他点错了什么。
     */
    override fun countCatTplHits(html: String, tpl: String): Int {
        if (tpl.isBlank()) return -1
        val doc = runCatching { Jsoup.parse(html, site.baseUrl) }.getOrNull() ?: return -1
        val tmp = LinkedHashMap<String, Category>()
        return runCatching {
            if (HtmlTemplates.isSlashCatTpl(tpl)) {
                collectSlashDirCategories(doc, tmp, expectDir = HtmlTemplates.dirOfSlashCatTpl(tpl))
            } else {
                collectByCatTpl(doc, tmp, tpl)
            }
            tmp.size
        }.getOrDefault(-1)
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

            // ★ v1.0.34 软 404 守卫：候选地址把**首页**原样吐回来时，这份「结果」与关键词无关。
            //
            //   野果实测（2026-09-19）：`/?s=庆余年` 与 `/?s=zzzq不存在的词` 返回的页面
            //   **与首页 sha256 完全相同**（都是 246747 B），而首页自带 58 条详情链接、
            //   其中 3 条恰好含「庆余年」—— 严格遍于是判定"命中"，把这份**首页推荐位**
            //   当成了搜索结果，还把 `/?s={kw}` 固化进配方。
            //   用户看到的是「搜什么都一样」，而状态码 / 条数 / 详情链接数**全是绿的**。
            //
            //   刻意放在 `rememberShape` **之前**：首页副本不该被当成"当前上下文"去学站点结构。
            //   拿不到首页签名时守卫返回 false（放行）—— 宁可漏收，不可错收。
            if (com.videoshell.data.net.SoftMiss.isHomeCopy(site.baseUrl, html)) {
                searchTplSwap = "已跳过候选 $tpl：它返回的是**首页副本**（软 404 回首页），" +
                        "与关键词无关 —— 本站的 URL 搜索不可用"
                continue
            }

            val doc = Jsoup.parse(html, site.baseUrl)
            rememberShape(doc)
            val fresh = accept(HtmlExtractor.parseList(doc, site.baseUrl, vodIsCategory, html), page)
            if (fresh == null) continue
            learnDetailTpl(doc)
            if (strict && fresh.isNotEmpty() &&
                fresh.none { it.name.contains(keyword, ignoreCase = true) }
            ) continue
            if (strict && searchTpl != tpl) {
                val was = searchTpl
                searchTpl = tpl
                RecipeStore.update(site.baseUrl) { it.copy(searchTpl = tpl) }
                // 留痕：[was] 可能是**人工校准**那一条。野果实测就是这样救回来的
                //（校准存的 `/search/drama/{kw}/` 是 200 空壳、0 条结果，
                //  自动学到的 `/?s={kw}` 出 58 条）。替换本身是对的，但**不能悄悄做** ——
                // 否则配方与它的 calibNote 各说各话，下一次排查就只剩猜（见 [searchTplSwap]）。
                searchTplSwap = "搜索模板已被替换：$was → $tpl" +
                        "（原模板在严格遍一条结果都没有，已改用实测有结果的那条）"
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



}
