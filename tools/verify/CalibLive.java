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

        // ---- 照用户的三步，推出一份「诚实的校准产物」 ----
        derivision();

        System.out.println("\n配方（校准产物）= catTpl=" + n(catTpl) + "  navSel=" + n(navSel)
                + "  detailTpl=" + n(detailTpl) + "  playTpl=" + n(playTpl));
        System.out.println("样例影片 id = " + n(firstId) + "，用它当分类的分类页 = " + n(firstCat));

        // ---- 配方矩阵 ----
        System.out.println("\n================ 配方矩阵：哪一份会让详情失败？ ================");
        String hdr = String.format("%-46s %6s %6s %s", "配方", "分类", "列表", "详情");
        System.out.println(hdr);
        System.out.println("-".repeat(110));

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
            System.out.println(pad(label) + pad(String.valueOf(cats.size())) + pad("0")
                    + "（列表为空，跳过详情）");
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
        System.out.println(pad(label) + pad(String.valueOf(cats.size()))
                + pad(String.valueOf(list.size())) + detail);
    }

    static String pad(String s) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < 46) sb.append(' ');
        return sb + " ";
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
