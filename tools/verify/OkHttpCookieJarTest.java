import com.sun.net.httpserver.HttpServer;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ## 决定性实验：OkHttp 的 CookieJar 会不会**覆盖**我们手写的 `Cookie` 头？
 *
 * 这是「扫码登录成功 → 过一会儿就提示登录已过期」这条线上唯一的可疑机理，
 * 必须用**真请求**判死，不能靠读文档猜。
 *
 * ### 现象与假设
 *
 * 网盘的登录态是 `DriveStore` 里的一整份 cookie（`__pus` / `__puus` / `__uid` …），
 * 由 [com.videoshell.data.net.Http] 以**显式 `Cookie` 头**挂上去；而同一个 client 上
 * 还挂着服务于站点解析的 CookieJar（不分桶、交给 `Cookie.matches` 判域）。
 *
 * 第一次请求时 jar 是空的 ⇒ 手写头生效 ⇒ 校验通过（用户看到"登录成功"）。
 * 但那次响应里服务端**必定**会 `Set-Cookie`（刷新 `__puus`，或下个埋点 cookie），
 * 于是 jar 对这个 host 非空；下一次请求 jar 返回非空列表 ⇒ OkHttp 的
 * `BridgeInterceptor` 用 jar 里那点 cookie **整条替换**手写头 ⇒ 完整登录态丢失 ⇒
 * `code:31001 require login` ⇒ `markExpired` ⇒ 账号页显示"登录已过期"。
 *
 * ### 为什么这个实验能判死
 *
 * 起一个本地 HTTP 服务（JDK 自带 `HttpServer`，**不联网**），让它像真站点一样
 * 每次响应都 `Set-Cookie`。两次请求都手写同一份 `Cookie: mine=AAA`，只看
 * **服务器第二次实际收到什么**：
 *
 * - 收到 `srvmark=1`（服务端那份）⇒ **覆盖成立**，假设成立；
 * - 收到 `mine=AAA` ⇒ 覆盖不成立，得另找原因（例如服务端轮换 `__puus`）。
 *
 * 三个场景各自独立（各用新的 client / 新的 jar），保证「只改一个变量」：
 * A 现状；B 只把「显式头另存 tag、在 network 位写回」补上；C 把 jar 摘掉（对照组，
 * 证明观察点本身是有效的 —— 若 C 都看不到 `mine=AAA`，说明实验装置是坏的）。
 */
public class OkHttpCookieJarTest {

    static final List<String> seen = new CopyOnWriteArrayList<>();
    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    static void ok(String name, boolean good, String extra) {
        if (good) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; fails.add(name + "  " + extra); System.out.println("  [FAIL] " + name + "   " + extra); }
    }

    /** 照抄 `Http.kt` 的 cookieJar：**不分桶**，一律交给 `Cookie.matches` 判该不该发 */
    static CookieJar jar() {
        return new CookieJar() {
            final LinkedHashMap<String, Cookie> store = new LinkedHashMap<>();
            String k(Cookie c) { return c.name() + "|" + c.domain() + "|" + c.path(); }

            @Override public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                long now = System.currentTimeMillis();
                for (Cookie c : cookies) {
                    if (c.expiresAt() <= now) store.remove(k(c)); else store.put(k(c), c);
                }
            }

            @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
                long now = System.currentTimeMillis();
                List<Cookie> out = new ArrayList<>();
                for (Cookie c : store.values()) if (c.expiresAt() > now && c.matches(url)) out.add(c);
                return out;
            }
        };
    }

    /** 显式 Cookie 的暂存槽（与 `Http.kt` 里的实现同名同义） */
    static final class OwnCookie { final String value; OwnCookie(String v) { value = v; } }

    /** 把**手写**的 Cookie 另存进 tag —— application 位，早于 BridgeInterceptor */
    static final Interceptor STASH = chain -> {
        Request req = chain.request();
        String mine = req.header("Cookie");
        if (mine == null || mine.isEmpty()) return chain.proceed(req);
        return chain.proceed(req.newBuilder().tag(OwnCookie.class, new OwnCookie(mine)).build());
    };

    /** 在 network 位把手写值写回 —— BridgeInterceptor **之后**，所以是最后写入者 */
    static final Interceptor RESTORE = chain -> {
        Request req = chain.request();
        OwnCookie mine = req.tag(OwnCookie.class);
        if (mine == null) return chain.proceed(req);
        return chain.proceed(req.newBuilder().header("Cookie", mine.value).build());
    };

    static void get(OkHttpClient c, String url, String cookie, String page) throws Exception {
        Request.Builder b = new Request.Builder().url(url).get();
        if (cookie != null) b.header("Cookie", cookie);          // 模拟 DriveStore 那份整份 cookie
        if (page != null) b.header("Referer", page);
        try (Response r = c.newCall(b.build()).execute()) {
            r.body().string();
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api", ex -> {
            String ck = ex.getRequestHeaders().getFirst("Cookie");
            seen.add(ck == null ? "(无)" : ck);
            byte[] body = "{\"code\":0}".getBytes("UTF-8");
            ex.getResponseHeaders().add("Content-Type", "application/json");
            // ★ 真实站点必发：刷新登录 token / 下埋点 cookie。这条是实验的全部前提。
            ex.getResponseHeaders().add("Set-Cookie", "srvmark=1; Path=/");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        srv.start();
        String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/api";
        String MINE = "__pus=aaa; __puus=bbb; __uid=ccc";

        // ---------------------------------------------------------------- A 现状
        seen.clear();
        OkHttpClient a = new OkHttpClient.Builder().cookieJar(jar()).build();
        get(a, url, MINE, "https://pan.quark.cn/");
        get(a, url, MINE, "https://pan.quark.cn/");
        String a1 = seen.get(0), a2 = seen.get(1);
        System.out.println("A 第 1 次服务器收到 : " + a1);
        System.out.println("A 第 2 次服务器收到 : " + a2);
        ok("A1 第 1 次请求：手写 Cookie 生效（jar 还空着）", a1.contains("__puus=bbb"), a1);
        ok("A2 第 2 次请求：**手写 Cookie 被 jar 整条覆盖** ⇒ 登录态丢失（这就是线上症状）",
                !a2.contains("__puus") && a2.contains("srvmark"), a2);
        ok("A3 对照组：服务端确实发了 Set-Cookie（否则 A2 是别的原因）",
                a2.contains("srvmark"), a2);

        // ---------------------------------------------------------------- B 修法
        seen.clear();
        OkHttpClient b = new OkHttpClient.Builder()
                .cookieJar(jar())
                .addInterceptor(STASH)
                .addNetworkInterceptor(RESTORE)
                .build();
        get(b, url, MINE, "https://pan.quark.cn/");
        get(b, url, MINE, "https://pan.quark.cn/");
        get(b, url, MINE, "https://pan.quark.cn/");
        System.out.println("B 三次服务器收到     : " + seen);
        boolAll("B1 修复后：每次请求手写 Cookie 都完整送达（含 __puus）", seen, "__puus=bbb");

        // ---------------------------------------------------------------- C 装置有效性
        seen.clear();
        OkHttpClient c = new OkHttpClient.Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .addInterceptor(STASH)
                .addNetworkInterceptor(RESTORE)
                .build();
        get(c, url, MINE, "https://pan.quark.cn/");
        get(c, url, MINE, "https://pan.quark.cn/");
        System.out.println("C 摘掉 jar 后        : " + seen);
        boolAll("C1 对照组：观察点有效（摘了 jar 就一定看得到手写值）", seen, "__puus=bbb");

        // ---------------------------------------------------------------- D 无显式头时不许留下痕迹
        seen.clear();
        get(b, url, null, null);
        System.out.println("D 不带显式 Cookie    : " + seen);
        ok("D1 没显式 Cookie 时，jar 照常工作（站点解析不受影响）",
                !seen.isEmpty() && seen.get(0).contains("srvmark"),
                seen.isEmpty() ? "(空)" : seen.get(0));

        // ---------------------------------------------------------------- E 位置敏感性（对照）
        // 把 RESTORE 误挂成 application 拦截器 —— 它就跑在 BridgeInterceptor **之前**，
        // 于是照旧被覆盖。这条**没有任何报错、编译也过**，是本层最阴的写错方式，
        // 所以必须钉死"挂在 network 位"。
        seen.clear();
        OkHttpClient e = new OkHttpClient.Builder()
                .cookieJar(jar())
                .addInterceptor(STASH)
                .addInterceptor(RESTORE)        // ← 故意挂错位置
                .build();
        get(e, url, MINE, "https://pan.quark.cn/");
        get(e, url, MINE, "https://pan.quark.cn/");
        System.out.println("E 挂错位置（application 位）: " + seen);
        ok("E1 对照：RESTORE 挂 application 位 ⇒ **静默失效**（第 2 次仍被覆盖）",
                seen.size() == 2 && !seen.get(1).contains("__puus"), String.valueOf(seen));

        srv.stop(0);
        System.out.println();
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        for (String f : fails) System.out.println("  FAIL " + f);
        System.exit(fail == 0 ? 0 : 1);
    }

    static void boolAll(String name, List<String> got, String needle) {
        int hit = 0;
        for (String s : got) if (s.contains(needle)) hit++;
        ok(name, got.size() > 0 && hit == got.size(), "命中 " + hit + "/" + got.size() + " " + got);
    }
}
