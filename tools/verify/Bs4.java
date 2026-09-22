import com.videoshell.data.model.*;
import com.videoshell.data.site.*;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * v1.0.12 回归 —— 两件事：
 *
 *  A. 分集名去「剧名」前缀
 *     金牌影视的分集链接 `title="兰香如故第01集"`、可见文本 `第01集`。
 *     旧实现无条件优先 title，于是详情页整列都是「兰香如故第01集」。
 *
 *  B. 调试校准模式的纯逻辑（SiteCalib）+ 点击定性（G 组，只提示不拦人）
 *     校准的产物会被**固化**进配方，推错了比不推更糟（用户以为校准好了，
 *     实际每次都在用错模板）。所以这段推导必须有断言兜着。
 *
 * 全部离线：样本用 App 同款 OkHttp 从真实站点抓下来的页面（_bs/）。
 */
public class Bs4 {

    static int pass = 0, fail = 0;
    static final String BASE = "https://www.bolyship.com";

    /**
     * 只看**真正干活**的适配器 —— 沿 `underlying` 一直剥到底。
     *
     * 判据本体只有一份：[Chains]（为什么不能在这里再抄一遍，见它的文件头）。
     * 这里留一个同名薄壳，只是为了让读断言的人不用跳文件也知道在判什么。
     */
    static boolean worksAs(SiteAdapter a, Class<?> kind) {
        return Chains.worksAs(a, kind);
    }

    // 样本里的真实影片
    static final String DETAIL_URL = BASE + "/bspvd/548165.html";
    static final String PLAY_URL = BASE + "/bspvp/548165-4-1.html";
    static final String CAT_URL = BASE + "/bspvt/dianying.html";
    static final String DRAMA = "兰香如故";

    static String dir;

    public static void main(String[] args) throws Exception {
        dir = args.length > 0 ? args[0] : "_bs";
        Document home = doc("home.html");
        Document detail = doc("detail.html");
        Document play = doc("play.html");

        // ================================================================ A
        banner("A. 分集名不该带剧名（金牌影视实测样本）");
        for (String[] pair : new String[][]{{"详情页 detail.html", "detail"}, {"播放页 play.html", "play"}}) {
            Document d = "detail".equals(pair[1]) ? detail : play;
            List<PlayGroup> gs = HtmlExtractor.INSTANCE.parseGroups(d, BASE);
            System.out.println("  " + pair[0] + "：线路 " + gs.size() + " 条");
            ok(pair[0] + " 4 条线路", gs.size() == 4);
            if (gs.isEmpty()) continue;

            boolean allClean = true, allEp = true;
            int n = 0;
            for (PlayGroup g : gs) {
                for (Episode e : g.getEpisodes()) {
                    n++;
                    if (e.getName().contains(DRAMA)) allClean = false;
                    if (!e.getName().matches("第\\d+集")) allEp = false;
                }
            }
            System.out.println("  共 " + n + " 集，首集名 = "
                    + gs.get(0).getEpisodes().get(0).getName());
            ok(pair[0] + " 分集名里不含剧名「" + DRAMA + "」", allClean);
            ok(pair[0] + " 分集名全是「第N集」（修复前 = 兰香如故第N集）", allEp);
        }

        // ================================================================ B
        banner("B. stripTitlePrefix 纯函数（title 里没有可见文本可比对时的兜底）");
        List<PlayGroup> dirty = new ArrayList<>();
        List<Episode> es = new ArrayList<>();
        // Episode 增了第 3 字段 pic：Java 侧没有 Kotlin 默认值，构造点显式补空串
        es.add(new Episode(DRAMA + "第01集", "u1", ""));
        es.add(new Episode(DRAMA + "第02集", "u2", ""));
        dirty.add(new PlayGroup("云播一", es));

        List<PlayGroup> fixed = HtmlExtractor.INSTANCE.stripTitlePrefix(dirty, DRAMA);
        eq("全带剧名 -> 削掉", fixed.get(0).getEpisodes().get(0).getName(), "第01集");
        eq("全带剧名 -> 第2集也削", fixed.get(0).getEpisodes().get(1).getName(), "第02集");

        List<PlayGroup> half = new ArrayList<>();
        List<Episode> es2 = new ArrayList<>();
        es2.add(new Episode(DRAMA + "第01集", "u1", ""));
        es2.add(new Episode("预告", "u2", ""));
        half.add(new PlayGroup("云播一", es2));
        List<PlayGroup> keep = HtmlExtractor.INSTANCE.stripTitlePrefix(half, DRAMA);
        eq("只要有一条不带前缀 -> 一个都不动", keep.get(0).getEpisodes().get(0).getName(), DRAMA + "第01集");

        List<PlayGroup> emptyTitle = HtmlExtractor.INSTANCE.stripTitlePrefix(dirty, "  ");
        eq("剧名为空 -> 一个都不动", emptyTitle.get(0).getEpisodes().get(0).getName(), DRAMA + "第01集");

        List<PlayGroup> same = new ArrayList<>();
        List<Episode> es3 = new ArrayList<>();
        es3.add(new Episode(DRAMA, "u1", ""));
        es3.add(new Episode(DRAMA, "u2", ""));
        same.add(new PlayGroup("云播一", es3));
        List<PlayGroup> sameOut = HtmlExtractor.INSTANCE.stripTitlePrefix(same, DRAMA);
        eq("名字正好等于剧名 -> 削成空，必须原样保留",
                sameOut.get(0).getEpisodes().get(0).getName(), DRAMA);

        // ================================================================ C
        banner("C. SiteCalib.isCategoryShape —— 第一步「点分类」的判据");
        // 把分解判据打出来：这类"看着像却判错"的问题，只有把每一步摊开才看得见
        for (String h : new String[]{"/vodshow/id/6.html", "/bspvt/dianying.html"}) {
            System.out.println("  " + h);
            System.out.println("    isPlayLink=" + HtmlTemplates.INSTANCE.isPlayLink(h)
                    + "  isStrongDetail=" + HtmlTemplates.INSTANCE.isStrongDetail(h)
                    + "  isSlug=" + HtmlTemplates.INSTANCE.isSlugCategory(h)
                    + "  isSlugDir=" + HtmlTemplates.INSTANCE.isSlugDirCategory(h)
                    + "  isCatHref=" + HtmlTemplates.INSTANCE.isCategoryHref(h, false)
                    + "  videoId=" + HtmlTemplates.INSTANCE.videoIdOf(h, false));
        }
        ok("分类 /bspvt/dianying.html        -> true",
                SiteCalib.INSTANCE.isCategoryShape("/bspvt/dianying.html"));
        ok("分类 /meijutt（无后缀别名）      -> true",
                SiteCalib.INSTANCE.isCategoryShape("/meijutt"));
        ok("分类 /vodshow/id/6.html          -> true（通用详情正则也会命中它，但不能判成详情）",
                SiteCalib.INSTANCE.isCategoryShape("/vodshow/id/6.html"));
        ok("详情 /bspvd/548165.html          -> false",
                !SiteCalib.INSTANCE.isCategoryShape("/bspvd/548165.html"));
        ok("详情 /detail/548165.html         -> false",
                !SiteCalib.INSTANCE.isCategoryShape("/detail/548165.html"));
        ok("详情 /movie/23804.html           -> false",
                !SiteCalib.INSTANCE.isCategoryShape("/movie/23804.html"));
        ok("分集 /bspvp/548165-4-1.html      -> false",
                !SiteCalib.INSTANCE.isCategoryShape("/bspvp/548165-4-1.html"));
        ok("筛选页 /bspvc/----国产-------s-.html -> false（连续连字符 = maccms 筛选页）",
                !SiteCalib.INSTANCE.isCategoryShape("/bspvc/----%E5%9B%BD%E4%BA%A7-------s-.html"));

        // ================================================================ D
        banner("D. SiteCalib.navSel —— 从真实首页 DOM 反推「分类所在的容器」");
        String sel = SiteCalib.INSTANCE.navSel(home, CAT_URL);
        System.out.println("  推导结果 = " + sel);
        ok("推出来了", sel != null && !sel.isBlank());
        // 该主题类名全是哈希（qanoq6m7wx / 46iaorb6t5…），挑不对下次改版就失效
        ok("挑的是有语义的类名（含 nav/menu/head…），不是哈希类名",
                sel != null && sel.toLowerCase().contains("nav"));
        if (sel != null) {
            ok("选择器在首页里真能选到元素", !home.select(sel).isEmpty());
            int cats = 0;
            for (org.jsoup.nodes.Element a : home.select(sel + " a[href]")) {
                if (SiteCalib.INSTANCE.isCategoryShape(a.attr("href"))) cats++;
            }
            System.out.println("  该容器内分类链接 " + cats + " 条");
            ok("容器内分类链接 >= 5", cats >= 5);
        }
        ok("反例：首页上不存在的链接 -> null",
                SiteCalib.INSTANCE.navSel(home, BASE + "/nope/12345.html") == null);

        // ================================================================ E
        banner("E. 校准配方的「分类形状」—— 第一步点击真正学到的东西");
        eq("catTplFrom /bspvt/dianying.html",
                HtmlTemplates.INSTANCE.catTplFrom("https://www.bolyship.com/bspvt/dianying.html"),
                "/bspvt/{slug}.html");
        eq("catTplFrom 相对路径同样认",
                HtmlTemplates.INSTANCE.catTplFrom("/bspvt/dianying.html"), "/bspvt/{slug}.html");
        ok("catTplFrom 反例：别名是纯数字 = 详情页，必须 null",
                HtmlTemplates.INSTANCE.catTplFrom("/bspvd/548165.html") == null);
        ok("catTplFrom 反例：无后缀别名 /meijutt 学不到新形状（等同默认判据）",
                HtmlTemplates.INSTANCE.catTplFrom("/meijutt") == null);
        ok("catTplFrom 反例：maccms 筛选页（连续连字符）必须 null",
                HtmlTemplates.INSTANCE.catTplFrom("/bspvc/----x-------s-.html") == null);

        String catTpl = HtmlTemplates.INSTANCE.catTplFrom(CAT_URL);
        ok("matchesCatTpl 同目录别的分类 -> true",
                HtmlTemplates.INSTANCE.matchesCatTpl("/bspvt/dianshiju.html", catTpl));
        ok("matchesCatTpl 同目录但别名是数字 -> false",
                !HtmlTemplates.INSTANCE.matchesCatTpl("/bspvt/548165.html", catTpl));
        ok("matchesCatTpl 目录不同 -> false",
                !HtmlTemplates.INSTANCE.matchesCatTpl("/bspvd/dianying.html", catTpl));
        ok("matchesCatTpl 筛选页 -> false",
                !HtmlTemplates.INSTANCE.matchesCatTpl("/bspvt/----x-------s-.html", catTpl));
        ok("matchesCatTpl 三段路径 -> false",
                !HtmlTemplates.INSTANCE.matchesCatTpl("/bspvt/dianying/1.html", catTpl));

        // ================================================================ F
        banner("F. 校准配方真的被适配器用上（形状优先 + 容器兜底 + 失效自愈）");

        // F1 形状优先：认形状才能把散落在多个导航容器里的分类一次收全
        RecipeStore.INSTANCE.clear(BASE);
        RecipeStore.INSTANCE.save(BASE, new SiteRecipe(
                SiteRecipe.VER, false,
                BASE + "/bspvd/{id}.html", BASE + "/bspvp/{id}-4-1.html",
                null, null,
                sel, catTpl, /*homeCat*/ null, /*learnedCatTpl*/ null, /*learnedCatAt*/ 0L, System.currentTimeMillis(), "测试校准", 0L));
        SiteRecipe back = RecipeStore.INSTANCE.load(BASE);
        ok("配方读回：catTpl 一致", back != null && catTpl.equals(back.getCatTpl()));
        ok("配方读回：navSel 一致", back != null && sel.equals(back.getNavSel()));
        ok("配方读回：calibAt > 0（标记为人工校准）", back != null && back.getCalibAt() > 0);

        HtmlAdapter ad = new HtmlAdapter(new SiteConfig(
                "bs", "金牌影视", BASE, "", SiteConfig.MODE_HTML, "", "", 0L));
        List<Category> cats1 = ad.categoriesFrom(home);
        System.out.println("  分类 " + cats1.size() + " 个");
        ok("认形状后分类 >= 10（实际 " + cats1.size() + "）", cats1.size() >= 10);

        // 每一个分类都必须符合固化的形状 —— 这才能证明"认的是形状，不是碰巧"
        int offShape = 0;
        for (Category c : cats1) {
            if (!HtmlTemplates.INSTANCE.matchesCatTpl(c.getId(), catTpl)) offShape++;
        }
        ok("全部分类都符合固化形状（跑偏 " + offShape + " 个）", offShape == 0);
        ok("配方描述里标明是人工校准",
                RecipeStore.INSTANCE.describe(BASE).contains("调试校准模式"));

        // F2 只有容器（形状学不到时，比如 /meijutt 那种无后缀别名）
        RecipeStore.INSTANCE.save(BASE, new SiteRecipe(
                SiteRecipe.VER, false, null, null, null, null,
                sel, null, /*homeCat*/ null, /*learnedCatTpl*/ null, /*learnedCatAt*/ 0L, System.currentTimeMillis(), "只有容器", 0L));
        HtmlAdapter ad1b = new HtmlAdapter(new SiteConfig(
                "bs", "金牌影视", BASE, "", SiteConfig.MODE_HTML, "", "", 0L));
        List<Category> cats1b = ad1b.categoriesFrom(home);
        System.out.println("  只有容器时分类 " + cats1b.size() + " 个");
        ok("容器兜底生效，分类 >= 2（实际 " + cats1b.size() + "）", cats1b.size() >= 2);
        Set<String> inBox = new HashSet<>();
        if (sel != null) {
            for (org.jsoup.nodes.Element a : home.select(sel + " a[href]")) inBox.add(a.absUrl("href"));
        }
        int outside = 0;
        for (Category c : cats1b) if (!inBox.contains(c.getId())) outside++;
        ok("只有容器时，分类全部来自该容器（跑偏 " + outside + " 个）", outside == 0);

        // F3 两条规则都失效（站点改版）时不许把用户卡死
        RecipeStore.INSTANCE.save(BASE, new SiteRecipe(
                SiteRecipe.VER, false, null, null, null, null,
                "div#definitely-not-exist", "/nope/{slug}.html",
                /*homeCat*/ null, /*learnedCatTpl*/ null, /*learnedCatAt*/ 0L, System.currentTimeMillis(), "失效规则", 0L));
        HtmlAdapter ad2 = new HtmlAdapter(new SiteConfig(
                "bs", "金牌影视", BASE, "", SiteConfig.MODE_HTML, "", "", 0L));
        List<Category> cats2 = ad2.categoriesFrom(home);
        System.out.println("  规则失效时分类 " + cats2.size() + " 个");
        ok("规则失效时自动退回默认逻辑，分类 >= 10（实际 " + cats2.size() + "）",
                cats2.size() >= 10);

        RecipeStore.INSTANCE.clear(BASE);

        // ================================================================ G
        banner("G. 点击定性：只提示，不拦人（v1.0.14 修「卡在第一步」）");
        // 卡死的元凶是把判据当闸门：判据一否决就 return，用户既没有按钮可按、
        // 也不知道自己在等什么。现在判据只决定「界面怎么提示」——
        // 有地址就一定能继续（形状没学到也会退兜底），无地址才拦下并说明原因。
        kind("① 分类：相对 href /bspvt/dianying.html 认得出",
                "/bspvt/dianying.html", CAT_URL, SiteCalib.Step.CAT, SiteCalib.PickKind.GOOD);
        kind("① 分类：**绝对地址**也认得出（JS 报的就是 getAttribute 原样值）",
                CAT_URL, CAT_URL, SiteCalib.Step.CAT, SiteCalib.PickKind.GOOD);
        kind("① 分类：详情页链接只是「形状未识别」，不算拒绝",
                DETAIL_URL, DETAIL_URL, SiteCalib.Step.CAT, SiteCalib.PickKind.SHAPE_UNKNOWN);
        kind("① 分类：javascript: 是脚本链接",
                "javascript:;", "", SiteCalib.Step.CAT, SiteCalib.PickKind.SCRIPT_LINK);
        kind("① 分类：空 href 是「没有链接地址」",
                "", "", SiteCalib.Step.CAT, SiteCalib.PickKind.NOT_LINK);
        kind("① 分类：# 是脚本链接",
                "#", "", SiteCalib.Step.CAT, SiteCalib.PickKind.SCRIPT_LINK);
        kind("① 分类：mailto 是脚本链接",
                "mailto:a@b.com", "", SiteCalib.Step.CAT, SiteCalib.PickKind.SCRIPT_LINK);

        kind("② 影片：/bspvd/548165.html 认得出",
                DETAIL_URL, DETAIL_URL, SiteCalib.Step.DETAIL, SiteCalib.PickKind.GOOD);
        kind("② 影片：播放页链接**不能**被学成详情模板（关键）",
                PLAY_URL, PLAY_URL, SiteCalib.Step.DETAIL, SiteCalib.PickKind.SHAPE_UNKNOWN);
        kind("② 影片：分类链接只是形状未识别",
                CAT_URL, CAT_URL, SiteCalib.Step.DETAIL, SiteCalib.PickKind.SHAPE_UNKNOWN);

        kind("③ 分集：/bspvp/548165-4-1.html 认得出",
                PLAY_URL, PLAY_URL, SiteCalib.Step.PLAY, SiteCalib.PickKind.GOOD);
        kind("③ 分集：详情页链接只是形状未识别",
                DETAIL_URL, DETAIL_URL, SiteCalib.Step.PLAY, SiteCalib.PickKind.SHAPE_UNKNOWN);
        kind("③ 分集：空 href 仍会拦下并说明原因",
                "", "", SiteCalib.Step.PLAY, SiteCalib.PickKind.NOT_LINK);


        // ================================================================ H
        banner("H. 校准过的站点必须走 HTML 适配（否则「校准后规则没生效」）");
        // 「校准过」= 用户已在真实网页里点通分类→详情→分集并试播成功，
        // 这是比 apiMode 更强的证据。若不压过 apiMode，站点一旦被识别成采集接口模式
        // （或用户删掉重加一遍），校准固化的规则会被整个绕过 —— 这正是用户报的
        // 「校准完规则没生效」的形态。
        SiteConfig maccms = new SiteConfig("k1", "站", BASE,
                BASE + "/api.php/provide/vod/", SiteConfig.MODE_MACCMS_JSON, "at=json", "", 0L);

        RecipeStore.INSTANCE.clear(BASE);
        ok("未校准的采集接口站点 -> MaccmsAdapter",
                AdapterFactory.INSTANCE.create(maccms) instanceof MaccmsAdapter);

        RecipeStore.INSTANCE.save(BASE, new SiteRecipe(
                SiteRecipe.VER, true, BASE + "/bspvd/{id}.html", BASE + "/bspvp/{id}-1-1.html",
                null, null, "ul.header-nav", "/bspvt/{slug}.html",
                /*homeCat*/ null, /*learnedCatTpl*/ null, /*learnedCatAt*/ 0L, System.currentTimeMillis(), "校准过", 0L));
        ok("校准过的同一站点 -> 走网页解析（配方压过 apiMode）",
                worksAs(AdapterFactory.INSTANCE.create(maccms), HtmlAdapter.class));
        // 非空断言守卫：不只是"最终是 HtmlAdapter"，而是**确实经过了延迟路由那一层**。
        // 否则将来有人把 SeedRouter / FamilyRouter 从链路里摘掉，上面那条照样绿。
        ok("★ 守卫自证：未校准的未知域名确实**套了延迟路由**（剥壳 ≥1 跳）",
                Chains.hops(AdapterFactory.INSTANCE.create(maccms), HtmlAdapter.class) >= 1);

        RecipeStore.INSTANCE.clear(BASE);
        RecipeStore.INSTANCE.save(BASE, new SiteRecipe(
                SiteRecipe.VER, true, BASE + "/bspvd/{id}.html", null,
                null, null, null, null, /*homeCat*/ null, null, /*learnedCatAt*/ 0L, 0L, null, 0L));
        ok("只有自动学习的配方（calibAt=0）-> 仍按 apiMode 走 MaccmsAdapter",
                AdapterFactory.INSTANCE.create(maccms) instanceof MaccmsAdapter);
        ok("apiUrl 为空的站点 -> 走网页解析（原有行为不变）",
                worksAs(AdapterFactory.INSTANCE.create(new SiteConfig("k2", "站2", BASE, "",
                        SiteConfig.MODE_MACCMS_JSON, "", "", 0L)), HtmlAdapter.class));
        RecipeStore.INSTANCE.clear(BASE);

        System.out.println("\n" + "=".repeat(72));
        System.out.println(fail == 0 ? ("ALL CHECKS PASSED  (pass=" + pass + ")")
                : ("FAILED  pass=" + pass + " fail=" + fail));
        System.out.println("=".repeat(72));
        if (fail != 0) System.exit(1);
    }

    static Document doc(String name) throws Exception {
        File f = new File(dir, name);
        String html = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return Jsoup.parse(html, BASE);
    }

    static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + what);
        } else {
            fail++;
            System.out.println("  [FAIL] " + what);
        }
    }

    static void eq(String what, String actual, String expect) {
        boolean c = expect.equals(actual);
        if (!c) System.out.println("     expect=" + expect + "\n     actual=" + actual);
        ok(what, c);
    }

    /** raw = getAttribute 原样值，abs = 绝对化后的地址；classify 两个都要看 */
    static void kind(String what, String raw, String abs,
                     SiteCalib.Step step, SiteCalib.PickKind expect) {
        SiteCalib.PickKind got = SiteCalib.INSTANCE.classify(raw, abs, step);
        if (got != expect) {
            System.out.println("     raw=[" + raw + "] abs=[" + abs
                    + "] expect=" + expect + " actual=" + got);
        }
        ok(what, got == expect);
    }

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(72));
        System.out.println(s);
        System.out.println("=".repeat(72));
    }
}
