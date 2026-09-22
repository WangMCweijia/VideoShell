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
import java.util.Collections;
import java.util.List;

/**
 * v1.0.6 回归：网络层「自动重试 + 快速失败 + IPv4 优先 DNS」。
 *
 * 要证明三件事：
 *  1) 连不上的地址会重试（NetLog 里能看到多次尝试）—— 这正是"首次请求抖一下、
 *     整条分类栏就消失"的解药；
 *  2) 4xx 不重试（内容问题，重试没意义，别浪费时间）；
 *  3) 改完之后两条真实站点的链路仍然正常（无回归）。
 *
 * 注意：用 127.0.0.1:1（必然连接被拒）而不是不存在的域名 —— 有的运营商会劫持
 * NXDOMAIN 返回广告页，"域名不存在"在本机并不确定会失败，测不准。
 */
public class Retry {

    static int pass = 0, fail = 0;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        String outDir = args[0];
        Files.createDirectories(Paths.get(outDir));

        // ---------------------------------------------------------------- 1) 死地址必须重试
        banner("1) 连不上的地址 -> 必须重试（旧实现只试一次就放弃）");
        NetLog.INSTANCE.clear();
        String err1 = "";
        try {
            block((scope, cont) -> Http.INSTANCE.get("http://127.0.0.1:1/",
                    null, Http.UA, Collections.<String, String>emptyMap(), false,
                    (Continuation<? super String>) cont));
        } catch (Exception e) {
            err1 = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        int n1 = NetLog.INSTANCE.entries().size();
        System.out.println("  抛出的异常 : " + err1);
        System.out.println("  NetLog 记录:");
        for (NetLog.Entry e : NetLog.INSTANCE.entries()) {
            System.out.println("    [" + e.getStatus() + "] " + e.getUrl() + "  " + e.getErr());
        }
        ok("死地址最终抛异常（不会伪装成成功）", !err1.isEmpty());
        ok("尝试 3 次（1 次 + 2 次重试），实际 " + n1 + " 次", n1 == 3);

        // ---------------------------------------------------------------- 2) 4xx 不重试
        banner("2) 4xx -> 只试 1 次（内容层面的问题，重试没意义）");
        NetLog.INSTANCE.clear();
        String err2 = "";
        try {
            block((scope, cont) -> Http.INSTANCE.get("https://czzy.app/__videoshell_probe_missing_page__",
                    null, Http.UA, Collections.<String, String>emptyMap(), false,
                    (Continuation<? super String>) cont));
        } catch (Exception e) {
            err2 = String.valueOf(e.getMessage());
        }
        int n2 = NetLog.INSTANCE.entries().size();
        System.out.println("  抛出的异常 : " + err2 + "   NetLog 记录数: " + n2);
        if (err2.startsWith("HTTP 4")) {
            ok("4xx 只尝试 1 次，实际 " + n2 + " 次", n2 == 1);
        } else {
            System.out.println("  [SKIP] 该地址没有回 4xx（实际: " + err2 + "），跳过这条断言");
        }

        // ---------------------------------------------------------------- 3) 真实站点无回归
        checkSite("茶杯狐", new SiteConfig("cf", "茶杯狐", "https://www.cupfoxyy.com", "",
                SiteConfig.MODE_HTML, "", "", 0L), outDir, "cf");
        checkSite("厂长资源", new SiteConfig("cz", "厂长资源", "https://czzy.app", "",
                SiteConfig.MODE_HTML, "", "", 0L), outDir, "cz");

        // ---------------------------------------------------------------- 4) 坏站点诊断可定位
        banner("4) 打不开的站 -> 提示条必须能说清「哪个地址、什么错」");
        SiteConfig dead = new SiteConfig("dead", "坏站", "http://127.0.0.1:1", "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter da = AdapterFactory.INSTANCE.create(dead);
        List<Category> none = block((scope, cont) -> da.categories((Continuation<? super List<Category>>) cont));
        String diag = String.valueOf(da.getLastDiag());
        System.out.println("  lastDiag = " + diag);
        ok("坏站点分类为空", none.isEmpty());
        ok("lastDiag 非空（用户看得到原因）", !diag.trim().isEmpty());
        ok("lastDiag 带上了具体地址", diag.contains("127.0.0.1:1"));
        ok("lastDiag 带上了异常类型", diag.contains("Exception"));

        System.out.println("\n" + "=".repeat(72));
        System.out.println(fail == 0 ? ("ALL CHECKS PASSED  (pass=" + pass + ")")
                : ("FAILED  pass=" + pass + " fail=" + fail));
        System.out.println("=".repeat(72));
        if (fail != 0) System.exit(1);
    }

    static void checkSite(String label, SiteConfig site, String outDir, String tag) throws Exception {
        banner("3) 真实站点无回归 —— " + label + "  " + site.getBaseUrl());
        String report = block((scope, cont) -> SiteDoctor.INSTANCE.run(site, (Continuation<? super String>) cont));
        Files.write(Paths.get(outDir, tag + "_doctor_v6.txt"), report.getBytes(StandardCharsets.UTF_8));
        System.out.println(report);

        ok(label + " 报告带版本号", report.contains("版本：v"));
        ok(label + " [1] 首页 HTTP 通", !report.contains("[1] 首页请求\n    HTTP -1"));
        ok(label + " [2] 分类 > 0", !line(report, "[2]").contains("结果：0 个"));
        ok(label + " [3] 列表 > 0", !line(report, "[3]").contains("结果：0 条"));

        SiteAdapter a = AdapterFactory.INSTANCE.create(site);
        List<Category> cats = block((scope, cont) -> a.categories((Continuation<? super List<Category>>) cont));
        ok(label + " categories() 真实调用 > 0（实际 " + cats.size() + "）", cats.size() > 0);
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
