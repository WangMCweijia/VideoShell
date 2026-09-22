import com.videoshell.player.HlsPlaylistFixer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 用**茶杯狐极速播放的真实 playlist**（本地已抓取的 cupfox_playlist.m3u8）
 * 跑一遍 App 里的 HlsPlaylistFixer，并做严格结构校验：
 *  - EXTINF / 分片必须成对，不能出现孤儿标签
 *  - 不能残留 #EXT-X-DISCONTINUITY / 广告分片
 *  - TARGETDURATION 必须 >= 实际最大分片时长（HLS 规范要求）
 *  - 首个分片必须是绝对地址，且指向 0000000.ts
 */
public class HdFix2 {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0]
                : "cupfox_playlist.m3u8";
        String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        String url = "https://fengbao12.com/video/MISSIONgejubandeqianrusouchaguan/d577601c75a6/index.m3u8";

        System.out.println("原清单：字节=" + text.getBytes(StandardCharsets.UTF_8).length
                + " 行=" + lines(text) + " 分片=" + countSegs(text)
                + " DISCONTINUITY=" + count(text, "#EXT-X-DISCONTINUITY")
                + " adjump=" + count(text, "/adjump/"));

        String fixed = HlsPlaylistFixer.INSTANCE.fix(text, url);

        System.out.println("修后：字节=" + fixed.getBytes(StandardCharsets.UTF_8).length
                + " 行=" + lines(fixed) + " 分片=" + countSegs(fixed)
                + " DISCONTINUITY=" + count(fixed, "#EXT-X-DISCONTINUITY")
                + " adjump=" + count(fixed, "/adjump/"));

        System.out.println();
        System.out.println("--- 修后前 8 行 ---");
        String[] fl = fixed.split("\n");
        for (int i = 0; i < Math.min(8, fl.length); i++) System.out.println("| " + fl[i]);
        System.out.println("--- 修后末尾 4 行 ---");
        for (int i = Math.max(0, fl.length - 4); i < fl.length; i++)
            System.out.println("| " + fl[i]);

        System.out.println();
        // ---- 结构校验 ----
        ok("修后仍有分片", countSegs(fixed) > 0);
        ok("修后分片数 = 原分片数 - 27（广告）",
                countSegs(fixed) == countSegs(text) - 27);
        ok("修后不含 /adjump/ 广告分片", !fixed.contains("/adjump/"));
        ok("修后不含 #EXT-X-DISCONTINUITY", !fixed.contains("#EXT-X-DISCONTINUITY"));
        ok("修后所有分片都是绝对 URL", allAbsolute(fixed));

        // EXTINF / 分片配对
        List<String> orphans = orphanExtinf(fixed);
        ok("没有「有 #EXTINF 却缺分片」的孤儿（" + orphans.size() + " 个）", orphans.isEmpty());
        if (!orphans.isEmpty()) for (String o : orphans) System.out.println("      孤儿: " + o);
        ok("没有「有分片却缺 #EXTINF」", noBareSegment(fixed));

        // TARGETDURATION >= 最大 EXTINF
        double maxInf = maxExtinf(fixed);
        int td = targetDuration(fixed);
        System.out.println("  最大 #EXTINF=" + maxInf + "  修后 TARGETDURATION=" + td);
        ok("TARGETDURATION >= ceil(最大 EXTINF)", td >= Math.ceil(maxInf));

        // VOD 必须有 ENDLIST
        ok("VOD 清单带 #EXT-X-ENDLIST", fixed.contains("#EXT-X-PLAYLIST-TYPE:VOD")
                && fixed.contains("#EXT-X-ENDLIST"));
        ok("首行是 #EXTM3U", fl.length > 0 && fl[0].trim().equals("#EXTM3U"));
        ok("只出现一次 #EXTM3U", count(fixed, "#EXTM3U") == 1);
        ok("首个分片 = 0000000.ts 绝对地址",
                firstSeg(fixed).endsWith("/d577601c75a6/0000000.ts"));

        System.out.println();
        System.out.println("================ pass=" + pass + " fail=" + fail + " ================");
        if (fail > 0) System.exit(1);
    }

    static List<String> orphanExtinf(String t) {
        List<String> out = new ArrayList<>();
        String[] l = t.split("\n");
        for (int i = 0; i < l.length; i++) {
            if (l[i].trim().startsWith("#EXTINF")) {
                // 往后找最近的非注释行，中间不能有其它 #EXTINF
                boolean found = false;
                for (int j = i + 1; j < l.length; j++) {
                    String s = l[j].trim();
                    if (s.isEmpty()) continue;
                    if (s.startsWith("#EXTINF")) break;
                    if (!s.startsWith("#")) { found = true; break; }
                }
                if (!found) out.add("line " + (i + 1) + ": " + l[i].trim());
            }
        }
        return out;
    }

    static boolean noBareSegment(String t) {
        String[] l = t.split("\n");
        for (int i = 0; i < l.length; i++) {
            String s = l[i].trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            // 往回找 #EXTINF
            boolean found = false;
            for (int j = i - 1; j >= 0; j--) {
                String p = l[j].trim();
                if (p.isEmpty()) continue;
                if (!p.startsWith("#")) break;
                if (p.startsWith("#EXTINF")) { found = true; break; }
                if (p.startsWith("#EXT-X-STREAM-INF")) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    static boolean allAbsolute(String t) {
        for (String ln : t.split("\n")) {
            String s = ln.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            if (!s.startsWith("http://") && !s.startsWith("https://")) return false;
        }
        return true;
    }

    static double maxExtinf(String t) {
        double m = 0;
        for (String ln : t.split("\n")) {
            String s = ln.trim();
            if (!s.startsWith("#EXTINF")) continue;
            int p = s.indexOf(':');
            int q = s.indexOf(',', p);
            try {
                m = Math.max(m, Double.parseDouble(s.substring(p + 1, q).trim()));
            } catch (Exception ignore) {
            }
        }
        return m;
    }

    static int targetDuration(String t) {
        for (String ln : t.split("\n")) {
            String s = ln.trim();
            if (s.startsWith("#EXT-X-TARGETDURATION")) {
                try {
                    return Integer.parseInt(s.substring(s.indexOf(':') + 1).trim());
                } catch (Exception ignore) {
                }
            }
        }
        return -1;
    }

    static String firstSeg(String t) {
        for (String ln : t.split("\n")) {
            String s = ln.trim();
            if (!s.isEmpty() && !s.startsWith("#")) return s;
        }
        return "";
    }

    static int count(String t, String sub) {
        int n = 0, i = 0;
        while ((i = t.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    static int countSegs(String t) {
        int n = 0;
        for (String ln : t.split("\n")) {
            String s = ln.trim();
            if (!s.isEmpty() && !s.startsWith("#")) n++;
        }
        return n;
    }

    static int lines(String t) { return t.split("\n").length; }

    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + what); }
        else { fail++; System.out.println("  [FAIL] " + what); }
    }
}
