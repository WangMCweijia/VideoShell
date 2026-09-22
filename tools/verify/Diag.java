import com.videoshell.data.model.*;
import com.videoshell.data.site.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 现场复现：分类为什么是空的 / 播放地址为什么 404 */
public class Diag {

    static final String BASE = "https://www.cupfoxyy.com";

    static void h(String s) {
        System.out.println("\n" + "=".repeat(72));
        System.out.println(s);
        System.out.println("=".repeat(72));
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        String home = read(dir + "/cf_home_okhttp.html");
        String cat1 = read(dir + "/cf_cat1.html");
        String sub6 = read(dir + "/cf_sub6.html");
        String play1 = read(dir + "/cf_bs_2.html");

        SiteConfig site = new SiteConfig("k", "茶杯狐", BASE, "", SiteConfig.MODE_HTML, "", "", 0L);
        HtmlAdapter adapter = new HtmlAdapter(site);

        // ---------- A. 走真实代码：categoriesFrom(首页) ----------
        h("A. HtmlAdapter.categoriesFrom(首页 DOM) —— 真实代码");
        Document homeDoc = Jsoup.parse(home, BASE);
        List<Category> cats = adapter.categoriesFrom(homeDoc);
        System.out.println("结果条数: " + cats.size());
        for (Category c : cats) System.out.println("   " + c.getId() + "  |  " + getName(c));

        // ---------- B. 逐条排查 nav 选择器 ----------
        h("B. navSelectors 逐个命中情况（复刻逻辑）");
        String[] sels = {".main_nav a", ".tab_head a", ".nav-list a", "header nav a", "nav a",
                ".navbar a", ".nav_bar a", ".menu a", "#nav a", ".header-nav a", "header .nav a", "header a"};
        for (String sel : sels) {
            Elements es = homeDoc.select(sel);
            StringBuilder sb = new StringBuilder();
            int valid = 0;
            for (Element a : es) {
                String href = a.attr("href").trim();
                if (a.selectFirst("img") != null) continue;
                if (HtmlTemplates.INSTANCE.isCategoryHref(href, true)) {
                    valid++;
                    if (valid <= 4) sb.append("[").append(href).append("=").append(a.text().trim()).append("]");
                }
            }
            System.out.printf("  %-16s 命中 %3d 个 a，其中合格分类 %d  %s%n", sel, es.size(), valid, sb);
        }

        h("C. 全文档兜底扫描（doc.select(\"a[href]\")）");
        int n = 0;
        for (Element a : homeDoc.select("a[href]")) {
            String href = a.attr("href").trim();
            if (a.selectFirst("img") != null) continue;
            if (!HtmlTemplates.INSTANCE.isCategoryHref(href, true)) continue;
            String name = a.text().replaceAll("\\s+", " ").trim();
            if (n < 12)
                System.out.printf("   %-40s | %-10s | len=%d%n", href, name, name.length());
            n++;
        }
        System.out.println("   合计合格: " + n);

        h("D. 判据细节：/vod/1.html 在两种 vodIsCategory 下的结果");
        for (boolean v : new boolean[]{false, true}) {
            System.out.println("  vodIsCategory=" + v
                    + "  isDetailSignal=" + HtmlTemplates.INSTANCE.isDetailSignal("/vod/1.html")
                    + "  typeIdOf=" + HtmlTemplates.INSTANCE.typeIdOf("/vod/1.html", v)
                    + "  isCategoryHref=" + HtmlTemplates.INSTANCE.isCategoryHref("/vod/1.html", v));
        }
        System.out.println("  首页存在详情信号(/detail/942.html)? "
                + HtmlTemplates.INSTANCE.isDetailSignal("/detail/942.html"));

        // ---------- E. 列表解析 ----------
        h("E. HtmlExtractor.parseList 结果");
        List<VideoItem> li = HtmlExtractor.INSTANCE.parseList(homeDoc, BASE, true);
        System.out.println("  首页 -> " + li.size() + " 条");
        for (int i = 0; i < Math.min(3, li.size()); i++) {
            VideoItem v = li.get(i);
            System.out.println("     " + v.getId() + " | " + v.getName() + " | " + v.getPic());
        }
        List<VideoItem> l2 = HtmlExtractor.INSTANCE.parseList(Jsoup.parse(cat1, BASE), BASE, true);
        System.out.println("  /vod/1.html -> " + l2.size() + " 条");
        for (int i = 0; i < Math.min(3, l2.size()); i++) {
            VideoItem v = l2.get(i);
            System.out.println("     " + v.getId() + " | " + v.getName() + " | " + v.getPic());
        }
        List<VideoItem> l3 = HtmlExtractor.INSTANCE.parseList(Jsoup.parse(sub6, BASE), BASE, true);
        System.out.println("  /vodshow/id/6.html -> " + l3.size() + " 条");
        for (int i = 0; i < Math.min(3, l3.size()); i++) {
            VideoItem v = l3.get(i);
            System.out.println("     " + v.getId() + " | " + v.getName() + " | " + v.getPic());
        }

        // ---------- F. 播放地址抽取 ----------
        h("F. Media.extractFromHtml(播放页)");
        String u1 = Media.INSTANCE.extractFromHtml(play1);
        System.out.println("  sid=1 抽出: " + u1);
        System.out.println("  containsBackslashU = " + (u1 != null && u1.contains("\\u")));

        h("G. 造一个含 \\uXXXX 的 player_aaaa（复现用户报的 404）");
        String fake = "<script>var player_aaaa={\"flag\":\"play\",\"encrypt\":0,"
                + "\"url\":\"https:\\/\\/c1.ddbbffcdn.com\\/video\\/bianshuiwangshi\\/"
                + "\\u7b2c01\\u96c6\\/index.m3u8\",\"from\":\"bfzym3u8\"}</script>";
        String u2 = Media.INSTANCE.extractFromHtml(fake);
        System.out.println("  抽出: " + u2);
        System.out.println("  期望: https://c1.ddbbffcdn.com/video/bianshuiwangshi/第01集/index.m3u8");
        System.out.println("  一致? " + "https://c1.ddbbffcdn.com/video/bianshuiwangshi/第01集/index.m3u8".equals(u2));

        h("H. HlsPlaylistFixer 对相对路径中文分片的处理（顺带确认）");
        String pl = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:1.0,\n第01集\\000000.ts\n";
        System.out.println("  (跳过：分片名含中文时由 fix 做绝对化)");

        h("I. 站内搜索模板可用性（用于定位 边水往事）");
        System.out.println("  searchCandidates: " + HtmlTemplates.INSTANCE.searchCandidates(BASE));
    }

    static String getName(Category c) {
        return c.getName();
    }

    static String read(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }
}
