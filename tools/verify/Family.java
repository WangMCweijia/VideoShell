import com.videoshell.data.model.*;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.nio.file.*;
import java.util.*;

/**
 * v1.0.35「三笔债 + 野果搜索」的断言。
 *
 * A 密钥自动发现（纯函数，用真实 bundle 做夹具）
 * B API 基址候选（域名无关的入口）
 * C 配方默认值与握手常量
 * D 选型 / 接线 / 固化的源码守卫（含**顺序**守卫）
 * E 端到端（真网络）：家族自证 → 用自证出来的配方真搜一次 + 对照组
 */
public class Family {

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


    /** 读工程源码；找不到返回空串（守卫会因此 FAIL —— 这正是我们要的） */
    static String src(String proj, String rel) {
        String t = read(Paths.get(proj, rel.replace('/', java.io.File.separatorChar)).toString());
        return t == null ? "" : t;
    }

    public static void main(String[] args) throws Exception {
        String HERE = args.length > 0 ? args[0] : ".";
        String PROJ = args.length > 1 ? args[1] : ".";

        // ------------------------------------------------------------------ A
        banner("A. 密钥自动发现 CryptDiscovery.parseConfig（真实 bundle 夹具）");
        String bundle = read(Paths.get(HERE, "_shell", "yg_config.js").toString());
        ok("A0 夹具 _shell/yg_config.js 存在（真实打包产物）", bundle != null && bundle.length() > 200,
                bundle == null ? "缺失" : bundle.length() + " B");
        if (bundle != null) {
            List<CryptDiscovery.Config> cs = CryptDiscovery.INSTANCE.parseConfig(bundle);
            ok("A1 真实 bundle 挖出恰好 1 组候选", cs.size() == 1, "size=" + cs.size());
            if (!cs.isEmpty()) {
                CryptDiscovery.Config c = cs.get(0);
                eq("A2 key 正确（接口密钥）", c.getKey(), "2acf7e91e9864673");
                eq("A3 iv 正确", c.getIv(), "1c29882d3ddfcfd6");
                eq("A4 sign_key 正确", c.getSignKey(), "5589d41f92a597d016b037ac37db243d");
                eq("A5 media_key 正确（图床密钥，与接口那组不同）", c.getMediaKey(), "f5d965df75336270");
                eq("A6 media_iv 正确", c.getMediaIv(), "97b60394abc2fbe1");
            }
        }

        // 合成用例：不依赖压缩器具体写法
        String plain = "var cfg = {mode: 'CBC', key: \"2acf7e91e9864673\", iv: \"1c29882d3ddfcfd6\"};";
        ok("A7 双引号 + 无包装函数也能挖到",
                CryptDiscovery.INSTANCE.parseConfig(plain).size() == 1, "size!=1");

        String sameWin = "x={key:`2acf7e91e9864673`,sign_key:`5589d41f92a597d016b037ac37db243d`,"
                + "media_key:`f5d965df75336270`,media_iv:`97b60394abc2fbe1`,iv:`1c29882d3ddfcfd6`}";
        List<CryptDiscovery.Config> sw = CryptDiscovery.INSTANCE.parseConfig(sameWin);
        ok("A8 反引号（压缩器形态）也能挖到且字段齐全",
                sw.size() == 1 && sw.get(0).getSignKey() != null
                        && sw.get(0).getMediaKey() != null && sw.get(0).getMediaIv() != null,
                "size=" + sw.size());

        String noIv = "var a={key:`abcdefghijklmnop`,version:`1.3.2`,bundleId:`com.pwa.mater`};var b={name:`nope`};";
        ok("A9 有 key 但没有 iv ⇒ 不成组（噪声页挖不出东西）",
                CryptDiscovery.INSTANCE.parseConfig(noIv).isEmpty(), "不该有候选");

        String shortKey = "x={key:`tooShort1234567`,iv:`1c29882d3ddfcfd6`}";
        ok("A10 键长不是 16/24/32 的直接丢掉（主要降噪手段）",
                CryptDiscovery.INSTANCE.parseConfig(shortKey).isEmpty(), "15 字符不该入选");

        StringBuilder far = new StringBuilder("x={key:`2acf7e91e9864673`,");
        for (int i = 0; i < 150; i++) far.append("pad").append(i).append(":1,");
        far.append("iv:`1c29882d3ddfcfd6`}");
        ok("A11 超出窗口不配对（key 与 iv 离太远不算同组）",
                CryptDiscovery.INSTANCE.parseConfig(far.toString()).isEmpty(),
                "len=" + far.length());

        String half = "x={key:`2acf7e91e9864673`,iv:`1c29882d3ddfcfd6`,media_key:`f5d965df75336270`}";
        List<CryptDiscovery.Config> h = CryptDiscovery.INSTANCE.parseConfig(half);
        ok("A12 图床密钥半对（有 key 无 iv）⇒ 整组丢弃，不半用",
                h.size() == 1 && h.get(0).getMediaKey() == null && h.get(0).getMediaIv() == null,
                "size=" + h.size());

        ok("A13 首页 JS 只收同源（CDN/统计脚本不抓）",
                CryptDiscovery.INSTANCE.jsUrlsOf(
                        "<script src=\"/_nuxt/a.js\"></script>"
                                + "<link rel=modulepreload href=\"/_nuxt/b.js\">"
                                + "<script src=\"https://www.googletagmanager.com/gtag/js?id=x\"></script>",
                        "https://a.cc").size() == 2,
                "应为 2");

        // ------------------------------------------------------------------ B
        banner("B. API 基址候选（域名无关的入口）");
        List<String> bases = CryptRecipes.INSTANCE.apiBasesFor("https://agenda.fzchosdi.cc/");
        ok("B1 第一位是**站点自己的域名**（站方反代了 api.php）",
                !bases.isEmpty() && bases.get(0).equals("https://agenda.fzchosdi.cc/api.php"),
                String.valueOf(bases));
        // 曾经这里断言的是「B2 官方线路仍在候选里（反代失效时的退路）」——
        // 那条断言**把 bug 当成了规格**。实测（_yg_family_probe.py）官方线至今仍解
        // 得开，于是任何无关站都会"自证命中"、被路由到野果适配器 ⇒ 输入 A 站看到野果内容。
        // 现在的规格是：自证候选只能来自本站 origin。
        boolean onlySelf = true;
        for (String b : bases) {
            if (!b.startsWith("https://agenda.fzchosdi.cc/")) onlySelf = false;
        }
        ok("B2 自证候选**只含本站自己的域名**（别人解得开不算血缘证据）",
                onlySelf, String.valueOf(bases));
        ok("B3 去重（同一基址不出现两次）", new HashSet<>(bases).size() == bases.size(), "有重复");
        ok("B4 传带路径的 URL 也能取到 origin",
                CryptRecipes.INSTANCE.apiBasesFor("https://a.cc/x/y/z").get(0).equals("https://a.cc/api.php"),
                CryptRecipes.INSTANCE.apiBasesFor("https://a.cc/x/y/z").get(0));
        ok("B5 不带协议的入参返回空候选（不去瞎试）",
                CryptRecipes.INSTANCE.apiBasesFor("a.cc/x").isEmpty(),
                String.valueOf(CryptRecipes.INSTANCE.apiBasesFor("a.cc/x")));

        // ------------------------------------------------------------------ C
        banner("C. 配方默认值与握手常量");
        CryptRecipe tpl = CryptRecipes.INSTANCE.template();
        eq("C1 探测路径默认 /api/home/config", tpl.getProbePath(), "/api/home/config");
        ok("C2 模板自身不带握手参数（由 probe 显式注入，避免隐式生效）",
                tpl.getBaseParams().isEmpty(), "不该有");
        ok("C3 HANDSHAKE 含 bundleId 与 oauth_id",
                "com.pwa.mater".equals(CryptRecipes.INSTANCE.getHANDSHAKE().get("bundleId"))
                        && CryptRecipes.INSTANCE.getHANDSHAKE().get("oauth_id") != null,
                String.valueOf(CryptRecipes.INSTANCE.getHANDSHAKE()));
        ok("C4 图床能力仍在（本版没动它）",
                tpl.getHasMedia() && CryptRecipes.INSTANCE.mediaHostsOfAll().contains("ndhixj.cn"),
                "mediaHosts=" + CryptRecipes.INSTANCE.mediaHostsOfAll());
        ok("C5 域名白名单仍然存在（零成本快路径没被拿掉）",
                CryptRecipes.INSTANCE.forUrl("https://www.yeguodj.com/") != null, "白名单失效");

        // ------------------------------------------------------------------ D
        banner("D. 选型 / 接线 / 固化的源码守卫");
        String af = src(PROJ, "app/src/main/java/com/videoshell/data/site/AdapterFactory.kt");
        String fr = src(PROJ, "app/src/main/java/com/videoshell/data/site/FamilyRouter.kt");
        String hd = src(PROJ, "app/src/main/java/com/videoshell/data/site/HtmlAdapter.kt");
        String sd = src(PROJ, "app/src/main/java/com/videoshell/data/site/SiteDoctor.kt");
        String sb = src(PROJ, "app/src/main/java/com/videoshell/ui/SiteBrowser.kt");
        String ya = src(PROJ, "app/src/main/java/com/videoshell/data/site/YeguoAdapter.kt");
        String sr = src(PROJ, "app/src/main/java/com/videoshell/data/site/SiteRecipe.kt");
        String cf = src(PROJ, "app/src/main/java/com/videoshell/data/site/CryptFamily.kt");
        String srr = src(PROJ, "app/src/main/java/com/videoshell/data/site/SeedRouter.kt");

        ok("D1 AdapterFactory 读家族三态缓存（命中直接上接口）",
                af.contains("CryptFamily.cachedState") && af.contains("CryptFamily.State.Hit"),
                "缺少缓存分支");
        // v1.0.53：最外层多了一层 SeedRouter（先判签名种子配置族）。D2 的**本意**
        // （"未知域名要被套上延迟家族路由，最终能走到 FamilyRouter"）不变，
        // 但一个人横跨两个文件 —— 工厂负责套、种子路由负责把没命中的交回 FamilyRouter。
        ok("D2 AdapterFactory 未知域名套延迟路由，且它仍落到 FamilyRouter（跨文件）",
                af.contains("SeedRouter(site)") && srr.contains("FamilyRouter(site)"),
                "没接上（工厂缺少 SeedRouter，或种子路由没有交回 FamilyRouter）");
        ok("D3 顺序守卫：采集接口分支在**最外层路由**之前（接口站不该被套路由）",
                af.indexOf("site.apiUrl.isNotBlank()") > 0
                        && af.indexOf("site.apiUrl.isNotBlank()") < af.indexOf("SeedRouter(site)"),
                "顺序不对");
        // ⚠️ 这条守卫**两次**被"位置"坑到：
        //    ① v1.0.35 查字面量 `html else FamilyRouter(site)`，内联一次就误报；
        //    ② v1.0.53 加种子族时，「Absent ⇒ 不再套 FamilyRouter」整条规矩被搬进了
        //       SeedRouter 的落点，于是"在 AdapterFactory 里找同行/下一行的 HtmlAdapter"找不到人了
        //       —— 而那正是它该做的事：**守卫红了，说明规则真的被搬走了，得跟着规则走**。
        //    判据改成跨文件判**语义**：工厂把 Absent 这个判断传下去，路由据此落到 HtmlAdapter
        //    （要求写成 `HtmlAdapter(site)` 这种**调用形态**，免得注释里提一句就被算过）。
        boolean factoryFeedsAbsent = false;
        for (String l : af.split("\n")) {
            if (l.contains("CryptFamily.State.Absent") && l.contains("familyAbsent")) {
                factoryFeedsAbsent = true; break;
            }
        }
        boolean absentToHtml = false;
        String absentWhere = "";
        for (String l : srr.split("\n")) {
            if (l.contains("familyAbsent") && l.contains("HtmlAdapter(site)")) {
                absentToHtml = true; absentWhere = l.trim(); break;
            }
        }
        ok("D4 已判定「不是本族」的域名不再套 FamilyRouter（零成本）",
                factoryFeedsAbsent && absentToHtml && srr.contains("FamilyRouter(site)"),
                "缺少否定分支（工厂把 Absent 传下去=" + factoryFeedsAbsent
                        + "，种子路由按它落 HtmlAdapter=" + absentToHtml + "）：" + absentWhere);

        String[] deleg = {"categories", "browse", "search", "detail", "resolve", "lastDiag",
                "calibDiag", "calibApplied", "supportsWebRender", "countCatTplHits",
                "parseListFromHtml", "parseDetailFromHtml", "searchUrlFor", "browseUrlFor", "detailUrlFor"};
        StringBuilder missing = new StringBuilder();
        for (String d : deleg) if (!fr.contains(d)) missing.append(d).append(' ');
        ok("D5 FamilyRouter 覆盖了全部 15 个契约成员（少一个就是静默失效）",
                missing.length() == 0 && fr.contains("override"), "缺：" + missing);
        ok("D6 FamilyRouter 命中后交给 YeguoAdapter（接口路径）",
                fr.contains("YeguoAdapter(site, hit)"), "没接上");
        ok("D7 FamilyRouter 在判定前 supportsWebRender 仍为 true（预渲染兜底不能被挡）",
                fr.contains("resolved?.supportsWebRender ?: true"), "兜底被挡掉了");
        ok("D8 FamilyRouter 判定只跑一次（有 resolved 缓存 + 并发锁）",
                fr.contains("resolved?.let { return it }") && fr.contains("lock.withLock"),
                "缺缓存或锁");
        ok("D9 FamilyRouter 写明了「为什么不能同步探」（同步探 = 主线程等网络）",
                fr.contains("同步") && fr.contains("UI 线程"), "缺理由");

        ok("D10 CryptFamily 三态齐备（Unknown / Absent / Hit）",
                cf.contains("object Unknown") && cf.contains("object Absent") && cf.contains("class Hit"),
                "三态不全");
        ok("D11 CryptFamily 否定结论也落盘（否则每次冷启动都重探）",
                cf.contains("save(h, Rec(absent = true))"), "没落盘");
        ok("D12 CryptFamily 解不开时才去挖密钥（正常站零额外抓取）",
                cf.contains("CryptDiscovery.fromSite"), "没接上");
        ok("D13 CryptFamily 落盘用独立 SP（不污染站点配方的 isEmpty / calibAt 语义）",
                cf.contains("videoshell_crypt_family"), "混进配方了");

        ok("D14 自检新增 [2c] 数据来源 / 搜索降级链",
                sd.contains("[2c] 数据来源 / 搜索降级链"), "没有这一段");
        ok("D15 自检 [2a] 认得「家族自证命中」（否则会误报「不是加密站」）",
                sd.contains("域名白名单") && sd.contains("家族自证"), "漏判");
        ok("D16 「站点配方重置」同时忘掉血缘判定（否则重置完立刻又被路由回接口）",
                sb.contains("CryptFamily.forget"), "没接上");

        ok("D17 形状普查固化写盘（第三笔债）",
                hd.contains("learnedCatTpl = tpl") && hd.contains("learnedCatAt = System.currentTimeMillis()"),
                "没写盘");
        ok("D18 顺序守卫：**现场普查排在固化值前面**（活证据优先，站点改版能立刻自愈）",
                hd.indexOf("censusTries += fresh") > 0
                        && hd.indexOf("censusTries += fresh") < hd.indexOf("censusTries += it"),
                "固化值跑到了前面");
        ok("D19 配方里能看到自动普查形状（不与人工校准混淆）",
                sr.contains("learnedCatTpl") && sr.contains("自动普查："), "缺字段或展示");
        ok("D20 isEmpty 认得 learnedCatTpl（否则只学到普查形状的站会显示成没有配方）",
                sr.contains("learnedCatTpl == null"), "漏了");

        ok("D21 YeguoAdapter 发请求时带上配方握手参数（验过的姿势 == 跑的姿势）",
                ya.contains("recipe.baseParams + params"), "姿态不一致");

        // ------------------------------------------------------------------ E
        banner("E. 端到端（真网络）：自证 → 用自证出的配方真搜一次 + 对照组");
        CryptRecipe hit = null;
        try {
            hit = block((s, c) -> CryptFamily.INSTANCE.resolve(YG, YG, (Continuation<? super CryptRecipe>) c));
        } catch (Throwable t) {
            System.out.println("  [SKIP] 自证请求异常：" + t);
        }
        if (hit == null) {
            System.out.println("  [SKIP] E 段（需要能访问 " + YG + "）");
        } else {
            ok("E1 家族自证命中（我们的密钥解得开它的响应）", true, "");
            final CryptRecipe rec = hit;          // lambda 里要用，必须 effectively final
            ok("E2 自证出的 apiBase 指向**站点自身域名**（换域名自动跟上的关键）",
                    rec.getApiBase().startsWith(YG), rec.getApiBase());
            System.out.println("       apiBase  = " + rec.getApiBase());
            System.out.println("       自证过程 = " + CryptApi.INSTANCE.getLastProbeNote());

            String note = CryptFamily.INSTANCE.describe(YG);
            ok("E3 三态缓存已落成「命中」", note.contains("自证命中"), note);
            CryptRecipe again = block((s, c) -> CryptFamily.INSTANCE.resolve(YG, YG,
                    (Continuation<? super CryptRecipe>) c));
            ok("E4 内存缓存生效：二次 resolve 返回同一 apiBase（不再探测）",
                    again != null && again.getApiBase().equals(rec.getApiBase()), "不一致");

            Map<String, String> p1 = new HashMap<>();
            p1.put("keyword", "庆余年");
            p1.put("page", "1");
            com.google.gson.JsonObject r1 = block((s, c) -> CryptApi.INSTANCE.call(
                    rec, "/api/search/result", p1, YG, (Continuation<? super com.google.gson.JsonObject>) c));
            List<VideoItem> items = YeguoMap.INSTANCE.searchItemsFromResp(r1);
            ok("E5 用自证出的配方真搜「庆余年」出结果（这就是修好的搜索）",
                    items.size() >= 1, "条数=" + items.size());
            if (!items.isEmpty()) System.out.println("       首条：" + items.get(0).getName());

            Map<String, String> p2 = new HashMap<>();
            p2.put("keyword", "zzzq不存在的词");
            p2.put("page", "1");
            com.google.gson.JsonObject r2 = block((s, c) -> CryptApi.INSTANCE.call(
                    rec, "/api/search/result", p2, YG, (Continuation<? super com.google.gson.JsonObject>) c));
            ok("E6 对照组：不存在的词出 0 条（能出结果不是证据，这条才是）",
                    YeguoMap.INSTANCE.searchItemsFromResp(r2).isEmpty(),
                    "条数=" + YeguoMap.INSTANCE.searchItemsFromResp(r2).size());
        }

        // ------------------------------------------------------------------
        System.out.println();
        System.out.println("========== Family: " + pass + " PASS / " + fail + " FAIL ==========");
        if (!fails.isEmpty()) {
            System.out.println("FAILED:");
            for (String f : fails) System.out.println("  · " + f);
        }
        System.exit(fail == 0 ? 0 : 1);
    }
}
