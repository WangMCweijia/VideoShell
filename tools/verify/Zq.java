import okhttp3.*;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * 诊断 zqkhmy.com 为何识别失败：同一 URL 换 4 组请求头，看返回的是「裸 HTML」还是「JSON 转义串」。
 *
 * 判据：body 第一段是不是 `"<!DOCTYPE`（JSON 转义）还是 `<!DOCTYPE`（裸 HTML）。
 * 目的：确认「返回 JSON 转义」是否由 Accept 里的 application/json 触发 —— 若成立，
 * 修法是把请求头对齐浏览器，而不是去解包。
 */
public class Zq {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static void main(String[] a) throws Exception {
        String url = a.length > 0 ? a[0] : "https://www.zqkhmy.com/";
        OkHttpClient c = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS).followRedirects(true).build();

        run(c, url, "1 app 现状(Accept 含 application/json)", b -> b
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", url));

        run(c, url, "2 Accept 去掉 application/json", b -> b
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8"));

        run(c, url, "3 完整浏览器头(Sec-Fetch/Upgrade/watchdog)", b -> b
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Cache-Control", "max-age=0"));

        run(c, url, "4 只带 UA", b -> b.header("User-Agent", UA));

        run(c, url, "5 无 UA(裸 OkHttp)", b -> b.header("Accept", "text/html,*/*;q=0.8"));
    }

    interface H { Request.Builder apply(Request.Builder b); }

    static void run(OkHttpClient c, String url, String label, H h) {
        System.out.println("=".repeat(74));
        System.out.println(label);
        try (Response r = c.newCall(h.apply(new Request.Builder().url(url)).build()).execute()) {
            byte[] by = r.body() == null ? new byte[0] : r.body().bytes();
            String s = new String(by, Charset.forName("UTF-8"));
            String head = s.length() > 60 ? s.substring(0, 60) : s;
            boolean jsonWrapped = s.trim().startsWith("\"<");
            System.out.println("  code=" + r.code() + " bytes=" + by.length
                    + " ct=" + r.header("Content-Type"));
            System.out.println("  含转义 \\/  = " + s.contains("\\/"));
            System.out.println("  JSON转义串? " + jsonWrapped);
            System.out.println("  head: " + head.replace("\n", "\\n").replace("\r", ""));
            System.out.println("  set-cookie: " + r.headers("Set-Cookie"));
        } catch (Exception e) {
            System.out.println("  !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
