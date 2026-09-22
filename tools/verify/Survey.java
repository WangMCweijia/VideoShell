import okhttp3.*;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * 播放页「媒体候选」勘察：模拟嗅探器会看到什么。
 *
 * 1) 用 App 同款 OkHttp 抓播放页（含 Referer）
 * 2) 在 HTML + 内联 JS 里挖出所有 m3u8 / mp4 / mpd / flv 地址（处理 反斜杠转义 与 反斜杠uXXXX 转义）
 * 3) 对每个 m3u8 实际 GET 一次，报出：状态码 / 是否 master 清单 / EXTINF 分片数
 *    —— 分片数是「正片 vs 广告」最硬的判据：正片几百条，广告 1~5 条
 *
 * 注意：Java 会在**注释和字符串**里预处理 反斜杠u 转义，所以本文件里
 * 拼接反斜杠一律用 BS 常量，绝不写出 反斜杠u 相邻的字面量。
 */
public class Survey {

    /** 单个反斜杠。Java 的 反斜杠u 预处理会吃掉字面量里的 反斜杠u 相邻序列，故用字符构造 */
    static final String BS = String.valueOf((char) 92);

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static final Pattern MEDIA = Pattern.compile(
            "https?://[^\\s\"'<>" + BS + BS + "\\)\\]\\}]+?\\.(?:m3u8|mp4|mpd|flv)"
                    + "(?:\\?[^\\s\"'<>" + BS + BS + "\\)\\]\\}]*)?",
            Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) throws Exception {
        String page = args[0];
        String referer = args.length > 1 ? args[1] : page;

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();

        System.out.println("=".repeat(78));
        System.out.println("PLAY PAGE  " + page);

        String html = get(client, page, referer);
        if (html == null) {
            System.out.println("  !! 页面抓取失败");
            return;
        }
        System.out.println("  页面字节=" + html.length() + "  <title>=" + title(html));

        // 归一化转义，让 反斜杠/ 形式的 URL 也能被扫到
        String flat = html.replace(BS + "/", "/")
                .replace(BS + BS, BS)
                .replace(BS + "u0026", "&")
                .replace(BS + "u003d", "=");
        flat = decodeUnicode(flat);

        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher m = MEDIA.matcher(flat);
        while (m.find()) found.add(clean(m.group()));

        System.out.println("  挖到媒体候选 " + found.size() + " 个：");
        int i = 0;
        for (String u : found) {
            i++;
            System.out.println();
            System.out.println("  --- 候选 " + i + " ---");
            System.out.println("  " + u);
            System.out.println("  上下文：" + context(flat, u, 90));
            if (u.toLowerCase().contains("m3u8")) probePlaylist(client, u, referer);
        }

        System.out.println();
        System.out.println("  === 页面内所有含 m3u8 / mp4 的行（去重后前 25 条） ===");
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (String ln : flat.split("[\\r\\n]+")) {
            String t = ln.trim();
            if (t.length() > 400) t = t.substring(0, 400) + "…";
            String low = t.toLowerCase();
            if (low.contains("m3u8") || low.contains(".mp4")) lines.add(t);
            if (lines.size() >= 25) break;
        }
        for (String ln : lines) System.out.println("  | " + ln);
    }

    static void probePlaylist(OkHttpClient client, String url, String referer) {
        try {
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Referer", referer)
                    .header("Accept", "*/*")
                    .build();
            try (Response r = client.newCall(req).execute()) {
                String body = r.body() != null ? r.body().string() : "";
                int extinf = count(body, "#EXTINF");
                boolean master = body.contains("#EXT-X-STREAM-INF");
                boolean vod = body.contains("#EXT-X-PLAYLIST-TYPE:VOD");
                String first = body.isEmpty() ? "" : body.split("[\\r\\n]+")[0];
                System.out.println("      HTTP " + r.code()
                        + "  ct=" + r.header("Content-Type")
                        + "  master=" + master + "  VOD=" + vod + "  分片数=" + extinf);
                System.out.println("      首行=" + first);
                if (master) {
                    Matcher mm = Pattern.compile("https?://[^\\s\"']+").matcher(body);
                    int n = 0;
                    while (mm.find() && n++ < 4) System.out.println("      变体 -> " + mm.group());
                } else if (extinf > 0) {
                    Matcher mm = Pattern.compile("[^\\r\\n]+\\.ts[^\\r\\n]*").matcher(body);
                    int n = 0;
                    while (mm.find() && n++ < 3) System.out.println("      分片 -> " + mm.group().trim());
                }
            }
        } catch (Exception e) {
            System.out.println("      !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    static String context(String s, String needle, int radius) {
        int i = s.indexOf(needle);
        if (i < 0) return "";
        int a = Math.max(0, i - radius), b = Math.min(s.length(), i + needle.length() + radius);
        return s.substring(a, b).replaceAll("[\\r\\n]+", " ");
    }

    static String clean(String u) {
        String s = u.trim();
        while (s.endsWith(".") || s.endsWith(",")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 处理 反斜杠uXXXX 形式的转义（含代理对） */
    static String decodeUnicode(String s) {
        Matcher m = Pattern.compile(BS + BS + "u([0-9a-fA-F]{4})").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String get(OkHttpClient client, String url, String referer) {
        try {
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", referer)
                    .build();
            try (Response r = client.newCall(req).execute()) {
                System.out.println("  页面 HTTP " + r.code() + "  " + r.request().url());
                byte[] b = r.body() != null ? r.body().bytes() : new byte[0];
                return decode(b, r.header("Content-Type"));
            }
        } catch (IOException e) {
            System.out.println("  !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    static String title(String s) {
        Matcher m = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE).matcher(s);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").trim() : "";
    }

    static String decode(byte[] bytes, String ct) {
        if (ct != null) {
            Matcher m = Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE).matcher(ct);
            if (m.find()) {
                try { return new String(bytes, Charset.forName(m.group(1))); } catch (Exception ignore) {}
            }
        }
        String u = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (u.indexOf('\uFFFD') >= 0) {
            try { return new String(bytes, Charset.forName("GBK")); } catch (Exception ignore) {}
        }
        return u;
    }
}
