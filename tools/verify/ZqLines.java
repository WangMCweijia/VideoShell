import com.videoshell.data.model.Episode;
import com.videoshell.data.model.PlayGroup;
import com.videoshell.data.site.HtmlExtractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 离线校验：**多条播放源被合成一条线路**（v1.0.28 修复）。
 *
 * 用户现象：点进 zqkhmy 的剧集，分集列表把「蓝光2k / 至臻4k / 自营t / 自营y / 自营r」等多条源的
 * 分集**缝成一个列表**（该片 924 集 = 181+180+21+181+181+180，站点真实只有 6 条线）。
 * 之前别的站源出现过同类问题（骚火 52 = 26+26），所以这不是某一站的事。
 *
 * 机理：老实现只在**容器的直接子层**找分块。zqkhmy 的块外面还裹了一层
 * （`.anthology` → `.anthology-list` → 6 个 `.anthology-list-box`），
 * 所有锚点在第 1 层归到同一个 `.anthology-list` ⇒ 只有 1 堆 ⇒ 直接放弃拆分。
 *
 * 修法两条独立证据：
 *  ① DOM 逐层下探（第 1 层不行看第 2、3 层，要求 ≥2 堆且每堆 ≥2 集）；
 *  ② 地址形状兜底：maccms 的 `/{目录}/{影片}-{线路}-{集}.html`，同一列表里线路段必然唯一，
 *     一组里出现 ≥2 个线路段 ⇒ 必然是缝起来的（DOM 分不开时还能靠它）。
 *
 * 断言分三块：
 *  A. 真实夹具（`_zq/zq_detail_*.html`，ZqFetch 抓的线上页面）——线路数/集数/sid 必须一致；
 *  B. 合成形状 —— 老行为（骚火 2 块）不许回归，正常主题不许误拆；
 *  C. 兜底判据自身的边界 —— 认不出形状时必须**整体放弃**，不许拆出一堆单集线路。
 */
public class ZqLines {

    static final String BASE = "https://www.zqkhmy.com";

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what);
        if (cond) pass++; else fail++;
    }

    static void eq(String what, Object want, Object got) {
        ok(what + "（期望 " + want + "，实际 " + got + "）", String.valueOf(want).equals(String.valueOf(got)));
    }

    static List<PlayGroup> parse(String html) {
        return HtmlExtractor.INSTANCE.parseGroups(Jsoup.parse(html, BASE), BASE);
    }

    static int epCount(List<PlayGroup> gs) {
        int n = 0;
        for (PlayGroup g : gs) n += g.getEpisodes().size();
        return n;
    }

    static String names(List<PlayGroup> gs) {
        List<String> l = new ArrayList<>();
        for (PlayGroup g : gs) l.add(g.getName() + "×" + g.getEpisodes().size());
        return String.join(" | ", l);
    }

    public static void main(String[] args) throws Exception {
        sectionA();
        sectionB();
        sectionC();
        System.out.println();
        System.out.println("==== runzqlines  pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ A. 真实页面

    static void sectionA() throws Exception {
        System.out.println("=== A. 真实页面夹具（_zq/zq_detail_*.html）===");
        File dir = new File("_zq");
        List<File> files = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all != null) {
            for (File f : all) if (f.getName().startsWith("zq_detail_")) files.add(f);
        }
        Collections.sort(files);
        if (files.isEmpty()) {
            System.out.println("  (没有夹具，跳过 —— 先跑 runzqfetch.py)");
            return;
        }
        for (File f : files) {
            String html = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(html, BASE);

            // 站点自己的真相：只数 maccms 播放页形状 `/{目录}/{影片}-{线路}-{集}.html`
            // （不能数 isEpisodeLink —— 它的宽松判据把 `/user/plays.html` 这种含 "play" 的
            //  导航链接也算作分集，数出来会比页面真实分集多 1）
            LinkedHashMap<String, Integer> bySid = new LinkedHashMap<>();
            int anchors = 0;
            java.util.regex.Pattern shape = java.util.regex.Pattern
                    .compile("/[A-Za-z][\\w_\\-]*/(\\d+)-(\\d+)-(\\d+)\\.html");
            for (org.jsoup.nodes.Element a : doc.select("a[href]")) {
                String h = a.attr("href").trim();
                java.util.regex.Matcher m = shape.matcher(h);
                if (!m.find()) continue;
                anchors++;
                String sid = m.group(2);
                bySid.merge(sid, 1, Integer::sum);
            }
            int realSid = bySid.size();

            List<PlayGroup> gs = parse(html);
            System.out.println("  " + f.getName() + " 锚点 " + anchors + " / sid " + bySid
                    + " → " + names(gs));
            eq("  " + f.getName() + " 线路数 == 站点线路数", realSid, gs.size());
            eq("  " + f.getName() + " 一条都不少", anchors, epCount(gs));
            // 每组内部只允许一个线路段（同一条分集列表不可能跨线路）
            boolean single = true;
            for (PlayGroup g : gs) {
                Set<String> sids = new LinkedHashSet<>();
                for (Episode e : g.getEpisodes()) {
                    String[] parts = e.getUrl().split("/")[e.getUrl().split("/").length - 1]
                            .replace(".html", "").split("-");
                    if (parts.length >= 3) sids.add(parts[parts.length - 2]);
                }
                if (sids.size() > 1) single = false;
            }
            ok("  " + f.getName() + " 每条线路内部只有一个 sid", single);
        }
    }

    // ------------------------------------------------------------------ B. 合成形状

    /** zqkhmy 形状：一层包装 + 容器内 tab 栏 + 内层 badge 计数 */
    static String wrapperShape(int lines, int perLine) {
        StringBuilder tab = new StringBuilder();
        String[] tabNames = {"蓝光2k", "至臻4k", "自营t", "自营y", "自营r", "云播"};
        for (int i = 0; i < lines; i++) {
            tab.append("<a class=\"swiper-slide\"><i class=\"fa ds-dianying\"></i>&nbsp;")
               .append(tabNames[i % tabNames.length])
               .append("<span class=\"badge\">").append(perLine).append("</span></a>");
        }
        StringBuilder boxes = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            boxes.append("<div class=\"anthology-list-box none\"><div>")
                 .append("<ul class=\"anthology-list-play size\">");
            for (int k = perLine; k >= 1; k--) {   // 站点是按倒序给的
                boxes.append("<li class=\"box border\"><a class=\"hide\" href=\"/play/20245-")
                     .append(8 + i).append('-').append(k).append(".html\">")
                     .append(k).append("</a></li>");
            }
            boxes.append("</ul></div></div>");
        }
        return "<div class=\"anthology wow fadeInUp top20\">"
                + "<div class=\"anthology-tab nav-swiper\"><div class=\"swiper-wrapper\">"
                + tab + "</div></div>"
                + "<div class=\"anthology-list top20 select-a\">" + boxes + "</div></div>";
    }

    static void sectionB() {
        System.out.println();
        System.out.println("=== B. 合成形状 ===");

        // B1 zqkhmy 形状：3 条源 × 4 集 → 3 组 4 集，名字取容器内 tab 栏（含 &nbsp; 归一）
        List<PlayGroup> g1 = parse(wrapperShape(3, 4));
        eq("B1 一层包装形状：线路数", 3, g1.size());
        eq("B1 集数合计", 12, epCount(g1));
        eq("B1 组名取容器内 tab 栏", "蓝光2k | 至臻4k | 自营t", joinNames(g1));
        ok("B1 badge 计数没混进名字（无 「蓝光2k4」）",
                !joinNames(g1).contains("蓝光2k4") && !joinNames(g1).contains("\u00A0"));

        // B2 骚火形状（老行为）：<ul class="play_list"><li>26</li><li>26</li></ul>
        String shdy = "<div class=\"play_from\"><ul><li>线路1</li><li>线路2</li></ul></div>"
                + "<ul class=\"play_list\"><li>" + flatEps(8, 5) + "</li><li>" + flatEps(9, 5) + "</li></ul>";
        List<PlayGroup> g2 = parse(shdy);
        eq("B2 骚火形状：仍是 2 组", 2, g2.size());
        eq("B2 集数合计", 10, epCount(g2));

        // B3 标准主题「每集一个 li」→ 不许拆
        StringBuilder li = new StringBuilder("<div class=\"stui-content__playlist\">");
        for (int i = 1; i <= 9; i++) li.append("<li><a href=\"/play/100-1-").append(i).append(".html\">第")
                .append(i).append("集</a></li>");
        li.append("</div>");
        List<PlayGroup> g3 = parse(li.toString());
        eq("B3 每集一个 li：不误拆（1 组）", 1, g3.size());
        eq("B3 集数", 9, epCount(g3));

        // B4 平铺列表（锚点直接是容器子元素）→ 不许拆
        StringBuilder flat = new StringBuilder("<div class=\"playlist\">");
        for (int i = 1; i <= 8; i++) flat.append("<a href=\"/play/100-1-").append(i).append(".html\">第")
                .append(i).append("集</a>");
        flat.append("</div>");
        List<PlayGroup> g4 = parse(flat.toString());
        eq("B4 平铺列表：不误拆（1 组）", 1, g4.size());
        eq("B4 集数", 8, epCount(g4));
    }

    static String joinNames(List<PlayGroup> gs) {
        List<String> l = new ArrayList<>();
        for (PlayGroup g : gs) l.add(g.getName());
        return String.join(" | ", l);
    }

    static String flatEps(int line, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++)
            sb.append("<a href=\"/play/100-").append(line).append('-').append(i).append(".html\">第")
              .append(i).append("集</a>");
        return sb.toString();
    }

    // ------------------------------------------------------------------ C. 兜底判据的边界

    static void sectionC() {
        System.out.println();
        System.out.println("=== C. 地址兜底的边界 ===");

        // C1 几条源**交错**摆在同一层（DOM 分不开）→ 靠地址里的线路段拆
        StringBuilder inter = new StringBuilder("<div class=\"playlist interleave\">");
        for (int i = 1; i <= 6; i++) {
            inter.append("<a href=\"/play/100-1-").append(i).append(".html\">A").append(i).append("</a>");
            inter.append("<a href=\"/play/100-2-").append(i).append(".html\">B").append(i).append("</a>");
        }
        inter.append("</div>");
        List<PlayGroup> g1 = parse(inter.toString());
        eq("C1 交错形状：按地址拆成 2 组", 2, g1.size());
        eq("C1 集数合计", 12, epCount(g1));

        // C2 地址里没有线路段（只有 `/play/{id}.html`）→ 必须整体放弃，保持 1 组
        StringBuilder noSid = new StringBuilder("<div class=\"playlist\">");
        for (int i = 1; i <= 6; i++)
            noSid.append("<a href=\"/play/100").append(i).append(".html\">第").append(i).append("集</a>");
        noSid.append("</div>");
        List<PlayGroup> g2 = parse(noSid.toString());
        eq("C2 地址无线路段：不拆（1 组）", 1, g2.size());

        // C3 段序被换过（`{影片}-{集}-{线路}`）→ 判据要自己挑对线段（取「分出来堆更少」那段），
        //    拆成 2 组、每组 6 集，且每组内部最后一段一致。
        StringBuilder swapped = new StringBuilder("<div class=\"playlist swap\">");
        for (int i = 1; i <= 6; i++) {
            swapped.append("<a href=\"/play/100-").append(i).append("-1.html\">A").append(i).append("</a>");
            swapped.append("<a href=\"/play/100-").append(i).append("-2.html\">B").append(i).append("</a>");
        }
        swapped.append("</div>");
        List<PlayGroup> g3 = parse(swapped.toString());
        eq("C3 段序颠倒：仍拆成 2 组（自己挑对线段）", 2, g3.size());
        eq("C3 集数合计", 12, epCount(g3));
        boolean sameTail = g3.size() == 2 && g3.get(0).getEpisodes().size() == 6
                && g3.get(1).getEpisodes().size() == 6;
        if (sameTail) {
            for (PlayGroup g : g3) {
                Set<String> tails = new LinkedHashSet<>();
                for (Episode e : g.getEpisodes()) {
                    String[] seg = e.getUrl().split("/");
                    tails.add(seg[seg.length - 1].replace(".html", "").split("-")[2]);
                }
                if (tails.size() != 1) sameTail = false;
            }
        }
        ok("C3 每组 6 集且组内「线路段」一致", sameTail);

        // C4 单一线路（线路段只有 1 个值）→ 必须整体放弃
        StringBuilder one = new StringBuilder("<div class=\"playlist one\">");
        for (int i = 1; i <= 8; i++)
            one.append("<a href=\"/play/100-7-").append(i).append(".html\">第").append(i).append("集</a>");
        one.append("</div>");
        List<PlayGroup> g4 = parse(one.toString());
        eq("C4 单线路：不拆（1 组）", 1, g4.size());
        eq("C4 集数", 8, epCount(g4));
    }
}
