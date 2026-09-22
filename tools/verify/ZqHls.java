import com.videoshell.player.HlsPlaylistFixer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 用**真实的** CNTV playlist（zqkhmy 的 ps:1 线路）喂 App 里的 HlsPlaylistFixer，
 * 再对产出做严格结构校验。目的只有一个：证明「App 交给 ExoPlayer 的清单」到底合不合法。
 *
 * 只读文件，不改任何东西。
 */
public class ZqHls {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  [PASS] " + what); }
        else { fail++; System.out.println("  [FAIL] " + what + "   " + detail); }
    }

    static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        for (String l : s.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (!l.trim().isEmpty()) out.add(l.trim());
        }
        return out;
    }

    static void inspect(String tag, String text, String url) {
        System.out.println();
        System.out.println("==========================================================");
        System.out.println(tag);
        System.out.println("==========================================================");
        String fixed = HlsPlaylistFixer.INSTANCE.fix(text, url);
        List<String> fl = lines(fixed);
        List<String> ol = lines(text);

        int oSegs = 0, fSegs = 0, oAd = 0, fDisc = 0, oDisc = 0;
        for (String l : ol) if (!l.startsWith("#")) oSegs++;
        for (String l : fl) if (!l.startsWith("#")) fSegs++;
        for (String l : ol) if (!l.startsWith("#") && Regexes.isAd(l)) oAd++;
        for (String l : fl) if (l.startsWith("#EXT-X-DISCONTINUITY")) fDisc++;
        for (String l : ol) if (l.startsWith("#EXT-X-DISCONTINUITY")) oDisc++;

        System.out.println("原始：段=" + oSegs + "  DISC=" + oDisc + "  命中AD=" + oAd);
        System.out.println("产出：段=" + fSegs + "  DISC=" + fDisc
                + "  " + (fixed.equals(text) ? "（原样返回）" : "（已重写，少 " + (oSegs - fSegs) + " 段）"));

        // ---- 严格结构校验（ExoPlayer / RFC 8216 的硬要求）
        ok("以 #EXTM3U 开头", fixed.startsWith("#EXTM3U"), fixed.substring(0, Math.min(40, fixed.length())));
        boolean hasTd = fixed.contains("#EXT-X-TARGETDURATION");
        ok("有 #EXT-X-TARGETDURATION", hasTd, "");
        boolean hasEnd = fixed.contains("#EXT-X-ENDLIST");
        ok("有 #EXT-X-ENDLIST（否则会被当直播）", hasEnd, "");

        // 每条 EXTINF 后面必须紧跟一条地址；地址行必须绝对
        int extinf = 0, abs = 0, rel = 0, bad = 0;
        int firstSeg = -1;
        for (int i = 0; i < fl.size(); i++) {
            String l = fl.get(i);
            if (l.startsWith("#EXTINF")) {
                extinf++;
                if (i + 1 >= fl.size() || fl.get(i + 1).startsWith("#")) bad++;
            } else if (!l.startsWith("#")) {
                if (firstSeg < 0) firstSeg = i;
                if (l.startsWith("http://") || l.startsWith("https://")) abs++; else rel++;
            }
        }
        ok("EXTINF 数量 == 段数量", extinf == fSegs, "extinf=" + extinf + " segs=" + fSegs);
        ok("★ 每条 EXTINF 后紧跟一条地址（没有悬空 EXTINF）", bad == 0, "悬空 " + bad + " 条");
        ok("★ 所有分片地址都已绝对化（相对 " + rel + " 条）", rel == 0, "rel=" + rel);

        // TARGETDURATION 必须 >= 最大 EXTINF
        double maxDur = 0, sum = 0;
        for (String l : fl) {
            if (l.startsWith("#EXTINF")) {
                String v = l.substring(7).trim();
                int c = v.indexOf(',');
                if (c < 0) c = v.length();
                try { double d = Double.parseDouble(v.substring(0, c).trim()); maxDur = Math.max(maxDur, d); sum += d; }
                catch (Exception ignore) { }
            }
        }
        int td = 0;
        for (String l : fl) if (l.startsWith("#EXT-X-TARGETDURATION")) {
            try { td = Integer.parseInt(l.split(":")[1].trim()); } catch (Exception ignore) { }
        }
        ok("★ TARGETDURATION(" + td + ") >= ceil(最大 EXTINF)(" + (int) Math.ceil(maxDur) + ")",
                td >= (int) Math.ceil(maxDur), "max=" + maxDur);
        System.out.println("        → 产出总时长≈" + (long) sum + "s（" + fSegs + " 段）");

        // 开头不能是 DISCONTINUITY（无意义的空段）
        int firstNonHead = -1;
        for (int i = 0; i < fl.size(); i++) {
            String l = fl.get(i);
            if (l.startsWith("#EXTINF") || (!l.startsWith("#") )) { firstNonHead = i; break; }
        }
        ok("★ 媒体段不是以 #EXT-X-DISCONTINUITY 起头",
                firstNonHead >= 0 && !fl.get(firstNonHead).startsWith("#EXT-X-DISCONTINUITY"),
                firstNonHead < 0 ? "无媒体段" : fl.get(firstNonHead));

        System.out.println("  --- 产出前 14 行 ---");
        for (int i = 0; i < Math.min(14, fl.size()); i++) {
            System.out.println("    | " + cut(fl.get(i), 120));
        }
        System.out.println("  --- 产出末 4 行 ---");
        for (int i = Math.max(0, fl.size() - 4); i < fl.size(); i++) {
            System.out.println("    | " + cut(fl.get(i), 120));
        }
        System.out.println("  --- 产出里的分片地址（首 3 条，验证绝对化） ---");
        int shown = 0;
        for (String l : fl) {
            if (!l.startsWith("#")) {
                System.out.println("    > " + cut(l, 160));
                if (++shown >= 3) break;
            }
        }
    }

    static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    /** 复刻 HlsPlaylistFixer.AD_PATH，便于离线统计 */
    static class Regexes {
        static final java.util.regex.Pattern AD = java.util.regex.Pattern.compile(
                "/(adjump|ad|ads|adv|advert|guanggao|gg|advertise)/",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        static boolean isAd(String s) { return AD.matcher(s).find(); }
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        String urlA = "https://cibn-edge-5g.1ljx.com/cloud/flv/2d3029772f38c1df67b37478d6e1cec0/65b5f10edd406c2c13681f9b2f65faf8/6aaf7fa8422b1.m3u8?auth_key=1789886376.271025-1-348632880.info-internet-2-llq-d295616d3044cc7a";
        for (String name : new String[] { "_zqana_A.m3u8", "_zqana_D.m3u8" }) {
            File f = new File(dir, name);
            if (!f.exists()) { System.out.println("缺 " + name); continue; }
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            inspect(name, text, urlA);
        }

        // 真实的分片地址（绝对）应原样保留；相对分片要能按 query 里的斜杠正确落目录
        System.out.println();
        System.out.println("==========================================================");
        System.out.println("补充：带 query 的 playlist 目录推导（v1.0.38 修过的那处）");
        System.out.println("==========================================================");
        String mk = "#EXTM3U\n#EXTINF:5,\nseg1.ts\n#EXT-X-ENDLIST\n";
        String o = HlsPlaylistFixer.INSTANCE.fix(mk, "https://h/a/b/index.m3u8?auth_key=x/y");
        ok("★ ?auth_key=x/y 里的斜杠不会把目录算错",
                o.contains("https://h/a/b/seg1.ts"), o.replace("\n", "\\n"));

        System.out.println();
        System.out.println("==== ZqHls  pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }
}
