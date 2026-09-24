import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.jsoup.Jsoup;

import com.videoshell.data.model.SiteConfig;
import com.videoshell.data.net.Http;
import com.videoshell.data.net.SoftMiss;
import com.videoshell.data.site.HtmlAdapter;
import com.videoshell.data.site.HtmlTemplates;
import com.videoshell.data.site.SiteAdapter;

import kotlin.jvm.functions.Function1;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * v1.0.34 —— 「按 30 多个版本的踩坑经验提高通配性」的断言套件。
 *
 * 本轮把四条**只服务于某个站**的补丁升级成**与站点无关的机制**，这个套件就是它们的守门人：
 *
 *   A  SoftMiss（软 404 / 首页回退守卫）—— 判据是纯函数，可离线断言；
 *   B  形状普查（用真实首页夹具验证「数量分得开」这条判据本身成立）；
 *   C  免校准分类（合成一个"新站"页面，端到端跑 categoriesFrom，证明不用校准也能出分类）；
 *   D  ImageCipher 的未收录站探针（源码守卫 + 语义守卫）；
 *   E  自检报告新增的 [2b] 与改写后的 [3a]（printedLine 守卫，不把注释算成回归）；
 *   F  实时端到端 —— **真正的对照组**：首页副本要认出来，真分类页 / JS 空壳不能被误杀；
 *      F5 的 oracle 按 `SoftMiss` 的**两档**判据独立重算（不调 `isCopyOf`，避免循环论证），
 *      另配 F5b 负控防止 oracle 恒真（见 §4.72）。
 *
 * 入参：a[0] = 夹具目录   a[1] = 工程根（D/E 段读源码）
 */
public class Universal {

    static int pass = 0, fail = 0, skip = 0;

    static void ok(String name, boolean cond, String got) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + "   实际: " + got);
        }
    }

    static void note(String s) {
        System.out.println("       · " + s);
    }

    static void banner(String s) {
        System.out.println("\n== " + s);
    }

    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
    }

    static String src(String root, String rel) throws IOException {
        return read(root + File.separator + rel.replace('/', File.separatorChar));
    }

    /**
     * 源码里有没有**会被打印出去**的一行同时含 needle —— 即 `L("...")` 输出行。
     * 不能直接用 contains：注释里为了讲清"上一版错在哪"必须引用旧文案（见 SiteDoctor 里那段）。
     */
    static boolean printedLine(String srcText, String needle) {
        for (String line : srcText.split("\n")) {
            if (line.contains("L(\"") && line.contains(needle)) return true;
        }
        return false;
    }

    /** 分类名的判据（与适配器内联的那份一致；普查只负责数，不判语义） */
    static final Function1<String, Boolean> KEEP_NAME = new Function1<String, Boolean>() {
        @Override
        public Boolean invoke(String n) {
            return n != null && !n.isEmpty() && n.length() <= 10;
        }
    };

    public static void main(String[] a) throws Exception {
        String here = a.length > 0 ? a[0] : ".";
        String proj = a.length > 1 ? a[1] : ".";

        String home = read(here + "/_shell/yg_home.html");
        String searchOk = read(here + "/_shell/yg_search_ok.html");
        String stub = read(here + "/_shell/yg_search_stub.html");

        // ---------------------------------------------------------------- A 软 404 判据
        banner("A. SoftMiss —— 软 404 / 首页回退（纯函数判据，全离线）");

        SoftMiss.Sig sHome = SoftMiss.INSTANCE.sigOf(home);
        SoftMiss.Sig sOk = SoftMiss.INSTANCE.sigOf(searchOk);
        SoftMiss.Sig sStub = SoftMiss.INSTANCE.sigOf(stub);
        note("首页 " + sHome.getLen() + " B / 唯一链接 " + sHome.getLinks() + " / 链接集 " + sHome.getLinkHash());
        note("软404页 " + sOk.getLen() + " B / 唯一链接 " + sOk.getLinks() + " / 链接集 " + sOk.getLinkHash());
        note("空壳页 " + sStub.getLen() + " B / 唯一链接 " + sStub.getLinks() + " / 链接集 " + sStub.getLinkHash());

        ok("A1 夹具本身成立：软 404 那页与首页**逐字节相同**（长度一致）",
                sHome.getLen() == sOk.getLen(), sHome.getLen() + " vs " + sOk.getLen());
        ok("A2 夹具本身成立：链接集合哈希也一致（且非空）",
                sHome.getLinkHash().equals(sOk.getLinkHash()) && !sHome.getLinkHash().isEmpty(),
                sHome.getLinkHash() + " vs " + sOk.getLinkHash());

        ok("A3 守卫认出软 404（`/?s=` 那页被判为首页副本）",
                SoftMiss.INSTANCE.whyCopyOf(home, searchOk) != null,
                String.valueOf(SoftMiss.INSTANCE.whyCopyOf(home, searchOk)));
        ok("A4 自反：首页对自己的副本判定成立",
                SoftMiss.INSTANCE.isCopyOf(home, home), "false");
        ok("A5 **不误杀**：JS 空壳页不是首页副本",
                SoftMiss.INSTANCE.whyCopyOf(home, stub) == null,
                String.valueOf(SoftMiss.INSTANCE.whyCopyOf(home, stub)));
        ok("A6 近乎等同档生效（长度差 <1% ⇒ 仍判副本）",
                SoftMiss.INSTANCE.isCopyOf(home, home + "<!--x-->"), "false");
        ok("A7 反例：**多一条链接就不再算副本**（链接集合是有效判据）",
                !SoftMiss.INSTANCE.isCopyOf(home, home + "<a href=\"/zzzq\">z</a>"), "被判成副本了");
        ok("A8 空输入一律不判（拿不到参照物 ⇒ 放行）",
                SoftMiss.INSTANCE.whyCopyOf("", home) == null &&
                        SoftMiss.INSTANCE.whyCopyOf(home, "") == null, "有空输入被误判");
        ok("A9 isCopyOf 与 whyCopyOf 结论一致",
                SoftMiss.INSTANCE.isCopyOf(home, searchOk) ==
                        (SoftMiss.INSTANCE.whyCopyOf(home, searchOk) != null), "不一致");
        ok("A10 空壳页的唯一链接数远小于首页（结构确实不同，不是巧合）",
                sStub.getLinks() * 4 < sHome.getLinks(),
                sStub.getLinks() + " / " + sHome.getLinks());

        // A11~A14：F5 的**独立 oracle**（`expectHomeCopy`）必须在**离线**就可断言 ——
        // 否则它只在真网络下被验，凡连不上该站的路径都会整段 SKIP（本机实测就是 SKIP）。
        // 夹具用 v1.0.72 那次真机 CI 的实测形状：首页 229556 B / 软404候选 229555 B（**差 1 字节**）
        // / 链接集哈希一致 ⇒ 掉进 tier ②。老 F5 就是在这条形状上假红（E35 / §4.72）。
        SoftMiss.Sig liveHomeShape = new SoftMiss.Sig(229556, "野果短剧", 199, "ef59b85b68b23177");
        SoftMiss.Sig liveSoftShape = new SoftMiss.Sig(229555, "野果短剧", 199, "ef59b85b68b23177");
        note("live 形状夹具：首页 " + liveHomeShape.getLen() + " B / 候选 "
                + liveSoftShape.getLen() + " B / 链接集 " + liveSoftShape.getLinkHash());
        ok("A11 oracle 两档：差 1 字节 + 标题/链接数/链接集一致 ⇒ 判副本（tier ②）",
                expectHomeCopy(liveHomeShape, liveSoftShape), "false");
        ok("A12 oracle 两档：完全相同的页 ⇒ 判副本（tier ①）",
                expectHomeCopy(liveHomeShape, liveHomeShape), "false");
        ok("A13 oracle **不恒真**：链接集合变了（多一条）⇒ 判「不是副本」",
                !expectHomeCopy(liveHomeShape,
                        new SoftMiss.Sig(229600, "野果短剧", 200, "ffffffffffffffff")),
                "恒真了 ⇒ F5 退化成空断言");
        ok("A14 oracle 与守卫在**离线夹具**上同结论（1 字节漂移这条真实形状）",
                expectHomeCopy(sHome, SoftMiss.INSTANCE.sigOf(home + "x"))
                        == SoftMiss.INSTANCE.isCopyOf(home, home + "x"), "不一致");

        // ---------------------------------------------------------------- B 形状普查
        banner("B. 形状普查 —— 用真实首页验证「数量分得开」这条判据");

        org.jsoup.nodes.Document doc = Jsoup.parse(home, "https://agenda.fzchosdi.cc/");
        List<HtmlTemplates.ShapeHit> hits = HtmlTemplates.INSTANCE.shapeCensus(doc, KEEP_NAME);
        note("普查出 " + hits.size() + " 种形状，前 5 条：");
        for (int i = 0; i < Math.min(5, hits.size()); i++) {
            HtmlTemplates.ShapeHit h = hits.get(i);
            note("  " + h.getTpl() + "  · 命中 " + h.getLinks() + " · 别名 " + h.getAliases()
                    + " · 名字 " + h.getNames() + "  样例=" + h.getSample());
        }

        HtmlTemplates.ShapeHit tag = null, explore = null;
        for (HtmlTemplates.ShapeHit h : hits) {
            if ("/tag/{slug}/".equals(h.getTpl())) tag = h;
            if ("/explore/{slug}/".equals(h.getTpl())) explore = h;
        }
        ok("B1 普查命中真分类形状 /tag/{slug}/", tag != null, "没找到");
        ok("B2 真分类形状的**不同别名** ≥ 2（这是分界线）",
                tag != null && tag.getAliases() >= 2, tag == null ? "-" : String.valueOf(tag.getAliases()));
        ok("B3 真分类形状的**不同名字** ≥ 2",
                tag != null && tag.getNames() >= 2, tag == null ? "-" : String.valueOf(tag.getNames()));
        ok("B4 导航项 /explore/{slug}/ 只有 **1** 个别名 ⇒ 会被 ≥2 那道门挡下",
                explore == null || explore.getAliases() == 1,
                explore == null ? "(本次没出现)" : String.valueOf(explore.getAliases()));
        ok("B5 排序按「不同名字数」降序（最像分类的排最前）",
                hits.isEmpty() || isSortedByNames(hits), "排序不对");

        HtmlAdapter probe = new HtmlAdapter(site("https://agenda.fzchosdi.cc"));
        boolean allAccepted = true;
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < Math.min(5, hits.size()); i++) {
            String tpl = hits.get(i).getTpl();
            if (probe.countCatTplHits(home, tpl) < 1) {
                allAccepted = false;
                bad.append(tpl).append(' ');
            }
        }
        ok("B6 **判据只有一份**：普查提议的每个形状，运行时收集器都认（前 5 条）",
                allAccepted, "认不出: " + bad);

        ok("B7 真分类形状经运行时收集器能得到 ≥2 个分类",
                probe.countCatTplHits(home, "/tag/{slug}/") >= 2,
                String.valueOf(probe.countCatTplHits(home, "/tag/{slug}/")));
        ok("B8 导航项形状经运行时收集器**只有 1 个** ⇒ 这就是它永远不可能生效的原因",
                probe.countCatTplHits(home, "/explore/{slug}/") == 1,
                String.valueOf(probe.countCatTplHits(home, "/explore/{slug}/")));

        // ---------------------------------------------------------------- C 免校准分类
        banner("C. 免校准分类 —— 合成一个「新站」页面，端到端跑 categoriesFrom");

        String newSite =
                "<html><head><title>某新站</title></head><body>"
                        + "<div class='hero'><a href='/movie/1.html'><img src='/a.jpg'>热映</a></div>"
                        + "<div id='tags'>"
                        + "<a href='/category/action.html'>动作</a>"
                        + "<a href='/category/comedy.html'>喜剧</a>"
                        + "<a href='/category/scifi.html'>科幻</a>"
                        + "</div>"
                        + "<a href='/explore/drama/'>探索分类</a>"
                        + "</body></html>";

        HtmlAdapter a1 = new HtmlAdapter(site("https://brand-new-site.example"));
        List<com.videoshell.data.model.Category> cats1 =
                a1.categoriesFrom(Jsoup.parse(newSite, "https://brand-new-site.example/"));

        ok("C1 新站（`.html` 别名目录、不在任何导航容器里）**免校准**拿到了 3 个分类",
                cats1.size() == 3, "实际 " + cats1.size());
        StringBuilder names = new StringBuilder();
        for (com.videoshell.data.model.Category c : cats1) names.append(c.getName()).append(',');
        ok("C2 分类名正确（动作/喜剧/科幻）",
                names.toString().contains("动作") && names.toString().contains("喜剧")
                        && names.toString().contains("科幻"), names.toString());
        ok("C3 诊断说明分类栏来自**形状普查**（用户能看到是谁收的）",
                a1.getCalibDiag().contains("形状普查"), a1.getCalibDiag());

        String navOnly =
                "<html><head><title>x</title></head><body>"
                        + "<a href='/explore/drama/'>探索分类</a>"
                        + "<a href='/rank/drama/'>排行榜</a>"
                        + "</body></html>";
        HtmlAdapter a2 = new HtmlAdapter(site("https://brand-new-site2.example"));
        List<com.videoshell.data.model.Category> cats2 =
                a2.categoriesFrom(Jsoup.parse(navOnly, "https://brand-new-site2.example/"));
        List<HtmlTemplates.ShapeHit> hits2 = HtmlTemplates.INSTANCE.shapeCensus(
                Jsoup.parse(navOnly, "https://brand-new-site2.example/"), KEEP_NAME);
        boolean allSingle = true;
        for (HtmlTemplates.ShapeHit h : hits2) if (h.getAliases() >= 2) allSingle = false;

        ok("C4 **负控**：全是「单个别名」的导航项 ⇒ 一个分类都不收（不靠站点白名单）",
                cats2.size() < 2, "实际 " + cats2.size());
        ok("C5 负控页里每种形状的别名数都是 1（证明 C4 是被数量挡下的）",
                allSingle, "出现了别名 ≥2 的形状");

        // ---------------------------------------------------------------- D ImageCipher
        banner("D. ImageCipher 未收录站探针 —— 源码守卫");

        String imgc = src(proj, "app/src/main/java/com/videoshell/data/net/ImageCipher.kt");
        ok("D1 未收录分支改走探针（不再无条件 proceed 后返回）",
                imgc.contains("probeUnknownBed(url, resp)"), "没接上");
        ok("D2 三道门控齐备（路径 / 206 / octet-stream）",
                imgc.contains("looksLikeImagePath") && imgc.contains("resp.code == 206")
                        && imgc.contains("octet-stream"), "门控缺项");
        ok("D3 读体后必须原样还回去（否则 Coil 拿到空体）",
                imgc.contains("rebuild(resp, raw, ct"), "没还回去");
        ok("D4 认得出图片魔数就不登记（普通图片零影响）",
                imgc.contains("AesCipher.isImage(raw)"), "缺判据");
        ok("D5 **绝不往响应头塞中文**（否则每张封面都抛 Unexpected char）",
                !imgc.contains("X-VideoShell-Image") && !imgc.contains("header(\"X-"),
                "又出现了自定义响应头");
        ok("D6 疑似图床登记表存在并对外可读（自检要展示它）",
                imgc.contains("val suspected") && imgc.contains("fun suspectedBed"), "缺接口");

        String doctor = src(proj, "app/src/main/java/com/videoshell/data/site/SiteDoctor.kt");
        ok("D7 自检报告引用疑似图床登记",
                doctor.contains("suspectedBed"), "没引用");

        // ---------------------------------------------------------------- E 自检报告
        banner("E. 自检报告 —— [2b] 新增与 [3a] 改写");

        ok("E1 新增 [2b] 搜索路由自证（对照组）",
                printedLine(doctor, "[2b] 搜索路由自证（对照组）"), "没打印");
        ok("E2 [2b] 会打印两个关键词的实测与结论",
                printedLine(doctor, "真关键词") && printedLine(doctor, "对照词"), "缺行");
        ok("E3 [2b] 判据是「两次响应是否同一个页面」而不是「数结果条数」",
                doctor.contains("两次响应**完全一致**") && doctor.contains("忽略关键词"),
                "判据没写对");
        ok("E4 [3a] 未收录站改成**整段取图走 App 真实链路**",
                printedLine(doctor, "整段取图（App 真实链路）"), "没打印");
        ok("E5 [3a] 旧的 Range 取图那行不再打印（206 分片看不出密文魔数）",
                !printedLine(doctor, "带 Referer 请求"), "旧行还在");
        ok("E6 [3a] 解读里点名「疑似加密图床 ⇒ 本站未收录密钥」",
                printedLine(doctor, "疑似加密图床"), "没写");

        // ---------------------------------------------------------------- F 实时端到端
        banner("F. 实时端到端 —— 真正的对照组（真网络）");

        String base = "https://agenda.fzchosdi.cc";
        String liveHome = null, liveSoft = null, liveStub = null, liveCat = null;
        try {
            liveHome = fetch(base + "/");
            liveSoft = fetch(base + "/?s=zzq9xk3");
            liveStub = fetch(base + "/search/drama/zzq9xk3/");
            liveCat = fetch(base + "/explore/drama/");
        } catch (Throwable t) {
            note("网络不可用: " + t);
        }

        if (liveHome == null || liveSoft == null || liveStub == null || liveCat == null) {
            skip++;
            System.out.println("[SKIP] F 段：本次网络抓取失败，跳过实时断言（离线 A~E 已覆盖判据本身）");
        } else {
            SoftMiss.Sig lh = SoftMiss.INSTANCE.sigOf(liveHome);
            SoftMiss.Sig ls = SoftMiss.INSTANCE.sigOf(liveSoft);
            note("首页 " + lh.getLen() + " B / 链接集 " + lh.getLinkHash());
            note("软404候选 " + ls.getLen() + " B / 链接集 " + ls.getLinkHash());

            ok("F1 首页抓到了（规模正常）", lh.getLen() > 100000, String.valueOf(lh.getLen()));
            ok("F2 **不误杀**：真分类频道页 /explore/drama/ 不是首页副本",
                    !SoftMiss.INSTANCE.isCopyOf(liveHome, liveCat), "被误判成副本");
            ok("F3 **不误杀**：JS 空壳搜索页不是首页副本",
                    !SoftMiss.INSTANCE.isCopyOf(liveHome, liveStub), "被误判成副本");
            ok("F4 两个不同的搜索地址确实是两个不同页面（判据有分辨力）",
                    !SoftMiss.INSTANCE.sigOf(liveStub).getLinkHash()
                            .equals(ls.getLinkHash())
                            || SoftMiss.INSTANCE.sigOf(liveStub).getLen() != ls.getLen(),
                    "两份响应一模一样");
            // F5 的 oracle 必须与 `whyCopyOf` 的**文档定义**同档位。
            //
            // ⚠️ 老版本的 F5 拿「只有 tier ①」的 `byteIdentical` 当 oracle，而守卫刻意有**两档**
            //    （②「标题相同 + 唯一链接数相同 + 长度差 <1%」专门兜随机广告位/时间戳）。
            //    站点后来在 `/?s=` 上差 1 个字节（广告位/时间戳），落进 tier ② ⇒ 守卫判"是副本"
            //    **完全正确**，老 oracle 却按 tier ① 判"不是" ⇒ 假红（E35 / §4.72）。
            //    所以这里**独立重算两档** —— 故意**不**调 `isCopyOf`，否则就是循环论证（拿实现验实现，
            //    实现一旦错就一起错，断言恒真）。
            SoftMiss.Sig lsStub = SoftMiss.INSTANCE.sigOf(liveStub);
            boolean guardVerdict = SoftMiss.INSTANCE.isCopyOf(liveHome, liveSoft);
            boolean expectCopy = expectHomeCopy(lh, ls);
            note("对照组实测：`/?s=<不可能的词>` 与首页逐字节相同? "
                    + (lh.getLen() == ls.getLen() && lh.getLinkHash().equals(ls.getLinkHash()))
                    + " / 守卫结论: " + SoftMiss.INSTANCE.whyCopyOf(liveHome, liveSoft));
            ok("F5 守卫与文档判据一致（两档独立重算，不调 isCopyOf）",
                    guardVerdict == expectCopy,
                    "守卫=" + guardVerdict + " 独立重算=" + expectCopy);
            // 负控：这份独立 oracle 不能恒真 —— 一份明显不同的页（JS 空壳）必须被判「不是副本」。
            // 没有它，"guardVerdict == expectCopy" 有可能是两边同时恒 true 的空断言。
            ok("F5b 负控：独立 oracle 对明显不同的页判「不是副本」（防恒真）",
                    !expectHomeCopy(lh, lsStub),
                    "对 JS 空壳页也判成了副本 ⇒ oracle 恒真，F5 失去意义");
        }

        System.out.println("\n================ Universal: " + pass + " PASS / " + fail + " FAIL"
                + (skip > 0 ? " / " + skip + " SKIP" : "") + " ================");
        if (fail > 0) System.exit(1);
    }

    /**
     * F5 的**独立 oracle**：按 [SoftMiss] KDoc 里写死的**两档判据**重算一遍
     * 「给定首页身份，这份页算不算它的副本」。
     *
     * 刻意**不复用** `SoftMiss.whyCopyOf` —— 那是拿实现验实现，断言会退化成恒真；
     * 这里的价值正是「文档说的两档」与「代码实现的两档」是否一致（E35 / §4.72）。
     */
    static boolean expectHomeCopy(SoftMiss.Sig home, SoftMiss.Sig now) {
        // ① 字节级等同：长度一致 + 链接集合哈希一致
        if (!home.getLinkHash().isEmpty() && home.getLen() == now.getLen()
                && home.getLinkHash().equals(now.getLinkHash())) {
            return true;
        }
        // ② 近乎等同：标题一致 + 唯一链接数一致 + 长度差 <1%（下限 64）
        if (!home.getTitle().isEmpty() && home.getTitle().equals(now.getTitle())
                && home.getLinks() == now.getLinks()
                && Math.abs(home.getLen() - now.getLen()) <= Math.max(home.getLen() / 100, 64)) {
            return true;
        }
        return false;
    }

    static boolean isSortedByNames(List<HtmlTemplates.ShapeHit> hits) {
        for (int i = 1; i < hits.size(); i++) {
            if (hits.get(i - 1).getNames() < hits.get(i).getNames()) return false;
        }
        return true;
    }

    static SiteConfig site(String base) {
        return new SiteConfig("k", "测试站", base, "", SiteConfig.MODE_HTML, "", "", 0L);
    }

    /** 直接走应用自己的 OkHttp（带 UA），不经过协程封装 —— 离线 harness 里最省事的真实请求 */
    static String fetch(String url) throws IOException {
        OkHttpClient client = Http.INSTANCE.getClient();
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .build();
        Response resp = client.newCall(req).execute();
        try {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code());
            }
            return resp.body().string();
        } finally {
            resp.close();
        }
    }
}
