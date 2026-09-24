package tools.verify;

import com.videoshell.data.site.SiteDetector;

import java.util.ArrayList;
import java.util.List;

/**
 * 站名净化（v1.0.68 E49）的纯函数守卫 —— 无网络、无样本文件。
 *
 * 判据三条腿：
 *   A) 牢骚子句必须被剔除（真实实测 title 逐个过）；
 *   B) 正常站名**逐字节保留**（净化的误伤必须为零 —— 改坏一个好站名比留着一句牢骚严重）；
 *   C) 全句皆牢骚 ⇒ 空（调用方回落 host）或回落 hostFallback（存量自愈路径）。
 */
public class SiteNameTest {

    static int pass = 0, fail = 0;
    static final List<String> BAD = new ArrayList<>();

    static void ok(String what, boolean cond, String detail) {
        String line = (cond ? "[PASS] " : "[FAIL] ") + what
                + (detail == null || detail.isEmpty() ? "" : "   → " + detail);
        System.out.println(line);
        if (cond) pass++;
        else { fail++; BAD.add(what); }
    }

    public static void main(String[] args) {
        SiteDetector d = SiteDetector.INSTANCE;

        System.out.println("-- A. 真实实测 title（2026-09-24 大巡查抓到的原句） --");
        ok("A1 木偶「再见，我们跑路了」⇒ 空（全句皆牢骚，回落 host）",
                d.cleanSiteName("再见，我们跑路了", "").isEmpty(), "");
        ok("A2 快映「随机接口纯自用，求大佬们别爬了」⇒ 空",
                d.cleanSiteName("随机接口纯自用，求大佬们别爬了", "").isEmpty(), "");
        ok("A3 蜡笔「自用求大佬不要爬！」⇒ 空",
                d.cleanSiteName("自用求大佬不要爬！", "").isEmpty(), "");
        ok("A4 欧哥「网站关闭」⇒ 空（title 是关闭告示，站还活着）",
                d.cleanSiteName("网站关闭", "").isEmpty(), "");
        ok("A5 虎斑「求大佬们别爬了」⇒ 空",
                d.cleanSiteName("求大佬们别爬了", "").isEmpty(), "");

        System.out.println("-- B. 正常站名逐字节保留（误伤必须为零） --");
        ok("B1 「玩偶哥哥网盘站 断片中...」原样",
                "玩偶哥哥网盘站 断片中...".equals(d.cleanSiteName("玩偶哥哥网盘站 断片中...", "")), "");
        ok("B2 「闪电优汐」原样", "闪电优汐".equals(d.cleanSiteName("闪电优汐", "")), "");
        ok("B3 「玩偶哥哥网盘站」原样", "玩偶哥哥网盘站".equals(d.cleanSiteName("玩偶哥哥网盘站", "")), "");
        ok("B4 含「大」不含牢骚词的普通名不受「关闭」连字误伤：「大关影院」原样",
                "大关影院".equals(d.cleanSiteName("大关影院", "")), "");
        ok("B5 混合型「精品影院 求收藏勿爬」只剔牢骚子句、留主体",
                "精品影院".equals(d.cleanSiteName("精品影院，求收藏勿爬", "")), "");

        System.out.println("-- C. 回落路径 --");
        ok("C1 全句皆牢骚 + hostFallback ⇒ host",
                "123.666291.xyz".equals(d.cleanSiteName("再见，我们跑路了", "123.666291.xyz")), "");
        ok("C2 空串 + hostFallback ⇒ host",
                "tvpanpan.site".equals(d.cleanSiteName("", "tvpanpan.site")), "");
        ok("C3 空串 + 无 fallback ⇒ 空串",
                d.cleanSiteName("", "").isEmpty(), "");
        ok("C4 牢骚在主体后：「XX影视-别爬了」留主体",
                "XX影视".equals(d.cleanSiteName("XX影视-别爬了", "")), "");

        System.out.println();
        System.out.println("==== SiteNameTest PASS=" + pass + " FAIL=" + fail + " ====");
        if (fail > 0) System.out.println("FAILED: " + BAD);
        System.exit(fail == 0 ? 0 : 1);
    }
}
