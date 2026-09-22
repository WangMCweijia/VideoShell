import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.site.*;

import kotlin.Pair;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * v1.0.16 回归 —— 野果短剧（`capable.fzchosdi.cc`）那类「自研 SSR 站」的两处结构性缺口。
 *
 * ## 用户报的现象
 *
 * 校准后重载站源拿不到列表。自检（v1.0.15）如实报出：
 * ```
 * [2] 分类解析   结果：5 个分类
 *       · 回家的路 / 搜索剧集 / 联系我们 / 常见问题 / 使用条款
 * [3] 列表解析（分类：回家的路）  结果：0 条
 * ```
 * 5 个「分类」**全是页脚/头部功能页**，点进去当然 0 条。
 *
 * ## 两处缺口（都不是"解析写错了"，是判据覆盖不到）
 *
 * **A. 分类是「尾斜杠目录式」**：真分类是 `/tag/{URL编码中文}/`，
 *    而现有三条判据一条都认不出（单段 slug 只认一段、slugDir 要求 `.html`、CATEGORY 要求
 *    `vodshow/type/list`）。反过来页脚功能页 `/contact/`、`/search/` 恰好命中单段 slug 判据。
 *    ⇒ 该进的没进、不该进的进了，且因为 `collectSlugCategories` 排在前面还会**提前返回**。
 *
 * **B. 分集不在 DOM 里**：本站是 Nuxt3 SSR，分集按钮走前端路由 ——
 *    详情页 `episode-list` 容器里只有一个锚点、href 是同一个 `/drama/video/{id}/`，
 *    集号由客户端 JS 从路由读。`HtmlExtractor.parseGroups` 只能返回 0 组。
 *    但播放页把接口响应整块塞进 `<script id="__NUXT_DATA__">`，
 *    里面 `episodeAll` 是完整分集数组、**每集自带独立播放地址**。
 *
 * ## 全部离线
 *
 * 样本是 App 同款 OkHttp 从真实站点抓下来的页面（`_yg/`）。断言只用真实产品代码。
 */
public class Yg {

    static int pass = 0, fail = 0;
    static final String BASE = "https://capable.fzchosdi.cc";
    static String dir;

    public static void main(String[] args) throws Exception {
        dir = args.length > 0 ? args[0] : "_yg";
        String homeHtml = read("home.html");
        String detailHtml = read("detail.html");
        String playHtml = read("e1.html");          // /drama/video/3381/（5 集）
        String play1Html = read("video.html");       // /drama/video/3379/（1 集）

        Document home = Jsoup.parse(homeHtml, BASE);
        Document detail = Jsoup.parse(detailHtml, BASE);

        // ================================================================ A
        banner("A. 分类识别 —— /tag/{slug}/ 必须能认出来，功能页必须挡在外面");
        RecipeStore.INSTANCE.clear(BASE);
        HtmlAdapter ad = new HtmlAdapter(new SiteConfig(
                "yg", "野果", BASE, "", SiteConfig.MODE_HTML, "", "", 0L));
        List<Category> cats = ad.categoriesFrom(home);
        System.out.println("  categoriesFrom(home) = " + cats.size() + " 个；前 6 个：");
        for (int i = 0; i < Math.min(6, cats.size()); i++) {
            System.out.println("      · " + cats.get(i).getName() + "   " + cats.get(i).getId());
        }
        ok("真分类能认出来（≥10 个）", cats.size() >= 10);

        boolean allTag = !cats.isEmpty(), noFunc = true, noNoise = true;
        Set<String> names = new HashSet<>();
        for (Category c : cats) {
            if (!c.getId().contains("/tag/")) allTag = false;
            String id = c.getId();
            // 页脚功能页的路径段
            for (String s : new String[]{"/contact", "/question", "/protocol", "/privacy", "/search"}) {
                if (id.contains(s)) noFunc = false;
            }
            // 形状相同但只有 1 个别名的「功能目录」
            if (id.contains("/rank/") || id.contains("/explore/")) noNoise = false;
            names.add(c.getName());
        }
        ok("全部指向 /tag/（不再是页脚功能页）", allTag);
        ok("不含页脚功能页 contact/question/protocol/privacy/search", noFunc);
        ok("不含 /rank/drama/、/explore/drama/（形状相同，靠聚类挡掉）", noNoise);
        ok("分类名互不重复", names.size() == cats.size());
        ok("不含「回家的路」这类单链表页", !cats.isEmpty()
                && cats.stream().noneMatch(c -> c.getName().contains("回家的路")));

        banner("A2. 校准固化形状后也一样（人工校准走 expectDir 分支，跳过聚类）");
        RecipeStore.INSTANCE.clear(BASE);
        RecipeStore.INSTANCE.save(BASE, new SiteRecipe(SiteRecipe.VER, true,
                BASE + "/drama/detail/{id}/", null, null, null,
                null, "/tag/{slug}/", /*homeCat*/ null,
                /*learnedCatTpl*/ null, /*learnedCatAt*/ 0L,
                System.currentTimeMillis(), "校准过", 0L));
        HtmlAdapter ad2 = new HtmlAdapter(new SiteConfig(
                "yg", "野果", BASE, "", SiteConfig.MODE_HTML, "", "", 0L));
        List<Category> cats2 = ad2.categoriesFrom(home);
        System.out.println("  配方 catTpl=/tag/{slug}/ -> " + cats2.size() + " 个分类");
        ok("校准形状生效（≥10 个）", cats2.size() >= 10);
        RecipeStore.INSTANCE.clear(BASE);

        // ================================================================ B
        banner("B. 形状判据（正例 + 反例，改判据必跑）");
        ok("/tag/{slug}/ 能抽形状",
                "/tag/{slug}/".equals(HtmlTemplates.INSTANCE.catTplFrom("/tag/AI%E7%9F%AD%E5%89%A7/")));
        ok("旧形状 /bspvt/{slug}.html 行为不变",
                "/bspvt/{slug}.html".equals(HtmlTemplates.INSTANCE.catTplFrom("/bspvt/dianying.html")));
        ok("isSlashCatTpl(/tag/{slug}/) = true",
                HtmlTemplates.INSTANCE.isSlashCatTpl("/tag/{slug}/"));
        ok("isSlashCatTpl(/bspvt/{slug}.html) = false",
                !HtmlTemplates.INSTANCE.isSlashCatTpl("/bspvt/{slug}.html"));
        eq("dirOfSlashCatTpl(/tag/{slug}/)", "tag",
                HtmlTemplates.INSTANCE.dirOfSlashCatTpl("/tag/{slug}/"));

        // 逆运算必须配对，否则两边各走各的
        ok("matchesCatTpl 正例",
                HtmlTemplates.INSTANCE.matchesCatTpl("/tag/%E7%86%9F%E5%A5%B3/", "/tag/{slug}/"));
        ok("matchesCatTpl 反例：别的目录不算",
                !HtmlTemplates.INSTANCE.matchesCatTpl("/drama/rec-x/", "/tag/{slug}/"));
        ok("matchesCatTpl 反例：纯数字段不算",
                !HtmlTemplates.INSTANCE.matchesCatTpl("/tag/123/", "/tag/{slug}/"));

        // 形状本身分不开功能页 —— 这点必须写下来，提醒后来者别把聚类删了
        ok("/rank/drama/ 形状与分类相同（⇒ 必须靠聚类）",
                HtmlTemplates.INSTANCE.isSlashDirCategory("/rank/drama/"));
        ok("反例：详情页 /drama/detail/3381/ 不是分类（纯数字别名）",
                !HtmlTemplates.INSTANCE.isSlashDirCategory("/drama/detail/3381/"));
        ok("反例：分页 /tag/x/page/2/ 不是分类（三段）",
                !HtmlTemplates.INSTANCE.isSlashDirCategory("/tag/AI%E7%9F%AD%E5%89%A7/page/2/"));
        ok("反例：根级单段 /contact/ 不是尾斜杠目录分类（它是一段）",
                !HtmlTemplates.INSTANCE.isSlashDirCategory("/contact/"));

        banner("B2. 通用「数字段 -> {id}」模板（自研站专有形状）");
        eq("播放页模板", "/drama/video/{id}/",
                HtmlTemplates.INSTANCE.tplFromNumericSegment("/drama/video/3381/"));
        eq("分集子路径归一到同一形状", "/drama/video/{id}/",
                HtmlTemplates.INSTANCE.tplFromNumericSegment("/drama/video/3381/ep-4/"));
        eq("详情页模板", "/drama/detail/{id}/",
                HtmlTemplates.INSTANCE.tplFromNumericSegment("/drama/detail/3379/"));
        ok("反例：maccms 筛选页（不以 / 结尾）-> null",
                HtmlTemplates.INSTANCE.tplFromNumericSegment("/vodshow/12--------3---.html") == null);
        ok("反例：没有数字段 -> null",
                HtmlTemplates.INSTANCE.tplFromNumericSegment("/drama/rec-hot-drama/") == null);

        // ================================================================ C
        banner("C. payload 分集提取（DOM 拿不到时的唯一出路）");
        List<SsrPayload.Ep> eps = SsrPayload.INSTANCE.episodes(playHtml);
        System.out.println("  episodes(/drama/video/3381/) = " + eps.size() + " 集");
        for (SsrPayload.Ep e : eps) {
            System.out.println("      " + e.getName() + "  -> " + shortUrl(e.getUrl()));
        }
        eq("5 集剧拿到 5 集", 5, eps.size());

        boolean asc = true, distinctUrl = true, allHls = true, cleanName = true;
        Set<String> us = new HashSet<>();
        int prev = 0;
        for (SsrPayload.Ep e : eps) {
            if (e.getNo() <= prev) asc = false;
            prev = e.getNo();
            if (!us.add(e.getUrl())) distinctUrl = false;
            if (!e.getUrl().contains("m3u8")) allHls = false;
            if (!e.getName().matches("第\\d+集")) cleanName = false;
        }
        ok("集号严格递增（乱序的 episodeAll 被正确重排）", asc);
        ok("每集地址互不相同（不是同一个 m3u8）", distinctUrl);
        ok("每集地址都是 m3u8", allHls);
        ok("集名干净，形如「第N集」（不是那串带简介的脏标题）", cleanName);
        ok("集号从 1 开始", !eps.isEmpty() && eps.get(0).getNo() == 1);

        List<PlayGroup> pg = SsrPayload.INSTANCE.groups(playHtml);
        eq("groups() = 1 组", 1, pg.size());
        eq("组里 5 集", 5, pg.get(0).getEpisodes().size());

        banner("C2. 单集剧 / 详情页 / 边界");
        eq("单集剧拿到 1 集", 1, SsrPayload.INSTANCE.episodes(play1Html).size());
        // 详情页 payload 里的 episodes 只有 id/sort/title，**没有 video_url** ——
        // 所以它取不出来，必须去播放页。这条断言守着"别以为详情页够用"。
        eq("详情页 payload 取不出分集（没有地址）⇒ 必须走播放页",
                0, SsrPayload.INSTANCE.episodes(detailHtml).size());
        eq("空/异常输入不抛异常（空串）", 0, SsrPayload.INSTANCE.episodes("").size());
        eq("空/异常输入不抛异常（null）", 0, SsrPayload.INSTANCE.episodes(null).size());
        eq("空/异常输入不抛异常（坏 JSON）", 0,
                SsrPayload.INSTANCE.episodes("<script id=\"__NUXT_DATA__\">{oops</script>").size());
        eq("普通 JSON 页面（非扁平数组）不误判", 0,
                SsrPayload.INSTANCE.episodes("<script type=\"application/json\">{\"a\":1}</script>").size());

        // ================================================================ D
        banner("D. DOM 层没被过度修正（原有行为必须原样保留）");
        eq("DOM 解析本站详情页仍是 0 组（所以兜底链必须有 payload）",
                0, HtmlExtractor.INSTANCE.parseGroups(detail, BASE).size());
        List<VideoItem> items = HtmlExtractor.INSTANCE.parseList(Jsoup.parse(read("tag.html"), BASE), BASE, false);
        System.out.println("  真实分类页卡片 = " + items.size() + " 条");
        ok("真实分类页卡片解析正常（≥10 条）", items.size() >= 10);

        System.out.println();
        System.out.println("========================================================");
        System.out.println("  PASS=" + pass + "  FAIL=" + fail);
        System.out.println("========================================================");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- 工具

    static String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
    }

    static String shortUrl(String u) {
        if (u == null) return "null";
        return u.length() <= 92 ? u : u.substring(0, 92) + "…";
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  " + s);
        System.out.println("================================================================");
    }

    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + what); }
        else { fail++; System.out.println("  [FAIL] " + what); }
    }

    static void eq(String what, Object expect, Object got) {
        ok(what + "（期望 " + expect + "，得到 " + got + "）",
                String.valueOf(expect).equals(String.valueOf(got)));
    }
}
