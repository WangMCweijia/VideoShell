import com.videoshell.data.site.HtmlExtractor;
import com.videoshell.data.site.HtmlTemplates;
import com.videoshell.player.HlsPlaylistFixer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * v1.0.2 改动的离线校验：直接调用编译产物（不是复制逻辑）。
 * 覆盖 HLS playlist 规范化 + 分集列表解析。
 */
public class Verify2 {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + "  -> " + detail);
        }
    }

    static String read(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    static int countLinesStart(String[] lines, String prefix) {
        int n = 0;
        for (String l : lines) if (l.trim().startsWith(prefix)) n++;
        return n;
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        String samples = args.length > 1 ? args[1] : dir;

        // ============================ 1. HLS playlist 规范化 =================
        System.out.println("== 1. HLS playlist 规范化（极速播放源实测样本）==");
        String raw = read(samples + "/_bf.m3u8");
        String url = "https://bfeng11.com/video/zaijianheise/195098e2e23e/index.m3u8";
        String fixed = HlsPlaylistFixer.INSTANCE.fix(raw, url);

        String[] rawLines = raw.split("\n");
        String[] fxLines = fixed.split("\n");

        int rawSegs = 0, rawAds = 0;
        for (String l : rawLines) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            rawSegs++;
            if (t.contains("/adjump/")) rawAds++;
        }
        int fxSegs = 0, fxAds = 0, fxRel = 0;
        for (String l : fxLines) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            fxSegs++;
            if (t.contains("/adjump/")) fxAds++;
            if (!t.startsWith("http")) fxRel++;
        }

        System.out.println("  原：分片 " + rawSegs + "（其中广告 " + rawAds + "），TARGETDURATION="
                + (raw.contains("#EXT-X-TARGETDURATION:2") ? "2" : "?"));
        System.out.println("  修：分片 " + fxSegs + "，广告 " + fxAds + "，相对路径 " + fxRel
                + "，TARGETDURATION=" + (fixed.contains("#EXT-X-TARGETDURATION:3") ? "3" : "?"));

        check("剔除全部广告分片", fxAds == 0, "剩余 " + fxAds);
        check("广告分片数量与原文一致地消失", fxSegs == rawSegs - rawAds,
                fxSegs + " != " + (rawSegs - rawAds));
        check("分片路径全部绝对化", fxRel == 0, "仍有 " + fxRel + " 个相对路径");

        // 合规性：TARGETDURATION 必须 >= 实际最大分片时长（原文件声明 2 但广告分片有 3s，是违规的）
        double maxGap = 0;
        for (String l : fxLines) {
            String t = l.trim();
            if (t.startsWith("#EXTINF:")) {
                try {
                    maxGap = Math.max(maxGap, Double.parseDouble(t.substring(8).split(",")[0].trim()));
                } catch (Exception ignored) { }
            }
        }
        int tdOut = -1;
        java.util.regex.Matcher tm =
                java.util.regex.Pattern.compile("#EXT-X-TARGETDURATION:(\\d+)").matcher(fixed);
        if (tm.find()) tdOut = Integer.parseInt(tm.group(1));
        check("TARGETDURATION >= ceil(实际最大分片 " + maxGap + ")",
                tdOut >= (int) Math.ceil(maxGap), "td=" + tdOut);
        check("TARGETDURATION 修正为 2（正片最大 1.64s）", tdOut == 2, "td=" + tdOut);
        check("#EXTM3U 只出现一次", count(fixed, "#EXTM3U") == 1, "" + count(fixed, "#EXTM3U"));
        check("不再含 DISCONTINUITY", !fixed.contains("#EXT-X-DISCONTINUITY"), "");
        check("补上 ENDLIST", fixed.contains("#EXT-X-ENDLIST"), "");
        check("首行就是 #EXTM3U", fxLines[0].trim().equals("#EXTM3U"), fxLines[0]);
        check("无 BOM", fixed.charAt(0) == '#', "");
        check("保留 VOD 类型", fixed.contains("#EXT-X-PLAYLIST-TYPE:VOD"), "");

        // master playlist 必须原样返回
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000,RESOLUTION=1920x1080\n3000k/hls/mixed.m3u8\n";
        check("master playlist 原样返回", HlsPlaylistFixer.INSTANCE.fix(master, "https://x/a/index.m3u8").equals(master), "");

        // 直播流（没有 VOD 标签）不该被补 ENDLIST
        String live = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:9\n#EXTINF:1,\n0.ts\n#EXTINF:1,\n1.ts\n";
        String fxLive = HlsPlaylistFixer.INSTANCE.fix(live, "https://x/a/index.m3u8");
        check("直播流不补 ENDLIST", !fxLive.contains("#EXT-X-ENDLIST"), fxLive.replace("\n", "|"));
        check("直播流保留 MEDIA-SEQUENCE", fxLive.contains("#EXT-X-MEDIA-SEQUENCE:9"), "");

        // 相对/根路径绝对化
        String rel = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:1,\nseg1.ts\n#EXTINF:1,\n/sub/seg2.ts\n#EXT-X-ENDLIST\n";
        String fxRel2 = HlsPlaylistFixer.INSTANCE.fix(rel, "https://h.com/a/b/index.m3u8");
        check("同级分片绝对化", fxRel2.contains("https://h.com/a/b/seg1.ts"), fxRel2.replace("\n", "|"));
        check("根路径分片绝对化", fxRel2.contains("https://h.com/sub/seg2.ts"), fxRel2.replace("\n", "|"));

        // ============================ 2. 分集列表 ============================
        System.out.println("== 2. 分集列表解析（真实页面）==");
        String series = read(samples + "/_series.html");
        Document ds = Jsoup.parse(series, "https://www.cupfoxyy.com/");
        List<?> groups = HtmlExtractor.INSTANCE.parseGroups(ds, "https://www.cupfoxyy.com/");
        System.out.println("  剧集详情 -> " + groups.size() + " 条线路");
        int total = 0;
        for (Object g : groups) {
            com.videoshell.data.model.PlayGroup pg = (com.videoshell.data.model.PlayGroup) g;
            System.out.println("     " + pg.getName() + " : " + pg.getEpisodes().size() + " 集");
            total += pg.getEpisodes().size();
        }
        check("剧集详情解析出 3 条线路", groups.size() == 3, "" + groups.size());
        check("线路总集数 >= 33", total >= 33, "" + total);
        if (groups.size() > 0) {
            com.videoshell.data.model.PlayGroup g0 =
                    (com.videoshell.data.model.PlayGroup) groups.get(0);
            check("首线路名为「极速播放」（按 tab href 映射）",
                    g0.getName().contains("极速"), g0.getName());
        }

        String movie = read(samples + "/_p11061a.html");
        Document dm = Jsoup.parse(movie, "https://www.cupfoxyy.com/");
        List<?> mg = HtmlExtractor.INSTANCE.parseGroups(dm, "https://www.cupfoxyy.com/");
        System.out.println("  电影详情 -> " + mg.size() + " 条线路");
        check("电影详情仍能解析线路", mg.size() >= 1, "" + mg.size());

        // ============================ 3. 链接语义 ============================
        System.out.println("== 3. 分集链接判据 ==");
        check("episode: /play/1-2-3.html", HtmlTemplates.INSTANCE.isEpisodeLink("/play/1-2-3.html"), "");
        check("episode: /vodplay/1-2-3.html", HtmlTemplates.INSTANCE.isEpisodeLink("/vodplay/1-2-3.html"), "");
        check("episode: /v/1-2-3.html", HtmlTemplates.INSTANCE.isEpisodeLink("/v/1-2-3.html"), "");
        check("episode: 123-1-1.html", HtmlTemplates.INSTANCE.isEpisodeLink("123-1-1.html"), "");
        check("非 episode: javascript", !HtmlTemplates.INSTANCE.isEpisodeLink("javascript:void(0)"), "");
        check("非 episode: #tab", !HtmlTemplates.INSTANCE.isEpisodeLink("#playlist1"), "");
        check("非 episode: 首页", !HtmlTemplates.INSTANCE.isEpisodeLink("/"), "");

        System.out.println();
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        System.out.println(fail == 0 ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        if (fail > 0) System.exit(1);
    }
}
