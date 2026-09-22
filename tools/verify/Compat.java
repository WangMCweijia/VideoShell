import com.videoshell.data.site.Media;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 对照实验：同一个媒体地址，分别用
 *   A) OkHttp              —— 自检（Http.probe）用的栈
 *   B) HttpURLConnection   —— ExoPlayer DefaultHttpDataSource 用的栈
 * 相同 UA / Referer 去请求，比较状态码。
 *
 * 重点验证：含中文（非 ASCII）路径的 URL，两套栈的处理是否不同。
 */
public class Compat {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build();

    public static void main(String[] args) throws Exception {
        String playFast = args.length > 0 ? args[0] : "https://www.cupfoxyy.com/play/127866-2-1.html";
        String playHd = args.length > 1 ? args[1] : "https://www.cupfoxyy.com/play/127866-1-1.html";

        banner("① 从播放页抠直链（真实 Media.extractFromHtml）");
        String u1 = extract(playFast);
        String u2 = extract(playHd);
        System.out.println("极速播放 直链 = " + u1);
        System.out.println("高清云播 直链 = " + u2);

        for (String u : new String[]{u1, u2}) {
            if (u == null || u.isBlank()) continue;
            banner("② 媒体请求对照：" + u);
            probeOk(u, playFast);
            probeConn(u, playFast);
        }

        banner("③ 非 ASCII 路径编码差异（原始 bug 报告里的地址）");
        String raw = "https://c1.ddbbffcdn.com/video/bianshuiwangshi/\u7b2c01\u96c6/index.m3u8";
        String enc = "https://c1.ddbbffcdn.com/video/bianshuiwangshi/%E7%AC%AC01%E9%9B%86/index.m3u8";
        System.out.println("raw = " + raw);
        System.out.println("enc = " + enc);
        System.out.println("-- OkHttp(raw) --");
        probeOk(raw, "https://www.cupfoxyy.com/");
        System.out.println("-- HttpURLConnection(raw) --");
        probeConn(raw, "https://www.cupfoxyy.com/");
        System.out.println("-- HttpURLConnection(enc) --");
        probeConn(enc, "https://www.cupfoxyy.com/");
    }

    // ------------------------------------------------------------------ 步骤

    static String extract(String page) {
        try {
            Request.Builder b = new Request.Builder().url(page)
                    .header("User-Agent", UA)
                    .header("Accept", "*/*");
            try (Response r = client.newCall(b.build()).execute()) {
                String html = r.body() == null ? "" : new String(r.body().bytes(), StandardCharsets.UTF_8);
                String direct = Media.INSTANCE.extractFromHtml(html);
                System.out.println("  页面 " + page + " -> HTTP " + r.code() + " " + html.length() + " 字");
                return direct;
            }
        } catch (Exception e) {
            System.out.println("  页面 " + page + " 异常：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    static void probeOk(String url, String referer) {
        try {
            Request.Builder b = new Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "*/*");
            if (referer != null && !referer.isBlank()) b.header("Referer", referer);
            long t0 = System.currentTimeMillis();
            try (Response r = client.newCall(b.build()).execute()) {
                byte[] bytes = r.body() == null ? new byte[0] : r.body().bytes();
                long ms = System.currentTimeMillis() - t0;
                System.out.println("  [OkHttp] " + r.code() + "  " + ms + "ms  ct="
                        + r.header("Content-Type") + "  len=" + bytes.length);
                System.out.println("           实际请求 URL = " + r.request().url());
                if (bytes.length > 0) {
                    String head = new String(bytes, StandardCharsets.UTF_8);
                    for (String ln : head.split("\n")) {
                        if (!ln.trim().isEmpty() && !ln.startsWith("#")) {
                            System.out.println("           首个分片 = " + ln.trim());
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  [OkHttp] 异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static void probeConn(String url, String referer) {
        String effective = url;
        try {
            // 复刻 DefaultHttpDataSource：new URL(uri.toString()) + HttpURLConnection
            URL u = new URL(url);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (referer != null && !referer.isBlank()) c.setRequestProperty("Referer", referer);
            long t0 = System.currentTimeMillis();
            int code = c.getResponseCode();
            long ms = System.currentTimeMillis() - t0;
            byte[] bytes = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            System.out.println("  [HttpURLConnection] " + code + "  " + ms + "ms  ct="
                    + c.getHeaderField("Content-Type") + "  len=" + bytes.length);
            System.out.println("           URL.getPath() = " + u.getPath());
            Map<String, List<String>> hs = c.getHeaderFields();
            System.out.println("           Server = " + hs.get("Server") + "  Via = " + hs.get("Via"));
            if (bytes.length > 0 && code < 400) {
                String head = new String(bytes, StandardCharsets.UTF_8);
                for (String ln : head.split("\n")) {
                    if (!ln.trim().isEmpty() && !ln.startsWith("#")) {
                        System.out.println("           首个分片 = " + ln.trim());
                        break;
                    }
                }
            }
            c.disconnect();
        } catch (Exception e) {
            System.out.println("  [HttpURLConnection] 异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static byte[] readAll(InputStream in) {
        if (in == null) return new byte[0];
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("========== " + s + " ==========");
    }
}
