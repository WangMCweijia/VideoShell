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

import java.util.*;

/**
 * v1.0.30 野果封面现场复核（用户报「仍然没有封面」，第二次）。
 *
 * 只做两件确定的事，绕开先前 Java 调 suspend `Http.probe` 的协程互操作坑：
 *   A. 解析层：列表 / 详情的 pic 有没有值、是不是绝对地址、落在哪些主机；
 *   B. 取图：**用 App 自己那条 OkHttp 栈**（Coil 通过 callFactory 挂的就是它）真取，
 *      浏览器 UA + Accept image/*，看 code / Content-Type / 字节数。
 *
 * 结论口径：A、B 都正常 ⇒ 线上解析与图床都没问题 ⇒ 用户侧多半是**设备 DNS 被污染**
 * （域名有答案但连不上），v1.0.30 的 sysDnsUsable 回落 DoH 正是针对这一态。
 */
public class YgCover2 {

    static final String BASE = System.getProperty("vs.base", "https://www.yeguodj.com");

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] a) throws Exception {
        System.out.println("BASE = " + BASE);
        SiteConfig site = new SiteConfig("yg", "野果短剧", BASE, "", SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter ad = AdapterFactory.INSTANCE.create(site);
        System.out.println("adapter = " + ad.getClass().getSimpleName());

        List<VideoItem> items = block((s, c) -> ad.browse("", 1, (Continuation<? super List<VideoItem>>) c));
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
        System.out.println("列表 " + items.size() + " 条：空封面 " + blank + "，非绝对地址 " + rel + "，去重封面 " + pics.size());
        for (int i = 0; i < Math.min(3, items.size()); i++) {
            VideoItem v = items.get(i);
            System.out.println("  #" + i + " " + v.getName() + "  pic=" + v.getPic());
        }
        Map<String, Integer> hosts = new LinkedHashMap<>();
        for (String p : pics) {
            String h = p.replaceAll("^(https?://[^/]+).*$", "$1");
            hosts.put(h, hosts.getOrDefault(h, 0) + 1);
        }
        System.out.println("封面主机分布: " + hosts);

        if (!items.isEmpty()) {
            String id = items.get(0).getId();
            VideoDetail d = block((s, c) -> ad.detail(id, (Continuation<? super VideoDetail>) c));
            System.out.println("详情 " + d.getName() + "  pic=" + d.getPic()
                    + "  线路=" + d.getGroups().size());
        }

        System.out.println("\n=== B. 取图（App 的 OkHttp 栈 + 浏览器 UA） ===");
        int n = Math.min(8, pics.size()), ok = 0;
        for (int i = 0; i < n; i++) {
            String u = pics.get(i);
            Request r = new Request.Builder().url(u)
                    .header("User-Agent", Http.UA)
                    .header("Accept", "image/*,*/*;q=0.8")
                    .build();
            try (Response resp = Http.INSTANCE.getClient().newCall(r).execute()) {
                byte[] by = resp.body() == null ? new byte[0] : resp.body().bytes();
                System.out.println("  [" + resp.code() + "] " + by.length + "B  "
                        + resp.header("Content-Type") + "  " + u);
                if (resp.code() == 200 && by.length > 0) ok++;
            } catch (Exception e) {
                System.out.println("  [EX] " + e.getClass().getSimpleName() + ": " + e.getMessage() + "  " + u);
            }
        }
        System.out.println("B 汇总：抓图成功 " + ok + "/" + n);
        System.out.println("\n==== pass=" + ok + " fail=" + (n - ok) + " ====");
    }
}
