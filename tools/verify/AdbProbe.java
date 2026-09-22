import com.videoshell.data.net.AdBlock;
import com.videoshell.data.net.Http;
import com.videoshell.util.ExtKt;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.*;

/**
 * 去广告覆盖勘察：拿一个**真实页面**，把它的子资源逐条喂给**真判据**（`AdBlock`），
 * 然后按"用户能不能看见"分类汇报漏了什么。
 *
 * ## 为什么不是"照着 AdBlock 另写一遍判据"
 *
 * 那样测的是我抄得对不对，不是产品行为。这里走 `_cp.classpath()` 加载**真编译产物**，
 * 判据与 App 用的是同一份字节码 —— 与 `Adb.java` 同样的口径。
 *
 * ## 为什么按主机汇总，而不是直接列 URL
 *
 * 「广告仍然很多」是个**体感**描述，要落成可动手的清单，得先回答两件事：
 *   1. 页面上的第三方主机里，哪些**根本不在** `AD_HOSTS`（= 判据不认识它）；
 *   2. 用户**肉眼能看见**的那类广告（iframe / 图片位）有没有被拦。
 * 只列 URL 得到的是几百行噪声，没人能从中判断"该加什么"。
 *
 * ## 判据的界限（本工具刻意不越界）
 *
 * `AdBlock` 只拦"域名本身就是广告/统计服务"和"整段等于广告词的路径"。**同域广告**
 * （站点把自己的 banner 图放在 `/uploads/…` 下、链接跳自家 `/ad/`）在这套判据里
 * **不可能**被拦 —— 因为仅凭 URL 无法把它和正文图区分开。所以本工具把这类单独归到
 * 「同域待人工判断」，不要试图让 URL 判据去解决它。
 *
 * 用法：由 `runadbprobe.py` 调用，argv[0] = 页面地址。
 */
public class AdbProbe {

    static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    static String pageUrl;
    static String pageRoot;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    static String get(String url) {
        try {
            return block((s, c) -> Http.INSTANCE.get(
                    url, pageRoot, UA, Collections.<String, String>emptyMap(), false,
                    (Continuation<? super String>) c));
        } catch (Throwable t) {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        pageUrl = args.length > 0 ? args[0] : "https://www.bolyship.com";
        pageRoot = pageUrl;
        final AdBlock A = AdBlock.INSTANCE;

        System.out.println("页面 = " + pageUrl);
        String html = get(pageUrl);
        if (html == null) {
            System.out.println("!! 抓取失败（网络 / 证书 / 站点不可达）");
            System.exit(2);
        }
        System.out.println("页面长度 = " + html.length() + " 字");
        Document doc = Jsoup.parse(html, pageUrl);

        // ---- 收集子资源（各标签的 URL 属性） ----
        // 这些正是 `shouldInterceptRequest` 会看到的东西，所以判据该在这里生效。
        String[][] spec = {
                {"script", "src"}, {"img", "src"}, {"iframe", "src"}, {"link", "href"},
                {"source", "src"}, {"video", "src"}, {"audio", "src"}, {"embed", "src"},
        };
        LinkedHashMap<String, String> urlToTag = new LinkedHashMap<>();
        for (String[] s : spec) {
            for (Element e : doc.select(s[0] + "[" + s[1] + "]")) {
                String abs = abs(e.attr(s[1]));
                if (abs.startsWith("http")) urlToTag.putIfAbsent(abs, s[0]);
            }
        }
        // 行内样式里的 url(...)：广告图常常被塞在 style 里
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("url\\(\\s*['\"]?(https?://[^)'\"\\s]+)").matcher(html);
        while (m.find()) urlToTag.putIfAbsent(m.group(1), "css-url");

        System.out.println("子资源候选 = " + urlToTag.size() + " 条\n");

        // ---- 按主机汇总 ----
        LinkedHashMap<String, int[]> byHost = new LinkedHashMap<>();   // host -> {总, 拦下}
        LinkedHashMap<String, String> sample = new LinkedHashMap<>();
        int total = 0, blocked = 0;
        for (Map.Entry<String, String> e : urlToTag.entrySet()) {
            String u = e.getKey();
            boolean hit = A.blockedResource(u);
            total++;
            if (hit) blocked++;
            String h = A.hostOf(u);
            if (h == null) continue;
            int[] c = byHost.get(h);
            if (c == null) { c = new int[2]; byHost.put(h, c); sample.put(h, u); }
            c[0]++;
            if (hit) c[1]++;
        }
        System.out.println("================ 判据总账 ================");
        System.out.println("子资源 " + total + " 条，判据拦下 " + blocked + " 条"
                + "（" + pct(blocked, total) + "）");
        System.out.println();
        System.out.println("★ 注意：这个比例**不能**直接读成「去广告有效率」——");
        System.out.println("  页面上的绝大部分资源本来就是正文（图片 / 播放器 / 站点自己的 JS），");
        System.out.println("  判据的职责是「一条都不误伤」地把广告那一小撮挑出来。");
        System.out.println("  所以下面按主机分组看：**没被拦的第三方主机**才是「漏了什么」的线索。");

        // ---- 主机分组 ----
        String own = A.rootOf(A.hostOf(pageUrl) == null ? "" : A.hostOf(pageUrl));
        List<String> thirdHosts = new ArrayList<>();
        List<String> ownHosts = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byHost.entrySet()) {
            if (own.equals(A.rootOf(e.getKey()))) ownHosts.add(e.getKey());
            else thirdHosts.add(e.getKey());
        }
        System.out.println();
        System.out.println("================ 第三方主机（" + thirdHosts.size() + " 个） ================");
        System.out.println("被拦 = 判据认得它；**没被拦 = 判据不认识，可能就是漏掉的广告源**");
        report(A, byHost, sample, thirdHosts, true);

        System.out.println();
        System.out.println("================ 同域主机（" + ownHosts.size() + " 个） ================");
        System.out.println("（同域广告在这套判据里**不可能**被拦 —— 见文件头「判据的界限」）");
        report(A, byHost, sample, ownHosts, false);

        // ---- 用户能看见的那一类：iframe ----
        System.out.println();
        System.out.println("================ iframe（用户能看见的广告位，逐条） ================");
        List<Element> frames = doc.select("iframe[src]");
        if (frames.isEmpty()) System.out.println("（本页没有 iframe）");
        for (Element e : frames) {
            String u = abs(e.attr("src"));
            System.out.println((A.blockedResource(u) ? "  [拦] " : "  [漏] ") + u);
        }

        // ---- 关键词命中但没被拦：候选补丁 ----
        System.out.println();
        System.out.println("================ 关键词像广告、但判据放行了（候选补丁） ================");
        // 匹配口径**与 AdBlock 一致**：关键词必须作为**完整的一段**出现
        // （主机标签 / 路径段 / 去掉扩展名的文件名）。
        // 用 `contains` 会得到满屏噪声 —— 图片文件名是 32 位十六进制哈希，
        // 里面必然偶尔出现 "ad"（实测一批 `…ac24d18db7…webp` 全被算成命中）。
        String[] kw = {"ad", "ads", "gg", "banner", "guanggao", "tuiguang", "pop", "popup",
                "promo", "tj", "tongji", "count", "stat", "stats", "track", "tracker",
                "sponsor", "union", "goto", "jump", "invite"};
        int guessed = 0;
        for (String u : urlToTag.keySet()) {
            if (A.blockedResource(u)) continue;
            String hit = segHit(u.toLowerCase(), kw);
            if (hit == null) continue;
            guessed++;
            System.out.println("  [漏·整段 " + hit + "] " + u);
            if (guessed >= 25) { System.out.println("  …（还有更多，已截断）"); break; }
        }
        if (guessed == 0) System.out.println("（按整段口径，没有疑似漏拦）");
        System.out.println();
        System.out.println("★ 上面这些只是**线索**，不是结论。两条纪律：");
        System.out.println("  1. `download` / `load` / `thread` 这类正常路径也含 `ad`，");
        System.out.println("     误拦一个分片就是「播放挂掉」—— 要加词先按 `Adb.java` 的反例清单");
        System.out.println("     补一条「不许误伤」的断言；");
        System.out.println("  2. **图片广告在本判据里不可能被抓**：`/uploads/ads/banner.jpg` 和");
        System.out.println("     一张封面图在 URL 形状上没有区别。这类只能靠 DOM 启发式，");
        System.out.println("     而它一定会误伤 —— 所以本工程刻意不做（见 AdBlock 的三条自我约束）。");
        // ---- 页面源码里出现过的**全部**主机（含内联 JS 字符串） ----
        //
        // 为什么必须看这一步：标签属性里的子资源只能反映"静态就存在的东西"。真实的中文站
        // 广告绝大多数是**运行时由内联/外链 JS 造出来的** —— 它写在混淆 JS 的字符串里，
        // 不出现在任何 `script[src]` 上。只看标签属性会得出「这页没有广告源」的假结论
        // （本项目实测：某首页 55 条子资源、判据 0 命中，而 JS 里还躺着别的域名）。
        System.out.println();
        System.out.println("================ 源码里出现过的第三方主机（含内联 JS） ================");
        Map<String, Integer> hostCount = new TreeMap<>();
        java.util.regex.Matcher hm = java.util.regex.Pattern
                .compile("https?://([a-z0-9.\\-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        while (hm.find()) {
            String h = hm.group(1).toLowerCase();
            Integer c = hostCount.get(h);
            hostCount.put(h, c == null ? 1 : c + 1);
        }
        // 按出现次数降序
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(hostCount.entrySet());
        ordered.sort((x, y) -> y.getValue() - x.getValue());
        int tp = 0, tpBlocked = 0;
        for (Map.Entry<String, Integer> e : ordered) {
            String h = e.getKey();
            if (own.equals(A.rootOf(h))) continue;
            tp++;
            boolean hit = A.hostBlocked("https://" + h + "/x");
            if (hit) tpBlocked++;
            System.out.printf("  %-4s %-36s %3d 次%n", hit ? "拦" : "漏", h, e.getValue());
        }
        System.out.println();
        System.out.println("第三方主机 " + tp + " 个，其中判据认得（会拦）" + tpBlocked + " 个");
        System.out.println("★ 「漏」的那些里，**图片 CDN 是正常的**（海报/剧照站几乎全用第三方图床，");
        System.out.println("  拦掉整页就没图了）。真正要看的是：有没有**既不是图床、也不是播放器**的域名。");

        // ---- 第三方 a[href]：跳转广告的线索 ----
        System.out.println();
        System.out.println("================ 第三方出站链接 a[href]（跳转广告线索） ================");
        Map<String, Integer> outCount = new TreeMap<>();
        for (Element e : doc.select("a[href]")) {
            String u = abs(e.attr("href"));
            String h = A.hostOf(u);
            if (h == null || own.equals(A.rootOf(h))) continue;
            Integer c = outCount.get(h);
            outCount.put(h, c == null ? 1 : c + 1);
        }
        if (outCount.isEmpty()) System.out.println("（没有第三方出站链接）");
        for (Map.Entry<String, Integer> e : outCount.entrySet()) {
            System.out.printf("  %-36s %3d 次%n", e.getKey(), e.getValue());
        }

        System.out.println();
        System.out.println("【本页 iframe 选择器】注入 CSS 会藏掉这些 src 的 iframe：");
        for (String h : A.getAD_HOSTS()) {
            if (!doc.select("iframe[src*='" + h + "']").isEmpty()) System.out.println("  " + h);
        }
    }

    static void report(AdBlock A, LinkedHashMap<String, int[]> byHost,
                       LinkedHashMap<String, String> sample, List<String> hosts, boolean showSample) {
        for (String h : hosts) {
            int[] c = byHost.get(h);
            String verdict = c[1] > 0 ? "拦" : "漏";
            System.out.printf("  %-4s %-34s %2d 条%s%n", verdict, h, c[0],
                    showSample ? "   例：" + clip(sample.get(h)) : "");
        }
    }

    static String clip(String u) {
        return u.length() <= 60 ? u : u.substring(0, 57) + "…";
    }

    /**
     * 关键词是不是**作为完整的一段**出现（主机标签 / 路径段 / 去扩展名的文件名）。
     *
     * 与 `AdBlock` 的 `AD_SEG` / `AD_FILES` 是同一口径 —— 这不是"另写一遍判据"，
     * 而是**用同样的严格度**去猜"如果加词，会命中什么"。口径放宽（`contains`）
     * 得到的候选里 99% 是噪声，人会直接放弃看这份清单。
     */
    static String segHit(String url, String[] kws) {
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        for (String seg : u.split("[/.]")) {
            if (seg.isEmpty()) continue;
            int dot = seg.indexOf('.');
            String bare = dot > 0 ? seg.substring(0, dot) : seg;
            for (String k : kws) if (bare.equals(k)) return k;
        }
        return null;
    }

    static String pct(int a, int b) {
        return b == 0 ? "0%" : String.format("%.1f%%", 100.0 * a / b);
    }

    static String abs(String href) {
        String u = href.trim();
        if (u.isEmpty()) return "";
        if (u.startsWith("http")) return u;
        if (u.startsWith("//")) return "https:" + u;
        try { return ExtKt.resolveUrl(pageUrl, u); } catch (Exception e) { return u; }
    }
}
