import com.videoshell.player.SniffCandidate;
import com.videoshell.player.SniffQueue;
import com.videoshell.player.SniffRank;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 嗅探候选「智能筛选」断言（v1.0.8）。
 *
 * 分三块：
 *  A. 广告词判定 —— **重点是反例**：`contains("ad")` 那种写法会把 download/load/head
 *     全部误杀，必须证明按"路径段/主机标签"匹配不会误伤。
 *  B. 内容判定 —— 用**线上真实 playlist** 断言正片被认成正片、广告被认成广告。
 *  C. 自动播放策略 —— 单候选才自动播；两个都像正片时不许替用户决定；广告永不自动播。
 *  D. 候选队列换源语义。
 */
public class Rank {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        final SniffRank R = SniffRank.INSTANCE;

        banner("A. 广告词判定：必须抓广告，且绝不能误伤正常词");

        // A1 反例：这些是"看似含广告词"的正常地址，一个都不能被判为广告
        String[] mustNotFlag = {
                "https://cdn.x.com/download/video/1.m3u8",                  // download 含 "ad"
                "https://cdn.x.com/load/playlist.m3u8",                     // load 含 "ad"
                "https://cdn.x.com/video/head/main.m3u8",                   // head 含 "ad"
                "https://cdn.x.com/upload/1.m3u8",                          // upload 含 "ad"
                "https://cdn.x.com/radar/1.m3u8",                           // radar 含 "ad"
                "https://npm.xn--swt777gelc.xyz/wp-content/themes/mibt/assets/js/jquery.lazyload.min.js",
                "https://cdn.x.com/thread/movie.m3u8",                      // thread 含 "ad"
                "https://cdn.x.com/shadow/1.m3u8",                          // shadow 含 "ad"
                "https://cdn.x.com/ready/main.m3u8",                        // ready 含 "ad"
                "https://cdn.x.com/broadcast/1.m3u8",                       // broadcast 含 "ad"
                "https://cdn.x.com/addon/playlist.m3u8",                    // addon 含 "ad"
        };
        for (String u : mustNotFlag) {
            ok("不误伤 " + tail(u), !R.isSuspect(u));
        }

        // A2 正例：真正的广告/埋点地址必须被抓到
        String[] mustFlag = {
                "https://ads.example.com/1.m3u8",              // 主机标签 ads
                "https://ad.cdn.com/x/1.m3u8",                 // 主机标签 ad
                "https://x.com/ad/1.m3u8",                     // 路径段 ad
                "https://x.com/adjump/time/x.ts",              // 路径段 adjump
                "https://x.com/guanggao/1.m3u8",               // 路径段 guanggao
                "https://x.com/video/preroll/index.m3u8",      // 路径段 preroll
                "https://x.com/trailer/1.m3u8",                // 路径段 trailer
                "https://x.com/stat/ping.m3u8",                // 路径段 stat
                "https://x.com/v/index.m3u8?type=preroll",     // 查询值
        };
        for (String u : mustFlag) {
            ok("抓到广告 " + tail(u), R.isSuspect(u));
        }

        // A3 两个站的**真实正片地址**绝不能被误判
        String real1 = "https://fengbao12.com/video/MISSIONgejubandeqianrusouchaguan/d577601c75a6/index.m3u8";
        String real2 = "https://cdn.yzzy31-play.com/20260917/26042_6b8268b2/index.m3u8";
        ok("茶杯狐极速真实直链不被误判", !R.isSuspect(real1));
        ok("茶杯狐高清真实直链不被误判", !R.isSuspect(real2));

        banner("B. 内容判定：正片 vs 广告（真实 playlist）");

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(35, TimeUnit.SECONDS)
                .build();
        String referer = "https://www.cupfoxyy.com/";

        String flat = get(client, real1, referer);
        String master = get(client, real2, referer);

        if (flat != null) {
            ok("极速（扁平 TS，" + segs(flat) + " 个分片）判为正片",
                    R.verdict(flat) == SniffRank.CONTENT_REAL);
            System.out.println("      describe => " + R.describe(flat));
        } else {
            bad("极速 playlist 抓取失败");
        }
        if (master != null) {
            ok("高清（多码率主清单）判为正片",
                    R.verdict(master) == SniffRank.CONTENT_REAL);
            System.out.println("      describe => " + R.describe(master));
        } else {
            bad("高清 playlist 抓取失败");
        }

        // 广告清单：10 个分片 × 5 秒 = 50 秒
        StringBuilder ad = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:5\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MEDIA-SEQUENCE:0\n");
        for (int i = 0; i < 10; i++) ad.append("#EXTINF:5.000,\nseg").append(i).append(".ts\n");
        ad.append("#EXT-X-ENDLIST\n");
        ok("50 秒的插播清单判为疑似广告",
                R.verdict(ad.toString()) == SniffRank.CONTENT_LIKELY_AD);
        System.out.println("      describe => " + R.describe(ad.toString()));

        // 预告片：45 秒 6 个分片
        StringBuilder pv = new StringBuilder("#EXTM3U\n#EXTINF:7.5,\na.ts\n");
        for (int i = 1; i < 6; i++) pv.append("#EXTINF:7.5,\nb").append(i).append(".ts\n");
        ok("45 秒预告判为疑似广告",
                R.verdict(pv.toString()) == SniffRank.CONTENT_LIKELY_AD);

        // 不能武断：没有 EXTINF 的清单判未知，而不是广告
        ok("无分片信息的清单判为未知（不武断）",
                R.verdict("#EXTM3U\n#EXT-X-TARGETDURATION:6\n") == SniffRank.CONTENT_UNKNOWN);
        ok("非 m3u8 文本判为未知", R.verdict("<html></html>") == SniffRank.CONTENT_UNKNOWN);

        banner("C. 排序与自动播放策略");

        SniffCandidate realC = cand(real1, "HLS", 1, false,
                flat != null ? SniffRank.CONTENT_REAL : SniffRank.CONTENT_UNKNOWN, "");
        SniffCandidate adC = cand("https://ads.example.com/preroll/index.m3u8", "HLS", 9, true,
                SniffRank.CONTENT_LIKELY_AD, "约 0 分钟 / 10 个分片");

        List<SniffCandidate> two = R.rank(Arrays.asList(adC, realC), "", Collections.emptyMap());
        ok("正片排在广告嫌疑之前（" + two.get(0).display() + "）", two.get(0) == realC);
        ok("广告嫌疑候选被明确标注", !two.get(0).getSuspect() && two.get(1).getSuspect());
        ok("命中次数刷高的广告不能靠 hits 反超（广告 hits=" + adC.getHits() + "）",
                two.get(0) == realC);

        ok("单一候选 ⇒ 直接自动播", R.autoPick(Collections.singletonList(realC), true) == realC);
        ok("首选是正片、次选不是 ⇒ 自动播首选",
                R.autoPick(Arrays.asList(realC, adC), true) == realC);

        SniffCandidate real2C = cand(real2, "HLS", 1, false, SniffRank.CONTENT_REAL, "");
        ok("两个候选都是正片 ⇒ 不替用户决定（返回 null）",
                R.autoPick(Arrays.asList(realC, real2C), true) == null);

        List<SniffCandidate> onlyAd = Arrays.asList(
                cand("https://ads.example.com/a.m3u8", "HLS", 9, true, SniffRank.CONTENT_LIKELY_AD, ""),
                cand("https://x.com/preroll/b.m3u8", "HLS", 8, true, SniffRank.CONTENT_LIKELY_AD, ""));
        ok("候选全是广告嫌疑 ⇒ 不自动播", R.autoPick(onlyAd, true) == null);

        List<SniffCandidate> unknown = Arrays.asList(
                cand("https://a.com/1.m3u8", "HLS", 2, false, SniffRank.CONTENT_UNKNOWN, ""),
                cand("https://b.com/2.m3u8", "HLS", 1, false, SniffRank.CONTENT_UNKNOWN, ""));
        ok("全都没探出内容 ⇒ 退回最高分（不卡住用户）",
                R.autoPick(unknown, true) == unknown.get(0));
        ok("没探测过 ⇒ 退回最高分（保持旧行为）",
                R.autoPick(unknown, false) == unknown.get(0));

        banner("D. 候选队列的换源语义");

        SniffQueue Q = SniffQueue.INSTANCE;
        Q.clear();
        Q.set(Arrays.asList(realC, adC, real2C), 0);
        ok("起点是第 1 个", Q.getIndex() == 0 && Q.current() == realC);
        ok("还有下一个", Q.hasNext());
        ok("换源后到第 2 个", Q.advance() == adC && Q.getIndex() == 1);
        ok("还能再换一个", Q.advance() == real2C && Q.getIndex() == 2);
        ok("到底了就停住（不会越界）", !Q.hasNext() && Q.advance() == null);
        Q.clear();
        ok("clear 之后没有候选", Q.getCandidates().isEmpty() && !Q.hasNext());

        System.out.println();
        System.out.println("================ pass=" + pass + " fail=" + fail + " ================");
        if (fail > 0) System.exit(1);
    }

    static SniffCandidate cand(String url, String type, int hits, boolean suspect, int content, String note) {
        return new SniffCandidate(url, type, hits, suspect, content, note, 0);
    }

    static int segs(String t) {
        int n = 0;
        for (String ln : t.split("\n")) {
            String s = ln.trim();
            if (!s.isEmpty() && !s.startsWith("#")) n++;
        }
        return n;
    }

    static String tail(String u) {
        if (u.length() <= 52) return u;
        return "…" + u.substring(u.length() - 50);
    }

    static String get(OkHttpClient c, String url, String referer) {
        try {
            Request r = new Request.Builder().url(url)
                    .header("User-Agent", UA).header("Referer", referer).header("Accept", "*/*").build();
            try (Response resp = c.newCall(r).execute()) {
                System.out.println("      HTTP " + resp.code() + "  " + url);
                return resp.body() == null ? null : resp.body().string();
            }
        } catch (Exception e) {
            System.out.println("      !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
