import okhttp3.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * 抓 zqkhmy.com 的真实详情页落盘（必须用 OkHttp —— 该站 WAF 挑请求姿态，urllib 会被挡）。
 *
 * 用途：分集列表「多条播放源被合成一条」的取证样本。
 * 输出到 `_zq/zq_detail_<id>.html`，原始字节，不做任何改写。
 */
public class ZqFetch {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static void main(String[] a) throws Exception {
        String base = "https://www.zqkhmy.com";
        String[] ids = a.length > 0 ? a : new String[]{"50767", "20245", "64637", "93950", "76269"};
        OkHttpClient c = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS).followRedirects(true).build();
        File dir = new File("_zq");
        dir.mkdirs();
        for (String id : ids) {
            String url = base + "/detail/" + id + ".html";
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", base + "/")
                    .build();
            try (Response r = c.newCall(req).execute()) {
                byte[] by = r.body() == null ? new byte[0] : r.body().bytes();
                String s = new String(by, StandardCharsets.UTF_8);
                File out = new File(dir, "zq_detail_" + id + ".html");
                Files.write(out.toPath(), by);
                System.out.println("id=" + id + " code=" + r.code() + " bytes=" + by.length
                        + " jsonWrapped=" + s.trim().startsWith("\"<") + " -> " + out.getPath());
            } catch (Exception e) {
                System.out.println("id=" + id + " !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
}
