import com.videoshell.data.model.*;
import com.videoshell.data.net.NetLog;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.util.List;

/**
 * 「跨 Activity」回归 —— v1.0.11 的核心修复点。
 *
 * 真机结构：SiteActivity 与 DetailActivity 是**两个 Activity，各建一个 Adapter 实例**。
 * 而 Bs.java / Bs2.java 都只用一个实例跑完全链路，所以
 * 「分类能出、列表能出、一点详情就失败」这个 bug 在离线永远测不出来。
 *
 * 本类刻意**每个步骤都新建 adapter**，复现真机行为。
 */
public class Bs3 {

    static int pass = 0, fail = 0;
    static final String BASE = "https://www.bolyship.com";
    static final String ID = "548165";

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    static SiteConfig site() {
        return new SiteConfig("bs", "金牌影视", BASE, "", SiteConfig.MODE_HTML, "", "", 0L);
    }

    public static void main(String[] args) throws Exception {
        NetLog.INSTANCE.setVerbose(true);

        // ---------------------------------------------------------------- A
        banner("A. 配方为空 + 全新实例直接 detail（冷启动 / 从历史记录进详情）");
        RecipeStore.INSTANCE.clear(BASE);
        SiteAdapter d0 = AdapterFactory.INSTANCE.create(site());
        NetLog.INSTANCE.clear();
        VideoDetail v0 = block((s, c) -> d0.detail(ID, (Continuation<? super VideoDetail>) c));
        ok("detail 成功且线路 = 4（实际 " + v0.getGroups().size() + "）", v0.getGroups().size() == 4);
        SiteRecipe r0 = RecipeStore.INSTANCE.load(BASE);
        ok("配方已固化 detailTpl", r0 != null && r0.getDetailTpl() != null);
        if (r0 != null) {
            System.out.println("    detailTpl = " + r0.getDetailTpl());
            System.out.println("    playTpl   = " + r0.getPlayTpl());
        }
        // 曾经写错过：substring 切掉了目录名、groupValues 索引整体偏移 1
        ok("playTpl 形状正确（目录名与 sid/nid 都不能错）",
                r0 != null && "https://www.bolyship.com/bspvp/{id}-1-1.html".equals(r0.getPlayTpl()));
        System.out.println("    本次共发出 " + NetLog.INSTANCE.entries().size() + " 条请求（含回首页现学）");

        // ---------------------------------------------------------------- B
        banner("B. 模拟 SiteActivity -> DetailActivity（两个独立实例）");
        SiteAdapter listA = AdapterFactory.INSTANCE.create(site());          // SiteActivity 的
        List<Category> cats = block((s, c) ->
                listA.categories((Continuation<? super List<Category>>) c));
        ok("分类 > 0（实际 " + cats.size() + "）", cats.size() > 0);

        String typeId = cats.isEmpty() ? "" : cats.get(0).getId();
        List<VideoItem> items = block((s, c) ->
                listA.browse(typeId, 1, (Continuation<? super List<VideoItem>>) c));
        ok("列表 > 0（实际 " + items.size() + "）", items.size() > 0);

        String id = items.isEmpty() ? ID : items.get(0).getId();
        SiteAdapter detailB = AdapterFactory.INSTANCE.create(site());        // DetailActivity 的（全新！）
        NetLog.INSTANCE.clear();
        VideoDetail v1 = block((s, c) ->
                detailB.detail(id, (Continuation<? super VideoDetail>) c));
        for (PlayGroup g : v1.getGroups()) {
            System.out.println("    · " + g.getName() + "  " + g.getEpisodes().size() + " 集");
        }
        // ⚠️ 这里**不判「线路 == 4」**：id 取自「站点当前列表的第一条」，会随站点换片单
        // 漂移（本次实测这一部就是 2 条）。判"条数等于 4"= 把站点的**内容**当规格，
        // 站点一换片单我们就收到一条假红。本段真正要证明的是
        // 「**一个全新实例也能把详情解析出来**」（v1.0.11 的修复），所以判"成型"。
        ok("新实例 detail 成功且每组成型（" + shape(v1) + "）", wellFormed(v1));
        List<?> es = NetLog.INSTANCE.entries();
        System.out.println("    详情页网络请求 " + es.size() + " 条：");
        System.out.println(NetLog.INSTANCE.report());
        // 修复前：配方不存在 -> 先穷举 8 个 detail 候选 + 6 个 play 候选，全 404
        ok("配方生效：只 1 次请求就命中（没退化成穷举）", es.size() == 1);

        // ---------------------------------------------------------------- C
        banner("C. viewpager 场景：同一实例重复 detail（缓存不污染）");
        VideoDetail v2 = block((s, c) ->
                detailB.detail(id, (Continuation<? super VideoDetail>) c));
        // C 判「重复调用与首次**完全一致**」= 缓存不污染。
        // 用"和第一次逐条相等"而不是再写一遍 4：前者更强（条数/名字/集数全比），
        // 而且同样不受片单漂移影响。
        ok("第二次 detail 与第一次逐条一致（缓存不污染）", shape(v2).equals(shape(v1)));

        // ---------------------------------------------------------------- D
        banner("D. 配方跨实例可见（模拟 App 重启后的新实例）");
        SiteAdapter fresh = AdapterFactory.INSTANCE.create(site());
        NetLog.INSTANCE.clear();
        VideoDetail v3 = block((s, c) ->
                fresh.detail(ID, (Continuation<? super VideoDetail>) c));
        ok("又一个新实例 detail 成功", v3.getGroups().size() == 4);
        ok("仍只 1 次请求", NetLog.INSTANCE.entries().size() == 1);

        // ---------------------------------------------------------------- E
        banner("E. playTplFrom / detailTplFrom 纯函数");
        eq("playTplFrom  /bspvp/548165-1-1.html",
                HtmlTemplates.INSTANCE.playTplFrom("https://x.com/bspvp/548165-1-1.html"),
                "https://x.com/bspvp/{id}-1-1.html");
        eq("playTplFrom  /v_play/23804-2-3.html（厂长那类）",
                HtmlTemplates.INSTANCE.playTplFrom("https://x.com/v_play/23804-2-3.html"),
                "https://x.com/v_play/{id}-2-3.html");
        eq("playTplFrom  /vodplay/123-1-1.html",
                HtmlTemplates.INSTANCE.playTplFrom("https://x.com/vodplay/123-1-1.html"),
                "https://x.com/vodplay/{id}-1-1.html");
        ok("playTplFrom 反例：非数字 id 必须返回 null",
                HtmlTemplates.INSTANCE.playTplFrom("https://x.com/v_play/YWJj-2-3.html") == null);
        eq("detailTplFrom  /bspvd/548165.html",
                HtmlTemplates.INSTANCE.detailTplFrom("https://x.com/bspvd/548165.html", "548165"),
                "https://x.com/bspvd/{id}.html");
        ok("detailTplFrom 反例：id 不在路径里必须返回 null",
                HtmlTemplates.INSTANCE.detailTplFrom("https://x.com/bspvd/999.html", "548165") == null);

        System.out.println("\n" + "=".repeat(72));
        System.out.println(fail == 0 ? ("ALL CHECKS PASSED  (pass=" + pass + ")")
                : ("FAILED  pass=" + pass + " fail=" + fail));
        System.out.println("=".repeat(72));
        if (fail != 0) System.exit(1);
    }

    static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + what);
        } else {
            fail++;
            System.out.println("  [FAIL] " + what);
        }
    }

    /**
     * 线路列表「成型」：至少 1 组，且每组名字非空、集列表非空。
     *
     * 为什么需要这个：B/C 段用的是「站点当前列表的第一条」拿到的 id，
     * 会随站点换片单而漂移 —— 判"条数 == 4"等于把**站点的内容**当规格，
     * 站点一换片单就假红（本次实测就是 2 条）。这一段真正要证明的是
     * 「**一个全新的实例也能把详情解析出来**」（v1.0.11 的修复），所以判"成型"。
     * 钉死"恰好 4 条"的断言留在 A / D —— 那两处用的是固定 id 548165。
     */
    static boolean wellFormed(VideoDetail v) {
        if (v == null || v.getGroups().isEmpty()) return false;
        for (PlayGroup g : v.getGroups()) {
            if (g.getName() == null || g.getName().trim().isEmpty()) return false;
            if (g.getEpisodes().isEmpty()) return false;
        }
        return true;
    }

    /** 把一列线路压成可比较的字符串（名字 + 集数），用于判"两次结果是否逐条一致"。 */
    static String shape(VideoDetail v) {
        StringBuilder b = new StringBuilder();
        for (PlayGroup g : v.getGroups()) {
            b.append(g.getName()).append('/').append(g.getEpisodes().size()).append("集 ");
        }
        return b.toString().trim();
    }

    static void eq(String what, String actual, String expect) {
        boolean c = expect.equals(actual);
        if (!c) System.out.println("     expect=" + expect + "\n     actual=" + actual);
        ok(what, c);
    }

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(72));
        System.out.println(s);
        System.out.println("=".repeat(72));
    }
}
