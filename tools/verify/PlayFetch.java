import com.videoshell.data.site.Media;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 取证：详情页 → 每个线路取前 2 集 → 抓真实播放页 → 跑 Media.extractFromHtml
 * 统计「点集能不能直接播」（抠不出直链 = 只能靠嗅探）。
 *
 * 输出：_play/ 下的页面 + 控制台逐条结果。
 */
public class PlayFetch {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    static final String BASE = "https://www.zqkhmy.com";
    static final Pattern PLAY = Pattern.compile("/play/(\\d+)-(\\d+)-(\\d+)\\.html");

    public static void main(String[] a) throws Exception {
        OkHttpClient c = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS).followRedirects(true).build();

        File dir = new File("_play");
        dir.mkdirs();
        File det = new File("_zq/zq_detail_20245.html");
        if (!det.exists()) {
            System.out.println("缺少夹具 " + det.getPath());
            return;
        }
        Document d = Jsoup.parse(new String(Files.readAllBytes(det.toPath()),
                StandardCharsets.UTF_8), BASE);

        // 按线号（URL 第 2 段）分组，每组保留前 2 个
        Map<String, List<String>> byLine = new LinkedHashMap<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element el : d.select("a[href]")) {
            String h = el.attr("href");
            Matcher m = PLAY.matcher(h);
            if (!m.find()) continue;
            String abs = m.group(0).startsWith("http") ? m.group(0) : BASE + m.group(0);
            if (!seen.add(abs)) continue;
            byLine.computeIfAbsent(m.group(2), k -> new ArrayList<>()).add(abs);
        }
        System.out.println("线数=" + byLine.size() + " 播放页总数=" + seen.size());

        int ok = 0, fail = 0, idx = 0;
        for (Map.Entry<String, List<String>> e : byLine.entrySet()) {
            List<String> ls = e.getValue();
            for (int i = 0; i < Math.min(2, ls.size()); i++) {
                String url = ls.get(i);
                idx++;
                String html = null;
                int code = -1;
                try {
                    Request req = new Request.Builder().url(url)
                            .header("User-Agent", UA)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                            .header("Referer", BASE + "/")
                            .build();
                    try (Response r = c.newCall(req).execute()) {
                        code = r.code();
                        html = r.body() == null ? null : r.body().string();
                    }
                } catch (Exception ex) {
                    System.out.println(idx + " " + url + " !! " + ex.getClass().getSimpleName());
                    fail++;
                    continue;
                }
                String real = Media.INSTANCE.extractFromHtml(html);
                String tag = "line" + e.getKey() + "#" + (i + 1);
                if (real != null) {
                    ok++;
                    System.out.println(idx + " " + tag + " code=" + code + " OK  " + trim(real));
                } else {
                    fail++;
                    int bytes = html == null ? 0 : html.getBytes(StandardCharsets.UTF_8).length;
                    System.out.println(idx + " " + tag + " code=" + code + " FAIL bytes=" + bytes
                            + " iframe=" + countIframe(html) + " urlKey=" + countUrlKey(html)
                            + "  " + url);
                    if (html != null) {
                        File out = new File(dir, "p" + idx + "_line" + e.getKey() + ".html");
                        Files.write(out.toPath(), html.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }
        System.out.println("==== ok=" + ok + " fail=" + fail + " ====");
    }

    static int countIframe(String h) {
        if (h == null) return -1;
        Matcher m = Pattern.compile("<iframe", Pattern.CASE_INSENSITIVE).matcher(h);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    static int countUrlKey(String h) {
        if (h == null) return -1;
        Matcher m = Pattern.compile("\"url\"\\s*:", Pattern.CASE_INSENSITIVE).matcher(h);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    static String trim(String s) {
        return s.length() <= 110 ? s : s.substring(0, 110) + "…";
    }
}
