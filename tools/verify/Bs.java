import com.videoshell.data.model.Category;
import com.videoshell.data.model.PlayGroup;
import com.videoshell.data.model.SiteConfig;
import com.videoshell.data.model.VideoItem;
import com.videoshell.data.site.HtmlAdapter;
import com.videoshell.data.site.HtmlExtractor;
import com.videoshell.data.site.HtmlTemplates;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 金牌影视（bolyship.com）通配性诊断。
 *
 * 站点形态（实测）：
 *   分类 /bspvt/{slug}.html      详情 /bspvd/{id}.html
 *   列表 /bspvs/{slug}-----------.html
 *   播放 /bspvp/{id}-{sid}-{nid}.html     线路名「云播一/二/三/四」
 * 采集接口 8 路全 closed ⇒ 正确地落在 MODE_HTML。
 */
public class Bs {

    static int pass = 0, fail = 0;
    // 只是占位：runbs.py 总会把解析好的样本目录作为 args[0] 传进来（CI 上无 D: 盘）
    static final String DIR = "_bs/";
    static final String BASE = "https://www.bolyship.com";

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : DIR;
        // 调用方给的目录可能不带结尾分隔符（Python 侧 os.path.join 就是这样）——
        // 本文件是**裸拼接** dir + "home.html"，不补就会拼成 "_bshome.html" 而"缺样本"退出。
        if (!dir.endsWith("/") && !dir.endsWith("\\")) {
            dir = dir + "/";
        }

        // ---------------- A. 纯判据 ----------------
        banner("A. 链接语义判据");
        p("isPlayLink   /bspvp/548165-1-1.html", HtmlTemplates.INSTANCE.isPlayLink("/bspvp/548165-1-1.html"));
        p("isStrongDetail /bspvd/548165.html", HtmlTemplates.INSTANCE.isStrongDetail("/bspvd/548165.html"));
        p("videoIdOf    /bspvd/548165.html", HtmlTemplates.INSTANCE.videoIdOf("/bspvd/548165.html", false));
        p("typeIdOf     /bspvt/dianying.html", HtmlTemplates.INSTANCE.typeIdOf("/bspvt/dianying.html", false));
        p("detailTplFrom /bspvd/548165.html", HtmlTemplates.INSTANCE.detailTplFrom("/bspvd/548165.html", "548165"));

        ok("isPlayLink 认形状 /bspvp/{id}-{sid}-{nid}.html（不靠目录名）",
                HtmlTemplates.INSTANCE.isPlayLink("/bspvp/548165-1-1.html"));
        ok("isSlugDirCategory 认 /bspvt/dianying.html（带 .html 的目录式分类）",
                HtmlTemplates.INSTANCE.isSlugDirCategory("/bspvt/dianying.html"));
        ok("isSlugDirCategory 不误收详情 /bspvd/548165.html（第二段是数字）",
                !HtmlTemplates.INSTANCE.isSlugDirCategory("/bspvd/548165.html"));
        ok("isSlugDirCategory 不误收三段路径 /actor/type/id/mingxing.html",
                !HtmlTemplates.INSTANCE.isSlugDirCategory("/actor/type/id/mingxing.html"));
        ok("isSlugDirCategory 不误收 maccms 筛选页（连续连字符）",
                !HtmlTemplates.INSTANCE.isSlugDirCategory("/bspvs/dianying-----------.html"));
        ok("isSlugDirCategory 不误收单段 /actor.html", !HtmlTemplates.INSTANCE.isSlugDirCategory("/actor.html"));

        // ---------------- B. 分类 ----------------
        banner("B. categoriesFrom(首页)");
        Document home = doc(dir + "home.html");
        int catCount = 0;
        boolean hasMovie = false, dupName = false;
        StringBuilder names = new StringBuilder();
        try {
            HtmlAdapter ad = new HtmlAdapter(new SiteConfig("k", "n", BASE, "", "html", "", "", 0L));
            List<Category> cs = ad.categoriesFrom(home);
            catCount = cs.size();
            System.out.println("  分类数 = " + cs.size());
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (Category c : cs) {
                System.out.println("    · " + c.getName() + "  ->  " + c.getId());
                if (!seen.add(c.getName())) dupName = true;
                if ("电影".equals(c.getName())) hasMovie = true;
                names.append(c.getName()).append('/');
            }
        } catch (Throwable t) {
            System.out.println("  !! " + t.getClass().getName() + ": " + t.getMessage());
        }
        ok("分类数 >= 10（修复前 = 0）", catCount >= 10);
        ok("含「电影」分类", hasMovie);
        ok("分类名不重复", !dupName);
        ok("未把「演员」当分类", !names.toString().contains("演员"));

        // ---------------- C. 列表解析 ----------------
        banner("C. parseList(分类页 /bspvt/dianying.html)");
        List<VideoItem> items = HtmlExtractor.INSTANCE.parseList(doc(dir + "cat.html"), BASE, false);
        System.out.println("  条数 = " + items.size());
        for (int i = 0; i < Math.min(6, items.size()); i++) {
            VideoItem v = items.get(i);
            System.out.println("    · id=" + v.getId() + " name=" + v.getName()
                    + " pic=" + shortUrl(v.getPic()) + " remarks=" + v.getRemarks());
        }

        banner("C2. parseList(列表页 /bspvs/dianying-----------.html)");
        List<VideoItem> items2 = HtmlExtractor.INSTANCE.parseList(doc(dir + "list.html"), BASE, false);
        System.out.println("  条数 = " + items2.size());
        for (int i = 0; i < Math.min(4, items2.size()); i++) {
            VideoItem v = items2.get(i);
            System.out.println("    · id=" + v.getId() + " name=" + v.getName());
        }

        // ---------------- D. 详情页选集 ----------------
        banner("D. parseGroups(详情页 /bspvd/548165.html)");
        List<PlayGroup> dg = HtmlExtractor.INSTANCE.parseGroups(doc(dir + "detail.html"), BASE);
        dump(dg);
        ok("详情页 4 条线路", dg.size() == 4);
        if (!dg.isEmpty()) {
            ok("线路名无相邻重复词（修复前 = 云播四 云播四 云播四）", !hasRepeatWord(dg.get(0).getName()));
            boolean epOk = true;
            for (PlayGroup g : dg) if (g.getEpisodes().size() != 14) epOk = false;
            ok("每条线路 14 集", epOk);
            String u0 = dg.get(0).getEpisodes().get(0).getUrl();
            ok("分集 URL 是 /bspvp/{id}-{sid}-{nid}.html",
                    u0.contains("/bspvp/548165-") && u0.endsWith(".html"));
        }

        // ---------------- E. 播放页选集 ----------------
        banner("E. parseGroups(播放页 /bspvp/548165-1-1.html)");
        List<PlayGroup> pg = HtmlExtractor.INSTANCE.parseGroups(doc(dir + "play.html"), BASE);
        dump(pg);
        ok("播放页也能解析出 4 条线路 × 14 集",
                pg.size() == 4 && pg.get(0).getEpisodes().size() == 14);

        // ---------------- F. 详情页模板学习 ----------------
        banner("F. detailTplHint");
        try {
            System.out.println("  cat  -> " + HtmlExtractor.INSTANCE.detailTplHint(doc(dir + "cat.html"), BASE, false));
        } catch (Throwable t) {
            System.out.println("  !! " + t);
        }

        // ---------------- G. 首页列表（分类为空时 UI 的兜底路径） ----------------
        banner("G. parseList(首页) —— vodIsCategory 两种取值都试");
        for (boolean vic : new boolean[]{false, true}) {
            List<VideoItem> hi = HtmlExtractor.INSTANCE.parseList(home, BASE, vic);
            System.out.println("  vodIsCategory=" + vic + "  ->  " + hi.size() + " 条");
            for (int i = 0; i < Math.min(3, hi.size()); i++) {
                System.out.println("      · id=" + hi.get(i).getId() + " name=" + hi.get(i).getName());
            }
        }

        // ---------------- H. 导航容器里到底有哪些链接（分类候选盘点） ----------------
        banner("H. 导航容器内的链接（前 30 条）");
        int n = 0;
        for (String sel : new String[]{".header-nav", ".navlist", "nav", ".nav", "header"}) {
            org.jsoup.select.Elements as = home.select(sel + " a[href]");
            System.out.println("  [" + sel + "] " + as.size() + " 条");
            for (org.jsoup.nodes.Element a : as) {
                if (n++ > 30) break;
                String h = a.attr("href").trim();
                System.out.println("      " + h + "   「" + a.text().trim() + "」"
                        + "  slugCat=" + HtmlTemplates.INSTANCE.isSlugCategory(h));
            }
        }

        System.out.println();
        System.out.println((fail == 0 ? "ALL CHECKS PASSED" : "FAILURES = " + fail)
                + "  (pass=" + pass + " fail=" + fail + ")");
    }

    // ------------------------------------------------------------------ 工具

    static void dump(List<PlayGroup> gs) {
        System.out.println("  线路数 = " + gs.size());
        for (PlayGroup g : gs) {
            System.out.println("    · 「" + g.getName() + "」 " + g.getEpisodes().size() + " 集");
            for (int i = 0; i < Math.min(3, g.getEpisodes().size()); i++) {
                System.out.println("         " + g.getEpisodes().get(i).getName()
                        + "  ->  " + g.getEpisodes().get(i).getUrl());
            }
            if (g.getEpisodes().size() > 3) System.out.println("         …");
        }
    }

    static Document doc(String path) throws Exception {
        String html = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        return Jsoup.parse(html, BASE);
    }

    static void p(String label, Object v) {
        System.out.println("  " + label + "  =  " + v);
    }

    static String shortUrl(String u) {
        if (u == null) return "null";
        return u.length() <= 60 ? u : u.substring(0, 57) + "...";
    }

    /** 串里有没有「相邻重复的词」（`云播四 云播四 云播四`） */
    static boolean hasRepeatWord(String t) {
        String[] ps = t.trim().split("\\s+");
        for (int i = 1; i < ps.length; i++) if (ps[i].equals(ps[i - 1])) return true;
        return false;
    }

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(72));
        System.out.println(s);
        System.out.println("=".repeat(72));
    }

    static void ok(String what, boolean cond) {
        if (cond) pass++; else fail++;
        System.out.println("   [" + (cond ? "PASS" : "FAIL") + "] " + what);
    }
}
