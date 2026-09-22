import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.videoshell.data.model.SiteConfig;
import com.videoshell.data.site.HtmlAdapter;
import com.videoshell.data.site.HtmlTemplates;
import com.videoshell.data.site.SiteCalib;

/**
 * v1.0.33 —— 「野果：校准流程走完了，但站源仍按原规则显示」的断言套件。
 *
 * 结论（先写在这里，免得下次重新推理）：**校准本身没失败，是那一次点击学到的形状永远不可能生效**。
 *   用户点的是侧栏导航项 `<a href="/explore/drama/">探索分类</a>`；
 *   HtmlTemplates.catTplFrom 把末段当可变别名 ⇒ 泛化成 `/explore/{slug}/`，
 *   与站点真分类 `/tag/{slug}/` 形状**完全相同**。唯一区别是数量：
 *   首页 330 个 a 标签里 `/explore/{slug}/` 只有 1 条、`/tag/{slug}/` 有 252 条。
 *   运行时判据要求 ≥2 ⇒ 条条不中 ⇒ 静默退回默认逻辑 ⇒ 用户看到「什么都没变」。
 *
 * 套件要钉住四件事：
 *   A  这个「数量差」在 App 自己的代码里成立（不是只在 Python 里成立），且新旧形状都能分开；
 *   C  「数 SSR 结果链接」这个判据**不成立** —— 它分不开搜索结果页和首页噪声
 *      （所以那条搜索探针被撤销了，E4 守着它别回来）；
 *   D  拒收时**保留旧配方**（点错一次不等于本站没有分类）；
 *   E  源码守卫：改动不会被静默回退，坏文案不会复活。
 *
 * 入参：a[0] = 夹具目录（本目录）  a[1] = 工程根（E 段读源码）
 */
public class YgCalib {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond, String got) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + "   实际: " + got);
        }
    }

    static void banner(String s) {
        System.out.println("\n== " + s);
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

    static String read(String p) throws IOException {
        String text = new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        String name = new File(p).getName();
        if (!name.endsWith(".kt")) return text;
        File dir = new File(p).getParentFile();
        String[] ns = dir == null ? null : dir.list();
        if (ns == null) return text;
        java.util.Arrays.sort(ns);
        String pre = name.substring(0, name.length() - 3) + "_";
        StringBuilder sb = new StringBuilder(text);
        for (String n : ns) {
            if (n.startsWith(pre) && n.endsWith(".kt")) {
                try {
                    String sub = new String(
                            Files.readAllBytes(new File(dir, n).toPath()), StandardCharsets.UTF_8);
                    for (String sl : sub.split("\n", -1)) {
                        sb.append(unwrap(sl, pre.substring(0, pre.length() - 1))).append('\n');
                    }
                } catch (Exception ignore) { }
            }
        }
        return sb.toString();
    }

    static String src(String root, String rel) throws IOException {
        return read(root + File.separator + rel.replace('/', File.separatorChar));
    }

    /** 源码里出现过的次数 */
    static int count(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /**
     * 源码里有没有**会被打印出去**的一行同时含 needle —— 即 `L("...")` 输出行。
     *
     * 为什么不直接 contains：注释里为了讲清"上一版错在哪"，**必须**引用旧文案
     * （见 SiteDoctor 里那段"v1.0.32 就是这么错的"）。用 contains 判会把注释也算成回归，
     * 于是守卫逼着人删掉注释 —— 那是把守卫写错了，不是把代码写错了。
     */
    static boolean printedLine(String srcText, String needle) {
        for (String line : srcText.split("\n")) {
            if (line.contains("L(\"") && line.contains(needle)) return true;
        }
        return false;
    }

    public static void main(String[] a) throws Exception {
        String here = a.length > 0 ? a[0] : ".";
        String proj = a.length > 1 ? a[1] : ".";
        String BASE = "https://agenda.fzchosdi.cc";

        String home = read(here + "/_shell/yg_home.html");
        String stub = read(here + "/_shell/yg_search_stub.html");

        SiteConfig site = new SiteConfig("yg", "野果", BASE, "", SiteConfig.MODE_HTML, "", "", 0L);
        HtmlAdapter ad = new HtmlAdapter(site);

        // ---------------------------------------------------------------- A
        banner("A. 分类形状「当场自证」—— 数量才是分界线（真实首页样本 " + home.length() + " B）");

        int ex = ad.countCatTplHits(home, "/explore/{slug}/");
        int tg = ad.countCatTplHits(home, "/tag/{slug}/");
        int rk = ad.countCatTplHits(home, "/rank/{slug}/");
        System.out.println("     /explore/{slug}/ = " + ex + "   /tag/{slug}/ = " + tg
                + "   /rank/{slug}/ = " + rk);

        // A1/A2 是这一轮 bug 的两半
        ok("A1 导航项形状 /explore/{slug}/ 命中 < 2（运行时必然静默退回默认逻辑）",
                ex >= 0 && ex < 2, "命中 " + ex);
        ok("A2 真分类形状 /tag/{slug}/ 命中 >= 30（默认逻辑给出的 40 个分类是真的）",
                tg >= 30, "命中 " + tg);
        ok("A3 功能页形状 /rank/{slug}/ 同样命中 < 2（与 /explore/ 属同一类噪声）",
                rk >= 0 && rk < 2, "命中 " + rk);
        ok("A4 两者数量差 >= 20 倍 ⇒ 形状分不开、只有数量分得开",
                ex > 0 && tg / Math.max(ex, 1) >= 20, ex + " vs " + tg);

        // A5/A6 把 -1 与 0 的语义分开：0 会触发拒收，-1 不会
        ok("A5 空模板返回 -1（数不出来、放弃判断），不是 0（确定没有、会拒收）",
                ad.countCatTplHits(home, "") == -1, String.valueOf(ad.countCatTplHits(home, "")));
        ok("A6 正常但无匹配的页面返回 0，且不抛异常",
                ad.countCatTplHits("<html><body>no links here</body></html>", "/tag/{slug}/") == 0,
                String.valueOf(ad.countCatTplHits("<html><body>no links here</body></html>", "/tag/{slug}/")));

        // A7 形状泛化事实：这就是 bug 的另一半
        ok("A7 catTplFrom(/explore/drama/) == /explore/{slug}/（把固定路由段当成了可变别名）",
                "/explore/{slug}/".equals(HtmlTemplates.INSTANCE.catTplFrom("/explore/drama/")),
                String.valueOf(HtmlTemplates.INSTANCE.catTplFrom("/explore/drama/")));
        ok("A8 同一个形状里，站点的 /tag/AI魔改/ 才是真分类（两者形状同族、判别只能靠数量）",
                "/tag/{slug}/".equals(HtmlTemplates.INSTANCE.catTplFrom("/tag/AI%E9%AD%94%E6%94%B9/")),
                String.valueOf(HtmlTemplates.INSTANCE.catTplFrom("/tag/AI%E9%AD%94%E6%94%B9/")));

        // ---------------------------------------------------------------- C
        banner("C. 为什么「数 SSR 结果链接」这个判据不成立");

        int homeDetails = count(home, "/drama/detail/");
        int stubDetails = count(stub, "/drama/detail/");
        System.out.println("     首页样本 " + home.length() + " B，/drama/detail/ 出现 " + homeDetails + " 次");
        System.out.println("     空壳搜索页样本 " + stub.length() + " B，/drama/detail/ 出现 " + stubDetails + " 次");

        ok("C1 空壳搜索页 0 条结果链接（5 KB 级）",
                stubDetails == 0 && stub.length() < 40000, "links=" + stubDetails);
        ok("C2 但首页自己就带 >= 20 条结果链接 ⇒ 同一个数字在首页上是噪声、在结果页上才是信号，"
                        + "两者不可区分（野果 /?s= 把首页原文原样返回，实测 sha256 与首页完全相同）",
                homeDetails >= 20, "links=" + homeDetails);
        ok("C3 首页含关键词（「庆余年」在首页推荐位里）⇒ 严格遍要求标题含关键词，同样会被首页噪声骗过",
                home.contains("庆余年"), "ok");

        // ---------------------------------------------------------------- D
        banner("D. 拒收语义：不写入，但不抹掉上一次学对的规则");

        String calib = src(proj, "app/src/main/java/com/videoshell/ui/CalibrateActivity.kt");
        ok("D1 拒收走 catTpl = null（mergeRecipe 按「没学到」处理 ⇒ 保留旧值）",
                calib.contains("catTpl = if (rejected) null else shape"), "ok");
        ok("D2 拒收时连容器一起不收（同一次点击推出来的 div.app-layout 也只是外壳）",
                calib.contains("navSel = if (rejected) null"), "ok");
        ok("D3 只对确实数到 0/1 判拒收；-1（数不出来）绝不拒收，避免误杀",
                calib.contains("val rejected = hits in 0..1"), "ok");

        // ---------------------------------------------------------------- E
        banner("E. 源码守卫 —— 防静默回退 / 防坏文案复活");

        String adapter = src(proj, "app/src/main/java/com/videoshell/data/site/HtmlAdapter.kt");
        String base = src(proj, "app/src/main/java/com/videoshell/data/site/SiteAdapter.kt");
        String doctor = src(proj, "app/src/main/java/com/videoshell/data/site/SiteDoctor.kt");
        String http = src(proj, "app/src/main/java/com/videoshell/data/net/Http.kt");

        ok("E1 基类声明了校准自证契约 countCatTplHits",
                base.contains("open fun countCatTplHits(html: String, tpl: String): Int = -1"), "ok");
        ok("E2 HtmlAdapter 实现了它，且复用 categoriesFrom 的同一套收集器（判据只有一份）",
                adapter.contains("override fun countCatTplHits") &&
                        count(adapter, "collectSlashDirCategories") >= 3 &&
                        adapter.contains("collectByCatTpl(doc, tmp, tpl)"), "ok");
        ok("E3 校准第 1 步真的调它、并据结果决定收不收",
                calib.contains("countCatTplHits(html, shape)") && calib.contains("hits in 0..1"), "ok");
        ok("E4 不可靠的搜索探针已撤销（数 SSR 结果链接会给出自信而错误的结论）",
                !calib.contains("probeSearchTpl"), "仍存在");
        ok("E5 [3a] 那句自相矛盾的文案已不再**打印**（注释里保留它是为了记下教训）",
                !printedLine(doctor, "图床改回明文了") && !printedLine(doctor, "字节判定"),
                "仍会打印");
        ok("E6 [3a] 改成分层汇报：CDN 原样字节 vs 取图链路",
                printedLine(doctor, "CDN 原样响应") && printedLine(doctor, "取图链路")
                        && doctor.contains("fetchBytesRaw"), "ok");
        ok("E7 自检能拿到未解密的原样字节（否则两层永远混在一起）",
                http.contains("clientRaw") &&
                        http.contains("interceptors().remove(ImageCipher.interceptor)"), "ok");
        ok("E8 [3a] 不再自己再解一遍（App 链路已经解过，重复解会掩盖真实链路）",
                !doctor.contains("AesCipher.decryptBytes"), "仍存在");
        ok("E9 人工搜索模板被自动学习顶掉时会留痕，并进 calibDiag",
                adapter.contains("searchTplSwap") && count(adapter, "searchTplSwap") >= 4, "ok");

        // ---------------------------------------------------------------- G
        // v1.0.36：用户报「校准时，每一步点了对应的位置后，校准流程中有可能提示没识别到」
        //          + 「最后一步填了关键词点确定，流程不会结束」。
        banner("G. v1.0.36 校准：判据一致 / 容器不再连坐 / 第 4 步必有终点");

        String sc = src(proj, "app/src/main/java/com/videoshell/data/site/SiteCalib.kt");

        // G1~G6 是「点了对的分类却提示没识别到」的根因：**同一件事两套判据**。
        // 尾斜杠家族（`/tag/{slug}/`）从 v1.0.32 起就是正式的分类形状家族，
        // catTplFrom / collectSlashDirCategories 都认它，只有 isCategoryShape 没跟上 ——
        // 于是点击当下报「这不像标准分类链接」，按「确定」之后又学得好好的。
        ok("G1 尾斜杠分类 `/tag/熟女/` 算分类形状（原先返 false）",
                SiteCalib.INSTANCE.isCategoryShape("/tag/熟女/"), "仍判为非分类");
        ok("G2 导航项 `/explore/drama/` 同样算（形状一样，分得开的是数量）",
                SiteCalib.INSTANCE.isCategoryShape("/explore/drama/"), "判为非分类");
        ok("G3 maccms 数字分类 `/vodshow/id/6.html` 仍算分类",
                SiteCalib.INSTANCE.isCategoryShape("/vodshow/id/6.html"), "判为非分类");
        ok("G4 详情页 / 分集页不被误判成分类（错收比漏收严重）",
                !SiteCalib.INSTANCE.isCategoryShape("/bspvd/548165.html")
                        && !SiteCalib.INSTANCE.isCategoryShape("/bspvp/548165-4-1.html"), "误收");
        ok("G5 形状判据仍只有一份（SiteCalib 自己不再写形状正则，只委托 HtmlTemplates）",
                sc.contains("HtmlTemplates.isSlashDirCategory"), "没委托");
        ok("G6 classify 对 `/tag/熟女/` 给出 GOOD（点击当下就说「已选中分类」）",
                "GOOD".equals(String.valueOf(SiteCalib.INSTANCE.classify(
                        "/tag/熟女/", "https://y.cc/tag/%E7%86%9F%E5%A5%B3/", SiteCalib.Step.CAT))),
                "不是 GOOD");
        String tpl = HtmlTemplates.INSTANCE.catTplFrom("https://y.cc/tag/%E7%86%9F%E5%A5%B3/");
        ok("G6b `/tag/熟女/` 真能学到形状（GOOD 必须与学习者同源）",
                "/tag/{slug}/".equals(tpl), String.valueOf(tpl));

        // G7~G12 源码守卫：坏写法不许复活
        // ⚠️ G7 曾经写成 `!calib.contains("navSel = if (rejected) null")` —— 那是在**推翻 D2**
        //    （D2 的注释里写着实测理由：同一次点击推出来的容器只是 `div.app-layout` 页面外壳）。
        //    「守卫绑死写法」的老坑在这里又露了一次头：把"我想要的行为"直接翻译成"某一行代码在不在"，
        //    而没有先问"这一行为什么要那样写"。最后留下的是**理由**，不是写法。
        ok("G7 拒收时写明「本次不写入任何分类规则」并说清原因（否则用户以为白点了）",
                calib.contains("不写入任何分类规则"), "没说清楚");
        ok("G8 容器会在「用户点的那一页」也找一遍（用户常在二级页点分类）",
                calib.contains("NavPick(sel, fromPage"), "只找首页");
        ok("G9 第 4 步学不到模板时**必有终点**（不许只改一行状态文本就 return）",
                calib.contains("R.string.calib_search_fail_finish")
                        && calib.contains("R.string.calib_search_fail_retry"), "没有出路");
        ok("G10 关键词对话框的负按钮写明「跳过（直接完成）」，不再是语义相反的「取消」",
                calib.contains("R.string.calib_search_skip"), "文案不对");
        ok("G11 第 4 步会记下点击上报的当前地址（SPA 搜索不换页，pageUrl 会停在首页）",
                calib.contains("if (page.isNotBlank()) pageUrl = page"), "没记");
        ok("G12 第 2 / 3 步的学习顺序与运行时一致（含自研站的 numeric segment 形状）",
                count(calib, "HtmlTemplates.tplFromNumericSegment") >= 2, "少了一条");

        System.out.println("\n---- YgCalib  " + pass + " PASS / " + fail + " FAIL");
        if (a.length > 2 && "strict".equals(a[2]) && fail > 0) System.exit(1);
    }
}
