import com.videoshell.player.HlsPlaylistFixer;
import okhttp3.*;

import java.util.concurrent.TimeUnit;

/**
 * 用真实的 m3u8 检验 HlsPlaylistFixer：
 * 拿线上 playlist 原文 → 跑 App 里那个真实的 fix() → 对比结构，
 * 并检查产出是否仍然合法可播（分片数、绝对路径、TARGETDURATION、ENDLIST）。
 *
 * 重点验证两类：
 *  A. master 清单（多码率）—— 相对路径变体必须原样透传，不能被"修"坏
 *  B. 下游媒体清单 —— 扁平 TS 会被重写，必须仍然合法
 */
public class HdFix {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .build();

        String referer = "https://www.cupfoxyy.com/";

        // ---------- A. master 清单 ----------
        String master = "https://cdn.yzzy31-play.com/20260917/26042_6b8268b2/index.m3u8";
        String masterText = get(client, master, referer);
        banner("A. master 清单必须原样透传（不能被修坏）");
        if (masterText == null) {
            bad("master 抓取失败");
        } else {
            String fixedMaster = HlsPlaylistFixer.INSTANCE.fix(masterText, master);
            ok("master 未被改动（fix 直接放行）", fixedMaster.equals(masterText));
            ok("master 仍含 #EXT-X-STREAM-INF", fixedMaster.contains("#EXT-X-STREAM-INF"));
            ok("master 仍含相对变体 3000k/hls/mixed.m3u8",
                    fixedMaster.contains("3000k/hls/mixed.m3u8"));
            System.out.println("    原文行数=" + lines(masterText));

            // 顺着相对路径往下走一层，拿到真正的媒体清单
            String variant = resolve(master, "3000k/hls/mixed.m3u8");
            System.out.println("    变体 -> " + variant);
            String media = get(client, variant, referer);
            if (media == null) {
                bad("媒体清单抓取失败");
            } else {
                checkMedia(media, variant);
            }
        }

        // ---------- B. 极速播放的扁平 TS 清单 ----------
        String flat = "https://fengbao12.com/video/MISSIONgejubandeqianrusouchaguan/d577601c75a6/index.m3u8";
        String flatText = get(client, flat, referer);
        banner("B. 扁平 TS 清单（极速播放）规范化后必须仍合法");
        if (flatText == null) {
            bad("扁平清单抓取失败");
        } else {
            checkMedia(flatText, flat);
        }

        System.out.println();
        System.out.println("================ pass=" + pass + " fail=" + fail + " ================");
        if (fail > 0) System.exit(1);
    }

    static void checkMedia(String text, String url) {
        int rawSeg = countSegments(text);
        System.out.println("    原清单：分片=" + rawSeg
                + "  master=" + text.contains("#EXT-X-STREAM-INF")
                + "  TARGETDURATION=" + tag(text, "#EXT-X-TARGETDURATION")
                + "  VOD=" + text.contains("#EXT-X-PLAYLIST-TYPE:VOD")
                + "  ENDLIST=" + text.contains("#EXT-X-ENDLIST")
                + "  MAP=" + text.contains("#EXT-X-MAP")
                + "  KEY=" + text.contains("#EXT-X-KEY"));

        String fixed = HlsPlaylistFixer.INSTANCE.fix(text, url);
        int fixSeg = countSegments(fixed);

        ok("规范化后仍有分片（" + fixSeg + " / 原 " + rawSeg + "）", fixSeg > 0);
        ok("规范化后有 #EXT-X-TARGETDURATION", fixed.contains("#EXT-X-TARGETDURATION"));

        // 分片/变体一律绝对化，消除 baseUri 歧义
        boolean allAbsolute = true;
        for (String ln : fixed.split("\n")) {
            String t = ln.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (!t.startsWith("http://") && !t.startsWith("https://")) allAbsolute = false;
        }
        ok("规范化后所有地址都是绝对 URL", allAbsolute);

        // 不能把广告目录塞回去
        ok("规范化后不含 /adjump/ 广告分片", !fixed.contains("/adjump/"));

        System.out.println("    --- 规范化后头部 5 行 ---");
        String[] fl = fixed.split("\n");
        for (int i = 0; i < Math.min(5, fl.length); i++) System.out.println("    | " + fl[i]);
        System.out.println("    --- 规范化后首个分片 ---");
        for (String ln : fl) {
            String t = ln.trim();
            if (!t.isEmpty() && !t.startsWith("#")) { System.out.println("    | " + t); break; }
        }
    }

    static int countSegments(String t) {
        int n = 0;
        for (String ln : t.split("\n")) {
            String s = ln.trim();
            if (!s.isEmpty() && !s.startsWith("#")) n++;
        }
        return n;
    }

    static String tag(String t, String name) {
        for (String ln : t.split("\n")) {
            if (ln.trim().startsWith(name)) return ln.trim();
        }
        return "(无)";
    }

    static int lines(String t) { return t.split("\n").length; }

    static String resolve(String base, String rel) {
        if (rel.startsWith("http")) return rel;
        java.net.URI b = java.net.URI.create(base);
        String dir = base.substring(0, base.lastIndexOf('/') + 1);
        return java.net.URI.create(dir).resolve(rel).toString();
    }

    static String get(OkHttpClient c, String url, String referer) {
        try {
            Request r = new Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Referer", referer)
                    .header("Accept", "*/*")
                    .build();
            try (Response resp = c.newCall(r).execute()) {
                System.out.println("    HTTP " + resp.code() + "  ct=" + resp.header("Content-Type")
                        + "  " + url);
                return resp.body() == null ? null : resp.body().string();
            }
        } catch (Exception e) {
            System.out.println("    !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("================ " + s + " ================");
    }

    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + what); }
        else { fail++; System.out.println("  [FAIL] " + what); }
    }

    static void bad(String what) {
        fail++;
        System.out.println("  [FAIL] " + what);
    }
}
