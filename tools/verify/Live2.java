import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.net.NetLog;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/**
 * v1.0.4 回归：走**真实 Http + 真实 suspend 链路**（runBlocking 驱动），
 * 覆盖 NetLog / CookieJar / AdapterFactory / HtmlAdapter / SiteDoctor。
 */
public class Live2 {

    static int pass = 0, fail = 0;

    /**
     * 只看**真正干活**的适配器。
     *
     * v1.0.35 起「未知域名」会套一层延迟家族路由 [FamilyRouter]（第一次真解析时自证
     * 是不是加密接口族），`instanceof HtmlAdapter` 会因为**外面多包了一层**而假红 ——
     * 而那句断言的本意（"apiUrl 为空的站不能掉进 maccms 分支"）依然成立。
     */
    /**
     * 只看**真正干活**的适配器（判据本体只有一份：[Chains]）。
     *
     * ⚠️ 这里原来写死"剥一层 FamilyRouter"，v1.0.53 最外层加了 SeedRouter 之后假红 ——
     *    而 `apiMode=null/unknown + apiUrl 空 必须走网页解析` 的**本意从未改变**。
     */
    static boolean worksAs(SiteAdapter a, Class<?> kind) {
        return Chains.worksAs(a, kind);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        String outDir = args[0];
        Files.createDirectories(Paths.get(outDir));

        check("茶杯狐", new SiteConfig("cf", "茶杯狐", "https://www.cupfoxyy.com", "",
                SiteConfig.MODE_HTML, "", "", 0L), outDir, "cf");
        check("厂长资源", new SiteConfig("cz", "厂长资源", "https://czzy.app", "",
                SiteConfig.MODE_HTML, "", "", 0L), outDir, "cz");

        banner("AdapterFactory 兜底（apiUrl 为空必须走 HTML，不能掉进 maccms）");
        SiteConfig weird = new SiteConfig("x", "怪配置", "https://example.com", "",
                SiteConfig.MODE_HTML, "", "", 0L);
        // 用反射把 apiMode 置 null —— 精确模拟 Gson 的行为：
        // Gson 走 Unsafe 分配对象、不调用构造函数，因此 Kotlin 的非空默认值不会生效，
        // 老配置里缺 apiMode 字段就会变成 null（这正是线上可能发生的那种"怪配置"）。
        java.lang.reflect.Field f = SiteConfig.class.getDeclaredField("apiMode");
        f.setAccessible(true);
        f.set(weird, null);
        SiteAdapter wa = AdapterFactory.INSTANCE.create(weird);
        ok("apiMode=null(Gson 场景) + apiUrl 空 -> " + wa.getClass().getSimpleName(),
                worksAs(wa, HtmlAdapter.class));
        SiteConfig weird2 = new SiteConfig("x2", "怪配置2", "https://example.com", "",
                "unknown_mode", "", "", 0L);
        ok("apiMode=unknown + apiUrl 空 -> " + AdapterFactory.INSTANCE.create(weird2).getClass().getSimpleName(),
                worksAs(AdapterFactory.INSTANCE.create(weird2), HtmlAdapter.class));
        SiteConfig normalMaccms = new SiteConfig("m", "正常maccms", "https://m.com",
                "https://m.com/api.php/provide/vod/", SiteConfig.MODE_MACCMS_JSON, "", "", 0L);
        ok("apiUrl 非空 + maccms_json -> " + AdapterFactory.INSTANCE.create(normalMaccms).getClass().getSimpleName(),
                AdapterFactory.INSTANCE.create(normalMaccms) instanceof MaccmsAdapter);

        banner("NetLog 工作正常");
        ok("NetLog 有记录（SiteDoctor 自检后）", !NetLog.INSTANCE.isEmpty());
        System.out.println("  --- lastFailure: " + NetLog.INSTANCE.lastFailure());

        System.out.println("\n" + "=".repeat(72));
        System.out.println(fail == 0 ? ("ALL CHECKS PASSED  (pass=" + pass + ")")
                : ("FAILED  pass=" + pass + " fail=" + fail));
        System.out.println("=".repeat(72));
        if (fail != 0) System.exit(1);
    }

    static void check(String label, SiteConfig site, String outDir, String tag) throws Exception {
        banner("站点 " + label + "  " + site.getBaseUrl());

        String report = block((scope, cont) -> SiteDoctor.INSTANCE.run(site, (Continuation<? super String>) cont));
        Files.write(Paths.get(outDir, tag + "_doctor.txt"), report.getBytes(StandardCharsets.UTF_8));
        System.out.println(report);

        ok("[1] 首页请求成功（报告里没有 ERR）", !report.contains("[1] 首页请求\n    HTTP -1"));
        ok("[2] 分类 > 0", !line(report, "[2]").contains("结果：0 个"));
        ok("[3] 列表 > 0", !line(report, "[3]").contains("结果：0 条"));
        ok("[4] 详情有线路", !line(report, "[4]").contains("结果：失败"));
        // 有的站播放页确实抠不到直链、需要嗅探 —— 那也是正确行为，不能算失败
        ok("[5] 播放地址解析可用（直链或嗅探均可）",
                !line(report, "[5]").contains("失败：") && !line(report, "[5]").contains("未返回结果"));

        // 再单独验证一次 categories()（真实 suspend 调用）
        SiteAdapter a = AdapterFactory.INSTANCE.create(site);
        List<Category> cats = block((scope, cont) -> a.categories((Continuation<? super List<Category>>) cont));
        ok("categories() 真实调用返回 > 0（实际 " + cats.size() + "）", cats.size() > 0);

        List<VideoItem> items = block((scope, cont) -> a.browse("", 1, (Continuation<? super List<VideoItem>>) cont));
        ok("browse(首页) 真实调用返回 > 0（实际 " + items.size() + "）", items.size() > 0);

        if (!items.isEmpty()) {
            VideoDetail d = block((scope, cont) -> a.detail(items.get(0).getId(),
                    (Continuation<? super VideoDetail>) cont));
            ok("detail() 真实调用有线路（实际 " + d.getGroups().size() + "）", !d.getGroups().isEmpty());
        }
    }

    /** 取报告中某一步那一行 */
    static String line(String report, String step) {
        int i = report.indexOf(step);
        if (i < 0) return "";
        int j = report.indexOf("\n[", i + 1);
        if (j < 0) j = report.length();
        return report.substring(i, j);
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

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(72));
        System.out.println(s);
        System.out.println("=".repeat(72));
    }
}
