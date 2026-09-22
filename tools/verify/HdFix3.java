import com.videoshell.player.HlsPlaylistFixer;

/**
 * 离线回归：HlsPlaylistFixer 的 ENDLIST 记账逻辑（v1.0.22）。
 *
 * 背景（野果"所有集从末尾播"的根因）：旧实现把 playlist 里**原生的**
 * #EXT-X-ENDLIST 剥掉，但只有声明了 PLAYLIST-TYPE:VOD 的才补回 ——
 * 大量"有 ENDLIST 但没写 PLAYLIST-TYPE"的普通 VOD 流经 fix 后丢了
 * ENDLIST，被 ExoPlayer 当成直播，从 live edge（≈片尾）起播。
 */
public class HdFix3 {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        String url = "https://cdn.example.com/v/ep1.m3u8";

        // ---- A. 野果形状：原生 ENDLIST、无 PLAYLIST-TYPE → fix 后 ENDLIST 必须还在
        String yg = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n"
                + "#EXTINF:9.0,\nseg0.ts\n#EXTINF:10.0,\nseg1.ts\n#EXT-X-ENDLIST\n";
        String f1 = HlsPlaylistFixer.INSTANCE.fix(yg, url);
        ok("原生ENDLIST(无PLAYLIST-TYPE) fix后仍保留", f1.contains("#EXT-X-ENDLIST"));
        ok("分片未丢失", f1.contains("seg0.ts") && f1.contains("seg1.ts"));
        ok("TARGETDURATION 按实际修正", f1.contains("#EXT-X-TARGETDURATION:10"));

        // ---- B. 真直播：无 ENDLIST、无 PLAYLIST-TYPE、MEDIA-SEQUENCE>0 → 不得补 ENDLIST
        String live = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-MEDIA-SEQUENCE:9000\n#EXTINF:5.0,\nlive0.ts\n#EXTINF:6.0,\nlive1.ts\n";
        String f2 = HlsPlaylistFixer.INSTANCE.fix(live, "https://cdn.example.com/live/index.m3u8");
        ok("真直播不补ENDLIST（补了会被当已结束截断）", !f2.contains("#EXT-X-ENDLIST"));
        ok("直播分片保留", f2.contains("live0.ts") && f2.contains("live1.ts"));

        // ---- C. PLAYLIST-TYPE:VOD 但漏写 ENDLIST → 照旧补上
        String vod = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXTINF:10.0,\nseg0.ts\n";
        String f3 = HlsPlaylistFixer.INSTANCE.fix(vod, "https://cdn.example.com/v/ep2.m3u8");
        ok("VOD声明补ENDLIST", f3.contains("#EXT-X-ENDLIST"));

        // ---- D. master playlist 原样透传
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nhi/index.m3u8\n";
        String f4 = HlsPlaylistFixer.INSTANCE.fix(master, "https://cdn.example.com/master.m3u8");
        ok("master 原样返回", f4.equals(master));

        // ---- E. 广告剔除后 ENDLIST 仍在（广告段 + 原生 ENDLIST 组合）
        String ad = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"
                + "#EXTINF:9.0,\nseg0.ts\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:5.0,\n/adjump/t/ad0.ts\n"
                + "#EXTINF:10.0,\nseg1.ts\n#EXT-X-ENDLIST\n";
        String f5 = HlsPlaylistFixer.INSTANCE.fix(ad, "https://cdn.example.com/v/ep3.m3u8");
        ok("剔除广告后ENDLIST仍保留", f5.contains("#EXT-X-ENDLIST"));
        ok("广告分片已剔除", !f5.contains("adjump"));

        System.out.println();
        System.out.println("==== HdFix3 PASS=" + pass + " FAIL=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }

    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + what); }
        else { fail++; System.out.println("  [FAIL] " + what); }
    }
}
