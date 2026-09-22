import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.site.*;
import com.videoshell.util.ExtKt;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.*;

/**
 * 线上重放 + 配方矩阵。
 *
 * 用户报：「校准后规则没生效，校准时试播正常，但完成后重载站源获取不到分集列表」。
 * 校准能试播 ⇒ playTpl 与播放页解析没问题。所以嫌疑在「配方落到具体解析时」的差异。
 *
 * 做法：先用真 Http + 真 Kotlin 逻辑照用户的三步点一遍，算出他会得到的那份配方；
 * 然后**枚举所有可能落进配方的组合**，看哪一份会让 detail() 拿不到分集。
 * 只要有一份能复现，就找到了根因；如果全都正常，说明问题在设备侧 / 站点侧。
 */
public class CalibLive {

    static final String BASE = System.getProperty("vs.base", "https://www.bolyship.com");
    static String detailTpl = null, playTpl = null, catTpl = null, navSel = null;
    static String firstId = null;
    static String firstCat = null;

    /**
     * `-v`：把每一行的**完整** `calibDiag` + `lastDiag` 原样打出来。
     *
     * 为什么需要：矩阵那几列是**压缩过的结论**（生效 / 退回默认 / …），而"为什么是这个结论"
     * 只有原文说得清。排查时最忌讳的就是从一个浓缩标签反推原因 —— 那等于用结论当证据。
     */
    static boolean VERBOSE = false;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    static String get(String url, String ref) {
        try {
            return block((s, c) -> Http.INSTANCE.get(
                    url, ref, Http.UA, Collections.<String, String>emptyMap(), false,
                    (Continuation<? super String>) c));
        } catch (Throwable t) {
            return null;
        }
    }

    static String n(String s) { return s == null ? "null" : s; }

    // ==================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("BASE = " + BASE);
        VERBOSE = java.util.Arrays.asList(args).contains("-v");

        // ---- 照用户的三步，推出一份「诚实的校准产物」 ----
        derivision();

        System.out.println("\n配方（校准产物）= catTpl=" + n(catTpl) + "  navSel=" + n(navSel)
                + "  detailTpl=" + n(detailTpl) + "  playTpl=" + n(playTpl));
        System.out.println("样例影片 id = " + n(firstId) + "，用它当分类的分类页 = " + n(firstCat));

        // ---- 配方矩阵 ----
        System.out.println("\n================ 配方矩阵：哪一份会让详情失败？ ================");
        // ★ v1.0.54 起多一列「校准校验」。
        //
        // 为什么必须有这一列：原来的矩阵**只看结果**（分类几个 / 列表几个 / 详情成不成），
        // 而结果对"配方有没有被用上"是**不敏感**的 —— 实测 10 份配方（含故意把
        // `/bspvd/{id}.html` 改成 `/bspvs/` 的那份）打出来的结论一模一样。
        // 于是在"通用兜底本来就能过"的站点上，这张表**分不出**下面两件事：
        //   (a) 配方真的生效了，只是结果恰好相同；
        //   (b) 配方被静默忽略了（写了没读 / 读了没用 / 用了但退回默认）。
        // 而"校准有没有生效"问的正是后者。判据要打在**决策**上，不是打在**结果**上。
        //
        // 这三列来自适配器自己的 `calibDiag`（[2a] 那一栏同源，见 SiteDoctor）：
        //   生效     —— 校准形状/容器本次页面命中了 ≥2 个分类；
        //   静默退回 —— 校准规则命中了但 <2 个 ⇒ 退回默认逻辑（"校准了却没变"最常见的原因）；
        //   没走校准 —— 配方里根本没有校准规则，或压根没解析到分类页。
        // 表头也用 padd 拼：`String.format("%-9s")` 数的是**字符数**（中文 1 个字符），
        // 而实际显示宽度是 2 列 —— 混用两种补白方式，表头与内容必然错位。
        System.out.println(padd("配方", 40) + padd("分类", 5) + padd("列表", 5)
                + padd("校准校验", 11) + "详情");
        System.out.println("-".repeat(118));

        run("（空配方：首次访问）", null, null, null, null, true);
        run("catTpl+navSel（第1步学到）", catTpl, navSel, null, null, true);
        run("detailTpl（第2步学到）", null, null, detailTpl, null, true);
        run("playTpl（第3步学到）", null, null, null, playTpl, true);
        run("三步全（校准完整产物）", catTpl, navSel, detailTpl, playTpl, true);
        run("三步全 vodIsCategory=false", catTpl, navSel, detailTpl, playTpl, false);
        run("仅 detailTpl+playTpl", null, null, detailTpl, playTpl, true);

        String pDetail = detailTpl == null ? null : pathOf(detailTpl);
        String pPlay = playTpl == null ? null : pathOf(playTpl);
        run("路径式 detailTpl+playTpl", null, null, pDetail, pPlay, true);
        run("坏 detailTpl（/bspvd/{id}.html 替成 /bspvs/）",
                null, null, detailTpl == null ? null : detailTpl.replace("/bspvd/", "/bspvs/"),
                playTpl, true);
        run("detailTpl 带 {id} 但是 play 模板错",
                null, null, detailTpl, playTpl == null ? null : playTpl.replace("/bspvp/", "/bspvs/"), true);
    }

    /** 走一遍「分类 -> 列表 -> 详情」，只看结论 */
    static void run(String label, String cTpl, String nSel, String dTpl, String pTpl,
                    boolean vodIsCategory) throws Exception {
        RecipeStore.INSTANCE.clear(BASE);
        if (cTpl != null || nSel != null || dTpl != null || pTpl != null) {
            RecipeStore.INSTANCE.save(BASE, new SiteRecipe(
                    SiteRecipe.VER, vodIsCategory, dTpl, pTpl, null, null, nSel, cTpl,
                    /*homeCat*/ null, /*learnedCatTpl*/ null, /*learnedCatAt*/ 0L,
                    System.currentTimeMillis(), label, 0L));
        }
        SiteConfig site = new SiteConfig("bs", "金牌影视", BASE, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter a = AdapterFactory.INSTANCE.create(site);

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Category> cats = (List<Category>) block(
                (s, c) -> a.categories((Continuation<? super List<Category>>) c));

        // ★ 校准校验必须在 `categories()` **之后**取：`calibOutcome` 是每次重解析重写的，
        //   在解析之前读只会拿到空串（那会得出"所有配方都没生效"的假结论）。
        String cverdict = calibVerdict(a);

        // 列表：优先用校准产物里的分类页，其次按首页推出来的那个
        String browsePicked = firstCat;
        for (Category c : cats) {
            if (firstCat != null && firstCat.equals(c.getId())) { browsePicked = c.getId(); break; }
        }
        final String browse = browsePicked;
        final SiteAdapter fa = a;
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<VideoItem> list = (List<VideoItem>) block(
                (s, c) -> fa.browse(browse, 1, (Continuation<? super List<VideoItem>>) c));
        if (list.isEmpty()) {
            System.out.println(padd(label, 40) + padd(String.valueOf(cats.size()), 5) + padd("0", 5)
                    + padd(cverdict, 11) + "（列表为空，跳过详情）");
            return;
        }

        String id = list.get(0).getId();
        String detail;
        try {
            VideoDetail d = (VideoDetail) block(
                    (s, c) -> a.detail(id, (Continuation<? super VideoDetail>) c));
            int eps = 0;
            for (PlayGroup g : d.getGroups()) eps += g.getEpisodes().size();
            detail = "OK  " + d.getGroups().size() + " 线路 / " + eps + " 集";
        } catch (Throwable t) {
            detail = "❌ " + t.getMessage();
        }
        // ★ 详情步的判据：`detail()` 跑完才可能被写入（那是它唯一的生产点）。
        //   它回答的正是上面「校准校验」那一列回答不了的问题 —— **配方里的详情模板有没有被用上**。
        //
        //   两种情况要分开报，因为处置完全不同：
        //     ⚠ 配方详情模板未命中 —— 配方是错的，但被兜底救活了（**结果全绿**，最容易漏）；
        //     ⇄ 配方详情模板被替换 —— 兜底成功且顺手改写了配方（自愈，但配方被悄悄改了）。
        String d = a.getCalibDiag();
        if (d.contains("配方里的详情模板") && d.contains("没能解析出分集")) {
            detail += "　⚠ 配方详情模板未命中";
        }
        if (d.contains("详情模板已被替换")) {
            detail += "　⇄ 配方详情模板被替换";
        }
        System.out.println(padd(label, 40) + padd(String.valueOf(cats.size()), 5)
                + padd(String.valueOf(list.size()), 5) + padd(cverdict, 11) + detail);
        if (VERBOSE) {
            System.out.println("      ┌ calibDiag ───────────────────────────────");
            for (String ln : a.getCalibDiag().split("\n")) System.out.println("      │ " + ln);
            System.out.println("      └ lastDiag ────────────────────────────────");
            for (String ln : a.getLastDiag().split("\n")) System.out.println("      │ " + ln);
        }
    }

    /**
     * 这一行配方**有没有被真的用上**。
     *
     * 判据只认适配器自己的结论（`calibApplied` / `calibDiag`），**不在这里另判一次** ——
     * 判据只能有一份（见 `SiteAdapter.calibDiag` 的注释）。
     */
    static String calibVerdict(SiteAdapter a) {
        if (a.getCalibApplied()) return "生效";
        String d = a.getCalibDiag();
        if (d.contains("已静默退回") || d.contains("退回默认逻辑")) return "退回默认";
        // 配方里有 calibAt>0（本 harness 每一行都这么写），但第 1 步没学到分类规则 ——
        // 这是"只校准了详情/分集"的正常形态，**不是**"校准失效"，不能混成一个标签。
        if (d.contains("没有学到分类规则")) return "无分类规则";
        if (d.contains("没走到校准规则")) return "没走校准";
        return "未生效";
    }

    /** 显示宽度：中日韩字符占 2 列。不按它补空格，表头与内容永远对不齐 */
    static int dw(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean wide = (c >= 0x1100 && c <= 0x115F) || (c >= 0x2E80 && c <= 0xA4CF)
                    || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF)
                    || (c >= 0xFE30 && c <= 0xFE6F) || (c >= 0xFF00 && c <= 0xFF60)
                    || (c >= 0xFFE0 && c <= 0xFFE6) || c == 0x274C || c == 0x26A0;
            n += wide ? 2 : 1;
        }
        return n;
    }

    static String padd(String s, int w) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        for (int i = dw(sb.toString()); i < w; i++) sb.append(' ');
        return sb.append(' ').toString();
    }

    static String pathOf(String u) {
        try { return new java.net.URI(u).getPath() + ""; } catch (Exception e) { return u; }
    }

    // ================================================================== 推导校准产物

    static void derivision() throws Exception {
        String home = get(BASE, BASE);
        if (home == null) { System.out.println("!! 首页抓取失败"); System.exit(1); }
        Document hdoc = Jsoup.parse(home, BASE);

        for (Element a : hdoc.select("a[href]")) {
            String abs = ExtKt.resolveUrl(BASE, a.attr("href").trim());
            String tpl = HtmlTemplates.INSTANCE.catTplFrom(abs);
            if (tpl == null) continue;
            if (a.text().trim().length() > 10) continue;
            catTpl = tpl;
            firstCat = abs;
            break;
        }
        if (firstCat != null) navSel = SiteCalib.INSTANCE.navSel(home, BASE, firstCat);

        String catHtml = firstCat == null ? null : get(firstCat, BASE);
        if (catHtml == null) { System.out.println("!! 分类页抓取失败"); System.exit(1); }
        List<VideoItem> items = HtmlExtractor.INSTANCE.parseList(Jsoup.parse(catHtml, BASE), BASE, false);
        if (items.isEmpty()) { System.out.println("!! 分类页无卡片"); System.exit(1); }
        firstId = items.get(0).getId();

        // 卡片链接 -> 详情模板（这就是第 2 步用户点一下学到的东西）
        Document cdoc = Jsoup.parse(catHtml, BASE);
        String cardAbs = null;
        for (Element a : cdoc.select("a[href]")) {
            String raw = a.attr("href").trim();
            String abs = ExtKt.resolveUrl(BASE, raw);
            String vid = HtmlTemplates.INSTANCE.videoIdOf(raw, false);
            if (vid == null) vid = HtmlTemplates.INSTANCE.videoIdOf(abs, false);
            if (firstId.equals(vid)) { cardAbs = abs; break; }
        }
        if (cardAbs != null) detailTpl = HtmlTemplates.INSTANCE.detailTplFrom(cardAbs, firstId);

        // 详情页里的第一集 -> 播放模板（第 3 步）
        String dHtml = cardAbs == null ? null : get(cardAbs, BASE);
        if (dHtml != null) {
            Document ddoc = Jsoup.parse(dHtml, BASE);
            for (Element a : ddoc.select("a[href]")) {
                String href = a.attr("href").trim();
                if (!HtmlTemplates.INSTANCE.isPlayLink(href)) continue;
                playTpl = HtmlTemplates.INSTANCE.playTplFrom(ExtKt.resolveUrl(BASE, href));
                if (playTpl != null) break;
            }
        }
    }
}
