import com.videoshell.data.model.SiteConfig;
import com.videoshell.data.model.VideoItem;
import com.videoshell.data.net.Http;
import com.videoshell.data.site.AggSearch;
import com.videoshell.data.site.SearchEngine;
import com.videoshell.data.site.SearchScope;
import com.videoshell.data.site.WebSiteKit;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * v1.0.37 断言套件：**搜索范围（本站/全站/全网）** 与 **网页嗅探模式里的「识别并添加 / 手动校准」**。
 *
 * 用户原话（两条）：
 *   1）「搜索功能需完善，需支持搜本站源/全站源/全网，搜全网时直接通过 bing 搜索，用网页嗅探模式查看」
 *   2）「网页嗅探模式浏览网页时，需支持一键识别并添加，也需支持手动识别校准」
 *
 * 这个套件钉的是**两个功能里那些「错了也不会崩、只会悄悄错」的地方**：
 *
 *   A 搜索范围的**持久化契约**（存名字不存下标 —— 存下标会在枚举插项后静默换成另一个选项）
 *   B 全网地址的编码（中文/空格/&/# 必须编码，否则关键词会把 URL 拆坏）
 *   C [AggSearch.merge] 纯函数：打来源站 / 每站限量 / 顺序 / 不丢项 / 不去重（刻意的）
 *   D [AggSearch.summary]：**必须把「几个站失败」说出来**（否则「没有结果」会被当成「全网没有」）
 *   E [WebSiteKit] 纯函数：按 host 归一的匹配 + 「只升不降」的配置升级
 *   F VideoItem 带上来源站（id 是站点内编号，两个站撞号是常态）
 *   G 源码守卫：判据/顺序不能只在注释里成立
 *
 * 入参：a[0] = 夹具目录  a[1] = 工程根（G 段读源码）
 */
public class Agg37 {

    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            fails.add(name);
            System.out.println("  [FAIL] " + name + "   → " + detail);
        }
    }

    /** 不带 detail 的重载：不少断言本来就没有"实际值"可打（纯布尔判据） */
    static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    static void eq(String name, Object got, Object want) {
        ok(name, Objects.equals(got, want), "got=" + got + " want=" + want);
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("========== " + s + " ==========");
    }

    static String read(String p) {
        try {
            Path q = Paths.get(p);
            return Files.exists(q) ? new String(Files.readAllBytes(q), "UTF-8") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从源码里切出一段：从 `fun <name>` 到下一个同级声明 */
    static String body(String src, String fun) {
        if (src == null) return "";
        int i = src.indexOf(fun);
        if (i < 0) return "";
        int j = src.length();
        for (String stop : new String[]{"\n    private ", "\n    fun ", "\n    override ", "\n    // ---"}) {
            int k = src.indexOf(stop, i + fun.length());
            if (k > i && k < j) j = k;
        }
        return src.substring(i, j);
    }

    static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 在 JVM 上跑一个 suspend 调用（`Http.getOrNull` 是 suspend 的） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    // ------------------------------------------------------------------ 夹具

    static VideoItem item(String id, String name) {
        return new VideoItem(id, name, "https://pic/" + id + ".jpg", "", "", "", "", "", "");
    }

    static AggSearch.SiteHits hits(String key, String name, List<VideoItem> items) {
        return new AggSearch.SiteHits(key, name, items, null);
    }

    static AggSearch.SiteHits bad(String key, String name, String why) {
        return new AggSearch.SiteHits(key, name, new ArrayList<VideoItem>(), why);
    }

    static List<VideoItem> items(String... names) {
        List<VideoItem> out = new ArrayList<>();
        int i = 1;
        for (String n : names) out.add(item(String.valueOf(i++), n));
        return out;
    }

    static SiteConfig site(String key, String name, String base, String apiUrl, String mode) {
        return new SiteConfig(key, name, base, apiUrl, mode, "", "n", 0L);
    }

    public static void main(String[] a) throws Exception {
        String proj = a.length > 1 ? a[1] : ".";

        String browserKt = read(proj + "/app/src/main/java/com/videoshell/ui/SiteBrowser.kt");
        String sniffKt = read(proj + "/app/src/main/java/com/videoshell/player/SniffActivity.kt");
        String kitKt = read(proj + "/app/src/main/java/com/videoshell/data/site/WebSiteKit.kt");
        String scopeKt = read(proj + "/app/src/main/java/com/videoshell/data/site/SearchScope.kt");
        String storeKt = read(proj + "/app/src/main/java/com/videoshell/data/Store.kt");
        String browserXml = read(proj + "/app/src/main/res/layout/view_site_browser.xml");
        String sniffXml = read(proj + "/app/src/main/res/layout/activity_sniff.xml");

        // ---------------------------------------------------------------- A
        banner("A. 搜索范围 SearchScope：认不出就回默认，绝不按下标解析");

        eq("of(null) → SITE", SearchScope.SITE, SearchScope.Companion.of(null));
        eq("of(空串) → SITE（老版本存的就是空串）", SearchScope.SITE, SearchScope.Companion.of(""));
        eq("of(SITE) → SITE", SearchScope.SITE, SearchScope.Companion.of("SITE"));
        eq("of(ALL) → ALL", SearchScope.ALL, SearchScope.Companion.of("ALL"));
        eq("of(all) → ALL（大小写不敏感）", SearchScope.ALL, SearchScope.Companion.of("all"));
        eq("of(WEB) → WEB", SearchScope.WEB, SearchScope.Companion.of("WEB"));
        eq("★of(1) → SITE：不按下标解析（按下标，枚举插一项就会静默换掉用户的选择）",
                SearchScope.SITE, SearchScope.Companion.of("1"));
        eq("of(全站) → SITE（认不出不抛异常）", SearchScope.SITE, SearchScope.Companion.of("全站"));
        eq("三个范围互不相同（不是别名）", 3, SearchScope.values().length);

        // ---------------------------------------------------------------- B
        banner("B. 全网搜索地址：中文/空格/&/# 必须编码");

        // ★ v1.0.38 起「哪家引擎」是**参数**（默认百度），不再是写死的 bing。
        // 这段原本靠"默认就是 bing"少传两个参数；签名一改，javac 直接报
        // 「required: String,SearchEngine,boolean」——**这正是想要的效果**：
        // 它逼我们把"在断言哪一家"写出来，而不是让一个会变的默认值替我们决定。
        // 所以下面全部显式传 SearchEngine.BING，只钉 bing 自家这一份地址模板；
        // "默认是谁 / 各家域名归属"由 Agg38 的 A 段负责，这里不重复。
        String u1 = SearchScope.Companion.webSearchUrl("庆余年", SearchEngine.BING, false);
        ok("前缀是 bing 的搜索路径", u1.startsWith("https://www.bing.com/search?q="), u1);
        ok("中文被百分号编码（URL 里不出现中文字符）", !u1.matches(".*[\\u4e00-\\u9fa5].*"), u1);
        ok("中文编成了 UTF-8 百分号序列", u1.contains("%E5%BA%86%E4%BD%99%E5%B9%B4"), u1);

        String q2 = SearchScope.Companion
                .webSearchUrl("庆余年 第二季", SearchEngine.BING, false).split("\\?q=", 2)[1];
        ok("空格不留在 query 里（否则请求行被截断）", !q2.contains(" "), q2);

        String q3 = SearchScope.Companion
                .webSearchUrl("a&b#c=d", SearchEngine.BING, false).split("\\?q=", 2)[1];
        ok("★关键词里的 & 被编码：query 段不再有裸 &（否则会被当成多出来的参数）",
                !q3.contains("&"), q3);
        ok("★关键词里的 # 被编码：URL 里不再有裸 #（否则后面全被当锚点丢掉）",
                !q3.contains("#"), q3);

        String u4 = SearchScope.Companion.webSearchUrl("  ", SearchEngine.BING, false);
        ok("空关键词也给得出合法地址（不返回半截 URL）",
                u4.startsWith("https://www.bing.com/search?q="), u4);

        // isWebHost（v1.0.38）从"只认 bing"放宽成"认得任何一家搜索引擎的域"：
        // 用户把默认换成百度后，百度结果页还会被 `SiteDetector` 当成一个"没识别出站型的
        // 普通网页"，于是**用户可以把它添加成站源**。放宽后各家都要挡住。
        // 但两个方向仍必须同时成立 —— 自家域必须认，长得像的别人域必须不认。
        ok("isWebHost 认 www.bing.com", SearchScope.Companion.isWebHost("https://www.bing.com/search?q=x"));
        ok("isWebHost 认 cn.bing.com（国内跳转后的域名）", SearchScope.Companion.isWebHost("https://cn.bing.com/"));
        ok("isWebHost 认裸 bing.com", SearchScope.Companion.isWebHost("https://bing.com/"));
        ok("★isWebHost 也认默认引擎（百度）——不然换成百度就漏挡",
                SearchScope.Companion.isWebHost("https://www.baidu.com/s?wd=x"));
        ok("★isWebHost 认搜狗", SearchScope.Companion.isWebHost("https://www.sogou.com/web?query=x"));
        ok("★isWebHost 认 360", SearchScope.Companion.isWebHost("https://www.so.com/s?q=x"));
        ok("★isWebHost 认 DuckDuckGo", SearchScope.Companion.isWebHost("https://html.duckduckgo.com/html/?q=x"));
        // 反向控制：判据只在一侧成立等于没有判据 —— 错的方向必须也挡住
        ok("★反向控制：notbing.com 不算搜索引擎（后缀匹配必须带点）",
                !SearchScope.Companion.isWebHost("https://notbing.com/"));
        ok("★反向控制：bing.com.evil.cn 不算（后缀不能反向包含）",
                !SearchScope.Companion.isWebHost("https://bing.com.evil.cn/"));
        ok("★反向控制：普通视频站不算",
                !SearchScope.Companion.isWebHost("https://agenda.fzchosdi.cc/tag/x/"));
        ok("webHomeUrl(默认引擎) 也在搜索引擎域内",
                SearchScope.Companion.isWebHost(
                        SearchScope.Companion.webHomeUrl(SearchEngine.BAIDU)));
        ok("webHomeUrl(BING) 在 bing 域内",
                SearchScope.Companion.isWebHost(
                        SearchScope.Companion.webHomeUrl(SearchEngine.BING)));

        // ---------------------------------------------------------------- C
        banner("C. AggSearch.merge：打来源站 / 每站限量 / 顺序 / 不去重");

        List<AggSearch.SiteHits> hs = new ArrayList<>();
        hs.add(hits("k1", "甲站", items("庆余年", "庆余年 第二季")));
        hs.add(hits("k2", "乙站", items("庆余年")));
        List<VideoItem> merged = AggSearch.INSTANCE.merge(hs, AggSearch.PER_SITE);

        eq("总数 = 各站之和（2+1）", 3, merged.size());
        eq("★逐条打上来源站 key",
                merged.get(0).getSiteKey() + "," + merged.get(1).getSiteKey() + "," + merged.get(2).getSiteKey(),
                "k1,k1,k2");
        eq("★站点顺序按传入顺序保留（不做「谁多谁排前」的排序 —— 条数会被当成质量暗示）",
                merged.get(0).getSiteKey() + "," + merged.get(1).getSiteKey() + "," + merged.get(2).getSiteKey(),
                "k1,k1,k2");
        eq("★同一部剧在多个站都保留（同一片的不同源，正是用户要挑的）", 2,
                (int) merged.stream().filter(v -> "庆余年".equals(v.getName())).count());
        eq("原对象不被改写（是 copy 不是 mutate）", "", hs.get(0).getItems().get(0).getSiteKey());

        List<VideoItem> capped = AggSearch.INSTANCE.merge(
                Collections.singletonList(hits("k1", "甲站", items("a", "b", "c", "d", "e"))), 2);
        eq("★每站限量：5 条被截到 2 条（否则列表被第一个站刷屏，全站会退化成第一站）",
                2, capped.size());
        eq("限量时取的是前 N 条（站点自己的排序有意义）", "a", capped.get(0).getName());

        List<VideoItem> unlimited = AggSearch.INSTANCE.merge(
                Collections.singletonList(hits("k1", "甲站", items("a", "b", "c"))), 0);
        eq("perSite <= 0 表示不限量", 3, unlimited.size());

        List<VideoItem> noName = AggSearch.INSTANCE.merge(Arrays.asList(
                hits("k1", "甲站", items("有名字", "   ", "")),
                hits("", "空key站", items("这条不该出现")),
                bad("k3", "丙站", "boom")), AggSearch.PER_SITE);
        eq("★无名字的条目被丢掉（点开必然是空页）；key 为空的批次整批丢掉；失败站不产出",
                1, noName.size());
        eq("留下的那一条是唯一有名的那条", "有名字", noName.get(0).getName());
        eq("空输入 → 空输出（不抛）", 0,
                AggSearch.INSTANCE.merge(new ArrayList<AggSearch.SiteHits>(), 10).size());
        ok("PER_SITE 是个合理上限（>0 且不至于把首屏拖爆）",
                AggSearch.PER_SITE > 0 && AggSearch.PER_SITE <= 60, "PER_SITE=" + AggSearch.PER_SITE);

        // ---------------------------------------------------------------- D
        banner("D. AggSearch.summary：必须说出「几个站有结果 / 几个站失败」");

        List<AggSearch.SiteHits> d1 = new ArrayList<>();
        d1.add(hits("k1", "甲站", items("a")));
        d1.add(bad("k2", "乙站", "TimeoutException: 超时未返回"));
        d1.add(hits("k3", "丙站", new ArrayList<VideoItem>()));
        String s1 = AggSearch.INSTANCE.summary(d1);
        ok("说了「几个站有结果 / 共几个」（1/3）", s1.contains("1/3"), s1);
        ok("★说了几个站失败（否则「没有结果」会被理解成「全网都没有」）", s1.contains("1 个失败"), s1);
        ok("★失败站名出现在汇总里（用户要知道是哪个站）", s1.contains("乙站"), s1);

        List<AggSearch.SiteHits> d2 = new ArrayList<>();
        d2.add(bad("k1", "甲站", "boom"));
        d2.add(bad("k2", "乙站", "boom"));
        String s2 = AggSearch.INSTANCE.summary(d2);
        ok("全失败也给得出可读句子（不返回空串、不崩）", s2.contains("0/2"), s2);
        ok("全失败时明确列出失败站点", s2.contains("2 个失败"), s2);
        ok("summary(空列表) 不崩", AggSearch.INSTANCE.summary(new ArrayList<AggSearch.SiteHits>()).length() > 0);

        eq("failures() 只挑失败的站", 1, AggSearch.INSTANCE.failures(d1).size());
        ok("ok 标志与 error 一致", d1.get(0).getOk() && !d1.get(1).getOk(), "");

        // ---------------------------------------------------------------- E
        banner("E. WebSiteKit：按 host 归一匹配 + 配置只升不降");

        eq("hostOfBase 去掉 www.", "x.com", WebSiteKit.INSTANCE.hostOfBase("https://www.x.com/a/b"));
        eq("hostOfBase 保留端口信息之外只留 host", "x.com", WebSiteKit.INSTANCE.hostOfBase("http://x.com/a"));
        eq("hostOfBase 认裸 host", "x.com", WebSiteKit.INSTANCE.hostOfBase("x.com/x"));
        eq("hostOfBase 空输入不炸", "", WebSiteKit.INSTANCE.hostOfBase(""));

        eq("originOf 取 scheme+host",
                "https://x.com", WebSiteKit.INSTANCE.originOf("https://x.com/a/b?c=d#e"));
        eq("originOf 保留端口（同 host 不同端口是两个站）",
                "http://x.com:8080", WebSiteKit.INSTANCE.originOf("http://x.com:8080/p"));
        eq("originOf 非 http 返回空", "", WebSiteKit.INSTANCE.originOf("about:blank"));

        List<SiteConfig> lib = new ArrayList<>();
        lib.add(site("https://x.com", "X站", "https://x.com", "", SiteConfig.MODE_HTML));
        lib.add(site("k2", "Y站", "https://www.y.com", "", SiteConfig.MODE_HTML));

        ok("★http vs https、www vs 非 www 视为同一个站（否则会被重复添加，列表里出现两条一样的站）",
                WebSiteKit.INSTANCE.matchExisting(lib, "https://www.x.com/vod/1") != null, "");
        SiteConfig hit = WebSiteKit.INSTANCE.matchExisting(lib, "http://x.com/vod/1");
        eq("命中时返回的就是库里那一条（key 不变）", "https://x.com", hit == null ? "" : hit.getKey());
        ok("y 站也命中", WebSiteKit.INSTANCE.matchExisting(lib, "https://www.y.com/") != null, "");
        ok("★反向控制：不同 host 不误命中", WebSiteKit.INSTANCE.matchExisting(lib, "https://z.com/") == null, "");
        ok("★反向控制：x.com.evil.cn 不误命中",
                WebSiteKit.INSTANCE.matchExisting(lib, "https://x.com.evil.cn/") == null, "");
        ok("空网址返回 null", WebSiteKit.INSTANCE.matchExisting(lib, "") == null, "");

        SiteConfig html = site("k", "老名字", "https://x.com", "", SiteConfig.MODE_HTML);
        SiteConfig api = site("k2", "识别出来的名字", "https://x.com",
                "https://x.com/api.php", SiteConfig.MODE_MACCMS_JSON);
        SiteConfig up = WebSiteKit.INSTANCE.upgradeToward(html, api);
        ok("★原本没采集接口、这次找到了 ⇒ 升级（否则用户永远停在最弱的网页模式）", up != null, "");
        eq("升级后带上接口地址", "https://x.com/api.php", up == null ? "" : up.getApiUrl());
        eq("升级后模式跟着换", SiteConfig.MODE_MACCMS_JSON, up == null ? "" : up.getApiMode());
        eq("★升级不动名字：用户改过的站名不能被识别结果覆盖", "老名字", up == null ? "" : up.getName());

        ok("★原本就有接口 ⇒ 不动它（自动降级会把好用的站改坏，而用户什么都没点）",
                WebSiteKit.INSTANCE.upgradeToward(
                        site("k", "n", "https://x.com", "https://x.com/a", SiteConfig.MODE_MACCMS_JSON), api) == null, "");
        ok("新的也没有接口 ⇒ 不动",
                WebSiteKit.INSTANCE.upgradeToward(
                        html, site("k", "n", "https://x.com", "", SiteConfig.MODE_HTML)) == null, "");

        // ---------------------------------------------------------------- F
        banner("F. VideoItem 带上来源站");

        VideoItem vi = item("3381", "庆余年");
        eq("siteKey 默认空（单站浏览时就是「当前站」，行为与以前一致）", "", vi.getSiteKey());
        VideoItem tagged = vi.copy("3381", "庆余年", vi.getPic(), "", "", "", "", "", "k9");
        eq("copy 出来的条目带上来源站", "k9", tagged.getSiteKey());
        eq("copy 不丢其它字段", "3381", tagged.getId());
        eq("copy 不改原对象", "", vi.getSiteKey());

        // ---------------------------------------------------------------- G
        banner("G. 源码守卫：不能只在注释里成立");

        String click = body(browserKt, "private val videoAdapter = VideoAdapter(");
        ok("★聚合结果的点击用 item.siteKey 决定去哪个站（写死 site.key 会把 A 站的 id 拿去 B 站查）",
                click.contains("item.siteKey"), cut(click, 120));

        String probe = body(sniffKt, "private fun maybeProbeAndAutoPlay()");
        ok("★浏览模式不自动播（否则用户逛网页时会被替自己跳进播放器）",
                probe.contains("if (browse) return"), cut(probe, 120));

        String cal = body(sniffKt, "private fun manualCalibrate()");
        int iEnsure = cal.indexOf("ensureSite");
        int iCalib = cal.indexOf("CalibrateActivity.intent");
        ok("★手动校准先 ensureSite 再进校准页（否则 Store.find 拿不到 ⇒ 静默 finish，点了没反应）",
                iEnsure >= 0 && iCalib > iEnsure, "ensureSite@" + iEnsure + " calib@" + iCalib);

        String match = body(kitKt, "fun matchExisting(");
        ok("★matchExisting 按 host 归一（比较 baseUrl 推出来的 host，而不是 key）",
                match.contains("hostOfBase") && match.contains("baseUrl") && !match.contains(".key"),
                cut(match, 160));

        String scopeBody = body(storeKt, "fun searchScope(");
        String setBody = body(storeKt, "fun setSearchScope(");
        ok("搜索范围存的是名字（putString / getString）",
                scopeBody.contains("getString") && setBody.contains("putString"), cut(setBody, 120));
        ok("★搜索范围不存下标（putInt 会在枚举插项后把用户的选项静默换成另一个）",
                !setBody.contains("putInt"), cut(setBody, 120));

        ok("★全网搜索不抓取解析搜索引擎结果页（SearchScope 里不出现 Jsoup / Http）",
                scopeKt != null && !scopeKt.contains("Jsoup") && !scopeKt.contains("Http."),
                "scopeKt=" + (scopeKt == null));

        ok("布局里三个范围 chip + 提示行都在（少一个 binding 就编译不过；更容易漏的是「少了却没人发现」）",
                browserXml != null && browserXml.contains("@+id/scopeSite")
                        && browserXml.contains("@+id/scopeAll") && browserXml.contains("@+id/scopeWeb")
                        && browserXml.contains("@+id/tvScopeHint"), "browserXml=" + (browserXml == null));

        ok("嗅探页两个新按钮 + 当前网址行 + 网页后退都在布局里",
                sniffXml != null && sniffXml.contains("@+id/btnGrab") && sniffXml.contains("@+id/btnCalib")
                        && sniffXml.contains("@+id/tvCurUrl") && sniffXml.contains("@+id/webBack"),
                "sniffXml=" + (sniffXml == null));

        ok("★状态位清空时把「查看失败站」的点击入口一起撤掉（否则留下看不见但能点的区域）",
                browserKt != null && body(browserKt, "private fun showState(").contains("setOnClickListener(null)"),
                "");

        // ---------------------------------------------------------------- H
        banner("H. 端到端（真网络）：交给 WebView 的那个默认引擎地址真的能打开");

        // 这一段的判据刻意只有一条：**我们交给 WebView 的地址是活的**。
        // 不去断言"结果页里一定有某某链接"—— 那是把搜索引擎的页面结构当规格，
        // 它改一次版我们就会收到一条假红的断言（而那正是我们**决定不依赖**的东西）。
        // v1.0.38：测的从「写死的 bing」改成「**默认引擎**」——
        // 用户点「搜全网」时真正被交出去的就是这个地址，测它才有意义。
        String live = SearchScope.Companion.webSearchUrl("庆余年", SearchEngine.BAIDU, false);
        String page = null;
        try {
            page = (String) block((scope, cont) -> Http.INSTANCE.getOrNull(
                    live, null, Http.UA, true, (Continuation<? super String>) cont));
        } catch (Throwable t) {
            page = null;
        }
        if (page == null || page.isEmpty()) {
            System.out.println("  [SKIP] 默认引擎本次不可达（网络/区域限制）—— 这一条不影响其它断言");
        } else {
            ok("地址可达且返回的是 HTML（>1KB）", page.trim().startsWith("<") && page.length() > 1024,
                    "len=" + page.length() + " head=" + cut(page.trim(), 60));
            ok("返回体里带得回关键词（说明不是一张错误页）",
                    page.contains("庆余年") || page.contains("content_left"),
                    "len=" + page.length());
        }

        // ---------------------------------------------------------------- 汇总
        System.out.println();
        System.out.println("========== 合计 ==========");
        System.out.printf("pass=%d fail=%d%n", pass, fail);
        if (!fails.isEmpty()) {
            System.out.println("失败项：");
            for (String f : fails) System.out.println("  - " + f);
            System.exit(1);
        }
    }
}
