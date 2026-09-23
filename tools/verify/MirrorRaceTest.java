import com.videoshell.data.model.SiteConfig;
import com.videoshell.data.site.MirrorRace;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 「一个站多个网址，哪个快用哪个」的离线回归（v1.0.67，无需网络）。
 *
 * ## 两层
 *
 * **第一层（A~D）真跑逻辑**：`normalize` / `candidates` / `race` 是本功能的全部判据所在，
 * 而且 `race` 的探针是**注入**的 —— 所以调度真跑，不是读源码猜。
 * 其中 C 组是这一版最容易写错的地方：**"先到先赢"不等于"按列表顺序取第一个成功的"**，
 * 判据是"耗时"，写成 `awaitAll` 那种"等最慢的"会让"快"这个信息完全失效。
 *
 * **第二层（G 组）源码级接线守卫**：`race` 再对，没人调用也毫无效果。
 * 这几条锁的是"接在哪、顺序如何"——它们是**不会报错**的错误
 * （编译绿、跑起来也不崩，只是这个功能静默失效）。
 */
public class MirrorRaceTest {

    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            fails.add(name + "  <<< " + detail);
            System.out.println("  FAIL  " + name + "   " + detail);
        }
    }

    static SiteConfig site(String base, String... mirrors) {
        List<String> m = mirrors.length == 0 ? null : Arrays.asList(mirrors);
        return new SiteConfig("k" + base, "站", base, "", SiteConfig.MODE_HTML, "", "", 0L, m);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------------ 源码守卫用的工具

    static String ROOT = System.getProperty("vs.root", "");

    static String src(String rel) {
        try {
            return new String(Files.readAllBytes(Paths.get(ROOT, rel.split("/"))),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 剥掉注释再断言：本工程的 KDoc 里常写反例，裸文本匹配会把自己判红（E13a 的教训）。
     *
     * ⚠️ **必须识别字符串字面量**，不能直接拿"块注释正则"往上套。实测踩到过：
     * `Http.kt` 里有一个 Accept 头字面量 `"…,image/webp,*` + `/*` + `;q=0.8"` ——
     * 字符串里的那个"斜杠星号"会被当成块注释起点，一直吃到后面某处真正的注释结束符，
     * **把中间约 600 字符的真代码整段删掉**。
     * 后果不是"守卫报错"，而是**守卫静默变假**：`!body.contains("NetLog.record")`
     * 这类否定断言一旦所在区域被吞掉，就恒真了 —— 那正是 vacuous 断言（本项目纪律里
     * 明令禁止的东西），而且看不出任何异常。
     */
    static String code(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        int i = 0, n = s.length();
        boolean inStr = false, inChr = false;
        while (i < n) {
            char ch = s.charAt(i);
            // Kotlin 原始字符串 """…"""：内部一律原样保留（里面可以有 // 和 /*）
            if (!inStr && !inChr && ch == '"' && i + 2 < n
                    && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                int j = s.indexOf("\"\"\"", i + 3);
                if (j < 0) { b.append(s, i, n); break; }
                b.append(s, i, j + 3);
                i = j + 3;
                continue;
            }
            if (inStr || inChr) {
                b.append(ch);
                if (ch == '\\' && i + 1 < n) { b.append(s.charAt(i + 1)); i += 2; continue; }
                if (inStr && ch == '"') inStr = false;
                if (inChr && ch == '\'') inChr = false;
                i++;
                continue;
            }
            if (ch == '"') { inStr = true; b.append(ch); i++; continue; }
            if (ch == '\'') { inChr = true; b.append(ch); i++; continue; }
            if (ch == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                continue;
            }
            if (ch == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
                b.append(' ');
                continue;
            }
            b.append(ch);
            i++;
        }
        return b.toString();
    }

    public static void main(String[] args) {
        System.out.println("== MirrorRace（多网址择优）离线回归 ==");

        // ---------------------------------------------------------------- A normalize
        System.out.println("\n-- A 地址规范化 --");

        ok("A1 去空白 + 去尾部斜杠",
                "http://a.com".equals(MirrorRace.INSTANCE.normalize("  http://a.com/  ")),
                MirrorRace.INSTANCE.normalize("  http://a.com/  "));

        ok("A2 没写协议的补 http://（用户就是会这么填）",
                "http://wogg.live".equals(MirrorRace.INSTANCE.normalize("wogg.live")),
                MirrorRace.INSTANCE.normalize("wogg.live"));

        ok("A3 https 保持不动",
                "https://a.com/tv".equals(MirrorRace.INSTANCE.normalize("https://a.com/tv")), "");

        ok("A4 带路径的地址不被砍成 origin（base 可以带子目录）",
                "http://a.com/tv/".equals(MirrorRace.INSTANCE.normalize("http://a.com/tv/"))
                        || "http://a.com/tv".equals(MirrorRace.INSTANCE.normalize("http://a.com/tv/")),
                MirrorRace.INSTANCE.normalize("http://a.com/tv/"));

        // 不抛异常：它处理的是用户手输/粘贴的内容，抛异常等于把一次手滑变成一次崩溃
        ok("A5 空串 ⇒ 空串（不抛）", "".equals(MirrorRace.INSTANCE.normalize("   ")), "");
        ok("A6 别的协议（quark://）⇒ 空串（不硬补 http://）",
                "".equals(MirrorRace.INSTANCE.normalize("quark://abc")), "");
        ok("A7 光秃秃的域名不够长 ⇒ 空串",
                "".equals(MirrorRace.INSTANCE.normalize("http://a")), "");
        ok("A8 中文/垃圾输入 ⇒ 空串（不抛）",
                "".equals(MirrorRace.INSTANCE.normalize("这是一串中文")), "");

        // ---------------------------------------------------------------- B candidates
        System.out.println("\n-- B 候选清单 --");

        ok("B1 baseUrl 在最前，备用地址按给定顺序跟在后面",
                MirrorRace.INSTANCE.candidates(site("http://a.com", "http://b.com", "http://c.com"))
                        .equals(Arrays.asList("http://a.com", "http://b.com", "http://c.com")),
                String.valueOf(MirrorRace.INSTANCE.candidates(
                        site("http://a.com", "http://b.com", "http://c.com"))));

        // ★ Gson 的坑：老配置里没有 mirrors 字段 ⇒ 反序列化后是 null
        //   （Gson 用 Unsafe 分配对象，Kotlin 的默认值不生效）
        ok("B2 【Gson 坑】mirrors 为 null 时不 NPE、只返回 baseUrl",
                MirrorRace.INSTANCE.candidates(site("http://a.com"))
                        .equals(Collections.singletonList("http://a.com")),
                String.valueOf(MirrorRace.INSTANCE.candidates(site("http://a.com"))));

        ok("B3 【Gson 坑】mirrors 显式传 null 同样安全",
                MirrorRace.INSTANCE.candidates(
                        new SiteConfig("k", "n", "http://a.com", "", "html", "", "", 0L, null))
                        .equals(Collections.singletonList("http://a.com")), "");

        ok("B4 备用地址写乱了（带空格/斜杠/重复）会被规范化 + 去重",
                MirrorRace.INSTANCE.candidates(
                        site("http://a.com/", "  http://a.com  ", "b.com")).size() == 2,
                String.valueOf(MirrorRace.INSTANCE.candidates(
                        site("http://a.com/", "  http://a.com  ", "b.com"))));

        ok("B5 非法备用地址被丢掉（不留一个永远探不通的候选）",
                MirrorRace.INSTANCE.candidates(site("http://a.com", "quark://x", "  "))
                        .size() == 1, "");

        ok("B6 label = host（诊断/提示用）",
                "a.com".equals(MirrorRace.INSTANCE.label("http://a.com/x?y=1")), "");

        // ---------------------------------------------------------------- C race（真跑调度）
        System.out.println("\n-- C 赛马：先到先赢（真跑，探针注入）--");

        // ★ 这一条是本功能的核心判据：赢家必须是**最快回来**的那个，
        //   而不是"列表里靠前的那个"。
        //   注意 suffix 要写全 host —— 写 `endsWith("slow")` 对 "http://slow.com"
        //   恒为 false，两个候选会双双落进 -1 分支，race 立刻返回 null：
        //   那是**测试写错**，不是调度错（C1 首跑就是这么红的）。
        MirrorRace.Prober order = url -> {
            if (url.endsWith("slow.com")) { sleep(700); return 700L; }
            if (url.endsWith("fast.com")) { sleep(120); return 120L; }
            return -1L;
        };
        String w1 = MirrorRace.INSTANCE.race(
                Arrays.asList("http://slow.com", "http://fast.com"), order, 4000L);
        ok("C1 最快回来的胜出（不是列表里靠前的那个）",
                "http://fast.com".equals(w1), String.valueOf(w1));

        // ★ 第二条核心判据：**不等最慢的那个**。写成 awaitAll 的实现会在这里超时。
        long t0 = System.currentTimeMillis();
        String w2 = MirrorRace.INSTANCE.race(
                Arrays.asList("http://slow.com", "http://fast.com"), order, 4000L);
        long dt = System.currentTimeMillis() - t0;
        ok("C2 **不等最慢的**（先到即返回；等齐的实现会耗掉 700ms+）",
                "http://fast.com".equals(w2) && dt < 500, "winner=" + w2 + " dt=" + dt + "ms");

        ok("C3 全不可达 ⇒ null",
                MirrorRace.INSTANCE.race(
                        Arrays.asList("http://x.com", "http://y.com"),
                        url -> -1L, 3000L) == null, "");

        ok("C4 空候选 ⇒ null（不抛）",
                MirrorRace.INSTANCE.race(new ArrayList<String>(), url -> 1L, 1000L) == null, "");

        ok("C5 只有一个候选且活着 ⇒ 就是它（同样受超时约束，不做特判）",
                "http://only.com".equals(MirrorRace.INSTANCE.race(
                        Collections.singletonList("http://only.com"), url -> 5L, 2000L)), "");

        ok("C6 只有一个候选且不可达 ⇒ null",
                MirrorRace.INSTANCE.race(
                        Collections.singletonList("http://only.com"), url -> -1L, 2000L) == null, "");

        // 探针自己抛异常 = 这条候选不可用，不能把整场赛马带崩
        ok("C7 探针抛异常 ⇒ 当作该候选失败（不影响其他候选）",
                "http://ok.com".equals(MirrorRace.INSTANCE.race(
                        Arrays.asList("http://boom.com", "http://ok.com"),
                        url -> {
                            if (url.endsWith("boom.com")) throw new IllegalStateException("boom");
                            return 7L;
                        }, 3000L)), "");

        // 整体超时：所有候选都卡住 ⇒ 到点收场返回 null（不无限等）
        long t1 = System.currentTimeMillis();
        String w3 = MirrorRace.INSTANCE.race(
                Collections.singletonList("http://hang.com"),
                url -> { sleep(2500); return 2500L; }, 400L);
        long dt2 = System.currentTimeMillis() - t1;
        ok("C8 整体超时到点收场（卡住的候选不会把调用方无限挂住）",
                w3 == null && dt2 < 1200, "w=" + w3 + " dt=" + dt2 + "ms");

        // 迟到者不算：赢家已经产生之后再来的成功不改变结论
        String w4 = MirrorRace.INSTANCE.race(
                Arrays.asList("http://a.com", "http://b.com", "http://c.com"),
                url -> {
                    if (url.endsWith("a.com")) return 20L;          // 立刻
                    if (url.endsWith("b.com")) { sleep(300); return 300L; }
                    sleep(600);
                    return 600L;
                }, 3000L);
        ok("C9 第一个成功者定案，后来的成功者不改写结论",
                "http://a.com".equals(w4), String.valueOf(w4));

        // ---------------------------------------------------------------- G 接线守卫（源码级）
        System.out.println("\n-- G 接线守卫（动了就静默失效的那些）--");

        String af = code(src("app/src/main/java/com/videoshell/data/site/AdapterFactory.kt"));
        String agg = code(src("app/src/main/java/com/videoshell/data/site/AggSearch.kt"));
        String mr = code(src("app/src/main/java/com/videoshell/data/site/MirrorRace.kt"));
        String http = code(src("app/src/main/java/com/videoshell/data/net/Http.kt"));
        String models = code(src("app/src/main/java/com/videoshell/data/model/Models.kt"));
        String sd = code(src("app/src/main/java/com/videoshell/data/site/SiteDetector.kt"));
        String sa = code(src("app/src/main/java/com/videoshell/ui/SearchActivity.kt"));
        String main = code(src("app/src/main/java/com/videoshell/ui/MainActivity.kt"));

        ok("G0 守卫自测：六个源文件都读得到（否则下面全是恒真断言）",
                af != null && agg != null && mr != null && http != null && models != null
                        && sd != null && sa != null && main != null,
                (af == null ? "AF " : "") + (agg == null ? "AGG " : "") + (mr == null ? "MR " : "")
                        + (http == null ? "HTTP " : "") + (models == null ? "MODELS " : "")
                        + (sd == null ? "SD " : "") + (sa == null ? "SA " : "")
                        + (main == null ? "MAIN " : ""));

        // ★ 剥注释助手自己的自测。本项目的纪律是"否定断言必须能证明被否定的东西还在"：
        //   G8 是 `!body.contains("NetLog.record")`，如果 code() 哪天被"简化"回正则版，
        //   它会从 `image/webp,*/*;q=0.8` 这个字符串里的 `/*` 开始吃掉整段代码 ——
        //   probeMs 的函数体会变成空的，G8 随即**恒真**（vacuous），而 G8 依然报 PASS。
        //   这一条就是钉住那件事：剥完之后这些**字符串字面量**必须完好。
        ok("G0b 剥注释助手没吞掉真代码（字符串里的 `/*` 曾把 600+ 字符整段删掉）",
                http != null && http.contains("probeClient.newCall")
                        && http.contains("image/webp,*/*;q=0.8"),
                "剥过头了 ⇒ G8 之类的否定断言会静默变成恒真");

        ok("G0c 但注释确实被剥掉了（否则 G 组会退化成读注释猜代码）",
                af != null && af.contains("MirrorRace.withCached") && !af.contains("15 处被调"), "");

        // 收口点是"一个工厂"，不是 15 个调用点：在 create() 第一行把地址换掉，
        // 下面每条分支（白名单 / 家族缓存 / 配方 / 拼接）就自动对齐了。
        int createFn = af == null ? -1 : af.indexOf("fun create(config: SiteConfig)");
        int withCached = af == null ? -1 : af.indexOf("MirrorRace.withCached(config)");
        int firstBranch = af == null ? -1 : af.indexOf("CryptRecipes.forUrl(site.baseUrl)");
        ok("G1 AdapterFactory.create 第一件事就是把地址换成会话里挑好的那个",
                createFn > 0 && withCached > createFn && firstBranch > withCached,
                "create@" + createFn + " withCached@" + withCached + " firstUse@" + firstBranch);

        ok("G2 参数名换成 config + 局部 site —— 防止后来者把收口点摘掉",
                af != null && af.contains("fun create(config: SiteConfig)")
                        && !af.contains("fun create(site: SiteConfig)"), "");

        // 赛马**不能**算进单站 12 秒超时里：那会把一个本来正常的站逼到超时
        int ofCall = agg == null ? -1 : agg.indexOf("MirrorRace.of(s)");
        int tw = agg == null ? -1 : agg.indexOf("withTimeoutOrNull(perSiteTimeoutMs)");
        ok("G3 AggSearch.oneSite 里赛马在 12 秒超时**之外**",
                ofCall > 0 && tw > 0 && ofCall < tw, "of@" + ofCall + " timeout@" + tw);

        ok("G4 单站搜索失败会作废这次的选中（自愈：下次重新赛马）",
                agg != null && agg.contains("MirrorRace.invalidate(s)"), "");

        ok("G5 搜索页也走同一套（单站搜索不是另一条路）",
                sa != null && sa.contains("MirrorRace.of(s)") && sa.contains("MirrorRace.invalidate(site)"),
                "");

        ok("G6 首页刷新时后台预热（用户点进站之前就挑好）",
                main != null && main.contains("MirrorRace.warmUp(list)"), "");

        // 挑出来的地址只活在内存里：写盘等于把一个临时结论固化成长期配置
        ok("G7 MirrorRace 不落盘（没有 SharedPreferences / Gson / File）",
                mr != null && !mr.contains("SharedPreferences") && !mr.contains("Gson")
                        && !mr.contains("java.io.File") && !mr.contains("CacheDir"), "");

        // 探活不该污染性能日志：一次搜索十几个探针会把"刚才那次卡顿"的记录冲掉。
        // 取"函数体"用**下一个同级声明**当右界，不用固定字符窗 —— 字符窗会随上面的
        // KDoc 长短漂移，今天恰好够不着、明天改一行注释就够着了（那种"防守忽明忽暗"
        // 的守卫比没有更糟）。
        int pm = http == null ? -1 : http.indexOf("fun probeMs(");
        int pmEnd = pm < 0 ? -1 : nextDecl(http, pm + 1);
        String pmBody = pm < 0 ? "" : http.substring(pm, pmEnd < 0 ? http.length() : pmEnd);
        ok("G8 Http.probeMs 不写 NetLog（探活是后台噪声）",
                pm > 0 && pmBody.contains("probeClient.newCall") && !pmBody.contains("NetLog.record"),
                "body=" + pmBody.length() + "B");

        // mirrors 必须按可空处理 —— 老配置反序列化后就是 null（Gson + Unsafe）
        ok("G9 SiteConfig.mirrors 声明为**可空**且提供 mirrorList()",
                models != null && models.contains("val mirrors: List<String>? = null")
                        && models.contains("fun mirrorList(): List<String>"), "");

        // 全仓扫描：`.mirrors` 只许出现在 Models.kt（那里是唯一的空安全出口）
        StringBuilder leak = new StringBuilder();
        for (java.io.File f : ktFiles(new File(ROOT, "app/src/main/java"))) {
            String t = code(read(f));
            if (t == null) continue;
            if (t.contains(".mirrors")) leak.append(f.getName()).append(' ');
        }
        ok("G10 别处一律走 mirrorList()，没有直读 .mirrors 的（直读＝一次 NPE）",
                leak.length() == 0, "命中：" + leak);

        ok("G11 多网址入口存在：detect 里用 splitUrls 拆输入",
                sd != null && sd.contains("fun splitUrls(") && sd.contains("val raws = splitUrls(rawInput)"),
                "");

        ok("G12 识别出的备用地址真的被写进 SiteConfig（mirrors = spare）",
                sd != null && sd.contains("mirrors = spare"), "");

        System.out.println();
        System.out.println("==== MirrorRace pass=" + pass + " fail=" + fail + " ====");
        for (String f : fails) System.out.println("  FAIL " + f);
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * 下一个**同级**声明的位置（用来切出一个函数的体）。
     *
     * 不用固定字符窗：窗口会随上面的 KDoc 长短漂移，今天恰好够不着、改一行注释就够着了 ——
     * 那种"防守忽明忽暗"的守卫比没有更糟。
     */
    static int nextDecl(String s, int from) {
        int best = -1;
        for (String pat : new String[]{"\n    fun ", "\n    suspend fun ", "\n    private fun ",
                "\n    internal fun ", "\n    private suspend fun ", "\n    internal suspend fun "}) {
            int i = s.indexOf(pat, from);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    static List<File> ktFiles(File dir) {        List<File> out = new ArrayList<>();
        File[] kids = dir.listFiles();
        if (kids == null) return out;
        for (File f : kids) {
            if (f.isDirectory()) out.addAll(ktFiles(f));
            else if (f.getName().endsWith(".kt")) out.add(f);
        }
        return out;
    }

    static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
