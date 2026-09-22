import com.videoshell.data.site.Media;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 验证 v1.0.9 的「取最内层媒体地址」修复（Media.innermost / extractFromHtml）。
 *
 * 两组用例：
 *  A. 合成用例 —— 各种"代理播放器把真地址塞进 ?url=" 的写法，以及不能误伤的正常地址
 *  B. 真实页面 —— 厂长资源 / 茶杯狐 两个线上播放页，直接用真实 HTML 跑
 */
public class Extract2 {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true).build();

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        banner("A. 合成用例：代理播放器嵌套地址");
        eq("普通直链原样返回",
                "https://a.com/x/index.m3u8",
                Media.INSTANCE.innermost("https://a.com/x/index.m3u8"));
        eq("外层代理 + 明文嵌套 -> 取内层",
                "https://m3hlsm3.py1080p.com:907/hls3/hls/a.m3u8",
                Media.INSTANCE.innermost(
                        "https://plaa.py1080p.com:8181/player/py.php?code=cs&if=1"
                                + "&url=https://m3hlsm3.py1080p.com:907/hls3/hls/a.m3u8"));
        eq("URL 编码嵌套 -> 解码后取内层",
                "https://m3hlsm3.py1080p.com:907/hls3/hls/a.m3u8",
                Media.INSTANCE.innermost(
                        "https://p.com/py.php?url=https%3A%2F%2Fm3hlsm3.py1080p.com%3A907"
                                + "%2Fhls3%2Fhls%2Fa.m3u8"));
        eq("含中文的内层地址（交给 encodeUrl 再编码）",
                "https://m3hlsm3.py1080p.com:907/hls3/hls/峡谷.m3u8",
                Media.INSTANCE.innermost(
                        "https://plaa.py1080p.com:8181/player/py.php?code=cs&if=1"
                                + "&url=https://m3hlsm3.py1080p.com:907/hls3/hls/峡谷.m3u8"));
        eq("?url= 指向的不是媒体 -> 不拆，保持原样",
                "https://p.com/page.html?url=https://p.com/about.html",
                Media.INSTANCE.innermost("https://p.com/page.html?url=https://p.com/about.html"));
        eq("路径里带 http 字样不会被误拆",
                "https://a.com/http-guide/index.m3u8",
                Media.INSTANCE.innermost("https://a.com/http-guide/index.m3u8"));

        ok("链式嵌套（两层）也能剥到最内层",
                Media.INSTANCE.innermost(
                        "https://p1.com/pl.php?url=https://p2.com/pl.php?url=https://r.com/a.m3u8")
                        .equals("https://r.com/a.m3u8"));

        banner("A2. iframe 形式（extractFromHtml 全链）");
        String html = "<html><body><iframe class=\"viframe\" src=\"https://plaa.py1080p.com:8181"
                + "/player/py.php?code=cs&if=1&url=https://m3hlsm3.py1080p.com:907/hls3/hls/峡谷.m3u8\""
                + " frameborder=\"no\"></iframe></body></html>";
        String got = Media.INSTANCE.extractFromHtml(html);
        eq("从 iframe src 里抠出真 m3u8",
                "https://m3hlsm3.py1080p.com:907/hls3/hls/峡谷.m3u8", got);
        ok("抠出的地址经 encodeUrl 变成纯 ASCII（播放器要的就是这个）",
                got != null && Media.INSTANCE.encodeUrl(got)
                        .equals("https://m3hlsm3.py1080p.com:907/hls3/hls/%E5%B3%A1%E8%B0%B7.m3u8"));

        banner("A3. player_aaaa 形式不能被改坏");
        String maccms = "<script>var player_aaaa={\"flag\":\"play\",\"url\":\"https:\\/\\/c1.dd.com"
                + "\\/video\\/bianshuiwangshi\\/\\u7b2c01\\u96c6\\/index.m3u8\"};</script>";
        String m = Media.INSTANCE.extractFromHtml(maccms);
        eq("player_aaaa 里的 \\uXXXX 中文路径正确还原",
                "https://c1.dd.com/video/bianshuiwangshi/第01集/index.m3u8", m);

        banner("B. 真实播放页");
        realCase("厂长资源-1080P-1", "https://czzy.app/v_play/bXZfMjAyOTQtbm1fMQ==.html",
                "https://czzy.app/movie/20294.html",
                "https://m3hlsm3.py1080p.com:907/hls3/hls/");
        realCase("茶杯狐-极速播放", "https://www.cupfoxyy.com/play/127866-2-1.html",
                "https://www.cupfoxyy.com", "fengbao12.com");
        realCase("茶杯狐-高清云播", "https://www.cupfoxyy.com/play/127866-1-1.html",
                "https://www.cupfoxyy.com", "cdn.yzzy31-play.com");

        System.out.println();
        System.out.println("================ pass=" + pass + " fail=" + fail + " ================");
        if (fail > 0) System.exit(1);
    }

    /** 抓真实页面 -> extractFromHtml -> 断言前缀 + 真的请求一次看返回什么 */
    static void realCase(String label, String url, String referer, String expectPrefix) {
        System.out.println("  · " + label + "  " + url);
        String html = fetch(url, referer);
        if (html == null) { bad(label + " 页面抓取失败"); return; }
        String u = Media.INSTANCE.extractFromHtml(html);
        System.out.println("      -> " + u);
        ok(label + " 抠到地址且以 " + expectPrefix + " 开头",
                u != null && u.startsWith("http") && u.contains(expectPrefix));
        ok(label + " 抠出的不是 HTML 代理页（不再含 .php?url=）",
                u != null && !u.contains(".php?"));
        if (u != null) {
            String ct = contentType(u, referer);
            ok(label + " 该地址返回的就是播放清单/媒体（ct=" + ct + "）",
                    ct.contains("mpegurl") || ct.contains("mp2t") || ct.contains("video"));
        }
    }

    static String fetch(String url, String referer) {
        try {
            Request.Builder b = new Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            if (referer != null) b.header("Referer", referer);
            try (Response r = client.newCall(b.build()).execute()) {
                if (r.code() != 200 || r.body() == null) return null;
                return r.body().string();
            }
        } catch (Exception e) {
            System.out.println("      !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    static String contentType(String url, String referer) {
        try {
            Request.Builder b = new Request.Builder().url(url)
                    .header("User-Agent", UA).header("Accept", "*/*");
            if (referer != null) b.header("Referer", referer);
            try (Response r = client.newCall(b.build()).execute()) {
                System.out.println("      HTTP " + r.code() + "  ct=" + r.header("Content-Type"));
                return String.valueOf(r.header("Content-Type"));
            }
        } catch (Exception e) {
            return "ERR " + e.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------------ 断言

    static void eq(String what, String expect, String got) {
        if (expect.equals(got)) {
            pass++;
            System.out.println("  [PASS] " + what);
        } else {
            fail++;
            System.out.println("  [FAIL] " + what);
            System.out.println("         期望: " + expect);
            System.out.println("         实际: " + got);
        }
    }

    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + what); }
        else { fail++; System.out.println("  [FAIL] " + what); }
    }

    static void bad(String what) {
        fail++;
        System.out.println("  [FAIL] " + what);
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("================ " + s + " ================");
    }
}
