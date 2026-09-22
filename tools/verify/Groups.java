import com.videoshell.data.model.Episode;
import com.videoshell.data.model.PlayGroup;
import com.videoshell.data.site.HtmlExtractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.List;

/**
 * 离线校验：**播放源线路的分组**（v1.0.18 第三个修复）。
 *
 * 用户现象（截图）：有的站详情页里「线路1」「线路2」没分成两颗 chip，
 * 而是融成一颗「线路1线路2」，选集 52 集 = 26 + 26 —— 两条线的分集被并进了同一个组。
 *
 * 机理（两个叠加）：
 *  1. 有的站几条线路共用一个外层包装（外层里并排两块播放列表），而两块列表都命中
 *     CONTAINER_SELECTORS。旧的去重规则**留外层弃内层** ⇒ 一个组收走两块的全部链接；
 *     组名从祖先链上的 tab 栏取，`.text()` 把相邻标签**不加空格连起来** ⇒「线路1线路2」。
 *  2. 修法：外层被内层候选的并集**全覆盖**时弃外层留内层（盖不全才留外层，别丢集）；
 *     组名撞上「标签栏整段文本」就跳过，落回「线路 N」；同名组再加序号兜底。
 *
 * 三种页面结构，全部不联网：
 *  A. 融合 bug 结构（外层包装 + 无 # 锚点的 tab 栏）→ 必须拆成 2 组 26 集；
 *  B. 标准 maccms（href="#playlistN" 映射）→ 行为不变，组名照旧；
 *  C. 外层比内层**多**分集（内层盖不全）→ 仍按外层算，一条不少。
 */
public class Groups {

    static final String BASE = "https://example.com";

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what);
        if (cond) pass++; else fail++;
    }

    /** n 集的 maccms 风格链接，line 用于把两条线的 URL 区分开 */
    static String eps(int line, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append("<a href=\"/play/100-").append(line).append('-').append(i)
              .append(".html\">").append(i).append("</a>");
        }
        return sb.toString();
    }

    static List<PlayGroup> parse(String html) {
        Document doc = Jsoup.parse(html, BASE);
        return HtmlExtractor.INSTANCE.parseGroups(doc, BASE);
    }

    static int epCount(List<PlayGroup> gs) {
        int n = 0;
        for (PlayGroup g : gs) n += g.getEpisodes().size();
        return n;
    }

    public static void main(String[] args) {
        System.out.println("==== 播放源线路分组 ====");
        System.out.println();

        // ---------- A. 融合 bug 结构 ----------
        System.out.println("-- A. 两条线共用一个外层包装（用户报的现象）--");
        String aHtml = "<html><body><div class=\"module\">" +
                "<div class=\"play-source-tab\">" +
                "<a href=\"javascript:;\">线路1</a><a href=\"javascript:;\">线路2</a>" +
                "</div>" +
                "<div class=\"play-list-content\">" +
                "<div class=\"playlist\">" + eps(1, 26) + "</div>" +
                "<div class=\"playlist\">" + eps(2, 26) + "</div>" +
                "</div></div></body></html>";
        List<PlayGroup> a = parse(aHtml);
        System.out.println("   组数 " + a.size() + "，总集数 " + epCount(a)
                + "，名字 " + names(a));
        ok("拆成 2 组（而不是 1 组）", a.size() == 2);
        ok("每组 26 集（而不是 52 集混在一起）",
                a.size() == 2 && a.get(0).getEpisodes().size() == 26
                        && a.get(1).getEpisodes().size() == 26);
        ok("总集数不丢（52）", epCount(a) == 52);
        ok("两条线的分集各自成组（URL 前缀 -1- / -2- 不混）",
                a.size() == 2 && allFrom(a.get(0), "-1-") && allFrom(a.get(1), "-2-"));
        ok("★ 组名不再是「线路1线路2」（标签栏整段文本被跳过）",
                a.size() == 2 && !names(a).contains("线路1线路2"));
        ok("两个组名互不相同（同名会加序号，UI 上分得开）",
                a.size() == 2 && !a.get(0).getName().equals(a.get(1).getName()));

        // ---------- B. 标准 maccms tab 映射（回归保护） ----------
        System.out.println();
        System.out.println("-- B. 标准 maccms（href=#playlistN 映射，行为必须不变）--");
        String bHtml = "<html><body><div class=\"module\">" +
                "<ul class=\"nav nav-tabs\">" +
                "<li><a href=\"#playlist1\">极速播放</a></li>" +
                "<li><a href=\"#playlist2\">红牛云播</a></li>" +
                "</ul>" +
                "<div id=\"playlist1\" class=\"tab-pane\">" + eps(1, 26) + "</div>" +
                "<div id=\"playlist2\" class=\"tab-pane\">" + eps(2, 26) + "</div>" +
                "</div></body></html>";
        List<PlayGroup> b = parse(bHtml);
        System.out.println("   组数 " + b.size() + "，名字 " + names(b));
        ok("仍是 2 组", b.size() == 2);
        ok("组名仍是 tab 上的「极速播放 / 红牛云播」",
                b.size() == 2 && "极速播放".equals(b.get(0).getName())
                        && "红牛云播".equals(b.get(1).getName()));
        ok("每组仍是 26 集", epCount(b) == 52);

        // ---------- C. 外层比内层多分集（内层盖不全 ⇒ 留外层，别丢集） ----------
        System.out.println();
        System.out.println("-- C. 内层盖不全外层（必须仍按外层算，不能丢集）--");
        String cHtml = "<html><body><div class=\"play-list-content\">" +
                "<a href=\"/play/100-9-1.html\">特别篇</a>" +
                "<div class=\"playlist\">" + eps(1, 26) + "</div>" +
                "</div></body></html>";
        List<PlayGroup> c = parse(cHtml);
        System.out.println("   组数 " + c.size() + "，总集数 " + epCount(c));
        ok("仍是 1 组（外层没有被拆掉）", c.size() == 1);
        ok("27 集一条不少（26 + 散落的 1 集）", epCount(c) == 27);

        // ---------- D. 骚火电影真实形状：ul>li，每条源一个 li ----------
        System.out.println();
        System.out.println("-- D. 骚火形状：<ul class=play_list><li>源1×26</li><li>源2×26</li></ul> --");
        String dHtml = "<html><body><section class=\"grid_box\">" +
                "<div class=\"play_from\"><span>播放源</span>" +
                "<ul class=\"from_list\"><li>线路1</li><li>线路2</li></ul></div>" +
                "<ul class=\"play_list\"><li class=\"current\">" + eps(1, 26) + "</li>" +
                "<li>" + eps(2, 26) + "</li></ul>" +
                "</section></body></html>";
        List<PlayGroup> d = parse(dHtml);
        System.out.println("   组数 " + d.size() + "，总集数 " + epCount(d)
                + "，名字 " + names(d));
        ok("拆成 2 组（不再 1 组 52 集融合）", d.size() == 2);
        ok("每组 26 集，总集数 52", epCount(d) == 52);
        ok("两条源各自成组（-1- / -2- 不混）",
                d.size() == 2 && allFrom(d.get(0), "-1-") && allFrom(d.get(1), "-2-"));
        ok("★ 组名取自线路标签栏（线路1 / 线路2）",
                d.size() == 2 && "线路1".equals(d.get(0).getName())
                        && "线路2".equals(d.get(1).getName()));

        // ---------- E. 负例：正常「每集一个 li」不许拆 ----------
        System.out.println();
        System.out.println("-- E. 负例：每集一个 li（拆了就是过度修正）--");
        StringBuilder eb = new StringBuilder("<html><body><ul class=\"play_list\">");
        for (int i = 1; i <= 26; i++) {
            eb.append("<li><a href=\"/play/100-1-").append(i).append(".html\">")
              .append(i).append("</a></li>");
        }
        eb.append("</ul></body></html>");
        List<PlayGroup> e = parse(eb.toString());
        System.out.println("   组数 " + e.size() + "，总集数 " + epCount(e));
        ok("仍是 1 组", e.size() == 1);
        ok("26 集不少", epCount(e) == 26);

        // ---------- F. 拼合标题：<h3><a>剧名</a>尾注</h3> ----------
        System.out.println();
        System.out.println("-- F. 组名取「剧名」而不是「剧名同类型影片」--");
        String fHtml = "<html><body><div>" +
                "<h3 class=\"title\"><a href=\"/movie/1.html\">交锋</a>同类型影片</h3>" +
                "<ul class=\"play_list\"><li>" + eps(1, 26) + "</li></ul>" +
                "</div></body></html>";
        List<PlayGroup> f = parse(fHtml);
        System.out.println("   组数 " + f.size() + "，名字 " + names(f));
        ok("★ 组名是「交锋」", f.size() == 1 && "交锋".equals(f.get(0).getName()));
        ok("26 集", epCount(f) == 26);

        System.out.println();
        System.out.println("==== Groups PASS=" + pass + " FAIL=" + fail + " ====");
        System.exit(fail == 0 ? 0 : 1);
    }

    static String names(List<PlayGroup> gs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < gs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(gs.get(i).getName());
        }
        return sb.append(']').toString();
    }

    static boolean allFrom(PlayGroup g, String marker) {
        if (g.getEpisodes().isEmpty()) return false;
        for (Episode e : g.getEpisodes()) if (!e.getUrl().contains(marker)) return false;
        return true;
    }
}
