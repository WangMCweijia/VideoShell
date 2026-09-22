import com.videoshell.data.model.PlayGroup;
import com.videoshell.data.site.HtmlExtractor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * v1.0.5 断言：区分「线路按钮」与「分集」。
 *
 * 用真实抓下来的两个详情页 + 两个合成样本，验证：
 *  - 厂长资源那类「一个容器里全是线路按钮」的页面 -> 每条线路一组、各 1 集；
 *  - 常规 maccms「第01集/第02集…」的页面 -> 仍然是一条线路、多个分集（不能回归）。
 */
public class LineFix {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        String samples = args.length > 0 ? args[0] : "samples/s5";

        // ---------- 合成负例：常规分集列表不能被拆成线路 ----------
        banner("A. 常规 maccms 分集列表（必须保持 1 条线路 × 3 集）");
        List<PlayGroup> a = groups("<div class=\"play-list\">"
                + "<a href=\"/play/123-1-1.html\">第01集</a>"
                + "<a href=\"/play/123-1-2.html\">第02集</a>"
                + "<a href=\"/play/123-1-3.html\">第03集</a>"
                + "</div>");
        dump(a);
        ok("线路数 = 1", a.size() == 1);
        if (a.size() == 1) {
            ok("集数 = 3", a.get(0).getEpisodes().size() == 3);
            ok("首集名 = 第01集", "第01集".equals(a.get(0).getEpisodes().get(0).getName()));
        }

        // ---------- 合成正例：同名线路按钮 ----------
        banner("B. 同名线路按钮（必须拆成 2 条线路 × 各 1 集）");
        List<PlayGroup> b = groups("<div class=\"paly_list_btn\">"
                + "<a href=\"/v_play/bXZfODQ5LW5tXzE=.html\">线路1080P</a>"
                + "<a href=\"/v_play/bXZfODQ5LW5tXzI=.html\">线路1080P</a>"
                + "</div>");
        dump(b);
        ok("线路数 = 2", b.size() == 2);
        if (b.size() == 2) {
            ok("每条 1 集", b.get(0).getEpisodes().size() == 1 && b.get(1).getEpisodes().size() == 1);
            ok("名字去重 = 线路1080P / 线路1080P (2)",
                    "线路1080P".equals(b.get(0).getName())
                            && "线路1080P (2)".equals(b.get(1).getName()));
            ok("两条线路指向不同 URL",
                    !b.get(0).getEpisodes().get(0).getUrl()
                            .equals(b.get(1).getEpisodes().get(0).getUrl()));
        }

        // ---------- 合成正例：不同清晰度标签 ----------
        banner("C. 不同清晰度标签（必须拆成 2 条线路）");
        List<PlayGroup> c = groups("<div class=\"paly_list_btn\">"
                + "<a href=\"/v_play/a.html\">线路1080P</a>"
                + "<a href=\"/v_play/b.html\">线路4K</a>"
                + "</div>");
        dump(c);
        ok("线路数 = 2 且各 1 集", c.size() == 2
                && c.get(0).getEpisodes().size() == 1 && c.get(1).getEpisodes().size() == 1);

        // ---------- 真实样本：厂长资源 ----------
        banner("D. 真实样本 czzy.app/movie/849.html（肖申克的救赎）");
        List<PlayGroup> d = real(new File(samples, "cz_detail.html.html"), "https://czzy.app/movie/849.html");
        dump(d);
        ok("线路数 = 2", d.size() == 2);
        ok("每条 1 集（电影不该显示成 2 集）",
                d.stream().allMatch(g -> g.getEpisodes().size() == 1));
        ok("线路名不含分隔符 | / ｜",
                d.stream().noneMatch(g -> g.getName().contains("|") || g.getName().contains("｜")));
        ok("线路名非空", d.stream().allMatch(g -> !g.getName().isBlank()));

        // ---------- 真实样本：茶杯狐（回归） ----------
        banner("E. 真实样本 cupfoxyy.com/detail/127866.html（回归：必须仍是 2 条线路）");
        List<PlayGroup> e = real(new File(samples, "cf_detail.html.html"),
                "https://www.cupfoxyy.com/detail/127866.html");
        dump(e);
        ok("线路数 >= 2", e.size() >= 2);
        ok("每线至少 1 集", e.stream().allMatch(g -> !g.getEpisodes().isEmpty()));
        ok("集数 != 线路数错乱（每线 <= 3 集）",
                e.stream().allMatch(g -> g.getEpisodes().size() <= 3));

        System.out.println();
        System.out.println(fail == 0 ? "ALL CHECKS PASSED  (pass=" + pass + ")"
                : "CHECKS FAILED  (pass=" + pass + ", fail=" + fail + ")");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------

    static List<PlayGroup> groups(String bodyHtml) {
        Document doc = Jsoup.parse("<html><body>" + bodyHtml + "</body></html>", "https://demo.test/");
        return HtmlExtractor.INSTANCE.parseGroups(doc, "https://demo.test/");
    }

    static List<PlayGroup> real(File f, String base) throws Exception {
        if (!f.isFile()) {
            System.out.println("  [跳过] 样本不存在：" + f);
            return List.of();
        }
        String html = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(html, base);
        return HtmlExtractor.INSTANCE.parseGroups(doc, base);
    }

    static void dump(List<PlayGroup> gs) {
        System.out.println("  线路 " + gs.size() + " 条");
        for (PlayGroup g : gs) {
            System.out.println("    · [" + g.getName() + "]  " + g.getEpisodes().size() + " 集");
            g.getEpisodes().stream().limit(3).forEach(ep ->
                    System.out.println("         - [" + ep.getName() + "] " + ep.getUrl()));
        }
    }

    static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + what);
        } else {
            fail++;
            System.out.println("  FAIL  " + what);
        }
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("---------- " + s + " ----------");
    }
}
