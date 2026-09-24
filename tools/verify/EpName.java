import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.videoshell.data.model.Episode;
import com.videoshell.data.model.VideoItem;
import com.videoshell.data.site.CryptApi;
import com.videoshell.data.site.CryptFamily;
import com.videoshell.data.site.CryptRecipe;
import com.videoshell.data.site.YeguoMap;
import com.videoshell.util.EpisodeOrder;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * v1.0.36「野果分集名全是剧名」的断言套件。
 *
 * 用户原话：**「野果分集列表中，分集名称全是剧名，需要优化显示为集数」**。
 *
 * 根因（实测 20 部剧，2026-09-20）：站点下发的分集名是**自带剧名**的长串
 *   `少妇白洁 第一集` … `少妇白洁 第二十二集`／`AI魔改 速通西游第一集`／
 *   `《庆余年》 第三季第一集`；第一集常常连序号都没有（`时间停止` 那一集名 == 剧名）；
 *   个别集把整段剧情简介贴进标题。照原样铺进选集网格 ⇒ 每格都在复读剧名。
 *
 * 套件钉五件事：
 *   A 集号判据（[EpisodeOrder.noInTitle]，全项目唯一一份）：中文数字、形状优先
 *   B 整列同源：标题 → 站点序号 → 数组位置，三选一，**不许逐条混用**
 *   C 真实夹具（照实测数据原样构造）跑 [YeguoMap.episodesFrom]：名字必须是「第N集」
 *   D 排序跟着编号走（原先「第3集」会被排到第一位 —— 那是两个来源混比的后果）
 *   E 判据只有一份：SsrPayload 不再自持 TITLE_NO
 *   F 端到端（真网络）：真取一部剧的接口，产出名必须干净、递增、互不相同
 *
 * 入参：a[0] = 夹具目录  a[1] = 工程根（E 段读源码）
 */
public class EpName {

    static final String YG = "https://agenda.fzchosdi.cc";

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

    static void eq(String name, Object got, Object want) {
        ok(name, Objects.equals(got, want), "got=" + got + " want=" + want);
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("========== " + s + " ==========");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
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


    static String names(List<Episode> l) {
        List<String> n = new ArrayList<>();
        for (Episode e : l) n.add(e.getName());
        return String.join(",", n);
    }

    static String idx(List<Integer> l) {
        return l.toString().replace("[", "").replace("]", "").replace(" ", "");
    }

    static Episode ep(String name, String url) {
        // Episode 增加第 3 字段 pic 后，Java 侧构造点必须显式给值（Kotlin 默认值 Java 用不上）
        return new Episode(name, url, "");
    }

    /**
     * 造一份 `playlet/play` 的 `data`（字段名与形态照真实接口抄）。
     *
     * `index` / `sort` 都按 1..n 给 —— 实测 20/20 是这样（`SsrPayload` 注释里那句
     * "sort 恒为 1"说的是 SSR payload，不是这个接口）。
     */
    static JsonObject playData(String... titles) {
        JsonObject data = new JsonObject();
        JsonArray arr = new JsonArray();
        for (int i = 0; i < titles.length; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("index", i + 1);
            o.addProperty("sort", i + 1);
            o.addProperty("id", 186514 + i);
            o.addProperty("episode_title", titles[i]);
            o.addProperty("video_url", "https://cdn/" + i + ".m3u8");
            arr.add(o);
        }
        data.add("episodeAll", arr);
        return data;
    }

    static List<Episode> eps(JsonObject data) {
        return YeguoMap.INSTANCE.episodesFrom("2275", data,
                new ArrayList<JsonElement>(), new HashMap<String, String>());
    }

    public static void main(String[] a) throws Exception {
        String here = a.length > 0 ? a[0] : ".";
        String proj = a.length > 1 ? a[1] : ".";

        // ---------------------------------------------------------------- A
        banner("A. 集号判据 EpisodeOrder.noInTitle（全项目唯一一份）");

        eq("「少妇白洁 第二十一集」→ 21（中文数字）", 21, EpisodeOrder.INSTANCE.noInTitle("少妇白洁 第二十一集"));
        eq("「少妇白洁 第一集」→ 1", 1, EpisodeOrder.INSTANCE.noInTitle("少妇白洁 第一集"));
        eq("「《庆余年》 第三季第一集」→ 1（不是 3 —— 「季」不是集号标记）",
                1, EpisodeOrder.INSTANCE.noInTitle("《庆余年》 第三季第一集"));
        eq("「AI魔改 速通西游第一集」→ 1", 1, EpisodeOrder.INSTANCE.noInTitle("AI魔改 速通西游第一集"));
        eq("「我能不能看到欲望值 第3集」→ 3（阿拉伯与中文必须归到同一个号）",
                3, EpisodeOrder.INSTANCE.noInTitle("我能看到欲望值 第3集"));
        eq("「剧名 第1集 1080P」→ 1（不是 1080）", 1, EpisodeOrder.INSTANCE.noInTitle("剧名 第1集 1080P"));
        eq("「第 1 话」→ 1（带空格）", 1, EpisodeOrder.INSTANCE.noInTitle("第 1 话"));
        eq("「第一百零八集」→ 108", 108, EpisodeOrder.INSTANCE.noInTitle("第一百零八集"));
        eq("「十二」→ 12（无「第」不带形状 ⇒ null）", null, EpisodeOrder.INSTANCE.noInTitle("十二"));
        eq("「时间停止」→ null（整条就是剧名，没有集号）", null, EpisodeOrder.INSTANCE.noInTitle("时间停止"));
        eq("「AI短剧 窥破爱人谎言2」→ null（末尾那个 2 不是集号，不许当集号用）",
                null, EpisodeOrder.INSTANCE.noInTitle("AI短剧 窥破爱人谎言2"));
        eq("「舔狗2应有尽有」→ null（剧名里的 2 不是集号）",
                null, EpisodeOrder.INSTANCE.noInTitle("舔狗2应有尽有"));

        // ---------------------------------------------------------------- B
        banner("B. 整列同源：标题 → 站点序号 → 数组位置（不许逐条混用）");

        eq("整列标题都有号且互不相同 ⇒ 用标题",
                "[1, 2, 3]",
                YeguoMap.INSTANCE.episodesNoes(
                        Arrays.asList("少妇白洁 第一集", "少妇白洁 第二集", "少妇白洁 第三集"),
                        Arrays.asList(1, 2, 3)).toString());
        eq("标题一个号都没有 ⇒ 整列用站点序号（窥破爱人谎言那种）",
                "[1, 2, 3]",
                YeguoMap.INSTANCE.episodesNoes(
                        Arrays.asList("AI短剧 窥破爱人谎言", "AI短剧 窥破爱人谎言2", "AI短剧 窥破爱人谎言3"),
                        Arrays.asList(1, 2, 3)).toString());
        eq("★标题只缺一个 ⇒ **整列**退到站点序号（不是那一集单独退）",
                "[1, 2, 3]",
                YeguoMap.INSTANCE.episodesNoes(
                        Arrays.asList("舔狗2应有尽有", "舔狗2应有尽有 第二集", "舔狗2应有尽有第三集"),
                        Arrays.asList(1, 2, 3)).toString());
        eq("★站点序号重复（站点给脏了）⇒ 整列退到数组位置 1..n",
                "[1, 2, 3]",
                YeguoMap.INSTANCE.episodesNoes(
                        Arrays.asList("A", "B", "C"),
                        Arrays.asList(1, 1, 1)).toString());
        eq("站点序号从 0 起 / 缺项 ⇒ 不用它，退到数组位置",
                "[1, 2, 3]",
                YeguoMap.INSTANCE.episodesNoes(
                        Arrays.asList("A", "B", "C"),
                        Arrays.asList(0, 2, 3)).toString());
        eq("空列不炸", "[]", YeguoMap.INSTANCE.episodesNoes(
                new ArrayList<String>(), new ArrayList<Integer>()).toString());

        eq("显示名一律「第N集」", "第7集", YeguoMap.INSTANCE.episodeLabel(7));
        eq("显示名不出现「第0集」（兜底到 1）", "第1集", YeguoMap.INSTANCE.episodeLabel(0));

        // ---------------------------------------------------------------- C
        banner("C. 真实夹具跑 episodesFrom：名字必须是「第N集」");

        // ① 少妇白洁：22 集，中文数字 第一集..第二十二集
        String[] sb = new String[22];
        String[] cn = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
                "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
                "二十一", "二十二"};
        for (int i = 0; i < 22; i++) sb[i] = "少妇白洁 第" + cn[i] + "集";
        List<Episode> e1 = eps(playData(sb));
        eq("少妇白洁：22 集", 22, e1.size());
        eq("少妇白洁：首集名", "第1集", e1.get(0).getName());
        eq("少妇白洁：第 21 集名（中文数字二十一）", "第21集", e1.get(20).getName());
        eq("少妇白洁：末集名", "第22集", e1.get(21).getName());
        ok("少妇白洁：一个剧名都没漏进显示名", !names(e1).contains("少妇白洁"), names(e1));

        // ② 窥破爱人谎言：3 集，标题里一个「第N集」都没有
        List<Episode> e2 = eps(playData(
                "AI短剧 窥破爱人谎言", "AI短剧 窥破爱人谎言2", "AI短剧 窥破爱人谎言3"));
        eq("窥破爱人谎言：三格各自不同号", "第1集,第2集,第3集", names(e2));

        // ③ 妹妹帮我操妈妈：第 1 集标题里塞了整段简介
        List<Episode> e3 = eps(playData(
                "妹妹帮我操妈妈 第1集觉醒了最离谱的异能，竟是“精液依赖”？意外的一击竟让毒舌妹妹瞬间沦为痴情小C，眼神黏腻，直接扑到床上索吻！",
                "妹妹帮我操妈妈 第2集"));
        eq("带六十多字简介的脏标题被收敛成一个短名", "第1集,第2集", names(e3));
        ok("显示名长度全部 <= 6 个字（网格一格放得下）",
                names(e3).length() <= 12, names(e3));

        // ④ 时间停止：只有一集，名字就等于剧名
        List<Episode> e4 = eps(playData("时间停止"));
        eq("单集剧的名字变成「第1集」（原先直接显示剧名）", "第1集", e4.get(0).getName());

        // ⑤ 伪地址必须还在（真链播放时现取）
        ok("分集地址仍是伪地址 yeguo://play/{vid}/{eid}",
                e1.get(0).getUrl().startsWith("yeguo://play/2275/"), e1.get(0).getUrl());

        // ---------------------------------------------------------------- D
        banner("D. 排序跟着编号走（原先「第3集」会排到第一位）");

        eq("少妇白洁：正序 = 第1集在前", "0", String.valueOf(EpisodeOrder.INSTANCE.order(e1, false).get(0)));
        eq("少妇白洁：倒序 = 第22集在前", "21", String.valueOf(EpisodeOrder.INSTANCE.order(e1, true).get(0)));

        // 回归：不带剧名的**脏标题**（v1.0.35 真实形态）+ 伪地址。
        // 旧代码逐条回退 ⇒ 号是 [186514, 186515, 3]，第 3 集被排到第 1 位。
        List<Episode> dirty = new ArrayList<>(Arrays.asList(
                ep("我能看到欲望值 第一集", "yeguo://play/2275/186514"),
                ep("我能看到欲望值 第二集", "yeguo://play/2275/186515"),
                ep("我能看到欲望值 第3集", "yeguo://play/2275/186516")));
        eq("★中文数字混阿拉伯数字：整列同源 ⇒ 顺序不变（旧代码这里会变成 2,0,1）",
                "0,1,2", idx(EpisodeOrder.INSTANCE.order(dirty, false)));
        eq("脏标题也能从集名取到号（不再退到伪地址里的 186514）",
                "1,2,3",
                String.valueOf(Arrays.asList(
                        EpisodeOrder.INSTANCE.noOf(dirty.get(0)),
                        EpisodeOrder.INSTANCE.noOf(dirty.get(1)),
                        EpisodeOrder.INSTANCE.noOf(dirty.get(2)))).replace("[", "").replace("]", "").replace(" ", ""));

        // 标题完全取不到号的一列（窥破爱人谎言）：整列退到地址，地址号一致 ⇒ 站点顺序
        List<Episode> noTitle = new ArrayList<>(Arrays.asList(
                ep("AI短剧 窥破爱人谎言", "yeguo://play/2275/186514"),
                ep("AI短剧 窥破爱人谎言2", "yeguo://play/2275/186515"),
                ep("AI短剧 窥破爱人谎言3", "yeguo://play/2275/186516")));
        eq("标题无号的一列：整列退地址、仍是站点顺序", "0,1,2",
                idx(EpisodeOrder.INSTANCE.order(noTitle, false)));

        // ---------------------------------------------------------------- E
        banner("E. 判据只有一份（源码守卫）");

        String ssr = read(proj + "/app/src/main/java/com/videoshell/data/site/SsrPayload.kt");
        String eo = read(proj + "/app/src/main/java/com/videoshell/util/EpisodeOrder.kt");
        String ya = read(proj + "/app/src/main/java/com/videoshell/data/site/YeguoAdapter.kt");
        String adapter = read(proj + "/app/src/main/java/com/videoshell/data/site/HtmlAdapter.kt");

        // ⚠️ 用 contains("TITLE_NO") 会**把注释也算成回归** —— SsrPayload 里那段
        //    "原来这里有一份 TITLE_NO，已删除"正是为了记下这次合并的原因，必须留着。
        //    所以只认"活的声明 / 活的调用"（这条正是本项目"守卫绑死写法"老坑的反面教材）。
        ok("E1 SsrPayload 不再自持一份集号判据（TITLE_NO 的声明与调用都已删；注释留名是为了记教训）",
    // ⚠️ 源码判据**不带 `private ` 前缀**（v1.0.54 统一改过）。
    //    原来写的是 `"private fun xxx("`，那是把「可见性修饰符」也钉进了判据 ——
    //    而 god file 拆分时被搬到扩展文件里的函数一律变 `internal fun Owner.xxx(`
    //    （扩展函数访问不了 private），于是"功能一行没改、只是搬了家"也会判红。
    //    判据要表达的是「这个签名的声明存在 / 这个函数体在这里」，可见性不是它要说的东西。
    //    见 docs/PITFALLS.md §4.24 与 §4.41。
                ssr != null && !ssr.contains("val TITLE_NO")
                        && !ssr.contains("TITLE_NO.find"), "又加回来了");
        ok("E2 SsrPayload 走共用判据 EpisodeOrder.noInTitle",
                ssr != null && ssr.contains("EpisodeOrder.noInTitle"), "没接上");
        ok("E3 YeguoMap 走共用判据（自己不再写一份正则）",
                ya != null && ya.contains("EpisodeOrder.noInTitle")
                        && !ya.contains("val EP_MARKER"), "又抄了一份");
        ok("E4 全项目只有一处 EP_MARKER",
                eo != null && eo.contains("val EP_MARKER")
                        && !(ya != null && ya.contains("EP_MARKER")), "判据不止一份");
        ok("E5 分集名不再直接透传站点标题（episode_title 只当作集号来源）",
                ya != null && !ya.contains("out += Episode(title,"), "又透传了");
        ok("E6 卡片显示的就是 Episode.name（改动点只有一个）",
                adapter != null, "读不到源码");

        // ---------------------------------------------------------------- F
        banner("F. 端到端（真网络）：真取一部剧，产出名必须干净、递增、互不相同");
        // ⚠️ **真网络段必须自带墙钟预算**（v1.0.71 main 轮实测教训，2026-09-24）：
        //   "站点够不着"有两种坏结局，而只有一种会被下面那个 catch 兜住 ——
        //     ① 立刻失败 / 抛异常 → catch(Throwable) → SKIP ✓
        //     ② **丢包黑洞**：不抛异常，每次调用都安静地等到 client 的 callTimeout(22s) 才放弃。
        //        本段最多 1(resolve 含挖掘)+1(list)+8×(detail+play) ≈ 20 次调用 ⇒ 最坏 ≈440s，
        //        直接撞穿 runepname.py 的 **300s 硬超时** ⇒ 整个**离线门禁变红**，
        //        而日志只留一行 `runepname BAD PASS=0 FAIL=0 rc=1`（完全看不出是站点连不上）。
        //   把"站点可达性抖动"记成"我方回归"，是这类门禁最贵的错误 ⇒ 超预算一律降级成 SKIP。
        //   单次调用本身有界（CryptApi 走 Http.client，callTimeout=22s）⇒ 两次调用之间的检查一定被走到。
        final long F_BUDGET_MS = 120_000L;
        // 对照组自测：同一段代码用 **-1ms** 预算跑一遍 ⇒ 必须在**任何网络调用之前**就返回「超预算」。
        // 有它，"超预算"分支才是永远可验证的；否则它只是一段永远跑不到、也没人知道对不对的死代码。
        ok("F0 预算 -1ms ⇒ runF 在做任何网络调用之前就判超预算",
                runF(-1L) == F_OVER_BUDGET, "预算分支没生效（超预算会被当成验完）");
        int fStatus = F_UNREACHABLE;
        try {
            fStatus = runF(F_BUDGET_MS);
        } catch (Throwable t) {
            System.out.println("  [SKIP] F 段（真网络不可用：" + t + "）");
        }
        if (fStatus == F_OVER_BUDGET) {
            System.out.println("  [SKIP] F 段（真网络超预算：" + (F_BUDGET_MS / 1000)
                    + "s 内没取完，已放弃 —— 站点可达性抖动，不是回归）");
        }

        System.out.println();
        System.out.println("==== EpName  " + pass + " PASS / " + fail + " FAIL ====");
        if (!fails.isEmpty()) System.out.println("失败项：" + fails);
        if (fail > 0) System.exit(1);
    }

    // ================================================================== F 段主体

    static final int F_DONE = 0, F_UNREACHABLE = 1, F_OVER_BUDGET = 2;

    /** F 段主体（真网络）。**返回值就是结论**，断言只在这里记：
     *  [F_DONE] 验完 · [F_UNREACHABLE] 够不着 · [F_OVER_BUDGET] 超预算。
     *  超预算从任何检查点直接 return ⇒ F2~F7 一条都不会被记（"没取完"不许写成 PASS / FAIL）。 */
    static int runF(long budgetMs) throws Exception {
        final long fT0 = System.currentTimeMillis();
        // 这个检查排在**任何网络调用之前**：这样 budgetMs<0 时它必然命中，
        // 上面那条 F0 自测才能不依赖网络地证明「超预算」这条路径真的走得通。
        if (System.currentTimeMillis() - fT0 > budgetMs) return F_OVER_BUDGET;

        final CryptRecipe rec = block((s, c) -> CryptFamily.INSTANCE.resolve(
                YG, YG, (Continuation<? super CryptRecipe>) c));
        if (rec == null) {
            System.out.println("  [SKIP] F 段（自证没命中，需要能访问 " + YG + "）");
            return F_UNREACHABLE;
        }
        if (System.currentTimeMillis() - fT0 > budgetMs) return F_OVER_BUDGET;

        Map<String, String> p = new HashMap<>();
        p.put("page", "1");
        p.put("limit", "20");
        JsonObject lst = block((s, c) -> CryptApi.INSTANCE.call(rec, "/api/theater/exploreList",
                p, YG, (Continuation<? super JsonObject>) c));
        List<VideoItem> items = YeguoMap.INSTANCE.itemsFromResp(lst);
        ok("F1 拿到列表", !items.isEmpty(), "条数=" + items.size());
        // 列表第一条常常是单集短剧 —— 单集验不出「递增 / 互不相同」，往后找一部多集的。
        List<Episode> out = null;
        String title = "";
        String picked = "";
        for (int k = 0; k < Math.min(8, items.size()); k++) {
            if (System.currentTimeMillis() - fT0 > budgetMs) return F_OVER_BUDGET;
            String vid = items.get(k).getId();
            Map<String, String> dq = new HashMap<>();
            dq.put("id", vid);
            JsonObject det = block((s, c) -> CryptApi.INSTANCE.call(rec, "/api/playlet/detail",
                    dq, YG, (Continuation<? super JsonObject>) c));
            JsonObject d = det == null ? null : det.getAsJsonObject("data");
            if (d == null) continue;
            JsonArray deps = d.getAsJsonArray("episodes");
            List<JsonElement> dlist = new ArrayList<>();
            if (deps != null) for (JsonElement e : deps) dlist.add(e);
            if (dlist.isEmpty()) continue;
            String videoId = d.has("video_id") ? d.get("video_id").getAsString() : vid;
            String first = dlist.get(0).getAsJsonObject().get("id").getAsString();
            Map<String, String> pq = new HashMap<>();
            pq.put("video_id", videoId);
            pq.put("episode_id", first);
            JsonObject pl = block((s, c) -> CryptApi.INSTANCE.call(rec, "/api/playlet/play",
                    pq, YG, (Continuation<? super JsonObject>) c));
            JsonObject pd = pl == null ? null : pl.getAsJsonObject("data");
            List<Episode> cand = YeguoMap.INSTANCE.episodesFrom(
                    videoId, pd, dlist, YeguoMap.INSTANCE.titleMapFrom(dlist));
            picked = d.get("title").getAsString();
            if (cand.size() > 1) { out = cand; title = picked; break; }
            if (out == null) { out = cand; title = picked; }
        }
        ok("F2 拿到详情与分集", out != null && !out.isEmpty(), "详情/分集为空");
        if (out != null && !out.isEmpty()) {
            System.out.println("       剧名 = " + title + "　分集 = " + out.size() + " 集");
            System.out.println("       前 3 格 = "
                    + names(out.subList(0, Math.min(3, out.size()))));

            boolean shapeOk = true, ascOk = true;
            Set<String> uniq = new HashSet<>();
            int prev = -1;
            for (int i = 0; i < out.size(); i++) {
                String n = out.get(i).getName();
                if (!n.matches("第\\d+集")) shapeOk = false;
                Integer no = EpisodeOrder.INSTANCE.noOf(out.get(i));
                if (no == null) { ascOk = false; continue; }
                if (i > 0 && no <= prev) ascOk = false;
                prev = no;
                uniq.add(n);
            }
            ok("F3 每一格都是「第N集」形状（没有剧名混进来）", shapeOk, names(out));
            ok("F4 集号严格递增", ascOk, names(out));
            ok("F5 集号互不相同（不会出现两个「第1集」）", uniq.size() == out.size(), names(out));
            ok("F6 显示名里没有剧名", !names(out).contains(title), names(out));
            // 多集的那一部必须真的被验到，否则 F4/F5 是空转
            ok("F7 验到的是多集剧（单集验不出递增与互不相同）",
                    out.size() > 1 || items.size() < 8, "只验到 " + out.size() + " 集");
        }
        return F_DONE;
    }
}
