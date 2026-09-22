import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 复核厂长资源（czzy.app）：网页到底需不需要登录才能播？
 * 顺着 首页 -> 详情页 -> 播放页 走一遍，把每一页里跟"播放地址"有关的痕迹都打出来。
 *
 * 用的是 App 里同一套 OkHttp 请求头，所以结论可以直接代表 App 看到的内容。
 */
public class Cz2 {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    static final String BASE = "https://czzy.app";

    static OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    public static void main(String[] args) throws Exception {
        String home = args.length > 0 ? args[0] : BASE + "/";
        System.out.println("=========== [1] 首页 " + home + " ===========");
        String h = get(home, null);
        if (h == null) return;
        System.out.println("长度=" + h.length());

        List<String> details = uniq(regex(h, "href=\"(/[a-z]+/\\d+\\.html)\""));
        System.out.println("详情页链接 " + details.size() + " 个，样本=" + head(details, 6));
        if (details.isEmpty()) {
            System.out.println(h.length() > 800 ? h.substring(0, 800) : h);
            return;
        }

        String det = details.get(0).startsWith("http") ? details.get(0) : BASE + details.get(0);
        System.out.println();
        System.out.println("=========== [2] 详情页 " + det + " ===========");
        String d = get(det, home);
        if (d == null) return;
        System.out.println("长度=" + d.length());
        System.out.println("含 noplay=" + d.contains("noplay")
                + "  player_aaaa=" + d.contains("player_aaaa")
                + "  m3u8=" + d.contains("m3u8")
                + "  登录=" + d.contains("登录"));
        List<String> plays = uniq(regex(d, "href=\"(/v_play/[^\"]+)\""));
        System.out.println("v_play 链接 " + plays.size() + " 个，样本=" + head(plays, 5));
        // 详情页里可能出现的数据接口
        for (String t : new String[]{"m3u8", "vod_play", "player_", "api/", ".mp4"}) {
            List<String> hits = ctx(d, t, 3);
            if (!hits.isEmpty()) {
                System.out.println("  [" + t + "]");
                for (String s : hits) System.out.println("     " + s);
            }
        }

        if (plays.isEmpty()) {
            System.out.println("详情页没有 v_play 链接，打印播放相关片段：");
            for (String s : ctx(d, "play", 6)) System.out.println("   " + s);
            return;
        }

        String pu = BASE + plays.get(0);
        System.out.println();
        System.out.println("=========== [3] 播放页 " + pu + " ===========");
        String p = get(pu, det);
        if (p == null) return;
        System.out.println("长度=" + p.length());
        for (String kw : new String[]{"noplay", "登录后", "请登录", "player_aaaa", "m3u8",
                ".mp4", "axios", "XMLHttpRequest", "fetch(", "/api/", "vod_play", "Player"}) {
            System.out.println("   含 " + pad(kw) + " : " + p.contains(kw));
        }
        System.out.println("  --- 媒体/接口痕迹 ---");
        for (String t : new String[]{"m3u8", ".mp4", "/api/", "ajax", "player"}) {
            List<String> hits = ctx(p, t, 5);
            if (!hits.isEmpty()) {
                System.out.println("  [" + t + "] " + hits.size() + " 处");
                for (String s : hits) System.out.println("     " + s);
            }
        }
        System.out.println("  --- 所有 <script src> ---");
        for (String s : uniq(regex(p, "<script[^>]+src=\"([^\"]+)\""))) System.out.println("     " + s);
        System.out.println("  --- 首屏 1200 字 ---");
        System.out.println(p.length() > 1200 ? p.substring(0, 1200) : p);
    }

    // ------------------------------------------------------------------ 工具

    static String get(String url, String referer) {
        try {
            Request.Builder b = new Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            if (referer != null) b.header("Referer", referer);
            try (Response r = client.newCall(b.build()).execute()) {
                System.out.println("   HTTP " + r.code() + "  " + r.header("Content-Type")
                        + "  " + r.request().url());
                if (r.body() == null) return null;
                return r.body().string();
            }
        } catch (Exception e) {
            System.out.println("   !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    static List<String> regex(String s, String re) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(re).matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static List<String> uniq(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    static List<String> ctx(String s, String kw, int max) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (out.size() < max && (i = s.indexOf(kw, i)) >= 0) {
            String seg = s.substring(Math.max(0, i - 90), Math.min(s.length(), i + 110));
            out.add(seg.replaceAll("\\s+", " "));
            i += kw.length();
        }
        return out;
    }

    static String head(List<String> l, int n) {
        return l.subList(0, Math.min(n, l.size())).toString();
    }

    static String pad(String s) {
        while (s.length() < 12) s = s + " ";
        return s;
    }
}
