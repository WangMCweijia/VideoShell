import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import com.videoshell.data.site.Media;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 用 App 里**真实的** Media.extractFromHtml 去解析两个真实播放页：
 *  - 茶杯狐 /play/127866-2-1.html（极速播放）
 *  - 厂长资源 /v_play/bXZfMjAyOTQtbm1fMQ==.html（iframe 里包着真 m3u8）
 *
 * 目的是回答"解析出来的地址到底是不是能播的那个"——这一步以前从没被单独验证过。
 */
public class Extract {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true).build();

    public static void main(String[] args) throws Exception {
        case1("茶杯狐-极速播放", "https://www.cupfoxyy.com/play/127866-2-1.html",
                "https://www.cupfoxyy.com");
        case1("茶杯狐-高清云播", "https://www.cupfoxyy.com/play/127866-1-1.html",
                "https://www.cupfoxyy.com");
        case1("厂长资源-1080P-1", "https://czzy.app/v_play/bXZfMjAyOTQtbm1fMQ==.html",
                "https://czzy.app/movie/20294.html");
    }

    static void case1(String label, String url, String referer) {
        System.out.println();
        System.out.println("================ " + label + " ================");
        System.out.println("播放页: " + url);
        String html;
        try {
            Request.Builder b = new Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", referer);
            try (Response r = client.newCall(b.build()).execute()) {
                System.out.println("HTTP " + r.code() + "  ct=" + r.header("Content-Type"));
                html = r.body() == null ? "" : r.body().string();
            }
        } catch (Exception e) {
            System.out.println("!! 抓取失败 " + e);
            return;
        }
        System.out.println("页面长度 = " + html.length());
        String name = label.replaceAll("[^A-Za-z0-9_-]", "_");
        try {
            Files.write(Paths.get(name + ".html"), html.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) {
        }

        String u = Media.INSTANCE.extractFromHtml(html);
        System.out.println("extractFromHtml -> " + u);
        if (u != null) {
            System.out.println("   looksLikeMedia = " + Media.INSTANCE.looksLikeMedia(u));
            System.out.println("   isHls          = " + Media.INSTANCE.isHls(u));
            System.out.println("   encodeUrl      = " + Media.INSTANCE.encodeUrl(u));
            System.out.println("   含嵌套 http    = " + (u.indexOf("http", 6) > 0));
            // 再发一次请求，看这个地址到底是什么
            probe(u, referer);
        }
        System.out.println("   页面里含 noplay = " + html.contains("noplay")
                + "  含「登录后」 = " + html.contains("登录后"));
    }

    static void probe(String url, String referer) {
        try {
            Request.Builder b = new Request.Builder().url(url)
                    .header("User-Agent", UA).header("Accept", "*/*");
            if (referer != null) b.header("Referer", referer);
            try (Response r = client.newCall(b.build()).execute()) {
                String ct = r.header("Content-Type");
                String body = r.body() == null ? "" : r.body().string();
                System.out.println("   -> 请求该地址: HTTP " + r.code() + "  ct=" + ct
                        + "  len=" + body.length());
                System.out.println("   -> 前 160 字: "
                        + body.substring(0, Math.min(160, body.length())).replaceAll("\\s+", " "));
            }
        } catch (Exception e) {
            System.out.println("   -> 请求该地址失败: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }
}
