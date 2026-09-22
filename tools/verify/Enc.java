import com.videoshell.data.site.Media;
import okhttp3.*;

import java.util.concurrent.TimeUnit;

/**
 * v1.0.7 断言：Media.encodeUrl —— 把非 ASCII 媒体路径规范成纯 ASCII。
 *
 * 这一条修复对应的是「自检全绿、播放全挂」：
 * ExoPlayer 的 DefaultHttpDataSource 底层是 HttpURLConnection，不做百分号编码；
 * 而站点 CDN 的媒体路径经常含中文（如 /video/bianshuiwangshi/第01集/index.m3u8）。
 */
public class Enc {

    static int pass = 0, fail = 0;

    static OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + what); }
        else { fail++; System.out.println("  [FAIL] " + what); }
    }

    static void eq(String what, String expect, String actual) {
        boolean c = expect.equals(actual);
        if (c) { pass++; System.out.println("  [PASS] " + what); }
        else {
            fail++;
            System.out.println("  [FAIL] " + what);
            System.out.println("         期望: " + expect);
            System.out.println("         实际: " + actual);
        }
    }

    static String enc(String s) { return Media.INSTANCE.encodeUrl(s); }

    public static void main(String[] args) throws Exception {
        banner("1. 纯 ASCII 必须原样返回（不能动任何字节，否则会破坏正常地址）");
        String[] ascii = {
                "https://fengbao12.com/video/MISSIONgejubandeqianrusouchaguan/d577601c75a6/index.m3u8",
                "https://czzy.app/v_play/bXZfODQ5LW5tXzE=.html",
                "https://cdn.example.com/a/b/c.m3u8?sign=abc123&t=1700000000",
                "http://127.0.0.1:8080/x.ts",
        };
        for (String a : ascii) eq("原样: " + a.substring(0, Math.min(a.length(), 56)), a, enc(a));

        banner("2. 中文路径必须编码（这是播放 404 的根因）");
        eq("边水往事 第01集",
                "https://c1.ddbbffcdn.com/video/bianshuiwangshi/%E7%AC%AC01%E9%9B%86/index.m3u8",
                enc("https://c1.ddbbffcdn.com/video/bianshuiwangshi/第01集/index.m3u8"));
        eq("单个中文 《庆余年》",
                "https://x.com/a/%E5%BA%86%E4%BD%99%E5%B9%B4/1.m3u8",
                enc("https://x.com/a/庆余年/1.m3u8"));

        banner("3. 已经是 %XX 的不能二次编码（否则会把 % 变成 %25）");
        eq("已编码保持不动",
                "https://c1.ddbbffcdn.com/video/bianshuiwangshi/%E7%AC%AC01%E9%9B%86/index.m3u8",
                enc("https://c1.ddbbffcdn.com/video/bianshuiwangshi/%E7%AC%AC01%E9%9B%86/index.m3u8"));
        eq("混合：已编码 + 未编码中文",
                "https://x.com/%E5%BA%86/abc%E5%B9%B4/1.m3u8",
                enc("https://x.com/%E5%BA%86/abc年/1.m3u8"));

        banner("4. 空格与其他必须转义的 ASCII");
        eq("空格 -> %20", "https://x.com/a%20b/c.m3u8", enc("https://x.com/a b/c.m3u8"));
        eq("双引号 -> %22", "https://x.com/a%22b.m3u8", enc("https://x.com/a\"b.m3u8"));

        banner("5. 幂等性：编码两次 == 编码一次");
        String once = enc("https://c1.ddbbffcdn.com/video/bianshuiwangshi/第01集/index.m3u8");
        eq("encode(encode(x)) == encode(x)", once, enc(once));

        banner("6. 代理对（补充平面字符）必须是 4 字节而非两个 3 字节");
        // U+1F600 😀 -> F0 9F 98 80
        eq("emoji 4 字节", "https://x.com/%F0%9F%98%80/1.m3u8", enc("https://x.com/\uD83D\uDE00/1.m3u8"));

        banner("7. 联网：编码后的中文地址在真实 CDN 上必须能拿到内容");
        String cn = "https://c1.ddbbffcdn.com/video/bianshuiwangshi/第01集/index.m3u8";
        for (String u : new String[]{cn, enc(cn)}) {
            try (Response r = client.newCall(new Request.Builder()
                    .url(u)
                    .header("User-Agent",
                            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Referer", "https://www.cupfoxyy.com/")
                    .build()).execute()) {
                boolean good = r.code() == 200 || r.code() == 206;
                System.out.println("  " + (u.equals(cn) ? "原始中文" : "编码后  ") + " -> HTTP " + r.code());
                ok("CDN 可达（" + r.code() + "）", good);
            } catch (Exception e) {
                System.out.println("  异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ok("CDN 可达", false);
            }
        }

        System.out.println("\n===== 断言结果：pass=" + pass + " fail=" + fail
                + (fail == 0 ? "  ALL CHECKS PASSED" : "  SOME CHECKS FAILED") + " =====");
        if (fail > 0) System.exit(1);
    }

    static void banner(String s) {
        System.out.println("\n----------------- " + s + " -----------------");
    }
}
