import com.videoshell.player.HlsPlaylistFixer;
import com.videoshell.player.HlsPrefetch;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ## HlsPrefetch 离线回归（v1.0.65）
 *
 * **真跑逻辑**，不是读源码文本猜：拉片的动作是注入的（[HlsPrefetch] 只依赖 JDK + kotlin-stdlib），
 * 所以这里用假 fetch 把每条约束都跑一遍 —— 并发度、窗口、字节上限、连续失败就停、
 * 在途去重、换源作废、直播/短清单不预取。
 *
 * 为什么值得单独写一整套：这类"加速"功能最典型的失败模式是**静默无效** ——
 * 线程开了、日志好看，但一片都没命中（用户体感"还是卡"）。所以断言都打在**决策与计数**上
 * （拉了多少片、拉的是哪些、缓存多少字节、有没有停），而不是"代码里有那几行字"。
 */
public class HlsPrefetchTest {

    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    /** 工程根（runner 用 `-Dvs.root=` 传；缺省按当前目录，源码守卫会自行报"读不到"） */
    static final String ROOT = System.getProperty("vs.root", ".");

    static String readSrc(String rel) {
        try {
            return new String(
                    java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(ROOT, rel.split("/"))),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 剥掉块注释与行注释。
     *
     * 判"代码里有没有某个东西"必须先剥：本项目多个文件**故意**在 KDoc 里写着反例
     * （"`file/download` 是死路"、"不做 127.0.0.1 转发"），按裸文本判会让守卫自己红。
     * 简化实现：不认字符串里的 `//`（本文件只用于不含 URL 字面量的源码，够用且行为可预期）。
     */
    static String stripComments(String s) {
        if (s == null) return null;
        StringBuilder out = new StringBuilder(s.length());
        boolean inBlock = false, inLine = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char nxt = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (inBlock) {
                if (c == '*' && nxt == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (c == '/' && nxt == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '/' && nxt == '/') {
                inLine = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    static void ok(String name, boolean cond, String why) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            fails.add(name + " —— " + why);
            System.out.println("  [FAIL] " + name + " —— " + why);
        }
    }

    /** 假域名：harness 里可以写，产品代码里不许（产品判据是形状，见 PanLinkTest E5） */
    static final String PL = "https://cdn.example.com/a/b/index.m3u8";
    static final String SEG = "https://cdn.example.com/a/b/s";

    static final Function1<String, Unit> NOOP = new Function1<String, Unit>() {
        @Override
        public Unit invoke(String s) {
            return Unit.INSTANCE;
        }
    };

    /** 点播清单：n 片 + ENDLIST */
    static String vod(int n) {
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        for (int i = 0; i < n; i++) b.append("#EXTINF:4.000,\n").append(SEG).append(i).append(".ts\n");
        b.append("#EXT-X-ENDLIST\n");
        return b.toString();
    }

    /** 假拉片器：记录拉过哪些片、并发峰值，可调延时/大小/必败 */
    static class Fake {
        final List<String> got = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger live = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        volatile long delayMs = 5;
        volatile int segBytes = 1000;
        volatile boolean alwaysFail = false;

        final Function1<String, byte[]> fn = new Function1<String, byte[]>() {
            @Override
            public byte[] invoke(String url) {
                int n = live.incrementAndGet();
                peak.accumulateAndGet(n, Math::max);
                try {
                    if (delayMs > 0) Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    live.decrementAndGet();
                }
                got.add(url);
                return alwaysFail ? null : new byte[segBytes];
            }
        };

        int count() {
            return got.size();
        }

        boolean has(int i) {
            return got.contains(SEG + i + ".ts");
        }

        int distinct() {
            return new LinkedHashSet<String>(got).size();
        }
    }

    public static void main(String[] args) {
        System.out.println("========== HlsPrefetch：并发预取的离线回归 ==========");

        // ---------------------------------------------------------------- P0 常量
        // 顺手钉住"文档里写的数字"与"代码里的数字"一致 —— 这类阈值被随手改大改小
        // （窗口 8 → 100、上限 48MB → 512MB）在低端机上就是 OOM / 抢带宽，且没有任何报错。
        ok("P0 阈值与文档一致（窗口 8 / 上限 48MB / 连败 3 次停）",
                HlsPrefetch.DEFAULT_WINDOW == 8
                        && HlsPrefetch.DEFAULT_MAX_BYTES == 48L * 1024 * 1024
                        && HlsPrefetch.FAILS_TO_STOP == 3,
                "窗口=" + HlsPrefetch.DEFAULT_WINDOW + " 上限=" + HlsPrefetch.DEFAULT_MAX_BYTES
                        + " 阈值=" + HlsPrefetch.FAILS_TO_STOP);

        // ---------------------------------------------------------------- P1 基本预热
        Fake f1 = new Fake();
        f1.delayMs = 60;
        HlsPrefetch p1 = new HlsPrefetch(f1.fn, NOOP, 4, 8, 1L << 20);
        p1.onPlaylist(PL, vod(20));
        boolean idle = p1.awaitIdle(8000);
        ok("P1a 在飞任务能在超时内跑完（harness 靠 awaitIdle，不靠 sleep 猜）", idle, "还有任务在飞");
        ok("P1b 预热**包含第 0 片**（起播最要紧的一片；游标初值必须是 -1 才覆盖得到）",
                f1.has(0), "第 0 片没被拉 ⇒ 起播那一下照样要等");
        ok("P1c 预热片数 = 窗口 8 片", f1.count() == 8, "实际拉了 " + f1.count());
        ok("P1d 缓存片数同步增长", p1.cachedCount() == 8, "缓存 " + p1.cachedCount());
        ok("P1e 没有重复拉同一片", f1.distinct() == f1.count(), "有重复：" + f1.count() + " 次 / " + f1.distinct() + " 片");

        // ---------------------------------------------------------------- P2 并发度
        ok("P2 真的并发了（峰值 ≥ 2）且不越界（≤ threads=4）",
                f1.peak.get() >= 2 && f1.peak.get() <= 4, "并发峰值 " + f1.peak.get());

        // ---------------------------------------------------------------- P3/P4/P5 不该预取的三种清单
        Fake f3 = new Fake();
        HlsPrefetch p3 = new HlsPrefetch(f3.fn, NOOP, 4, 8, 1L << 20);
        p3.onPlaylist("https://cdn.example.com/live/index.m3u8",
                vod(20).replace("#EXT-X-ENDLIST\n", ""));
        p3.awaitIdle(800);
        ok("P3 直播清单（无 ENDLIST）一片都不拉（窗口一直在变，拉了也是白拉）",
                f3.count() == 0, "拉了 " + f3.count());

        Fake f4 = new Fake();
        HlsPrefetch p4 = new HlsPrefetch(f4.fn, NOOP, 4, 8, 1L << 20);
        p4.onPlaylist(PL,
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080\n"
                        + "https://cdn.example.com/a/b/1080.m3u8\n#EXT-X-ENDLIST\n");
        p4.awaitIdle(800);
        ok("P4 master 清单（STREAM-INF）不拉（那是子清单地址，不是分片）",
                f4.count() == 0, "拉了 " + f4.count());

        Fake f5 = new Fake();
        HlsPrefetch p5 = new HlsPrefetch(f5.fn, NOOP, 4, 8, 1L << 20);
        p5.onPlaylist(PL, vod(3));
        p5.awaitIdle(800);
        ok("P5 太短的清单（3 片，少于 MIN_SEGMENTS）不预取 —— 预热反而抢播放器的带宽",
                f5.count() == 0, "拉了 " + f5.count());

        // ---------------------------------------------------------------- P6 命中
        ok("P6a 已预取的分片能取到整片字节", p1.get(SEG + "0.ts") != null, "取不到");
        ok("P6b 未预取的分片返回 null（不假装有）", p1.get(SEG + "19.ts") == null, "不该有却有了");
        ok("P6c 问过几次是可观测的（判定「有没有真命中」只看这里）",
                p1.lookupCount() == 2, "lookup=" + p1.lookupCount());

        // ---------------------------------------------------------------- P7 字节上限
        Fake f7 = new Fake();
        f7.segBytes = 1000;
        HlsPrefetch p7 = new HlsPrefetch(f7.fn, NOOP, 4, 8, 3000);
        p7.onPlaylist(PL, vod(20));
        p7.awaitIdle(8000);
        ok("P7a 缓存字节不越上限（按最久未用淘汰）",
                p7.cachedBytes() <= 3000, "占了 " + p7.cachedBytes() + " 字节");
        ok("P7b 淘汰不影响窗口内继续预取（8 片都拉过）", f7.count() == 8, "实际 " + f7.count());

        // ---------------------------------------------------------------- P8 单片超上限
        Fake f8 = new Fake();
        f8.segBytes = 5000;
        HlsPrefetch p8 = new HlsPrefetch(f8.fn, NOOP, 4, 8, 1000);
        p8.onPlaylist(PL, vod(20));
        p8.awaitIdle(8000);
        ok("P8 单片就超过上限 ⇒ 整片不缓存（否则会把已有缓存全冲光，命中率反而崩）",
                p8.cachedCount() == 0 && p8.cachedBytes() == 0,
                "缓存 " + p8.cachedCount() + " 片 / " + p8.cachedBytes() + " 字节");

        // ---------------------------------------------------------------- P9 连续失败就停
        Fake f9 = new Fake();
        f9.alwaysFail = true;
        HlsPrefetch p9 = new HlsPrefetch(f9.fn, NOOP, 4, 8, 1L << 20);
        p9.onPlaylist(PL, vod(20));
        p9.awaitIdle(8000);
        int before = f9.count();
        p9.onSegment(SEG + "10.ts");
        p9.awaitIdle(1000);
        ok("P9a 连续拿不到分片就停（这时正确动作是把带宽让回播放器，不是继续抢）",
                p9.isStopped(), "没停");
        ok("P9b 停了之后不再发起任何请求", f9.count() == before, "又拉了 " + (f9.count() - before));

        // ---------------------------------------------------------------- P10 跟着播放位置推进
        int before10 = f1.count();
        p1.onSegment(SEG + "8.ts");
        p1.awaitIdle(8000);
        ok("P10a 播放器走到第 8 片 ⇒ 接着预取 9~16（滑动窗口，不是只热开头）",
                f1.has(9) && f1.has(16), "第 9/16 片没拉");
        ok("P10b 推进过程中仍不重复拉（在途/已缓存都跳过）",
                f1.distinct() == f1.count(), "重复：" + f1.count() + " / " + f1.distinct());
        ok("P10c 确实拉过新的片（不是空转）", f1.count() > before10, "片数没变");

        // ---------------------------------------------------------------- P11 换源作废
        Fake f11 = new Fake();
        f11.delayMs = 150;
        HlsPrefetch p11 = new HlsPrefetch(f11.fn, NOOP, 4, 8, 1L << 20);
        p11.onPlaylist(PL, vod(20));
        p11.reset();
        p11.awaitIdle(8000);
        ok("P11 换源后旧任务的结果不进新缓存（generation 作废）—— 否则换台后播的是上一个源的分片",
                p11.cachedCount() == 0, "混进了 " + p11.cachedCount() + " 片");

        // ---------------------------------------------------------------- P12 观测点
        String st = p1.stats();
        ok("P12 stats 有命中/缓存这些字样（没有观测点就等于「机制存不存在都不知道」）",
                st.contains("命中") && st.contains("缓存"), st);

        // ---------------------------------------------------------------- P13 与 fixer 协同
        // 预取器吃的是 **fix 之后**的清单（HlsFixDataSource 就是这么接的），
        // 所以"fix 的输出能不能被认出来"必须单独验一次：相对路径要已绝对化、
        // 广告片要被剔掉、ENDLIST 要被补上 —— 少一样预取就静默失效（0 片）。
        StringBuilder raw = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        for (int i = 0; i < 10; i++) {
            raw.append("#EXTINF:4.000,\n").append("s").append(i).append(".ts\n");
            if (i == 3) raw.append("#EXTINF:4.000,\n/video/adjump/time/ad.ts\n");
        }
        String fixed = HlsPlaylistFixer.INSTANCE.fix(raw.toString(), PL);
        ok("P13a fixer 剔除了广告分片", !fixed.contains("adjump"), "广告还在");
        ok("P13b fixer 把相对分片绝对化了",
                fixed.contains("https://cdn.example.com/a/b/s0.ts"), "还是相对路径");
        Fake f13 = new Fake();
        HlsPrefetch p13 = new HlsPrefetch(f13.fn, NOOP, 4, 8, 1L << 20);
        p13.onPlaylist(PL, fixed);
        p13.awaitIdle(8000);
        ok("P13c 预取器认得 fixer 的输出并真的开始拉（两个组件是接得上的）",
                f13.count() == 8, "拉了 " + f13.count());
        ok("P13d 拉的是绝对地址（fixer 已绝对化，预取直接照用）",
                f13.count() > 0 && f13.got.get(0).startsWith("https://cdn.example.com/a/b/"),
                f13.count() > 0 ? f13.got.get(0) : "(一片都没拉)");

        // ---------------------------------------------------------------- G 接线守卫
        // 真跑逻辑管不到"有没有接上"：预取器全绿、但装配那一行被摘掉，表现就是
        // **完全没效果**（用户体感"还是卡"）且没有任何报错。所以补这几条源码形状守卫。
        String pm = readSrc("app/src/main/java/com/videoshell/player/PlayerActivity_Media.kt");
        String hf = readSrc("app/src/main/java/com/videoshell/player/HlsFix.kt");
        String hp = readSrc("app/src/main/java/com/videoshell/player/HlsPrefetch.kt");
        String ht = readSrc("app/src/main/java/com/videoshell/data/net/Http.kt");
        ok("G0 守卫自测：四个源文件都读得到（否则 G1~G6 全是恒真断言）",
                pm != null && hf != null && hp != null && ht != null,
                (pm == null ? "PM " : "") + (hf == null ? "HLSFIX " : "")
                        + (hp == null ? "PREFETCH " : "") + (ht == null ? "HTTP " : ""));

        // ⚠️ 判据一律走**剥掉注释**的文本：KDoc 里**故意**写着反例
        //（"不做 TVBox 那种 127.0.0.1 转发"），按裸文本判会让守卫自己红 —— 与 PanLinkTest E5/E13 同一个坑。
        String pmCode = stripComments(pm), hfCode = stripComments(hf);
        String hpCode = stripComments(hp), htCode = stripComments(ht);
        ok("G0b 守卫自测：KDoc 里**保留**着反例（否则 G5 会退化成恒真断言）",
                hp != null && hp.contains("127.0.0.1") && !hpCode.contains("127.0.0.1"),
                "剥注释剥掉了不该剥的东西，或 KDoc 里的反例没了");

        ok("G1 装配真的接上了（工厂收 prefetch，buildSource 会造一个）",
                pmCode != null && pmCode.contains("HlsFixDataSourceFactory(factory, prefetch)")
                        && pmCode.contains("HlsPrefetch("), "接线被摘掉 ⇒ 预取器再对也毫无效果");

        // 顺序守卫：缓存命中判断必须在 upstream.open() **之前**。
        // 写反了既不报错也不影响正确性 —— 只是"先发起请求、再从缓存读"，
        // 预取省下的时间全白搭，还平白多占一条连接。这是本层最容易写错的一处。
        int hitAt = hfCode == null ? -1 : hfCode.indexOf("prefetch?.get(");
        int upAt = hfCode == null ? -1 : hfCode.indexOf("val len = upstream.open(dataSpec)");
        ok("G2 缓存命中判断在 upstream.open() **之前**（先发请求再查缓存＝白预取）",
                hitAt > 0 && upAt > 0 && hitAt < upAt, "hit@" + hitAt + " upstreamOpen@" + upAt);

        ok("G3 只对「整份读取」生效（带 Range 的局部读不能拿整片缓存去糊）",
                hfCode != null && hfCode.contains("dataSpec.length == C.LENGTH_UNSET.toLong()")
                        && hfCode.contains("dataSpec.position == 0L"), "");

        // 每 host 并发额度：预取 4 条 + 播放器自身请求，OkHttp 默认的 5 会让播放器的请求
        // 排队等预取腾位置 —— 画面上是"带宽明明够却一顿一顿"，日志里没有一条错误。
        // 这一条判**原文**：Http.kt 里满是 `"https://…"` 字面量，简化版剥注释会把 URL 后面的
        // 同一行内容当成注释吃掉（KDoc 里的反例是 `= 5`，与 `= 8` 不冲突，所以不需要剥）。
        ok("G4 mediaClient 用独立 Dispatcher 且每 host 上限放宽到 8（默认 5 会把播放请求挤进队列）",
                ht != null && ht.contains("maxRequestsPerHost = 8")
                        && ht.contains("okhttp3.Dispatcher()"), "");

        ok("G5 不启本地服务、不改 m3u8 正文（不做 TVBox 那种本地代理）",
                hpCode != null && !hpCode.contains("ServerSocket") && !hpCode.contains("HttpServer")
                        && !hpCode.contains("localhost") && !hpCode.contains("127.0.0.1"), "");

        ok("G6 预取不落盘（直链带时效 ⇒ 绝不落盘，见 PITFALLS §4.60）",
                hpCode != null && !hpCode.contains("java.io.File")
                        && !hpCode.contains("SharedPreferences") && !hpCode.contains("CacheDir"), "");

        // ---------------------------------------------------------------- 汇总
        System.out.println();
        System.out.println("==== pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) {
            System.out.println("失败项：");
            for (String s : fails) System.out.println("  - " + s);
        }
        System.exit(fail == 0 ? 0 : 1);
    }
}
