import okhttp3.*;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** 用 App 完全相同的 OkHttp 请求姿态打站点，并把响应体落盘，用于离线复核。 */
public class Net {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    static String outDir = ".";

    public static void main(String[] args) throws Exception {
        outDir = args[0];
        Files.createDirectories(Paths.get(outDir));
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .build();

        int idx = 0;
        for (int i = 1; i < args.length; i++) {
            String spec = args[i];
            // 形式： url#name
            String url = spec, name = "net" + (idx++);
            int h = spec.indexOf('#');
            if (h >= 0) {
                url = spec.substring(0, h);
                name = spec.substring(h + 1);
            }
            System.out.println("=".repeat(72));
            System.out.println("[" + name + "] " + url);
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;application/json;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", url)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                System.out.println("  proto=" + resp.protocol() + " code=" + resp.code()
                        + " final=" + resp.request().url() + " server=" + resp.header("Server"));
                byte[] b = resp.body() != null ? resp.body().bytes() : new byte[0];
                String ct = resp.header("Content-Type");
                String s = decode(b, ct);
                System.out.println("  bytes=" + b.length + " ct=" + ct);
                Files.write(Paths.get(outDir, name + ".html"), s.getBytes("UTF-8"));
                System.out.println("  saved -> " + name + ".html");
                System.out.println("  /detail/=" + s.contains("/detail/")
                        + " /vod/1.html=" + s.contains("/vod/1.html")
                        + " /vodshow/=" + s.contains("/vodshow/")
                        + " player_aaaa=" + s.contains("player_aaaa")
                        + " <title>=" + title(s));
            } catch (IOException e) {
                System.out.println("  !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    static String title(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<title[^>]*>([\\s\\S]*?)</title>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(s);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").trim() : "";
    }

    static String decode(byte[] bytes, String ct) {
        if (ct != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(ct);
            if (m.find()) {
                try {
                    return new String(bytes, Charset.forName(m.group(1)));
                } catch (Exception ignore) {
                }
            }
        }
        String u = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (u.contains("\uFFFD")) {
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception ignore) {
            }
        }
        return u;
    }
}
