import com.videoshell.data.model.VideoItem;
import com.videoshell.data.site.AggSearch;
import com.videoshell.data.site.SearchEngine;
import com.videoshell.data.site.SearchScope;
import com.videoshell.player.HlsPlaylistFixer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * v1.0.38 断言套件。四件事：
 *
 *   1）网页嗅探模式的**浮窗**能收起、能拖动（挡住网页按钮是最直接的抱怨）
 *   2）「全站搜索」**先铺已到达的**（不再等最慢那个站）
 *   3）全网搜索**可换搜索引擎** + 影视化关键词后缀
 *   4）播放「时长读得到、一直转圈」：HlsFix 的目录/参数缺陷 + 播放器「卡住看门狗」
 *
 * 这个套件钉的是**错了不会崩、只会悄悄错**的地方：
 *   A 引擎枚举的持久化契约（存名字不存下标）与**域名归属**（防把结果页收成站源）
 *   B 关键词增强的边界（已带后缀不重复加 / 空词不加）
 *   C [AggSearch.block] 与 [AggSearch.merge] **同源**（分块拼起来 == 一次性 merge）
 *   D ★ 流式插入位置：**任意到达顺序**都要与全量 merge 逐条相等 ← 本套件的核心
 *   E [AggSearch.mergeArrived] / [progress]：顺序 + 分母
 *   F ★ HlsFix：带鉴权参数的 playlist 地址不能把分片拼错；静态整集要补 ENDLIST
 *   G 源码守卫：判据不能只在注释里成立（含「必须真的被调用」）
 *
 * 入参：a[0] = 夹具目录  a[1] = 工程根
 */
public class Agg38 {

    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            fails.add(name);
            System.out.println("  [FAIL] " + name + "   → " + detail);
        }
    }

    static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    // 调用点统一写 eq(标题, 期望值, 实测值)。参数名必须与打印顺序一致 ——
    // 之前写成 (name, got, want) 会把「实测」和「期望」在报错里颠倒，害我把
    // 正确的 4 误判成 6。(Objects.equals 对称 ⇒ 只影响文案，不影响判定。)
    static void eq(String name, Object want, Object got) {
        ok(name, Objects.equals(got, want), "want=" + want + " got=" + got);
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("========== " + s + " ==========");
    }

    static String read(String p) {
        String text;
        try {
            Path q = Paths.get(p);
            if (!Files.exists(q)) return null;
            text = new String(Files.readAllBytes(q), "UTF-8");
        } catch (Exception e) {
            return null;
        }
        return agg(p, text);
    }

    /** 源码的"有效文本" = 主文件 + 同主名的拆分子文件（`<主名>_*.kt`），
     *  子文件里的顶层 internal 声明经 unwrap() 还原成类成员写法。
     *
     * 守卫锁的是**代码文本**，不该锁**文件布局**：把 god file 按职责拆成几个文件是纯搬运
     * （逻辑一行没改）。为什么需要还原、以及为什么这样做不会掩盖真改动，见 unwrap 的注释。
     */
    static String agg(String p, String text) {
        String name = new java.io.File(p).getName();
        if (!name.endsWith(".kt")) return text;
        String owner = name.substring(0, name.length() - 3);
        java.io.File dir = new java.io.File(p).getParentFile();
        String[] ns = dir == null ? null : dir.list();
        if (ns == null) return text;
        Arrays.sort(ns);
        String pre = owner + "_";
        StringBuilder sb = new StringBuilder(text);
        for (String n : ns) {
            if (!n.startsWith(pre) || !n.endsWith(".kt")) continue;
            String sub;
            try {
                sub = new String(
                        Files.readAllBytes(new java.io.File(dir, n).toPath()), "UTF-8");
            } catch (Exception e) {
                continue;
            }
            sb.append('\n');
            for (String line : sub.split("\n", -1)) sb.append(unwrap(line, owner)).append('\n');
        }
        return sb.toString();
    }

    /** 把拆出去的子文件里的顶层 internal 声明**还原成类成员的写法**。
     *
     * 拆分后子文件里是 `internal fun Owner.xxx(` / `internal val xxx = …`
     * （扩展函数访问不了 private，被它读到的字段也要放宽），而守卫的判据写的是
     * 拆分前的样子 `private fun xxx(`。两者是**一一对应的同一段代码**，差别只在
     * 可见性修饰符与接收者前缀 —— 那是"搬到哪、谁能看见"，不是"代码做了什么"。
     *
     * 所以读取时按行做恒等改写，让聚合出来的文本与拆分前**逐行等价**，
     * 守卫从此不必知道文件被拆过（见 PITFALLS 4.41）。
     * 只在**子文件**上做；1:1 行替换，不复制、不删除 ⇒ 计数断言语义不变，
     * 真被删掉的代码也不会因为改写而"看起来还在"。
     */
    static String unwrap(String line, String owner) {
        if (!line.startsWith("internal ")) return line;
        String rest = line.substring("internal ".length());
        String p = "fun " + owner + ".";
        if (rest.startsWith(p)) return "private fun " + rest.substring(p.length());
        p = "suspend fun " + owner + ".";
        if (rest.startsWith(p)) return "private suspend fun " + rest.substring(p.length());
        if (rest.startsWith("val ")) return "private val " + rest.substring(4);
        if (rest.startsWith("var ")) return "private var " + rest.substring(4);
        return line;
    }


    /**
     * 源码里在**注释之外**是否出现某个片段。
     *
     * 注释不算证据 —— 本项目最贵的一条教训就是「注释在描述一个已经不存在的分支」，
     * 所以守卫必须只看活代码。同时也提醒：断言名里写「**必须真的被调用**」的那种，
     * 要数调用点数量，不能只看函数定义在不在。
     */
    static boolean live(String src, String needle) {
        if (src == null || needle.isEmpty()) return false;
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            if (line.contains(needle)) return true;
        }
        return false;
    }

    /** 数一个片段在**非注释行**里出现的次数 */
    static int countLive(String src, String needle) {
        if (src == null) return 0;
        int n = 0;
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            if (line.contains(needle)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ 夹具

    static VideoItem item(String id, String name) {
        return new VideoItem(id, name, "https://pic/" + id + ".jpg", "", "", "", "", "", "");
    }

    static AggSearch.SiteHits hits(String key, String name, List<VideoItem> items) {
        return new AggSearch.SiteHits(key, name, items, null);
    }

    static AggSearch.SiteHits bad(String key, String name, String why) {
        return new AggSearch.SiteHits(key, name, new ArrayList<VideoItem>(), why);
    }

    static List<VideoItem> items(String... names) {
        List<VideoItem> out = new ArrayList<>();
        int i = 1;
        for (String n : names) out.add(item(String.valueOf(i++), n));
        return out;
    }

    static List<String> names(List<VideoItem> l) {
        List<String> out = new ArrayList<>();
        for (VideoItem v : l) out.add(v.getName() + "@" + v.getSiteKey());
        return out;
    }

    /**
     * 行列表 → 可比较的字符串。标题行标成 `== 站名(条数)`，卡片仍是 `片名@站key`。
     *
     * v1.0.39 起网格的基本单位是**行**（每块前面多一行分组标题），所以 D 段的不变量
     * 必须在行空间里成立；这个函数就是那把尺子。
     */
    static List<String> rowNames(List<com.videoshell.data.model.VideoRow> l) {
        List<String> out = new ArrayList<>();
        for (com.videoshell.data.model.VideoRow r : l) {
            if (r instanceof com.videoshell.data.model.VideoRow.Header) {
                com.videoshell.data.model.VideoRow.Header h =
                        (com.videoshell.data.model.VideoRow.Header) r;
                out.add("== " + h.getName() + "(" + h.getCount() + ")");
            } else {
                VideoItem v = ((com.videoshell.data.model.VideoRow.Card) r).getItem();
                out.add(v.getName() + "@" + v.getSiteKey());
            }
        }
        return out;
    }

    /** 行列表的**卡片投影**（丢掉标题行）。它必须与卡片视图 merge 逐条相等 —— 判据只有一份 */
    static List<String> rowCardNames(List<com.videoshell.data.model.VideoRow> l) {
        List<String> out = new ArrayList<>();
        for (com.videoshell.data.model.VideoRow r : l) {
            if (r instanceof com.videoshell.data.model.VideoRow.Card) {
                out.add(((com.videoshell.data.model.VideoRow.Card) r).getItem().getName()
                        + "@" + ((com.videoshell.data.model.VideoRow.Card) r).getItem().getSiteKey());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        String proj = args.length > 1 ? args[1] : ".";

        aEngine(proj);
        bEnhance();
        cBlock();
        dStreaming();
        eArrived();
        fHls();
        gGuards(args.length > 0 ? args[0] : ".", proj);

        System.out.println();
        System.out.println("========== 合计 ==========");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.out.println("失败项：");
            for (String f : fails) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ A

    static void aEngine(String proj) {
        banner("A. 搜索引擎枚举：认名字不认下标 + 域名归属（含负控）");

        eq("of(null) → 默认引擎", SearchEngine.BAIDU, SearchEngine.Companion.of(null));
        eq("of(空串) → 默认引擎（老版本存的就是空串）", SearchEngine.BAIDU,
                SearchEngine.Companion.of(""));
        eq("of(BAIDU) → BAIDU", SearchEngine.BAIDU, SearchEngine.Companion.of("BAIDU"));
        eq("of(baidu) → BAIDU（大小写不敏感）", SearchEngine.BAIDU,
                SearchEngine.Companion.of("baidu"));
        eq("of(BING) → BING", SearchEngine.BING, SearchEngine.Companion.of("BING"));
        eq("of(DDG) → DDG", SearchEngine.DDG, SearchEngine.Companion.of("DDG"));
        eq("★ of(1) → 默认（绝不下标解析：枚举插一项就会静默换掉用户的选择）",
                SearchEngine.BAIDU, SearchEngine.Companion.of("1"));
        eq("of(乱码) 不抛异常 → 默认", SearchEngine.BAIDU,
                SearchEngine.Companion.of("搜狗!!"));
        ok("至少 4 个引擎可选", SearchEngine.values().length >= 4,
                "n=" + SearchEngine.values().length);

        // 每个引擎的地址都要能把关键词编码干净
        for (SearchEngine e : SearchEngine.values()) {
            String u = e.searchUrl("庆余年 第二季", false);
            ok("[" + e.name() + "] 地址里没有裸中文/空格", !u.contains("庆余年") && !u.contains(" "),
                    u);
            ok("[" + e.name() + "] 地址以 http 开头", u.startsWith("http"), u);
            String u2 = e.searchUrl("a&b#c=d", false);
            String q = u2.substring(u2.indexOf('?') + 1);
            ok("[" + e.name() + "] 关键词里的 & 与 # 都被编码", !q.contains("&") && !q.contains("#"),
                    q);
            ok("[" + e.name() + "] 首页也算本引擎域名",
                    SearchEngine.Companion.isSearchHost(e.home()), e.home());
        }

        // 域名归属：新引擎必须一起被认出来，否则结果页会被当成「还没识别的网页」收成站源
        ok("★ 认 m.baidu.com（百度移动端）",
                SearchEngine.Companion.isSearchHost("https://m.baidu.com/s?wd=x"));
        ok("★ 认 www.sogou.com",
                SearchEngine.Companion.isSearchHost("https://www.sogou.com/web?query=x"));
        ok("★ 认 www.so.com", SearchEngine.Companion.isSearchHost("https://www.so.com/s?q=x"));
        ok("★ 认 html.duckduckgo.com",
                SearchEngine.Companion.isSearchHost("https://html.duckduckgo.com/html/?q=x"));
        ok("认 www.bing.com（原有）",
                SearchEngine.Companion.isSearchHost("https://www.bing.com/search?q=x"));
        ok("认裸 baidu.com", SearchEngine.Companion.isSearchHost("baidu.com"));
        ok("★ 反向控制：notbaidu.com 不算",
                !SearchEngine.Companion.isSearchHost("https://notbaidu.com/"));
        ok("★ 反向控制：baidu.com.evil.cn 不算",
                !SearchEngine.Companion.isSearchHost("https://baidu.com.evil.cn/"));
        ok("★ 反向控制：普通视频站不算",
                !SearchEngine.Companion.isSearchHost("https://agenda.fzchosdi.cc/tag/x/"));
        ok("★ 反向控制：空串不算", !SearchEngine.Companion.isSearchHost(""));
        ok("SearchScope.isWebHost 已转发到全部引擎（不再写死 bing）",
                SearchScope.Companion.isWebHost("https://www.sogou.com/web?query=x"));

        // Scope 层的地址拼接要把引擎与后缀都带进来
        String sw = SearchScope.Companion.webSearchUrl("庆余年", SearchEngine.SOGOU, true);
        ok("webSearchUrl 用上了传入的引擎（不再是 bing）", sw.contains("sogou.com"), sw);
        ok("webSearchUrl 用上了后缀开关", sw.contains("%E5%9C%A8%E7%BA%BF%E8%A7%82%E7%9C%8B"), sw);

        // 源码守卫：默认值必须是个「可改的默认」，不是一个写死的域名
        String eng = read(proj + "/app/src/main/java/com/videoshell/data/site/SearchEngine.kt");
        ok("SearchEngine.kt 可读", eng != null && eng.length() > 500);
        ok("★ 引擎解析走名字（源码里 values() 与 name 比对）", live(eng, "values()"));
        ok("★ 引擎解析不碰 ordinal（存下标 = 插项后静默换选项）",
                eng != null && !live(eng, "ordinal"));
    }

    // ------------------------------------------------------------------ B

    static void bEnhance() {
        banner("B. 影视化关键词后缀：边界要收干净");

        eq("不开增强 → 原样", "庆余年", SearchEngine.Companion.keywordOf("庆余年", false));
        eq("开增强 → 追加后缀", "庆余年 在线观看",
                SearchEngine.Companion.keywordOf("庆余年", true));
        eq("已经带了后缀 → 不重复加", "庆余年 在线观看",
                SearchEngine.Companion.keywordOf("庆余年 在线观看", true));
        eq("前后空格被去掉", "庆余年",
                SearchEngine.Companion.keywordOf("  庆余年  ", false));
        eq("空关键词 → 不加（留空给首页）", "",
                SearchEngine.Companion.keywordOf("   ", true));
        ok("★ 后缀字面量一致（界面 chip 与拼地址用的是同一个常量）",
                "在线观看".equals(SearchEngine.SUFFIX), SearchEngine.SUFFIX);
    }

    // ------------------------------------------------------------------ C

    static void cBlock() {
        banner("C. AggSearch.block：分块拼起来必须 == 一次性 merge（判据只有一份）");

        List<AggSearch.SiteHits> hs = new ArrayList<>();
        hs.add(hits("k1", "甲站", items("A", "B", "C")));
        hs.add(hits("k2", "乙站", items("D", "E")));
        hs.add(bad("k3", "丙站", "boom"));

        List<VideoItem> oneShot = AggSearch.INSTANCE.merge(hs, AggSearch.PER_SITE);
        List<VideoItem> byBlocks = new ArrayList<>();
        for (AggSearch.SiteHits h : hs) {
            byBlocks.addAll(AggSearch.INSTANCE.block(h, AggSearch.PER_SITE));
        }
        eq("★ 分块拼接 == 一次性 merge", names(oneShot), names(byBlocks));

        eq("条数 = 各站之和（失败站不产出）", 5, oneShot.size());
        eq("★ 每条都打上了来源站", "k1", oneShot.get(0).getSiteKey());
        eq("第二站的那条打的是第二站的 key", "k2", oneShot.get(3).getSiteKey());

        eq("限量：5 条被裁到 2 条", 2, AggSearch.INSTANCE.block(
                hits("k1", "甲站", items("A", "B", "C", "D", "E")), 2).size());
        eq("★ 归零表示不限量", 5, AggSearch.INSTANCE.block(
                hits("k1", "甲站", items("A", "B", "C", "D", "E")), 0).size());
        eq("无名条目被丢掉（点开必然是空页）", 1, AggSearch.INSTANCE.block(
                hits("k1", "甲站", items("A")), AggSearch.PER_SITE).size());
        eq("key 为空的批次整批丢掉", 0, AggSearch.INSTANCE.block(
                hits("", "无key", items("A")), AggSearch.PER_SITE).size());
        eq("空输入 → 空输出", 0, AggSearch.INSTANCE.block(
                hits("k1", "甲站", new ArrayList<VideoItem>()), AggSearch.PER_SITE).size());
    }

    // ------------------------------------------------------------------ D

    static void dStreaming() {
        banner("D. ★ 流式插入位置（行空间）：任意到达顺序都要与全量 mergeRows 逐行相等");

        // 三个站，块长度刻意不同
        AggSearch.SiteHits h0 = hits("s0", "甲站", items("A1", "A2", "A3"));
        AggSearch.SiteHits h1 = hits("s1", "乙站", items("B1"));
        AggSearch.SiteHits h2 = hits("s2", "丙站", items("C1", "C2"));
        List<AggSearch.SiteHits> all = Arrays.asList(h0, h1, h2);

        int[][] seqs = {
                {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
        };
        // 卡片视图（不含标题行）：3+1+2 = 6 条
        List<String> wantCards = names(AggSearch.INSTANCE.merge(all, AggSearch.PER_SITE));
        eq("全量卡片数 = 3+1+2", 6, wantCards.size());

        // 行视图（v1.0.39）：每块 = 1 行分组标题 + 它的卡片 ⇒ 4 + 2 + 3 = 9 行
        List<com.videoshell.data.model.VideoRow> wantRows =
                AggSearch.INSTANCE.mergeRows(all, AggSearch.PER_SITE);
        eq("全量行数 = (3+1)+(1+1)+(2+1)", 9, wantRows.size());
        eq("★ 行视图的卡片投影 == 卡片视图（两份判据同源，不是两套实现）",
                wantCards, rowCardNames(wantRows));
        List<String> wn = rowNames(wantRows);
        eq("★ 每个站的第一行是它的分组标题，标题上写着这一块的条数",
                Arrays.asList("== 甲站(3)", "== 乙站(1)", "== 丙站(2)"),
                Arrays.asList(wn.get(0), wn.get(4), wn.get(6)));

        for (int[] seq : seqs) {
            AggSearch.SiteHits[] slots = new AggSearch.SiteHits[3];
            List<com.videoshell.data.model.VideoRow> grid = new ArrayList<>();
            boolean inRange = true;
            for (int i : seq) {
                slots[i] = all.get(i);
                int at = AggSearch.INSTANCE.insertAt(Arrays.asList(slots), i, AggSearch.PER_SITE);
                if (at < 0 || at > grid.size()) inRange = false;
                grid.addAll(at, AggSearch.INSTANCE.rows(all.get(i), AggSearch.PER_SITE));
            }
            ok("插入位置始终在界内 seq=" + Arrays.toString(seq), inRange);
            eq("★ 到达顺序 " + Arrays.toString(seq) + " 铺出来的网格 == 全量 mergeRows",
                    rowNames(wantRows), rowNames(grid));
        }

        // insertAt 的定义：只数「排在它前面、且已到达」的那些站占的**行数**（含它们的标题行）
        eq("前面都没到时，第 3 个站插到 0", 0, AggSearch.INSTANCE.insertAt(
                Arrays.<AggSearch.SiteHits>asList(null, null, h2), 2, AggSearch.PER_SITE));
        // h0 = 3 张卡 + 1 行标题 = 4 行（v1.0.39 之前这里是 3：那时还没有标题行）
        eq("前一个到了、本身没到，插入点 = 那个站占的行数（3 卡 + 1 标题）", 4,
                AggSearch.INSTANCE.insertAt(
                        Arrays.<AggSearch.SiteHits>asList(h0, null, null), 2, AggSearch.PER_SITE));
        // h0(4) + h1(2) = 6，不含自己(h2 = 3)
        eq("全都在时，最后一个站插在末尾（= 前面两块的行数 4+2，不含自己）", 6,
                AggSearch.INSTANCE.insertAt(
                        Arrays.<AggSearch.SiteHits>asList(h0, h1, h2), 2, AggSearch.PER_SITE));
        eq("越界下标不炸", 9, AggSearch.INSTANCE.insertAt(
                Arrays.<AggSearch.SiteHits>asList(h0, h1, h2), 9, AggSearch.PER_SITE));

        // growPlan：界面直接用的就是它，语义必须与 insertAt 一致
        int at = AggSearch.INSTANCE.growPlan(
                Arrays.<AggSearch.SiteHits>asList(h0, null, null), 1, AggSearch.PER_SITE).getFirst();
        eq("growPlan 与 insertAt 一致（行空间）", 4, at);
    }

    // ------------------------------------------------------------------ E

    static void eArrived() {
        banner("E. mergeArrived / progress：顺序 + 分母");

        AggSearch.SiteHits h0 = hits("s0", "甲站", items("A1", "A2"));
        AggSearch.SiteHits h2 = hits("s2", "丙站", items("C1"));
        List<AggSearch.SiteHits> slots = new ArrayList<>();
        slots.add(h0);
        slots.add(null);
        slots.add(h2);

        List<VideoItem> merged = AggSearch.INSTANCE.mergeArrived(slots, AggSearch.PER_SITE);
        eq("还没到的站不占位", 3, merged.size());
        eq("★ 顺序按站点顺序（不是到达顺序）",
                Arrays.asList("A1@s0", "A2@s0", "C1@s2"), names(merged));
        eq("全空 → 空", 0, AggSearch.INSTANCE.mergeArrived(
                Arrays.<AggSearch.SiteHits>asList(null, null), AggSearch.PER_SITE).size());

        String p = AggSearch.INSTANCE.progress(2, 5, 30);
        ok("★ 进度行必须写出分母（否则用户以为搜索已经结束）", p.contains("2/5"), p);
        ok("进度行要写出已有条数", p.contains("30"), p);
        ok("进度行表示还在继续（末尾是省略号）", p.endsWith("…"), p);
    }

    // ------------------------------------------------------------------ F

    static void fHls() {
        banner("F. ★ HlsFix：带参数的 playlist 地址 / 静态整集 ENDLIST / 协议相对");

        // F1 带鉴权参数、且参数里还有斜杠 —— 旧实现会把分片拼到参数里去
        String u1 = "https://cdn.x.com/hls/abc/index.m3u8?auth=tok/a/b";
        String t1 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n"
                + "#EXTINF:2.000,\n0.ts\n#EXTINF:2.000,\n1.ts\n#EXT-X-ENDLIST\n";
        String o1 = HlsPlaylistFixer.INSTANCE.fix(t1, u1);
        ok("★ 带参数地址：分片拼成 https://cdn.x.com/hls/abc/0.ts",
                o1.contains("https://cdn.x.com/hls/abc/0.ts"), o1.replace("\n", " | "));
        ok("★ 分片地址里不能出现 playlist 自己的文件名",
                !o1.contains("index.m3u8"), o1.replace("\n", " | "));
        ok("★ 分片地址里不能残留鉴权参数", !o1.contains("auth=tok"), o1.replace("\n", " | "));

        // F2 协议相对分片：必须用 playlist 自己的协议，不能写死 https
        String o2 = HlsPlaylistFixer.INSTANCE.fix(
                "#EXTM3U\n#EXTINF:2.0,\n//cdn.y.com/1.ts\n#EXT-X-ENDLIST\n",
                "http://cdn.y.com/hls/i.m3u8");
        ok("★ 协议相对用 playlist 的协议（http 站不能升级成 https）",
                o2.contains("http://cdn.y.com/1.ts"), o2.replace("\n", " | "));

        // F3 根路径分片
        String o3 = HlsPlaylistFixer.INSTANCE.fix(
                "#EXTM3U\n#EXTINF:2.0,\n/x/1.ts\n#EXT-X-ENDLIST\n",
                "https://cdn.z.com/a/b/i.m3u8");
        ok("根路径分片拼 scheme+authority", o3.contains("https://cdn.z.com/x/1.ts"),
                o3.replace("\n", " | "));

        // F4 静态整集：两样都没标，但明显是一整集 ⇒ 必须补 ENDLIST
        StringBuilder big = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
        for (int i = 0; i < 40; i++) big.append("#EXTINF:30.000,\n").append(i).append(".ts\n");
        String o4 = HlsPlaylistFixer.INSTANCE.fix(big.toString(), "https://cdn.a.com/h/i.m3u8");
        ok("★ 静态整集（40 片 × 30s，无 PLAYLIST-TYPE / 无 ENDLIST）要补 ENDLIST",
                o4.contains("#EXT-X-ENDLIST"), "kept=40 totalDur=1200");

        // F5 反向控制：直播窗口（几片、几十秒）不能补 ENDLIST
        String live = "#EXTM3U\n#EXT-X-VERSION:3\n"
                + "#EXTINF:6.000,\n0.ts\n#EXTINF:6.000,\n1.ts\n#EXTINF:6.000,\n2.ts\n"
                + "#EXTINF:6.000,\n3.ts\n#EXTINF:6.000,\n4.ts\n#EXTINF:6.000,\n5.ts\n";
        String o5 = HlsPlaylistFixer.INSTANCE.fix(live, "https://cdn.a.com/live/i.m3u8");
        ok("★ 反向控制：直播窗口不补 ENDLIST（补了会被当已结束截断）",
                !o5.contains("#EXT-X-ENDLIST"), o5.replace("\n", " | "));

        // F6 回归：原文自带 ENDLIST 要还回去
        String o6 = HlsPlaylistFixer.INSTANCE.fix(
                "#EXTM3U\n#EXTINF:2.0,\n0.ts\n#EXT-X-ENDLIST\n", "https://cdn.a.com/i.m3u8");
        ok("回归：原文带 ENDLIST 的要补回", o6.contains("#EXT-X-ENDLIST"));

        // F7 声明 VOD 的要补
        String o7 = HlsPlaylistFixer.INSTANCE.fix(
                "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:2.0,\n0.ts\n",
                "https://cdn.a.com/i.m3u8");
        ok("PLAYLIST-TYPE:VOD 补 ENDLIST", o7.contains("#EXT-X-ENDLIST"));

        // F8 master 逐字节不改
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1920x1040\n"
                + "3000k/hls/mixed.m3u8\n";
        eq("master playlist 原样返回", master,
                HlsPlaylistFixer.INSTANCE.fix(master, "https://cdn.a.com/m/i.m3u8"));

        // F9 广告段剔除 + TARGETDURATION 按剩余最大值
        String o9 = HlsPlaylistFixer.INSTANCE.fix(
                "#EXTM3U\n#EXT-X-TARGETDURATION:9\n#EXTINF:2.0,\n0.ts\n"
                        + "#EXT-X-DISCONTINUITY\n#EXTINF:9.0,\n/video/adjump/time/ad.ts\n"
                        + "#EXTINF:3.5,\n1.ts\n#EXT-X-ENDLIST\n",
                "https://cdn.a.com/h/i.m3u8");
        ok("广告段被剔除", !o9.contains("adjump"), o9.replace("\n", " | "));
        ok("TARGETDURATION 按剩余最大值（4）", o9.contains("#EXT-X-TARGETDURATION:4"),
                o9.replace("\n", " | "));
        ok("#EXTM3U 只出现一次", o9.indexOf("#EXTM3U") == o9.lastIndexOf("#EXTM3U"));

        // F10 全被剔光 → 原文返回（不产出空 playlist）
        String allAd = "#EXTM3U\n#EXTINF:9.0,\n/adjump/1.ts\n";
        eq("全被剔光时返回原文", allAd,
                HlsPlaylistFixer.INSTANCE.fix(allAd, "https://cdn.a.com/i.m3u8"));

        // F11 非 playlist 原样返回
        eq("非 playlist 原样返回", "hello",
                HlsPlaylistFixer.INSTANCE.fix("hello", "https://a/i.m3u8"));
    }

    // ------------------------------------------------------------------ G

    static void gGuards(String here, String proj) {
        banner("G. 源码守卫：判据不能只在注释里成立（含「必须真的被调用」）");

        String root = proj + "/app/src/main/java/com/videoshell/";
        String player = read(root + "player/PlayerActivity.kt");
        String sniff = read(root + "player/SniffActivity.kt");
        String hls = read(root + "player/HlsFix.kt");
        String adapter = read(root + "ui/adapter/VideoAdapter.kt");
        String browser = read(root + "ui/SiteBrowser.kt");
        String agg = read(root + "data/site/AggSearch.kt");
        String layoutSniff = read(proj + "/app/src/main/res/layout/activity_sniff.xml");
        String layoutBrowser = read(proj + "/app/src/main/res/layout/view_site_browser.xml");
        String strings = read(proj + "/app/src/main/res/values/strings.xml");
        String store = read(root + "data/Store.kt");

        // ★ 自证：断言辅助函数的文案顺序必须与调用点一致。
        // 反之（参数名叫 got 却收期望值）判定照样绿，但**报错会把"谁错了"指反** ——
        // v1.0.38 就是这么把正确的 4 改成 6 的。这类坑必须在源码层钉住。
        String selfSrc = read(Paths.get(here, "Agg38.java").toString());
        ok("★ eq 的参数名与打印顺序一致（期望在前、实测在后）",
                selfSrc != null
                        && selfSrc.contains("eq(String name, Object want, Object got)")
                        && selfSrc.contains("\"want=\" + want + \" got=\" + got"));

        for (String[] f : new String[][]{
                {"PlayerActivity.kt", player}, {"SniffActivity.kt", sniff}, {"HlsFix.kt", hls},
                {"VideoAdapter.kt", adapter}, {"SiteBrowser.kt", browser}, {"AggSearch.kt", agg},
                {"activity_sniff.xml", layoutSniff}, {"view_site_browser.xml", layoutBrowser},
                {"strings.xml", strings}, {"Store.kt", store}}) {
            ok(f[0] + " 可读", f[1] != null && f[1].length() > 200);
        }

        // ---- 播放「卡住看门狗」----
        // ⚠️ 判据**不带 `private ` 前缀**（v1.0.54 改）。原来写的是 `private fun xxx(`，
        //    那是把「可见性修饰符」也钉进了判据 —— 而 god file 拆分时，被搬到扩展文件里的
        //    函数一律变 `internal fun Owner.xxx(`（扩展函数访问不了 private）。
        //    于是"功能一行没改、只是搬了家"也会判红（见 PITFALLS §4.24 / §4.41）。
        //    这里要钉的是「这个名字、这个签名的声明存在」，可见性不是它要表达的东西。
        ok("★ 有 watchStall 定义", live(player, "fun watchStall()"));
        ok("★ watchStall 在刷新循环里真的被调用（不是只写了函数）",
                countLive(player, "watchStall()") >= 2, "count=" + countLive(player, "watchStall()"));
        ok("★ 卡住判据用的是 bufferedPosition（观测得到的事实）",
                live(player, "p.bufferedPosition"));
        ok("★ 有卡住时长常量（不能立刻判定，慢链路会被误伤）", live(player, "STALL_MS = "));
        ok("★ 有下一路候选时自动换源", live(player, "stalledSwitched"));
        ok("★ 卡住面板复用出错面板（同一套 UI）", live(player, "fun showStallPanel("));
        ok("★ 面板会报出 isLive（判错直播是转圈的第一嫌疑）",
                live(player, "isCurrentMediaItemLive"));
        ok("★ 重试次数被压小（原为 10 ⇒ 四十多秒静默转圈）",
                live(player, "MEDIA_RETRIES") && live(player, "DefaultLoadErrorHandlingPolicy("));
        ok("★ 播放器补 Origin（浏览器走 XHR 一定带，我们原来不带）",
                live(player, "\"Origin\""));
        ok("browserHeaders 只补缺失、不覆盖站点试出来的头",
                live(player, "fun browserHeaders(): Map<String, String>"));
        ok("用页面源算 Origin", live(player, "originOf(fallbackPage)"));

        // ---- HlsFix ----
        ok("★ 目录从 URI 的 rawPath 取（旧实现直接按最后一个斜杠切）", live(hls, "rawPath"));
        ok("★ 有独立的 dirOf（可断言的那一份判据）", live(hls, "fun dirOf("));
        ok("★ 静态整集阈值是常量（可调、可断言）",
                live(hls, "STATIC_MIN_SEGMENTS") && live(hls, "STATIC_MIN_SECONDS"));
        ok("协议相对用 playlist 自己的协议", live(hls, "scheme"));

        // ---- 嗅探浮窗 ----
        ok("布局里有拖动手柄", live(layoutSniff, "panelHandle"));
        ok("布局里有可收起的主体", live(layoutSniff, "panelBody"));
        ok("布局里有收起/展开按钮", live(layoutSniff, "btnPanelToggle"));
        ok("布局里有收起后那一行摘要", live(layoutSniff, "tvPanelBrief"));
        ok("★ 收起状态真的改了可见性（不是只存了个变量）",
                live(sniff, "binding.panelBody.visibility"));
        ok("★ 拖动用 rawX/rawY（用 x/y 手指移出手柄就乱）",
                live(sniff, "e.rawX") && live(sniff, "e.rawY"));
        ok("★ 有拖动/点击的位移阈值", live(sniff, "SLOP_DP"));
        ok("★ 浮窗被夹在父容器内（不能挪出屏幕找不回来）",
                live(sniff, "fun clampPanel()"));
        ok("★ 收起后重新夹取（否则拖到贴底的位置会浮在半空）",
                live(sniff, "post { clampPanel() }"));
        ok("★ 浏览模式默认收起（那一页是用来看网页的）",
                live(sniff, "panelCollapsed = browse"));
        ok("★ 收起后那一行随候选数更新", live(sniff, "refreshPanelBrief()"));

        // ---- 流式铺网格 ----
        ok("★ AggSearch 有流式入口", live(agg, "runStreaming("));
        ok("★ 界面用的是流式（不是等齐版）", live(browser, "runStreaming("));
        ok("★ 插入位置由 AggSearch 算（界面不做算术）", live(browser, "AggSearch.insertAt("));
        ok("★ 有结果时也挂上失败入口（少了一部分最容易被误读）",
                live(browser, "attachFailures(failures)"));
        ok("★ 失败清单为空时要摘掉监听（看不见但能点的区域）",
                live(browser, "b.tvScopeHint.setOnClickListener(null)"));
        ok("VideoAdapter 有按块插入", live(adapter, "fun insertBlock("));
        ok("★ 按块插入走局部通知，不整表重绑",
                live(adapter, "notifyItemRangeInserted(pos, block.size)"));

        // ---- 引擎选择 ----
        ok("站源页有引擎 chip 行", live(layoutBrowser, "engineRow"));
        ok("引擎 chip：百度/搜狗/360/Bing/DuckDuckGo",
                live(layoutBrowser, "engBaidu") && live(layoutBrowser, "engSogou")
                        && live(layoutBrowser, "eng360") && live(layoutBrowser, "engBing")
                        && live(layoutBrowser, "engDdg"));
        ok("关键词增强 chip 在界面上", live(layoutBrowser, "engSuffix"));
        ok("★ 引擎行只在全网范围出现", live(browser, "b.engineRow.visibility"));
        ok("★ 换引擎会持久化（按名字）", live(browser, "Store.setSearchEngine(act, e.name)"));
        ok("★ 正在搜时换引擎立刻重开（否则用户还盯着旧页面）",
                live(browser, "openWebSearch(keyword)"));
        ok("Store 有引擎与前缀开关的读写",
                live(store, "fun searchEngine(") && live(store, "fun setSearchEngine(")
                        && live(store, "fun searchEnhance(") && live(store, "fun setSearchEnhance("));
        ok("首页「浏览网页」用的是用户选定的引擎",
                live(read(root + "ui/MainActivity.kt"), "SearchEngine.of(Store.searchEngine("));

        // ---- 文案 ----
        for (String s : new String[]{"engine_baidu", "engine_sogou", "engine_360", "engine_bing",
                "engine_ddg", "engine_suffix_online", "scope_agg_streaming",
                "sniff_panel_collapse", "sniff_panel_expand", "sniff_panel_collapsed",
                "stall_title", "stall_state_live", "stall_switched"}) {
            ok("文案 " + s + " 存在", live(strings, "name=\"" + s + "\""));
        }
        ok("★ 全网提示不再写死 Bing（带引擎参数）",
                live(browser, "R.string.scope_hint_web, act.getString(engine.labelRes)"));
    }
}
