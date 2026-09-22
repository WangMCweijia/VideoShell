import okhttp3.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * 验证：非 ASCII（中文）媒体路径在真实 CDN 上的行为，
 * 以及 OkHttp / HttpURLConnection 各自发出的「请求行」到底长什么样。
 *
 * 背景：上一轮 Compat 实验用 JDK 的 HttpURLConnection 测出「中文路径也 200」，
 * 据此排除了请求栈差异 —— 但 JDK 与 Android 对 URL 的处理并不是一回事。
 * 这个实验把「框架实际发出的字节」打出来，不再看框架的结论。
 */
public class Url {

    static OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    static final String UA =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36";

    public static void main(String[] args) throws Exception {
        // 用户 v1.0.2 报错地址里 \u7b2c01\u96c6 对应的真实中文
        String[] raws = new String[]{
                "https://c1.ddbbffcdn.com/video/bianshuiwangshi/第01集/index.m3u8",
                "https://c1.ddbbffcdn.com/video/bianshuiwangshi/1/index.m3u8",
        };

        banner("A. OkHttp 请求「原样中文 URL」—— 注意它内部自动编码成什么");
        for (String raw : raws) {
            probeOk(raw, raw);
        }

        banner("B. OkHttp 请求「手工百分号编码后 URL」");
        for (String raw : raws) {
            String enc = encode(raw);
            probeOk(enc, raw);
        }

        banner("C. JDK HttpURLConnection 请求「原样中文 URL」+ 抓原始请求行");
        for (String raw : raws) {
            probeHuc(raw, true);
        }

        banner("D. JDK HttpURLConnection 请求「手工编码后 URL」+ 抓原始请求行");
        for (String raw : raws) {
            probeHuc(encode(raw), false);
        }

        banner("E. 本地回环：直接看两套栈发出的 request-line 字节");
        loopback();

        System.out.println("\n===== 实验结束 =====");
    }

    /** 只把「非 ASCII」字符做 UTF-8 百分号编码，保留已有的 %XX 与结构字符 */
    static String encode(String u) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c < 128) {
                sb.append(c);
            } else {
                byte[] bs = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bs) sb.append('%').append(String.format("%02X", b));
            }
        }
        return sb.toString();
    }

    static void probeOk(String url, String forDisplay) {
        System.out.println("  URL(传入) : " + forDisplay);
        System.out.println("  URL(入参) : " + url);
        try (Response r = client.newCall(new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.cupfoxyy.com/")
                .build()).execute()) {
            System.out.println("  实际请求  : " + r.request().url());   // OkHttp 真正发出去的形式
            System.out.println("  -> HTTP " + r.code() + "  " + r.header("Content-Type"));
        } catch (Exception e) {
            System.out.println("  -> 异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.println();
    }

    static void probeHuc(String url, boolean raw) {
        System.out.println("  URL : " + url);
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", "https://www.cupfoxyy.com/");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(12_000);
            c.setInstanceFollowRedirects(true);
            int st = c.getResponseCode();
            System.out.println("  -> HTTP " + st + "  " + c.getHeaderField("Content-Type"));
            System.out.println("  -> getURL() : " + c.getURL());
            c.disconnect();
        } catch (Exception e) {
            System.out.println("  -> 异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.println();
    }

    /** 本地起一个只打印 request-line 的服务器，分别用 OkHttp 与 HttpURLConnection 打过去 */
    static void loopback() throws Exception {
        ServerSocket ss = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        int port = ss.getLocalPort();
        final String[] captured = new String[2];
        Thread t = new Thread(() -> {
            for (int k = 0; k < 2; k++) {
                try (Socket s = ss.accept()) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line = br.readLine();
                    captured[k] = line;
                    OutputStream os = s.getOutputStream();
                    os.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok").getBytes(StandardCharsets.ISO_8859_1));
                    os.flush();
                } catch (Exception ignore) { }
            }
        });
        t.setDaemon(true);
        t.start();

        String target = "http://127.0.0.1:" + port + "/video/第01集/index.m3u8";
        // 1) OkHttp
        try (Response r = client.newCall(new Request.Builder().url(target).build()).execute()) {
            r.body().string();
        }
        // 2) HttpURLConnection
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
            c.getResponseCode();
            c.getInputStream().close();
            c.disconnect();
        } catch (Exception e) {
            System.out.println("  HttpURLConnection 异常：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        t.join(4000);
        ss.close();

        System.out.println("  OkHttp 发出的 request-line      : " + captured[0]);
        System.out.println("  HttpURLConnection 发出的        : " + captured[1]);
        System.out.println();
        System.out.println("  >>> 若二者不同，说明请求栈确实存在 URL 处理差异；");
        System.out.println("      若 JDK 侧也编码了，那 JDK 上的\"两边都 200\"就只是 JDK 自己兜了底。");
    }

    static void banner(String s) {
        System.out.println("\n----------------- " + s + " -----------------");
    }
}
