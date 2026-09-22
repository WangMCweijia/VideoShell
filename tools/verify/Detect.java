import okhttp3.*;

import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 复刻 SiteDetector.detect 的探测环节：
 * 逐条打 8 个采集接口候选，打印 HTTP 状态 / 前 120 字 / classify / hasData。
 * 目的：确认站点到底被判成 maccms 还是 html —— apiMode 判错会让整站全线失效。
 *
 * 另外复刻 ApiDetector 的「首页探活 + looksLikeVideoSite」判定。
 */
public class Detect {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    static final String ACCEPT = "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8";

    static OkHttpClient fast, client;

    static String[] PATHS = {
            "/api.php/provide/vod/at/json/",
            "/api.php/provide/vod/",
            "/api.php/provide/vod/from/json/",
            "/api.php/provide/vod/",
            "/api.php/provide/vod/at/xml/",
            "/provide/vod/",
            "/index.php/api/vod/",
            "/api/vod/",
    };
    static String[] FIXED = {"", "at=json", "", "", "", "at=json", "at=json", "at=json"};
    static String[] LABEL = {
            "苹果CMS JSON 路径", "苹果CMS JSON 参数", "苹果CMS from/json", "苹果CMS 默认接口",
            "苹果CMS XML 路径", "精简提供接口", "index.php 接口", "api/vod 接口"
    };

    public static void main(String[] a) throws Exception {
        fast = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).followRedirects(true).build();
        client = new OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true).followRedirects(true).build();

        if (a.length > 0) {
            for (String u : a) probe(host(u), u);
        } else {
            probe("茶杯狐", "https://www.cupfoxyy.com");
            probe("厂长资源", "https://czzy.app");
        }
    }

    static String host(String u) {
        String s = u.replaceFirst("^https?://", "");
        int i = s.indexOf('/');
        return i < 0 ? s : s.substring(0, i);
    }

    static void probe(String label, String base) {
        banner(label + "  " + base);

        Res home = get(base, base, true);
        System.out.println("首页 -> " + home.status + " / " + home.len + " 字");
        System.out.println("looksLikeVideoSite = " + looksLikeVideoSite(home.body));

        for (int i = 0; i < PATHS.length; i++) {
            String u = base + PATHS[i] + "?ac=list" + (FIXED[i].isEmpty() ? "" : "&" + FIXED[i]);
            Res r = get(u, base, true);
            String head = r.body == null ? "(null)" : r.body.trim();
            if (head.length() > 120) head = head.substring(0, 120);
            String mode = classify(r.body);
            boolean data = mode != null && hasData(r.body, mode);
            System.out.println(String.format("  %-18s %s  %-3d %6d 字  mode=%-12s hasData=%s",
                    LABEL[i], PATHS[i], r.status, r.len, String.valueOf(mode), data));
            System.out.println("       head: " + head.replace("\n", "\\n"));
        }
    }

    // ---- 复刻 SiteDetector.classify
    static String classify(String body) {
        if (body == null) return null;
        String t = body.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("{")) {
            // org.json 不可用，这里只做结构近似：能解析且含 "class"/"list" 键
            if (t.contains("\"class\"") || t.contains("\"list\"")) return "maccms_json";
            return null;
        }
        if (t.startsWith("<") && (t.contains("<rss") || t.contains("<list") || t.contains("<?xml"))) {
            return "maccms_xml";
        }
        return null;
    }

    static boolean hasData(String body, String mode) {
        if (body == null) return false;
        if ("maccms_json".equals(mode)) {
            return body.contains("\"class\":[") || body.contains("\"list\":[")
                    || body.contains("\"class\" :") || body.contains("\"list\" :");
        }
        return body.contains("<ty ") || body.contains("<ty>") || body.contains("<video>");
    }

    // ---- 复刻 looksLikeVideoSite
    static String[] VIDEO_WORDS = {"视频", "影视", "电影", "电视剧", "在线观看", "在线播放", "动漫", "综艺", "追剧",
            "vodplay", "vodshow", "voddetail", "vodtype", "maccms", "苹果cms", "海洋cms", "player_aaaa"};

    static boolean looksLikeVideoSite(String html) {
        if (html == null) return false;
        String head = html.substring(0, Math.min(300_000, html.length())).toLowerCase();
        int hit = 0;
        for (String w : VIDEO_WORDS) {
            if (head.contains(w)) {
                hit++;
                if (hit >= 2) return true;
            }
        }
        return false;
    }

    static class Res { int status; String body; int len; }

    static Res get(String url, String referer, boolean useFast) {
        Res r = new Res();
        Request.Builder b = new Request.Builder().url(url)
                .header("User-Agent", UA).header("Accept", ACCEPT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (referer != null && !referer.isBlank()) b.header("Referer", referer);
        try (Response resp = (useFast ? fast : client).newCall(b.build()).execute()) {
            r.status = resp.code();
            byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
            r.body = new String(bytes, Charset.forName("UTF-8"));
            r.len = r.body.length();
        } catch (Exception e) {
            r.status = -1;
            r.body = null;
            r.len = 0;
        }
        return r;
    }

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(74));
        System.out.println(s);
        System.out.println("=".repeat(74));
    }
}
