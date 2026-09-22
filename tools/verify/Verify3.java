import com.videoshell.data.model.*;
import com.videoshell.data.site.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** v1.0.3 离线校验：把现场抓下来的真实页面喂给真实代码，逐条断言。 */
public class Verify3 {

    static int pass = 0, fail = 0;
    static final String CF = "https://www.cupfoxyy.com";
    static final String CZ = "https://czzy.app";
    static String dir;

    public static void main(String[] args) throws Exception {
        dir = args[0];

        banner("① 极速播放源：player_aaaa 里的 \\uXXXX 必须解码（用户报的 404 根因）");
        String bs2 = Media.INSTANCE.extractFromHtml(read("cf_bs_2.html"));
        eq("边水往事 sid=2(bfzym3u8/极速) 抽出地址",
                "https://c1.ddbbffcdn.com/video/bianshuiwangshi/第01集/index.m3u8", bs2);
        ok("不再残留 \\u 转义", bs2 != null && !bs2.contains("\\u"));
        ok("looksLikeMedia", bs2 != null && Media.INSTANCE.looksLikeMedia(bs2));
        eq("边水往事 sid=1(ffm3u8) 抽出地址",
                "https://vip.ffzy-video.com/20240816/706_9c82c714/index.m3u8",
                Media.INSTANCE.extractFromHtml(read("cf_bs_1.html")));
        eq("纯 ASCII 路径不受影响（猪猪侠 sid=1）",
                "https://fengbao12.com/video/zhuzhuxiazhishikongjiyuan/fadec2bec869/index.m3u8",
                Media.INSTANCE.extractFromHtml(read("cf_play_127251_1.html")));
        eq("unescape 反斜杠转义", "a/b\"c", Media.INSTANCE.unescape("a\\/b\\\"c"));
        eq("unescape 代理对", "😀", Media.INSTANCE.unescape("\\uD83D\\uDE00"));
        eq("unescape 未知转义原样保留", "a\\qb", Media.INSTANCE.unescape("a\\qb"));
        eq("unescape 无转义直通", "第01集", Media.INSTANCE.unescape("第01集"));

        banner("② 链接语义：/movie/{id}.html 这类路径要能当影片");
        eq("videoIdOf(/movie/23804.html, false)", "23804",
                HtmlTemplates.INSTANCE.videoIdOf("/movie/23804.html", false));
        eq("videoIdOf(/movie/23804.html, true)", "23804",
                HtmlTemplates.INSTANCE.videoIdOf("/movie/23804.html", true));
        eq("videoIdOf(https://czzy.app/movie/20294.html#comments)", "20294",
                HtmlTemplates.INSTANCE.videoIdOf("https://czzy.app/movie/20294.html#comments", false));
        ok("videoIdOf(/detail/127825.html) 仍然可用",
                "127825".equals(HtmlTemplates.INSTANCE.videoIdOf("/detail/127825.html", true)));
        ok("回归：typeIdOf(/vod/1.html, true) 仍是分类 1",
                "1".equals(HtmlTemplates.INSTANCE.typeIdOf("/vod/1.html", true)));
        ok("回归：/vod/1.html 不是强详情（分类消歧不能破）",
                !HtmlTemplates.INSTANCE.isStrongDetail("/vod/1.html"));
        ok("isDetailSignal(/v_play/xxx.html) 为真",
                HtmlTemplates.INSTANCE.isDetailSignal("/v_play/bXZfMjAyOTQtbm1fMQ==.html"));
        ok("isSlugCategory(/meijutt)", HtmlTemplates.INSTANCE.isSlugCategory("/meijutt"));
        ok("isSlugCategory(/riju)", HtmlTemplates.INSTANCE.isSlugCategory("/riju"));
        ok("!isSlugCategory(/vod/1.html)（有点）",
                !HtmlTemplates.INSTANCE.isSlugCategory("/vod/1.html"));
        ok("!isSlugCategory(/movie/23804.html)",
                !HtmlTemplates.INSTANCE.isSlugCategory("/movie/23804.html"));
        ok("!isSlugCategory(https://t.me/xxx)（外链）",
                !HtmlTemplates.INSTANCE.isSlugCategory("https://t.me/xxx"));
        ok("!isSlugCategory(/2024)（纯数字）",
                !HtmlTemplates.INSTANCE.isSlugCategory("/2024"));
        eq("detailTplFrom 反推模板",
                "https://czzy.app/movie/{id}.html",
                HtmlTemplates.INSTANCE.detailTplFrom("https://czzy.app/movie/23804.html", "23804"));

        SiteConfig cfSite = new SiteConfig("cf", "茶杯狐", CF, "", SiteConfig.MODE_HTML, "", "", 0L);
        SiteConfig czSite = new SiteConfig("cz", "厂长资源", CZ, "", SiteConfig.MODE_HTML, "", "", 0L);
        HtmlAdapter cf = new HtmlAdapter(cfSite);
        HtmlAdapter cz = new HtmlAdapter(czSite);

        banner("③ 茶杯狐回归：分类 / 列表 / 选集 / 播放地址");
        List<Category> cfCats = cf.categoriesFrom(Jsoup.parse(read("cf_home_okhttp.html"), CF));
        eq("首页分类数 = 4", 4, cfCats.size());
        ok("分类都是真实 URL 且带名字",
                cfCats.stream().allMatch(c -> c.getId().startsWith(CF) && !c.getName().isBlank()));
        ok("第一个是 电影(/vod/1.html)",
                cfCats.get(0).getName().equals("电影") && cfCats.get(0).getId().endsWith("/vod/1.html"));
        eq("首页影片数 = 59", 59,
                HtmlExtractor.INSTANCE.parseList(Jsoup.parse(read("cf_home_okhttp.html"), CF), CF, true).size());
        eq("/vod/1.html 影片数 = 85", 85,
                HtmlExtractor.INSTANCE.parseList(Jsoup.parse(read("cf_cat1.html"), CF), CF, true).size());
        eq("/vodshow/id/6.html 影片数 = 24", 24,
                HtmlExtractor.INSTANCE.parseList(Jsoup.parse(read("cf_sub6.html"), CF), CF, true).size());
        eq("详情页学到模板 = /detail/{id}.html",
                CF + "/detail/{id}.html",
                HtmlExtractor.INSTANCE.detailTplHint(Jsoup.parse(read("cf_home_okhttp.html"), CF), CF, true));
        List<PlayGroup> cg = HtmlExtractor.INSTANCE.parseGroups(
                Jsoup.parse(read("cf_detail_127251.html"), CF), CF);
        ok("详情页分集 >= 1 条线路", !cg.isEmpty());
        ok("第一条线路有集数", !cg.isEmpty() && !cg.get(0).getEpisodes().isEmpty());

        banner("④ 厂长资源 czzy.app：分类（目录式）+ 列表（/movie/{id}.html + 懒加载图）");
        List<Category> czCats = cz.categoriesFrom(Jsoup.parse(read("cz_home_okhttp.html"), CZ));
        ok("首页解析出分类 >= 2（实际 " + czCats.size() + "）", czCats.size() >= 2);
        ok("含 美剧 -> /meijutt",
                czCats.stream().anyMatch(c -> c.getName().equals("美剧") && c.getId().endsWith("/meijutt")));
        ok("含 日剧 -> /riju",
                czCats.stream().anyMatch(c -> c.getName().equals("日剧") && c.getId().endsWith("/riju")));
        ok("过滤掉「关于本站-公告」",
                czCats.stream().noneMatch(c -> c.getName().contains("公告")));
        ok("过滤掉「求片须知」",
                czCats.stream().noneMatch(c -> c.getName().contains("须知")));
        ok("过滤掉外链 t.me",
                czCats.stream().noneMatch(c -> c.getId().contains("t.me")));
        ok("过滤掉「分类筛选」(/movie_bt)",
                czCats.stream().noneMatch(c -> c.getName().contains("筛选")));
        ok("分类都是同域绝对 URL",
                czCats.stream().allMatch(c -> c.getId().startsWith(CZ)));
        System.out.println("     分类: " + names(czCats));

        List<VideoItem> czHome = HtmlExtractor.INSTANCE.parseList(
                Jsoup.parse(read("cz_home_okhttp.html"), CZ), CZ, false);
        ok("首页影片数 > 8（实际 " + czHome.size() + "）", czHome.size() > 8);
        ok("字段完整", czHome.stream().allMatch(v -> !v.getId().isBlank() && !v.getName().isBlank()));
        ok("海报不是占位图 blank.gif", czHome.stream().allMatch(v -> !v.getPic().contains("blank.gif")));
        ok("海报都是 http 绝对地址", czHome.stream().allMatch(v -> v.getPic().startsWith("http")));
        System.out.println("     首条: " + show(czHome.isEmpty() ? null : czHome.get(0)));

        List<VideoItem> czCat = HtmlExtractor.INSTANCE.parseList(
                Jsoup.parse(read("cz_cat_meijutt.html"), CZ), CZ, false);
        ok("/meijutt 分类页影片数 > 8（实际 " + czCat.size() + "）", czCat.size() > 8);
        ok("/meijutt 海报不是占位图", czCat.stream().allMatch(v -> !v.getPic().contains("blank.gif")));
        System.out.println("     首条: " + show(czCat.isEmpty() ? null : czCat.get(0)));
        ok("/meijutt 有备注（全8集 之类）",
                czCat.stream().anyMatch(v -> v.getRemarks().contains("集")));

        eq("分类页学到模板", CZ + "/movie/{id}.html",
                HtmlExtractor.INSTANCE.detailTplHint(Jsoup.parse(read("cz_cat_meijutt.html"), CZ), CZ, false));

        List<PlayGroup> czg = HtmlExtractor.INSTANCE.parseGroups(
                Jsoup.parse(read("cz_detail.html"), CZ), CZ);
        ok("详情页解析出线路（实际 " + czg.size() + "）", !czg.isEmpty());
        if (!czg.isEmpty()) {
            ok("线路有集数（实际 " + czg.get(0).getEpisodes().size() + "）",
                    !czg.get(0).getEpisodes().isEmpty());
            ok("集名是 1080P-1 / 1080P-2",
                    czg.get(0).getEpisodes().stream().anyMatch(e -> e.getName().contains("1080P")));
            ok("集地址是 /v_play/",
                    czg.get(0).getEpisodes().get(0).getUrl().contains("/v_play/"));
            System.out.println("     线路: " + czg.get(0).getName()
                    + " -> " + epNames(czg.get(0)));
        }

        banner("⑤ 安全网：详情页判据认不出时，/vod/{id}.html 仍要能当分类");
        // 把首页里所有 /detail/ 抹掉 —— 模拟"站点详情 URL 花样太怪、判据没认出来"
        String noDetail = read("cf_home_okhttp.html").replace("/detail/", "/xdetail/");
        HtmlAdapter cf2 = new HtmlAdapter(cfSite);
        List<Category> altCats = cf2.categoriesFrom(Jsoup.parse(noDetail, CF));
        ok("强行当分类也扫得出 >= 2（实际 " + altCats.size() + "）", altCats.size() >= 2);
        ok("并且都是短名（没把影片名当分类）",
                altCats.stream().allMatch(c -> c.getName().length() <= 6
                        && c.getName().chars().noneMatch(Character::isDigit)));
        System.out.println("     分类: " + names(altCats));

        System.out.println("\n" + "=".repeat(72));
        System.out.println(fail == 0 ? ("ALL CHECKS PASSED  (pass=" + pass + ")")
                : ("FAILED  pass=" + pass + " fail=" + fail));
        System.out.println("=".repeat(72));
        if (fail != 0) System.exit(1);
    }

    static String names(List<Category> l) {
        StringBuilder sb = new StringBuilder();
        for (Category c : l) sb.append(c.getName()).append(' ');
        return sb.toString().trim();
    }

    static String epNames(PlayGroup g) {
        StringBuilder sb = new StringBuilder();
        for (Episode e : g.getEpisodes()) sb.append(e.getName()).append(' ');
        return sb.toString().trim();
    }

    static String show(VideoItem v) {
        return v == null ? "(null)" : (v.getId() + " | " + v.getName() + " | " + v.getRemarks()
                + " | " + v.getPic());
    }

    static String read(String n) throws Exception {
        return new String(Files.readAllBytes(Paths.get(dir, n)), StandardCharsets.UTF_8);
    }

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(72));
        System.out.println(s);
        System.out.println("=".repeat(72));
    }

    static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + what);
        } else {
            fail++;
            System.out.println("  [FAIL] " + what);
        }
    }

    static void eq(String what, Object expect, Object actual) {
        boolean c = expect == null ? actual == null : expect.equals(actual);
        if (c) {
            pass++;
            System.out.println("  [PASS] " + what);
        } else {
            fail++;
            System.out.println("  [FAIL] " + what);
            System.out.println("         期望: " + expect);
            System.out.println("         实际: " + actual);
        }
    }
}
