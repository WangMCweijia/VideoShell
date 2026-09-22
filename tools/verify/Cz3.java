import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 厂长资源播放页复核：网页端到底靠什么把片子放出来？
 * 把播放页原文落盘，并把「播放器配置 / 接口 / 媒体地址」的痕迹全部打出来。
 */
public class Cz3 {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true).build();

    public static void main(String[] args) throws Exception {
        String play = args.length > 0 ? args[0]
                : "https://czzy.app/v_play/bXZfMjAyOTQtbm1fMQ==.html";
        String referer = args.length > 1 ? args[1] : "https://czzy.app/movie/20294.html";

        System.out.println("=========== 播放页 " + play + " ===========");
        System.out.println("Referer = " + referer);
        Request.Builder b = new Request.Builder().url(play)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", referer);
        String html;
        try (Response r = client.newCall(b.build()).execute()) {
            System.out.println("HTTP " + r.code() + "  " + r.header("Content-Type")
                    + "  final=" + r.request().url());
            html = r.body() == null ? "" : r.body().string();
        }
        Files.write(Paths.get("cz_playpage.html"), html.getBytes(StandardCharsets.UTF_8));
        System.out.println("长度=" + html.length() + "（已存 cz_playpage.html）");

        for (String kw : new String[]{"noplay", "登录后", "请登录", "立即登录", "player_aaaa",
                "m3u8", ".mp4", "axios", "XMLHttpRequest", "fetch(", "/api/", "wp-json",
                "vod_play", "iframe", "Player"}) {
            if (html.contains(kw)) System.out.println("   含 " + kw);
        }

        System.out.println("\n  --- m3u8 / mp4 痕迹 ---");
        for (String s : ctx(html, "m3u8", 8)) System.out.println("     " + s);
        for (String s : ctx(html, ".mp4", 5)) System.out.println("     " + s);

        System.out.println("\n  --- 播放器/接口痕迹 ---");
        for (String s : ctx(html, "ajax", 5)) System.out.println("     " + s);
        for (String s : ctx(html, "/api/", 5)) System.out.println("     " + s);
        for (String s : ctx(html, "wp-json", 5)) System.out.println("     " + s);

        System.out.println("\n  --- 所有 script src ---");
        for (String s : uniq(regex(html, "<script[^>]+src=['\"]([^'\"]+)"))) System.out.println("     " + s);

        System.out.println("\n  --- 播放器容器附近 1500 字 ---");
        int i = html.indexOf("noplay");
        if (i < 0) i = html.indexOf("player");
        if (i < 0) i = html.indexOf("video");
        if (i >= 0) System.out.println(html.substring(Math.max(0, i - 700),
                Math.min(html.length(), i + 1500)));

        System.out.println("\n  --- body 里的 iframe / 数据脚本（去空白）---");
        for (String s : regex(html, "(<iframe[^>]*>)")) System.out.println("     " + s);
        for (String s : ctx(html, "var ", 8)) System.out.println("     " + s);
    }

    static List<String> regex(String s, String re) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(re, Pattern.CASE_INSENSITIVE).matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static List<String> uniq(List<String> in) { return new ArrayList<>(new LinkedHashSet<>(in)); }

    static List<String> ctx(String s, String kw, int max) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (out.size() < max && (i = s.indexOf(kw, i)) >= 0) {
            out.add(s.substring(Math.max(0, i - 100), Math.min(s.length(), i + 130))
                    .replaceAll("\\s+", " "));
            i += kw.length();
        }
        return out;
    }
}
