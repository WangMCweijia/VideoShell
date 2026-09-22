import com.videoshell.data.model.VideoItem;
import com.videoshell.data.site.HtmlExtractor;
import com.videoshell.data.site.RecipeStore;
import com.videoshell.data.site.SiteRecipe;
import com.videoshell.data.site.SsrPayload;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 离线校验：**「最新」tab（`browse("")` = 站点首页）的封面与剧名**（v1.0.18）。
 *
 * 用户现象：野果短剧列表全灰、没封面。
 *
 * `SiteActivity` 默认选中「最新」，`currentType = ""` ⇒ `browseUrls("")`
 * 返回 `listOf(site.baseUrl)` ⇒ 抓的是**站点首页**。而这个自研 Nuxt 站的首页是
 * 客户端渲染的 hero 轮播：SSR 里只有 SEO 配置，剧集数据要等客户端 JS 二次拉取 ⇒
 * **服务端拿到的 HTML 里一张封面都没有**，全是 `data:` 占位图。
 * 同时卡片是 <a aria-label="查看剧集">，`pickName` 取 aria-label ⇒ 整页同名。
 *
 * 修法两条：
 *  1. `HtmlExtractor.pickName` 每层遇到按钮文案（CTA）就跳过，别把它当剧名；
 *  2. `HtmlAdapter.substituteHome` 发现首页无封面时，自动改用**探到的真分类页**，
 *     并把结果固化进 `SiteRecipe.homeCat`（只探一次）。
 *
 * 本文件用真样本（`_yg/*.html`）跑纯解析，不联网。
 */
public class HomeCover {

    static final String BASE = "https://capable.fzchosdi.cc";

    /** 与 HtmlExtractor.CTA_NAMES 同步；产品端改了词表这里也要改 */
    static final Set<String> CTA = new HashSet<>(Arrays.asList(
            "查看剧集", "查看详情", "查看更多", "查看", "点击查看", "详情",
            "立即播放", "马上播放", "开始观看", "立即观看", "点击播放", "去播放", "播放", "观看",
            "选集", "下载", "收藏", "追剧", "免费观看", "在线观看"));

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what);
        if (cond) pass++; else fail++;
    }

    static String dir;

    public static void main(String[] args) throws Exception {
        dir = args.length > 0 ? args[0]
                : "_yg";

        System.out.println("==== 首页（「最新」tab）封面与剧名 ====");
        System.out.println();

        String home = read("home.html");
        String tag = read("tag.html");
        String rec = read("rec.html");
        if (home == null || tag == null || rec == null) {
            System.out.println("[FAIL] 缺样本，需要 _yg/{home,tag,rec}.html");
            System.exit(1);
        }

        // ---------- A. 首页 SSR 里根本没有封面数据 ----------
        System.out.println("-- A. 首页 SSR payload（为什么首页没封面）--");
        SsrPayload.Covers hc = SsrPayload.INSTANCE.covers(home);
        System.out.println("   首页 payload: byId=" + hc.getById().size() + "  byName=" + hc.getByName().size());
        ok("首页 SSR payload 里封面表为空（客户端渲染，服务端确实拿不到）",
                hc.getById().isEmpty() && hc.getByName().isEmpty());

        SsrPayload.Covers tc = SsrPayload.INSTANCE.covers(tag);
        SsrPayload.Covers rc = SsrPayload.INSTANCE.covers(rec);
        System.out.println("   tag.html  payload: byId=" + tc.getById().size() + "  byName=" + tc.getByName().size());
        System.out.println("   rec.html  payload: byId=" + rc.getById().size() + "  byName=" + rc.getByName().size());
        ok("真分类页 tag.html 有封面表（>0）", !(tc.getById().isEmpty() && tc.getByName().isEmpty()));
        ok("真分类页 rec.html 有封面表（>0）", !(rc.getById().isEmpty() && rc.getByName().isEmpty()));

        // ---------- B. 首页：无封面（现象复现），但剧名不再是按钮文案 ----------
        System.out.println();
        System.out.println("-- B. 首页列表 --");
        List<VideoItem> homeItems = parse(home);
        int homePic = countPic(homeItems);
        System.out.println("   条数 " + homeItems.size() + "，有封面 " + homePic + " 条");
        ok("首页确实解析出卡片（不是解析崩了）", homeItems.size() > 0);
        ok("首页有封面条数为 0（这正是用户看到的「全灰」，所以必须换分类页）", homePic == 0);

        ok("★ 首页剧名不再是按钮文案「查看剧集」（pickName 跳过 CTA 生效）",
                homeItems.size() > 0 && countCta(homeItems) == 0);
        double ratio = homeItems.isEmpty() ? 0 : distinctNameRatio(homeItems);
        System.out.println("   首页剧名去重率 " + String.format("%.0f%%", ratio * 100));
        ok("首页剧名去重率 > 60%（旧实现是 1/53 ≈ 2%）", ratio > 0.6);

        // ---------- C. 真分类页：有封面 + 剧名正常（这就是替代目标） ----------
        System.out.println();
        System.out.println("-- C. 真分类页（substituteHome 的替代目标）--");
        List<VideoItem> tagItems = parse(tag);
        List<VideoItem> recItems = parse(rec);
        int tagPic = countPic(tagItems), recPic = countPic(recItems);
        System.out.println("   tag.html: " + tagItems.size() + " 条，有封面 " + tagPic + " 条");
        System.out.println("   rec.html: " + recItems.size() + " 条，有封面 " + recPic + " 条");
        ok("tag.html 有封面（>0）", tagPic > 0);
        ok("rec.html 有封面（>0）", recPic > 0);
        ok("tag.html 剧名无 CTA 文案", countCta(tagItems) == 0);
        ok("rec.html 剧名无 CTA 文案", countCta(recItems) == 0);

        System.out.println("   样本（tag.html 前 4 条）：");
        for (int i = 0; i < Math.min(4, tagItems.size()); i++) {
            VideoItem v = tagItems.get(i);
            System.out.println("      · " + v.getName() + "   pic=" + tail(v.getPic()));
        }

        // ---------- D. homeCat 固化必须能过 Gson 往返 ----------
        System.out.println();
        System.out.println("-- D. SiteRecipe.homeCat 持久化往返 --");
        RecipeStore.INSTANCE.clear(BASE);
        RecipeStore.INSTANCE.save(BASE, new SiteRecipe(
                SiteRecipe.VER, false, null, null, null, null, null, null,
                "/tag/yule/", null, 0L, 0L, null, 0L));
        SiteRecipe back = RecipeStore.INSTANCE.load(BASE);
        ok("配方读回：homeCat 一致（Gson 往返没丢）",
                back != null && "/tag/yule/".equals(back.getHomeCat()));
        ok("带 homeCat 的配方不算空（不会被当成「没学到东西」丢掉）",
                back != null && !back.isEmpty());

        RecipeStore.INSTANCE.clear(BASE);
        RecipeStore.INSTANCE.save(BASE, new SiteRecipe(
                SiteRecipe.VER, false, null, null, null, null, null, null,
                null, null, 0L, 0L, null, 0L));
        SiteRecipe empty = RecipeStore.INSTANCE.load(BASE);
        ok("不带 homeCat 的空配方 isEmpty=true", empty != null && empty.isEmpty());

        System.out.println();
        System.out.println("==== HomeCover PASS=" + pass + " FAIL=" + fail + " ====");
        System.exit(fail == 0 ? 0 : 1);
    }

    static String read(String name) throws Exception {
        String p = Paths.get(dir, name).toString();
        if (!Files.exists(Paths.get(p))) return null;
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    static List<VideoItem> parse(String html) {
        Document doc = Jsoup.parse(html, BASE);
        return HtmlExtractor.INSTANCE.parseList(doc, BASE, false, html);
    }

    static int countPic(List<VideoItem> l) {
        int n = 0;
        for (VideoItem v : l) if (v.getPic() != null && !v.getPic().isBlank()) n++;
        return n;
    }

    static int countCta(List<VideoItem> l) {
        int n = 0;
        for (VideoItem v : l) if (CTA.contains(v.getName() == null ? "" : v.getName().trim())) n++;
        return n;
    }

    static double distinctNameRatio(List<VideoItem> l) {
        List<String> names = new ArrayList<>();
        for (VideoItem v : l) names.add(v.getName() == null ? "" : v.getName().trim());
        return new HashSet<>(names).size() / (double) l.size();
    }

    static String tail(String u) {
        if (u == null || u.isBlank()) return "<空>";
        return u.length() <= 76 ? u : "…" + u.substring(u.length() - 76);
    }
}
