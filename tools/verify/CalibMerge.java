import com.videoshell.data.site.SiteCalib;
import com.videoshell.data.site.SiteRecipe;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * v1.0.29：校准「跳过某一步」的归并语义 + 相关源码守卫。
 *
 * 三种输入的语义必须分清（混起来就「越校越错」）：
 *   学到了 → 覆盖；没学到 → 保留旧值；明确跳过 → 清空。
 */
public class CalibMerge {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + what);
        } else {
            fail++;
            System.out.println("[FAIL] " + what + "   <<< " + detail);
        }
    }

    static String read(File f) throws Exception {
        return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
    }

    public static void main(String[] a) throws Exception {
        String root = a.length > 0 ? a[0] : ".";

        // 旧配方：四步都有值
        SiteRecipe cur = new SiteRecipe(1, false, "d1", "p1", null, "s1", "nav1", "cat1",
                /*homeCat*/ null, /*learnedCatTpl*/ null, /*learnedCatAt*/ 0L,
                111L, "old", 222L);

        System.out.println("== R 归并语义 ==");
        SiteRecipe r1 = SiteCalib.INSTANCE.mergeRecipe(cur, "cat2", "nav2", "d2", "p2", "s2",
                false, false, false, "n1", 999L);
        ok("R1 学到了就覆盖",
                "cat2".equals(r1.getCatTpl()) && "nav2".equals(r1.getNavSel())
                        && "d2".equals(r1.getDetailTpl()) && "p2".equals(r1.getPlayTpl())
                        && "s2".equals(r1.getSearchTpl()), String.valueOf(r1.getCatTpl()));
        ok("R2 写入 calibAt / calibNote",
                r1.getCalibAt() == 999L && "n1".equals(r1.getCalibNote()),
                r1.getCalibAt() + "/" + r1.getCalibNote());

        SiteRecipe r2 = SiteCalib.INSTANCE.mergeRecipe(cur, null, null, null, null, null,
                false, false, false, "n2", 1L);
        ok("R3 没学到就保留旧值",
                "cat1".equals(r2.getCatTpl()) && "nav1".equals(r2.getNavSel())
                        && "d1".equals(r2.getDetailTpl()) && "p1".equals(r2.getPlayTpl()),
                String.valueOf(r2.getCatTpl()));

        SiteRecipe r3 = SiteCalib.INSTANCE.mergeRecipe(cur, null, null, null, null, null,
                true, false, false, "n3", 1L);
        ok("R4 跳过分类 ⇒ 清空 catTpl/navSel，其余保留",
                r3.getCatTpl() == null && r3.getNavSel() == null
                        && "d1".equals(r3.getDetailTpl()) && "p1".equals(r3.getPlayTpl()),
                r3.getCatTpl() + "/" + r3.getDetailTpl());

        SiteRecipe r4 = SiteCalib.INSTANCE.mergeRecipe(cur, null, null, null, null, null,
                false, true, false, "n4", 1L);
        ok("R5 跳过详情 ⇒ 清空 detailTpl，分类保留",
                r4.getDetailTpl() == null && "cat1".equals(r4.getCatTpl())
                        && "p1".equals(r4.getPlayTpl()),
                r4.getDetailTpl() + "/" + r4.getCatTpl());

        SiteRecipe r5 = SiteCalib.INSTANCE.mergeRecipe(cur, null, null, null, null, null,
                false, false, true, "n5", 1L);
        ok("R6 跳过分集 ⇒ 清空 playTpl，其余保留",
                r5.getPlayTpl() == null && "d1".equals(r5.getDetailTpl()),
                String.valueOf(r5.getPlayTpl()));

        // 跳过优先于"本次又传了值"—— 防止调用方顺序写错时静默保留错规则
        SiteRecipe r6 = SiteCalib.INSTANCE.mergeRecipe(cur, "catX", "navX", "dX", "pX", null,
                true, true, true, "n6", 1L);
        ok("R7 跳过优先于本次学到的值",
                r6.getCatTpl() == null && r6.getDetailTpl() == null && r6.getPlayTpl() == null
                        && r6.getNavSel() == null,
                r6.getCatTpl() + "/" + r6.getDetailTpl() + "/" + r6.getPlayTpl());

        // 搜索步没有"跳过"标志（留空即跳过）⇒ 不传就该保留旧值
        SiteRecipe r7 = SiteCalib.INSTANCE.mergeRecipe(cur, null, null, null, null, null,
                false, false, false, "n7", 1L);
        ok("R8 搜索模板留空不误清", "s1".equals(r7.getSearchTpl()), String.valueOf(r7.getSearchTpl()));

        // ---------------------------------------------------------------- 源码守卫
        System.out.println("== S 源码守卫 ==");
        String calib = read(new File(root, "app/src/main/java/com/videoshell/ui/CalibrateActivity.kt"));
        ok("S1 绑定了 btnSkip", calib.contains("binding.btnSkip.setOnClickListener"), "");
        ok("S2 覆盖 finish() 补 RESULT_OK（③ 根因 A）",
                calib.contains("override fun finish()")
                        && calib.contains("if (committed) setResult(RESULT_OK)"), "");
        ok("S3 commit 置位 committed", calib.contains("committed = true"), "");
        ok("S4 跳过时清空该步模板",
                calib.contains("skipCat = true") && calib.contains("catTpl = null")
                        && calib.contains("detailTpl = null") && calib.contains("playTpl = null"), "");
        ok("S5 跳过第 3 步后不再试播空地址",
                calib.contains("if (playUrl.isBlank())") && calib.contains("已跳过试播"), "");
        ok("S6 归并走 SiteCalib.mergeRecipe", calib.contains("SiteCalib.mergeRecipe("), "");

        String layout = read(new File(root, "app/src/main/res/layout/activity_calibrate.xml"));
        ok("S7 布局含 btnSkip", layout.contains("@+id/btnSkip"), "");

        String strings = read(new File(root, "app/src/main/res/values/strings.xml"));
        ok("S8 文案齐（calib_skip/cat/detail/play）",
                strings.contains("name=\"calib_skip\"")
                        && strings.contains("calib_skip_cat")
                        && strings.contains("calib_skip_detail")
                        && strings.contains("calib_skip_play"), "");

        String browser = read(new File(root, "app/src/main/java/com/videoshell/ui/SiteBrowser.kt"));
        ok("S9 onCalibReturned 有默认站源兜底（③ 根因 B）",
                browser.contains("Store.defaultSite(act)"), "");

        String adapter = read(new File(root, "app/src/main/java/com/videoshell/data/site/SiteAdapter.kt"));
        ok("S10 resolve 接入 MacPlayer 跟随",
                adapter.contains("MacPlayer.FOLLOW_MAX") && adapter.contains("linesForPlay"), "");
        ok("S11 嗅探目标仍是原始播放页（不引入回归）",
                adapter.contains("return MediaSource.Sniff(u, playHeaders())"), "");

        String macp = read(new File(root, "app/src/main/java/com/videoshell/data/site/MacPlayer.kt"));
        ok("S12 player_list 被负向断言排除",
                macp.contains("(?!list)"), "");
        ok("S13 支持抓外链 playerconfig.js",
                macp.contains("configScriptUrl"), "");

        System.out.println("==== pass=" + pass + " fail=" + fail + " ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}
