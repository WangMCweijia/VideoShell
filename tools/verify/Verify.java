import com.videoshell.data.model.Category;
import com.videoshell.data.model.PlayGroup;
import com.videoshell.data.model.SiteConfig;
import com.videoshell.data.model.VideoItem;
import com.videoshell.data.site.HtmlAdapter;
import com.videoshell.data.site.HtmlExtractor;
import com.videoshell.data.site.HtmlTemplates;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 离线校验：把刚编译出的 Kotlin 类跑在真实抓取的页面上，验证分类/列表/选集三处解析。
 * 用法: java -cp <classes>:<libs> Verify <htmlDir>
 */
public class Verify {

    static final String BASE = "https://www.cupfoxyy.com";
    static PrintStream out;
    static int fails = 0;

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        out = new PrintStream(System.out, true, "UTF-8");

        SiteConfig cfg = new SiteConfig(
                "key", "茶杯狐", BASE, "", SiteConfig.MODE_HTML, "", "HTML 通用适配", 0L);
        HtmlAdapter adapter = new HtmlAdapter(cfg);

        // ---------- 1. 首页：分类标签 ----------
        Document home = load(dir, "_site_home.html");
        List<Category> cats = adapter.categoriesFrom(home);
        out.println("=== 1. 首页分类 (期望 4 个: 电影/电视剧/综艺/动漫) ===");
        for (Category c : cats) out.println("    " + c.getName() + "  ->  " + c.getId());
        check(cats.size() == 4, "分类数量 = 4", "实际 " + cats.size());
        check(cats.size() > 0 && "电影".equals(cats.get(0).getName()), "第一个分类是 电影",
                cats.isEmpty() ? "空" : cats.get(0).getName());
        for (Category c : cats) {
            check(c.getId().startsWith("http"), "分类 id 是站点真实 URL", c.getId());
        }

        // ---------- 2. 首页：影片列表（导航链接不得混入）----------
        List<VideoItem> items = HtmlExtractor.INSTANCE.parseList(home, BASE, true);
        out.println("=== 2. 首页列表 (期望全是影片, 且不含导航词) ===");
        out.println("    count = " + items.size());
        int n = 0;
        for (VideoItem v : items) {
            if (n++ < 5) out.println("    " + v.getId() + " | " + v.getName() + " | pic="
                    + (v.getPic().isEmpty() ? "(空)" : v.getPic()) + " | 角标=" + v.getRemarks());
        }
        String[] navWords = {"电影", "电视剧", "综艺", "动漫", "首页"};
        for (String w : navWords) {
            boolean hit = false;
            for (VideoItem v : items) if (w.equals(v.getName())) hit = true;
            check(!hit, "列表不含导航词 " + w, "混入了");
        }
        boolean allHavePic = true;
        for (VideoItem v : items) if (v.getPic().isEmpty()) allHavePic = false;
        check(allHavePic, "所有条目都有海报", "存在无图条目");
        check(items.size() >= 20, "首页条目数 >= 20", "实际 " + items.size());

        // ---------- 3. 分类页 /vod/1.html ----------
        Document typeDoc = load(dir, "_p85221f.html");
        List<VideoItem> typeItems = HtmlExtractor.INSTANCE.parseList(typeDoc, BASE, true);
        out.println("=== 3. 分类页 /vod/1.html ===");
        out.println("    count = " + typeItems.size());
        for (int i = 0; i < Math.min(3, typeItems.size()); i++) {
            VideoItem v = typeItems.get(i);
            out.println("    " + v.getId() + " | " + v.getName() + " | 角标=" + v.getRemarks());
        }
        check(typeItems.size() >= 20, "分类页条目数 >= 20", "实际 " + typeItems.size());
        boolean typeHasNav = false;
        for (VideoItem v : typeItems) if ("电影".equals(v.getName()) || "首页".equals(v.getName())) typeHasNav = true;
        check(!typeHasNav, "分类页不含导航词", "混入了");

        // ---------- 4. 电影详情页：单线路 ----------
        Document movie = load(dir, "_p11061a.html");
        List<PlayGroup> mg = HtmlExtractor.INSTANCE.parseGroups(movie, BASE);
        out.println("=== 4. 电影详情页 (期望 1 条线路 + 1 集) ===");
        dumpGroups(mg);
        check(mg.size() == 1, "电影线路数 = 1", "实际 " + mg.size());
        check(mg.size() >= 1 && mg.get(0).getEpisodes().size() == 1, "电影集数 = 1",
                mg.isEmpty() ? "空" : "" + mg.get(0).getEpisodes().size());
        check(mg.size() >= 1 && "极速播放".equals(mg.get(0).getName()), "线路名 = 极速播放",
                mg.isEmpty() ? "空" : mg.get(0).getName());
        out.println("    标题 = " + HtmlExtractor.INSTANCE.parseTitle(movie));
        out.println("    简介 = " + HtmlExtractor.INSTANCE.parseSummary(movie));
        out.println("    海报 = " + HtmlExtractor.INSTANCE.parsePic(movie));

        // ---------- 5. 剧集详情页：多线路 + 分集 ----------
        Document series = load(dir, "_series.html");
        List<PlayGroup> sg = HtmlExtractor.INSTANCE.parseGroups(series, BASE);
        out.println("=== 5. 剧集详情页 (期望 3 条线路, 名称按 tab 顺序) ===");
        dumpGroups(sg);
        check(sg.size() == 3, "剧集线路数 = 3", "实际 " + sg.size());
        String[] wantNames = {"极速播放", "高清云播", "备用线路1"};
        for (int i = 0; i < Math.min(wantNames.length, sg.size()); i++) {
            check(wantNames[i].equals(sg.get(i).getName()), "线路 " + (i + 1) + " 名 = " + wantNames[i],
                    sg.get(i).getName());
        }
        int total = 0;
        for (PlayGroup g : sg) total += g.getEpisodes().size();
        check(total >= 30, "总集数 >= 30", "实际 " + total);
        for (PlayGroup g : sg) {
            check(!g.getEpisodes().isEmpty() && g.getEpisodes().get(0).getUrl().startsWith("http"),
                    "线路 " + g.getName() + " 首集地址可解析",
                    g.getEpisodes().isEmpty() ? "空" : g.getEpisodes().get(0).getUrl());
        }
        // 集名与地址必须对应同一线路
        if (!sg.isEmpty() && !sg.get(0).getEpisodes().isEmpty()) {
            String u = sg.get(0).getEpisodes().get(0).getUrl();
            check(u.contains("/play/"), "首集是播放页", u);
        }

        // ---------- 6. 搜索页 ----------
        Document search = load(dir, "_search.html");
        List<VideoItem> sItems = HtmlExtractor.INSTANCE.parseList(search, BASE, true);
        out.println("=== 6. 搜索页 (期望 20+) ===");
        out.println("    count = " + sItems.size());
        for (int i = 0; i < Math.min(3, sItems.size()); i++) {
            out.println("    " + sItems.get(i).getName() + " | pic="
                    + (sItems.get(i).getPic().isEmpty() ? "(空)" : "有"));
        }
        check(sItems.size() >= 20, "搜索页条目数 >= 20", "实际 " + sItems.size());

        // ---------- 7. 播放地址抽取 ----------
        String playHtml = new String(Files.readAllBytes(Paths.get(dir, "_play.html")), StandardCharsets.UTF_8);
        String real = com.videoshell.data.site.Media.INSTANCE.extractFromHtml(playHtml);
        out.println("=== 7. 播放页地址抽取 ===");
        out.println("    url = " + real);
        check(real != null && real.endsWith(".m3u8"), "能从 player_aaaa 抠出 m3u8", "" + real);

        // ---------- 8. URL 模板候选（保证老站点不回归）----------
        out.println("=== 8. 详情候选模板顺序 ===");
        for (String t : HtmlTemplates.INSTANCE.detailCandidates(BASE)) out.println("    " + t);

        out.println();
        out.println(fails == 0 ? "ALL CHECKS PASSED" : ("FAILED CHECKS: " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    static void dumpGroups(List<PlayGroup> gs) {
        for (PlayGroup g : gs) {
            out.println("    [" + g.getName() + "] " + g.getEpisodes().size() + " 集");
            for (int i = 0; i < Math.min(3, g.getEpisodes().size()); i++) {
                out.println("        " + g.getEpisodes().get(i).getName() + "  "
                        + g.getEpisodes().get(i).getUrl());
            }
        }
    }

    static void check(boolean ok, String what, String got) {
        if (!ok) fails++;
        out.println((ok ? "  OK   " : "  FAIL ") + what + (ok ? "" : "   (实际: " + got + ")"));
    }

    static Document load(String dir, String name) throws Exception {
        byte[] b = Files.readAllBytes(Paths.get(dir, name));
        String html = new String(b, StandardCharsets.UTF_8);
        return Jsoup.parse(html, BASE);
    }
}
