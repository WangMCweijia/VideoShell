import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 野果封面现场复核（用户报「仍然没有封面」）。
 *
 * 分三段查，别一上来就怀疑解析：
 *   A. 解析层产出：列表/详情的 pic 到底有没有值、是不是绝对地址；
 *   B. 取图可达性：用 **App 自己那条 OkHttp 栈**（Coil 通过 callFactory 挂的就是它）真取一次；
 *   C. UA 敏感性：同一 URL 分别用 浏览器UA / 无UA / okhttp-UA / coil-UA 打 —— 图床按 UA 拒图的话这里会现形。
 */
public class YgCoverLive {

    static final String BASE = System.getProperty("vs.base", "https://www.yeguodj.com");
    static final String BROWSER_UA = Http.UA;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        new File("_ygcoverlive").mkdirs();
        System.out.println("BASE = " + BASE);

        SiteConfig site = new SiteConfig("yg", "野果短剧", BASE, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter a = AdapterFactory.INSTANCE.create(site);
        System.out.println("adapter = " + a.getClass().getSimpleName());

        // ---------------- A. 解析层产出
        List<VideoItem> items = block((s, c) -> a.browse("", 1, (Continuation<? super List<VideoItem>>) c));
        int blank = 0, rel = 0;
        List<String> pics = new ArrayList<>();
        for (VideoItem v : items) {
            String p = v.getPic() == null ? "" : v.getPic().trim();
            if (p.isEmpty()) blank++;
            else {
                if (!p.startsWith("http")) rel++;
                if (!pics.contains(p)) pics.add(p);
            }
        }
        System.out.println("\n=== A. 解析层 ===");
        System.out.println("列表 " + items.size() + " 条：空封面 " + blank + "，非绝对地址 " + rel
                + "，去重封面 " + pics.size());
        for (int i = 0; i < Math.min(5, items.size()); i++) {
            VideoItem v = items.get(i);
            System.out.println("  #" + i + " " + v.getName() + "\n      pic=" + v.getPic());
        }
        for (int i = 0; i < Math.min(5, pics.size()); i++) System.out.println("  pic[" + i + "]=" + pics.get(i));

        // 封面域名分布
        Map<String, Integer> hosts = new LinkedHashMap<>();
        for (String p : pics) {
            String h = p.replaceAll("^(https?://[^/]+).*$", "$1");
            hosts.put(h, hosts.getOrDefault(h, 0) + 1);
        }
        System.out.println("封面主机分布: " + hosts);

        // 详情页也看一眼
        if (!items.isEmpty()) {
            String id = items.get(0).getId();
            VideoDetail d = block((s, c) -> a.detail(id, (Continuation<? super VideoDetail>) c));
            System.out.println("详情 " + d.getName() + " pic=" + d.getPic()
                    + " 线路=" + d.getGroups().size());
            for (int i = 0; i < Math.min(3, d.getGroups().size()); i++) {
                PlayGroup g = d.getGroups().get(i);
                System.out.println("   线路#" + i + " " + g.getName() + " 集数=" + g.getEpisodes().size());
            }
        }

        // ---------------- B/C. 取图
        System.out.println("\n=== B. 取图（App 那条 OkHttp 栈，浏览器 UA） ===");
        for (int i = 0; i < Math.min(6, pics.size()); i++) {
            String u = pics.get(i);
            try {
                Object[] pr = block((s, c) -> {
                    Object p = Http.INSTANCE.probe(u, BASE + "/", null, (Continuation<? super kotlin.Pair<Integer, String>>) c);
                    return new Object[]{p};
                });
                @SuppressWarnings("unchecked")
                kotlin.Pair<Integer, String> p = (kotlin.Pair<Integer, String>) pr[0];
                System.out.println("  [" + p.getFirst() + "] " + trim(p.getSecond(), 70) + "  " + trim(u, 90));
            } catch (Throwable t) {
                System.out.println("  [EX] " + t + "  " + trim(u, 90));
            }
        }

        System.out.println("\n=== C. UA 敏感性对照（第一条封面） ===");
        if (!pics.isEmpty()) {
            String u = pics.get(0);
            uaTry(u, "浏览器UA", BROWSER_UA);
            uaTry(u, "无UA(okhttp默认)", null);
            uaTry(u, "okhttp/4.12.0", "okhttp/4.12.0");
            uaTry(u, "coil/2.4.0", "coil/2.4.0");
        }

        // 把首页原文落盘，便于核对封面是不是根本没解析出来
        try {
            String home = block((s, c) -> Http.INSTANCE.get(BASE, BASE, Http.UA,
                    Collections.<String, String>emptyMap(), false, (Continuation<? super String>) c));
            Files.write(new File("_ygcoverlive/home.html").toPath(),
                    (home == null ? "" : home).getBytes(StandardCharsets.UTF_8));
            System.out.println("\n首页 bytes=" + (home == null ? -1 : home.getBytes(StandardCharsets.UTF_8).length));
        } catch (Throwable t) {
            System.out.println("首页抓取失败 " + t);
        }
    }

    static void uaTry(String url, String label, String ua) {
        OkHttpClient c = Http.INSTANCE.getClient();
        Request.Builder b = new Request.Builder().url(url).header("Accept", "image/*,*/*;q=0.8");
        if (ua != null) b.header("User-Agent", ua);
        try (Response r = c.newCall(b.build()).execute()) {
            String ct = r.header("Content-Type");
            byte[] by = r.body() == null ? new byte[0] : r.body().bytes();
            System.out.println("  " + pad(label, 18) + " -> " + r.code() + "  " + ct + "  " + by.length + " B");
        } catch (Exception e) {
            System.out.println("  " + pad(label, 18) + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static String trim(String s, int n) { return s == null ? "null" : (s.length() <= n ? s : s.substring(0, n) + "…"); }
    static String pad(String s, int n) { StringBuilder b = new StringBuilder(s); while (b.length() < n) b.append(' '); return b.toString(); }
}
