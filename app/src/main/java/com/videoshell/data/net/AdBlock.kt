package com.videoshell.data.net

import com.videoshell.data.site.Media
import java.net.URI

/**
 * 网页运行时的**广告拦截**（v1.0.52）。
 *
 * ## 为什么嗅探 / 校准页必须有它
 *
 * 那两个页面是全 App 唯一**真的把第三方站点跑起来**的地方（WebView），而它们要做的事
 * 恰恰是"让用户在网页里点一下正确的东西"：
 *
 *  - **校准**：让用户点分类、点影片、点分集 —— 学的是**用户点的那个链接的形状**。
 *    广告浮层（浮层盖住正文、透明层劫持点击、"3 秒后跳转"的弹窗）会让这次点击落在
 *    广告上，于是学到的形状是**广告链接的形状**，写进配方就是"这个站怎么点都不对"。
 *    这类错误极难排查：所有报错都是绿的，只有学出来的模板是错的。
 *  - **嗅探**：广告播放器会先于正片发出自己的 m3u8，候选清单因此被污染
 *    （[com.videoshell.player.SniffRank] 只能事后给它减分，拦在门口更省事）。
 *
 * ## 三条自我约束（比"拦得多"重要得多）
 *
 * 1. **媒体一律不拦**（[blockedResource] 里那道早退）：漏拦一个广告只是少省一次请求，
 *    误拦一个分片就是播放挂掉 —— 两侧代价完全不对称，所以这条判据先于一切。
 * 2. **不广谱隐藏 DOM**（[hideCss] 只针对**我们自己判定为广告的那些主机**的 iframe，
 *    外加 adsbygoogle 这一种行业公认容器）。`[class*=ad]` 那种写法会把
 *    `/thumb/`、`ads-container` 这类正文容器一起藏掉，用户点不到任何东西 —— 症状是
 *    "校准卡在第一步且没有任何提示"，正是本项目最忌讳的静默失效。
 * 3. **判据与产出同源**：[hideCss] 的选择器由 [AD_HOSTS] 生成，不另抄一份域名列表。
 *
 * ## 纯逻辑
 *
 * 本文件**不 import 任何 Android 类**（`WebResourceResponse` / `WebView` 在
 * [WebAdBlock] 里），所以整套判据能在离线 Java harness 里逐条断言 —— 见 `runadb.py`。
 */
object AdBlock {

    // ------------------------------------------------------------------ 域名

    /**
     * 广告 / 统计的**主机名后缀**（整段或 `.` 后缀，绝不子串匹配）。
     *
     * 只收"域名本身就是广告或统计服务"的：这类主机上的资源**不可能是页面正文**，
     * 拦掉不会让页面缺东西。所以不写 `bdstatic.com`、`upaiyun.com` 这类
     * "既放广告也放正经库"的 CDN —— 它们一封，页面自己的 JS 就死了。
     */
    val AD_HOSTS: List<String> = listOf(
        // 国际广告平台
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagservices.com", "adservice.google.com", "2mdn.net",
        "amazon-adsystem.com", "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "pubmatic.com", "rubiconproject.com",
        "openx.net", "smartadserver.com", "media.net", "yieldmo.com", "sharethrough.com",
        "adform.net", "casalemedia.com", "3lift.com", "zedo.com", "adblade.com",
        // 统计 / 埋点
        "google-analytics.com", "googletagmanager.com", "scorecardresearch.com",
        "quantserve.com", "exelator.com", "bluekai.com", "moatads.com", "hotjar.com",
        "mouseflow.com", "luckyorange.com", "clarity.ms", "statcounter.com",
        "histats.com", "matomo.cloud", "adjust.com", "appsflyer.com", "branch.io",
        // 国内广告 / 统计
        "admaster.com.cn", "miaozhen.com", "ipinyou.com", "alimama.com", "tanx.com",
        "mmstat.com", "cpro.baidu.com", "pos.baidu.com", "union.baidu.com",
        "hm.baidu.com", "cbjs.baidu.com", "mobads.baidu.com", "mob.com", "umeng.com",
        "umengcloud.com", "umeng.co", "cnzz.com", "cnzz.net", "51.la", "51yes.com",
        "talkingdata.com", "growingio.com", "sensorsdata.cn", "pv.sohu.com",
    )

    /**
     * **主机标签**级的广告词：`ads.example.com` / `adx.cdn.com` 这种。
     *
     * 与 [AD_SEG] 分开是有意的：主机标签里出现 `stat` / `analytics` 基本等于统计服务，
     * 而路径里出现 `count` / `notice` / `tip` 完全可能是正文（`/notice/` 是公告页、
     * `/tips/` 是攻略）—— 两边共用一个集合就一定有一边是错的。
     */
    private val AD_LABELS: Set<String> = setOf(
        "ad", "ads", "adx", "adv", "advert", "adsense", "adserver", "adservice",
        "adservices", "adnetwork", "advertising", "guanggao", "tuiguang",
        "tongji", "analytics", "tracker", "tracking", "stat", "stats", "popunder",
    )

    /**
     * **路径段 / 文件名**级的广告词。
     *
     * ⚠️ 只收"作为完整一段出现"才成立的词（`/ad/1.js`、`/gg/x`、`/preroll/x`）——
     * 这正是 `contains("ad")` 会误杀 `download` / `load` / `head` / `upload` /
     * `thread` / `broadcast` 的地方（[com.videoshell.player.SniffRank] 里那组反例断言
     * 就是为这件事立的）。刻意**不含** `thumb` / `sprite` / `notice` / `tip` / `preview`：
     * 它们的封杀对象通常是**图片**，而很多站的封面正好在 `/thumb/` 下，拦掉页面就没图了。
     */
    private val AD_SEG: Set<String> = setOf(
        "ad", "ads", "adx", "adv", "advert", "adverts", "advertise", "advertising",
        "adserver", "adservice", "adjump", "adframe", "adjs", "adhtml",
        "gg", "guanggao", "tuiguang", "preroll", "midroll", "postroll", "popunder",
    )

    /** 文件名级的广告脚本（`ad.js` / `adsbygoogle.js` / `baidu_hm.js`） */
    private val AD_FILES: Set<String> = setOf(
        "ad.js", "ads.js", "adx.js", "adv.js", "ad_js.js", "advert.js", "adfont.js",
        "adsbygoogle.js", "pagead2.js", "googletag.js", "gtag.js", "guanggao.js",
        "tuiguang.js", "baidu_hm.js", "hm.js", "cnzz.js", "stat.js", "tongji.js",
        "analytics.js", "track.js", "tracker.js", "popunder.js", "pop.js",
    )

    /** 查询串里出现的广告值：`?type=preroll`、`?ad=1` */
    private val AD_QUERY_VALS: Set<String> = setOf(
        "ad", "ads", "preroll", "midroll", "postroll", "advert", "popunder",
    )

    /**
     * 「跳 App」类 scheme。
     *
     * 这类链接在网页里**只有一个用途**：诱导用户点一下就把 App 拉起来（广告主的结算方式）。
     * 拦掉它同时也是防止误触 —— 校准页里用户点的是"分类"，不该弹出一个电商 App。
     */
    private val APP_SCHEMES: List<String> = listOf(
        "intent:", "market:", "taobao:", "tmall:", "weixin:", "wechat:", "alipay",
        "alipays:", "mqqapi:", "mqqwpa:", "baiduboxapp:", "snssdk", "openapp",
        "douyin", "kwai:", "manhuang:", "ucbrowser:",
    )

    // ------------------------------------------------------------------ 主机

    fun hostOf(url: String): String? =
        runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 主域（`a.b.example.com` 与 `cdn.example.com` 视为同一站）。
     *
     * 二级后缀（`com.cn` / `com.hk` / `co.jp` …）要当作**一层**，否则
     * `x.com.cn` 与 `y.com.cn` 会被判成同一个站 —— 而它们毫无关系。
     */
    fun rootOf(host: String): String {
        val h = host.lowercase()
        val p = h.split('.')
        if (p.size <= 2) return h
        val last2 = p.takeLast(2).joinToString(".")
        return if (last2 in TWO_LEVEL_SUFFIX) p.takeLast(3).joinToString(".") else last2
    }

    private val TWO_LEVEL_SUFFIX: Set<String> = setOf(
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "com.hk", "com.tw", "com.mo", "co.jp", "co.kr", "co.uk", "com.au", "com.sg",
    )

    /** 目标是不是**另一个站**（主域不同）。地址取不到主机时一律 false —— 判不出来就不拦 */
    fun crossSite(from: String, to: String): Boolean {
        val a = hostOf(from) ?: return false
        val b = hostOf(to) ?: return false
        return rootOf(a) != rootOf(b)
    }

    // ------------------------------------------------------------------ 判据

    /** 主机名命中广告/统计（整段或 `.` 后缀；再按主机标签查一次） */
    fun hostBlocked(url: String): Boolean {
        val h = hostOf(url) ?: return false
        if (AD_HOSTS.any { h == it || h.endsWith(".$it") }) return true
        return h.split('.').any { it in AD_LABELS }
    }

    /** 地址本身像广告（主机 / 路径段 / 文件名 / 查询串，四道都按"整段相等"判） */
    fun looksLikeAd(url: String): Boolean {
        if (url.isBlank()) return false
        if (hostBlocked(url)) return true
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        for (seg in u.path.orEmpty().lowercase().split('/')) {
            if (seg.isEmpty()) continue
            val bare = seg.substringBeforeLast('.')
            if (bare.isNotEmpty() && bare in AD_SEG) return true
            if (seg in AD_FILES) return true
        }
        for (kv in u.query.orEmpty().lowercase().split('&', ';')) {
            if (kv.isEmpty()) continue
            if (kv.substringBefore('=') in AD_SEG) return true
            if (kv.substringAfter('=', "") in AD_QUERY_VALS) return true
        }
        return false
    }

    /** 诱导跳 App 的 scheme */
    fun isAppJump(url: String): Boolean {
        val u = url.trim().lowercase()
        return APP_SCHEMES.any { u.startsWith(it) }
    }

    /**
     * 子资源（脚本 / 图片 / iframe / XHR）要不要拦。
     *
     * **媒体先放行**：这一条必须排在广告判据之前。反过来说，`/ad/movie/index.m3u8`
     * 这种"路径里有广告词但确实是片子"的地址，我们宁可放过（只是少省一次请求）。
     */
    fun blockedResource(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http", true)) return false        // data: / blob: / file: 不归我们管
        if (isMedia(u)) return false                         // ★ 安全阀，见函数注释
        return looksLikeAd(u)
    }

    /**
     * 顶层跳转要不要拦。
     *
     * 四条规则的顺序就是它们的把握程度：
     *
     *  1. **跳 App** ⇒ 拦（网页里没有正当用途）；
     *  2. **目标本身像广告** ⇒ 拦（哪怕用户点了一下：没人会故意点广告域名）；
     *  3. **用户自己的点击 / 站点自己的服务端跳转** ⇒ 放行。
     *     服务端 302 必须放行：域名轮换的站全靠它，拦了就等于把站打瘸；
     *  4. 剩下的"**跨站 + 无手势 + 不是重定向**"就是弹窗（popunder）。
     *     这是唯一一条**靠行为推断**的规则，因此调用方必须在拦下时**告诉用户**
     *     （见各 Activity 的提示 + 面板上的去广告开关）：推断就一定会错，
     *     错了要让人看得见、关得掉。
     */
    fun blockNav(from: String, to: String, hasGesture: Boolean, isRedirect: Boolean): Boolean {
        val t = to.trim()
        if (t.isEmpty()) return false
        if (isAppJump(t)) return true
        if (looksLikeAd(t)) return true
        if (hasGesture || isRedirect) return false
        return crossSite(from, t)
    }

    /** 媒体资源（含 `Media.looksLikeMedia` 没覆盖的几种容器/分片） */
    private fun isMedia(url: String): Boolean {
        if (Media.looksLikeMedia(url)) return true
        val u = url.lowercase()
        return u.contains(".m4s") || u.contains(".mkv") || u.contains(".webm") ||
            u.contains(".aac") || u.contains(".m3u")
    }

    // ------------------------------------------------------------------ 注入

    /**
     * 隐藏广告容器的 CSS。
     *
     * 只针对 **`[AD_HOSTS]` 里的主机**的 iframe（这类主机不可能承载正文）+
     * `adsbygoogle` 这个行业公认的广告容器。**刻意不写** `[class*=ad]`、
     * `.ad`、`[id*=gg]` 这类宽泛选择器 —— 它们会连正文容器一起藏，
     * 用户点不到任何东西，而界面不会给出任何解释。
     *
     * 为什么还要单独藏 iframe：资源被拦下之后 iframe 里是空的，但**空 iframe 照样
     * 占位置、照样吃掉点击** —— 只拦不藏，浮层的壳还在那儿挡事。
     */
    fun hideCss(): String {
        val sel = ArrayList<String>(AD_HOSTS.size + 4)
        sel += "ins.adsbygoogle"
        sel += ".adsbygoogle"
        sel += "iframe[id^='google_ads']"
        AD_HOSTS.forEach { sel += "iframe[src*='$it']" }
        return sel.joinToString(",") + "{display:none!important}"
    }

    /** 幂等注入 [hideCss] 的脚本（返回 `ok` / `err`，供调用方自检） */
    fun hideJs(): String =
        "(function(){try{" +
            "if(window.__vsAdCss)return 'ok';" +
            "var s=document.createElement('style');" +
            "s.setAttribute('data-vs','adblock');" +
            "s.textContent=" + jsString(hideCss()) + ";" +
            "(document.head||document.documentElement).appendChild(s);" +
            "window.__vsAdCss=1;return 'ok';" +
            "}catch(e){return 'err'}})()"

    // ------------------------------------------------------------------ DOM 清扫（v1.0.57）

    /**
     * 隐藏**运行时注入**的广告节点（v1.0.57）。
     *
     * ## 为什么 CSS 拦不住它
     *
     * 2026-09-22 用户实测截图（金牌影视校准页）：页头/页底出现**宽幅图片广告**
     * （"免费海量美女视频" / "深夜看片必备"）。事后用 PC 端 40 次抓样 + WebView 指纹
     * UA 对照都**复现不出**这版 HTML —— 广告是运行时 JS 插进来的，且服务端按
     * 请求特征（IP / 时段 / 频次）决定给不给。[hideCss] 只能藏"选择器写得出"的
     * 容器，对"运行时才出现的任意节点"无能为力。
     *
     * ## 两条判据（刻意只收"正文不可能是这个形状"的）
     *
     * 1. **宽幅外链图幅**：`<a href=外站>` 里包着 `<img>`（或背景图），且容器
     *    宽 ≥200px、宽高比 ≥2.5。正片海报是**同站链接 + 竖版**（宽高比 ≈0.7），
     *    天然不会命中 —— 这就是"外链 + 横幅"两个条件各自都在排除一半误伤的原因。
     * 2. **大面积悬浮层**：`position:fixed/sticky` + `z-index≥90` + 盖住半屏。
     *    返回顶部按钮也是 fixed 但很小，播控层在 video 里且已豁免。
     *
     * ## 防误伤的硬约束
     *
     * - 含 `<video>` 的元素一律不动（那是播放器本体）；
     * - 杀掉的节点都打 `data-vs-ad` 标记：幂等 + 可回查（"这节点为什么没了"）；
     * - MutationObserver + 前几次定时清扫兜住"广告比正文晚到"的路径。
     *
     * 返回值是**本次清扫新杀掉的节点数**（供留痕），整体幂等可反复注入。
     */
    fun sweepJs(): String {
        val TWO_LEVEL = "com.cn,net.cn,org.cn,gov.cn,edu.cn,ac.cn,com.hk,com.tw,com.mo,co.jp,co.kr,co.uk,com.au,com.sg"
        return "(function(){try{" +
            "window.__vsN=window.__vsN||0;" +
            "function ROOT(h){var p=h.split('.');var L=p.slice(-2).join('.');" +
            "if(${jsStringList(TWO_LEVEL)}.indexOf(L)>=0&&p.length>=3)L=p.slice(-3).join('.');return L;}" +
            "function CROSS(u){try{var h=new URL(u,location.href).hostname;if(!h)return false;" +
            "return ROOT(h)!==ROOT(location.hostname);}catch(e){return false;}}" +
            "function KILL(el,why){if(el.getAttribute('data-vs-ad'))return;" +
            "el.setAttribute('data-vs-ad',why);el.style.setProperty('display','none','important');window.__vsN++;}" +
            "function SWEEP(){try{" +
            "var as=document.querySelectorAll('a[href]');" +
            "for(var i=0;i<as.length;i++){var a=as[i];if(a.getAttribute('data-vs-ad'))continue;" +
            "var href=a.getAttribute('href')||'';" +
            "if(href.charAt(0)==='#'||href.indexOf('javascript:')===0)continue;" +
            "if(!CROSS(href))continue;" +
            "var img=a.querySelector('img');var r=a.getBoundingClientRect();" +
            "var hasBg=false;try{hasBg=getComputedStyle(a).backgroundImage.indexOf('url')>=0;}catch(e){}" +
            "if(!img&&!hasBg)continue;" +
            "if(r.width<200||r.height<40)continue;" +
            "if(r.width/r.height<2.5)continue;" +
            "KILL(a,'banner');}" +
            "var els=document.querySelectorAll('body *');" +
            "for(var j=0;j<els.length;j++){var el=els[j];if(el.getAttribute('data-vs-ad'))continue;" +
            "var s;try{s=getComputedStyle(el);}catch(e){continue;}" +
            "if(s.position!=='fixed'&&s.position!=='sticky')continue;" +
            "var z=parseInt(s.zIndex,10);if(!(z>=90))continue;" +
            "var r2=el.getBoundingClientRect();" +
            "if(r2.width<innerWidth*0.5&&r2.height<innerHeight*0.25)continue;" +
            "if(el.querySelector&&el.querySelector('video'))continue;" +
            "KILL(el,'overlay');}" +
            "}catch(e){}}" +
            "if(!window.__vsSweep){window.__vsSweep=1;" +
            "if(window.MutationObserver){new MutationObserver(function(){SWEEP();})" +
            ".observe(document.documentElement,{childList:true,subtree:true});}" +
            "var runs=0;var t=setInterval(function(){SWEEP();if(++runs>30)clearInterval(t);},700);}" +
            "var before=window.__vsN;SWEEP();return window.__vsN-before;" +
            "}catch(e){return 'err'}})()"
    }

    private fun jsStringList(csv: String): String =
        csv.split(',').joinToString(",") { "'${it.trim()}'" }

    /** 把任意文本塞进 JS 双引号字符串（转义 `\` / `"` / 换行；中文原样保留） */
    private fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
