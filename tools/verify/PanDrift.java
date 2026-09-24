import com.videoshell.data.model.Category;
import com.videoshell.data.model.SiteConfig;
import com.videoshell.data.model.VideoItem;
import com.videoshell.data.pan.PanLink;
import com.videoshell.data.site.HtmlAdapter;
import com.videoshell.data.site.HtmlExtractor;
import com.videoshell.data.site.HtmlTemplates;
import com.videoshell.data.site.PanShareExtract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线定性：**「网盘站源全是跑路了 / 分类取不到 / 播放不了」（v1.0.67 后的线上反馈）**。
 *
 * ## 现象与它的两种可能
 *
 * 用户报：站源显示「跑路了」「已关站求大佬别爬」，个别能取首页数据，大部分取不到分类数据，
 * 全部都播放不了。按项目纪律，这类反馈**先定性再动手** —— 同样一句「播放不了」，
 * 「站点改版了」要改样本/改形状判据，「我们改坏了」要回滚代码，方向相反。
 *
 * 实测（2026-09-24，样本见 `samples/pandrift/`）定性是**站点漂移**，且是**URL 形式整体换掉**：
 *
 * | 站 | 首页 title | 分类页 | 详情页 |
 * |---|---|---|---|
 * | 玩偶 wogg.live | `玩偶哥哥网盘站 断片中...` | `/vodtype/1.html`（老形式） | `/voddetail/N.html` |
 * | 蜡笔 tvpanpan.site | `自用求大佬不要爬！` | **`/index.php/vod/type/id/1.html`** | **`/index.php/vod/detail/id/N.html`** |
 * | 闪电 shandian.blog | `闪电优汐` | 同上 | 同上 |
 * | 快映 xsayang.fun | `随机接口纯自用，求大佬们别爬了` | 同上 | 同上 |
 *
 * 两个**独立**的事实必须分开记：
 *
 * 1. **用户看到的「跑路了 / 别爬」是站的 `<title>`**，不是关站页 —— 三个站的首页都是
 *    480~640KB 的完整页面（`module-item` 1900~2800 个）。站长把反爬牢骚写进了 title，
 *    而我们**把 title 当站名显示**，于是我们替站长把牢骚念给了用户听（E48）。
 * 2. **真正的功能故障是形状/URL 识别**，与 title 无关（E49）。
 *
 * ## 这个文件在测什么
 *
 * 全部用**真样本 + 本仓代码**，不联网。分层测，哪一层先断就说明根因在哪一层：
 *
 * - A 组：`HtmlTemplates` 对**新老两种 href** 的 id 提取（形状层）；
 * - B 组：`HtmlAdapter.categoriesFrom()` 在真首页上收出几个分类（分类层）；
 * - C 组：`HtmlExtractor.parseList()` 在真分类页上收出几个条目（列表层）；
 * - D 组：`PanShareExtract` 在真详情页上认不认得出网盘形状（网盘层）。
 *
 * 之所以不联网：联网只证明"站活着"，证明不了"我们的判据还认得它" —— 后者才是回归。
 */
public class PanDrift {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond, String detail) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what
                + (detail == null || detail.isEmpty() ? "" : "   → " + detail));
        if (cond) pass++; else fail++;
    }

    static void ok(String what, boolean cond) { ok(what, cond, null); }

    static final String LA = "http://tvpanpan.site";
    static final String SD = "http://shandian.blog";
    static final String KY = "http://43.248.128.118:12512";
    static final String WG = "https://www.wogg.live";

    static String dir;

    public static void main(String[] args) throws Exception {
        dir = args.length > 0 ? args[0] : "tools/verify/samples/pandrift";

        // ---------------------------------------------------------------- A. 形状层
        System.out.println("==== A. HtmlTemplates 对两种 href 形式的 id 提取 ====");
        System.out.println("-- A1~A4 新形式（index.php/vod/...）--");
        ok("A1 `index.php/vod/type/id/1.html` 认出分类 id",
                "1".equals(HtmlTemplates.INSTANCE.typeIdOf("/index.php/vod/type/id/1.html", false)),
                "-> " + HtmlTemplates.INSTANCE.typeIdOf("/index.php/vod/type/id/1.html", false));
        ok("A2 `index.php/vod/detail/id/21273.html` 认出影片 id",
                "21273".equals(HtmlTemplates.INSTANCE.videoIdOf("/index.php/vod/detail/id/21273.html", false)),
                "-> " + HtmlTemplates.INSTANCE.videoIdOf("/index.php/vod/detail/id/21273.html", false));
        ok("A3 分类 href 被判为分类（不是详情）",
                HtmlTemplates.INSTANCE.isCategoryHref("/index.php/vod/type/id/1.html", false));
        ok("A4 `index.php/vod/detail/id/N.html` 不判成分类",
                !HtmlTemplates.INSTANCE.isCategoryHref("/index.php/vod/detail/id/21273.html", false));

        System.out.println("-- A5~A8 对照组：老形式（玩偶那种）不许被破坏 --");
        ok("A5 `vodtype/1.html` 仍认出分类 id",
                "1".equals(HtmlTemplates.INSTANCE.typeIdOf("/vodtype/1.html", false)));
        ok("A6 `voddetail/130178.html` 仍认出影片 id",
                "130178".equals(HtmlTemplates.INSTANCE.videoIdOf("/voddetail/130178.html", false)));
        ok("A7 `vodshow/id/6.html` 仍认出分类 id",
                "6".equals(HtmlTemplates.INSTANCE.typeIdOf("/vodshow/id/6.html", false)));
        ok("A8 `index.php/vod/show/id/1/page/2.html` 认出分类 id（另一条候选形状）",
                "1".equals(HtmlTemplates.INSTANCE.typeIdOf("/index.php/vod/show/id/1/page/2.html", false)),
                "-> " + HtmlTemplates.INSTANCE.typeIdOf("/index.php/vod/show/id/1/page/2.html", false));

        // ---------------------------------------------------------------- B. 分类层
        System.out.println();
        System.out.println("-- B. 真首页 → categoriesFrom() 收出几个分类 --");
        reportCats("B1 蜡笔 tvpanpan.site 首页", LA, read("la_home.html"));
        reportCats("B2 闪电 shandian.blog 首页", SD, read("sd_home.html"));
        reportCats("B3 快映 xsayang.fun 首页", "http://xsayang.fun", read("ky_home.html"));
        reportCats("B4 对照：玩偶 wogg.live 首页", WG, read("wg_home.html"));

        // ---------------------------------------------------------------- C. 列表层
        System.out.println();
        System.out.println("-- C. 真分类页 → parseList() 收出几个条目 --");
        reportList("C1 蜡笔 /index.php/vod/type/id/1.html", LA, read("la_type1.html"));
        reportList("C2 闪电 /index.php/vod/type/id/1.html", SD, read("sd_type1.html"));
        reportList("C3 对照：玩偶 /vodtype/1.html", WG, read("wg_type1.html"));

        // ---------------------------------------------------------------- D. 网盘层
        System.out.println();
        System.out.println("-- D. 真详情页 → PanShareExtract 认不认得出网盘形状 --");
        reportPan("D1 蜡笔 /index.php/vod/detail/id/21273.html", LA, read("la_detail.html"));
        reportPan("D2 闪电 /index.php/vod/detail/id/182511.html", SD, read("sd_detail.html"));
        reportPan("D3 快映 /index.php/vod/detail/id/509515.html", KY, read("ky_detail.html"));

        System.out.println("-- D4 蜡笔详情页里的**诱饵** clipboard 必须被拒 --");
        String laDetail = read("la_detail.html");
        int trap = 0, trapRejected = 0;
        if (laDetail != null) {
            Document d = Jsoup.parse(laDetail, LA);
            for (org.jsoup.nodes.Element e : d.select("[data-clipboard-text]")) {
                String v = e.attr("data-clipboard-text").trim();
                if (v.contains("我正在自用求大佬不要爬") || v.startsWith("www.test.cn")) {
                    trap++;
                    if (PanLink.Companion.parse(v) == null) trapRejected++;
                }
            }
        }
        System.out.println("   诱饵 clipboard 命中 " + trap + " 个，其中被 PanLink 拒掉 " + trapRejected + " 个");
        ok("D4a 样本里确实有诱饵（否则 D4b 是空断言）", trap > 0, "trap=" + trap);
        ok("D4b 诱饵全部被 PanLink 拒掉（不能当成一条线路）", trap > 0 && trapRejected == trap,
                trapRejected + "/" + trap);

        System.out.println("-- D5 对照组：老的玩偶样本仍是网盘分享页 --");
        String wgDetail = read("../panshare/wg_detail.html");
        if (wgDetail == null) {
            ok("D5 玩偶老样本在场", false, "缺 samples/panshare/wg_detail.html");
        } else {
            Document d = Jsoup.parse(wgDetail, WG);
            List<String> links = PanShareExtract.INSTANCE.shareLinks(d);
            ok("D5 玩偶老样本仍是网盘分享页且线路 >=1",
                    PanShareExtract.INSTANCE.isPanSharePage(d) && !links.isEmpty(),
                    "links=" + links.size());
        }

        // ---------------------------------------------------------------- E. 站名噪声
        System.out.println();
        System.out.println("-- E. 站名：用户看到的「别爬 / 跑路了」是从哪来的 --");
        for (String[] s : new String[][]{
                {"蜡笔", "la_home.html"}, {"闪电", "sd_home.html"},
                {"快映", "ky_home.html"}, {"玩偶", "wg_home.html"}}) {
            String h = read(s[1]);
            String t = title(h);
            System.out.println("   " + s[0] + "  <title> = " + t);
        }
        String laTitle = title(read("la_home.html"));
        ok("E1 蜡笔首页 title 里确实是站长的反爬牢骚（这就是用户看到的「别爬」）",
                laTitle != null && (laTitle.contains("不要爬") || laTitle.contains("别爬")),
                "title=" + laTitle);
        // E2（v1.0.68 已落地 SiteDetector.cleanSiteName）：牢骚必须被剔除，
        // 剔完为空时回落 host —— 真实 title 逐个过一遍。
        String laClean = com.videoshell.data.site.SiteDetector.INSTANCE
                .cleanSiteName(laTitle, "");
        ok("E2a 蜡笔「自用求大佬不要爬！」净化后不含牢骚词",
                !laClean.contains("不要爬") && !laClean.contains("自用") && !laClean.contains("爬"),
                "净化后 = " + laClean);
        String muTitle = title(read("mu_home.html"));
        if (muTitle != null && !muTitle.isEmpty()) {
            String muClean = com.videoshell.data.site.SiteDetector.INSTANCE
                    .cleanSiteName(muTitle, "");
            ok("E2b 木偶「再见，我们跑路了」净化后为空（全句都是牢骚 ⇒ 回落 host）",
                    muClean.isEmpty(),
                    "title=" + muTitle + "  净化后=" + muClean);
        }
        String wgTitle = title(read("wg_home.html"));
        if (wgTitle != null && !wgTitle.isEmpty()) {
            String wgClean = com.videoshell.data.site.SiteDetector.INSTANCE
                    .cleanSiteName(wgTitle, "");
            ok("E2c 正常站名逐字节保留（净化的误伤必须为零）",
                    wgTitle.equals(wgClean),
                    "title=" + wgTitle + "  净化后=" + wgClean);
        }

        System.out.println();
        System.out.println("==== PanDrift PASS=" + pass + " FAIL=" + fail + " ====");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ 打印助手

    static void reportCats(String label, String base, String home) {
        if (home == null) { ok(label + " 样本在场", false, "缺样本"); return; }
        HtmlAdapter ad = new HtmlAdapter(new SiteConfig("k", "n", base, "", "html", "", "", 0L));
        List<Category> cats;
        try {
            cats = ad.categoriesFrom(Jsoup.parse(home, base));
        } catch (Throwable e) {
            ok(label + " 分类数 > 0", false, "抛异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, cats.size()); i++) sb.append(cats.get(i).getName()).append(' ');
        System.out.println("   " + label + " → " + cats.size() + " 个分类   [" + sb.toString().trim() + "]");
        ok(label + " 分类数 > 0", !cats.isEmpty(), "cats=" + cats.size());
    }

    static void reportList(String label, String base, String html) {
        if (html == null) { ok(label + " 样本在场", false, "缺样本"); return; }
        List<VideoItem> items;
        try {
            items = HtmlExtractor.INSTANCE.parseList(Jsoup.parse(html, base), base, false, html);
        } catch (Throwable e) {
            ok(label + " 条数 > 0", false, "抛异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        int pic = 0, named = 0;
        Set<String> names = new LinkedHashSet<>();
        for (VideoItem v : items) {
            if (v.getPic() != null && !v.getPic().isBlank()) pic++;
            if (v.getName() != null && !v.getName().isBlank()) { named++; names.add(v.getName()); }
        }
        System.out.println("   " + label + " → " + items.size() + " 条，有名字 " + named
                + "，有封面 " + pic + "，去重 " + names.size());
        ok(label + " 条数 > 0", !items.isEmpty(), "items=" + items.size());
        ok(label + " 全部有名字且去重率 > 60%",
                !items.isEmpty() && named == items.size()
                        && names.size() / (double) items.size() > 0.6,
                "named=" + named + "/" + items.size() + " distinct=" + names.size());
    }

    static void reportPan(String label, String base, String html) {
        if (html == null) { ok(label + " 样本在场", false, "缺样本"); return; }
        Document d = Jsoup.parse(html, base);
        boolean is = PanShareExtract.INSTANCE.isPanSharePage(d);
        List<String> links = PanShareExtract.INSTANCE.shareLinks(d);
        List<String> names = PanShareExtract.INSTANCE.lineNames(d);
        int recognized = 0;
        List<String> kinds = new ArrayList<>();
        for (String u : links) {
            PanLink p = PanLink.Companion.parse(u);
            if (p != null) { recognized++; kinds.add(p.getType().getLabel()); }
        }
        System.out.println("   " + label);
        System.out.println("      isPanSharePage=" + is + "  clipboard 链=" + links.size()
                + "（认出 " + recognized + "）线路名=" + names.size() + " 类型=" + kinds);
        ok(label + " 判为网盘分享页", is, "isPanSharePage=" + is);
        ok(label + " 线路名 >=1 且可识别分享链 >=1", !names.isEmpty() && recognized >= 1,
                "names=" + names.size() + " recognized=" + recognized);
    }

    static String read(String rel) {
        try {
            java.nio.file.Path p = Paths.get(dir, rel);
            if (Files.exists(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
        return null;
    }

    static String title(String html) {
        if (html == null) return null;
        Matcher m = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
                .matcher(html);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").trim() : null;
    }
}
