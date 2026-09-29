package tools.verify;

import com.videoshell.data.pan.BaiduShare;
import com.videoshell.data.pan.PanBaidu;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 百度网盘（P1 第一个）Provider 的**离线**守卫（无网络）。
 *
 * ## 为什么这一套只能守"纯函数的一半"
 *
 * {@code android.jar} 是桩：`new JSONObject(...)` 在 JVM 上抛 `Stub!`（PITFALLS §4.58），
 * 所以 `list` / `stream` / `verify` 里凡是碰信封的地方**离线都跑不到**。能跑的只有
 * 字符串那一层 —— 而它恰好是百度最容易错的那一层：
 *
 * | 判据 | 为什么它是纯函数 | 错了会怎样 |
 * |---|---|---|
 * | {@link PanBaidu#shareFields} | 页面是**JS 字面量**不是 JSON | 用 `JSONObject` 解必抛；漏 `share_uk` 则列不了目录 |
 * | {@link PanBaidu#shareIsDead} | 页面自己说的 `share_page_type` | 把**活着的**分享报成失效（或反过来） |
 * | {@link PanBaidu#useRoot} | 只有分享根要 `root=1` | 给子目录带上它 ⇒ 服务端静默忽略 `dir`，**每层原地打转**（§4.80） |
 * | {@link PanBaidu#errnoOf} / {@link PanBaidu#dlinkOf} | 从信封/JSON 里抠值 | 取流失败说不清是哪一步（只有"点了没反应"） |
 *
 * 另一半（"Cookie 就够 / 还要 `bdstoken`"、dlink 的 UA/Referer/Range 校验强度）
 * **必须问真网络**，用 `tools/verify/panbaidu_spike.py` + `BAIDU_COOKIE=` 复跑 ——
 * 那两条的答案会回来改 {@code PanBaidu.stream} 与 {@code mediaHeaders}，
 * 而不是改这里（见类文档"待实测的那一半"）。
 *
 * ## F 组是"接线守卫"
 *
 * 百度与夸克/UC 的形状**有三处相反**（子目录 fid 是 `path` 不是 fs_id、子目录**不能**带
 * `root=1`、分享页要**合并 jar 里的 `BDCLND`**）。这些写法编译都过、跑起来只是"点了没反应"，
 * 所以每条都得有源码级断言钉住。
 */
public class PanBaiduTest {

    static int pass = 0, fail = 0;
    static final List<String> BAD = new ArrayList<>();

    static void ok(String what, boolean cond, String detail) {
        String line = (cond ? "[PASS] " : "[FAIL] ") + what
                + (detail == null || detail.isEmpty() ? "" : "   → " + detail);
        System.out.println(line);
        if (cond) pass++;
        else { fail++; BAD.add(what); }
    }

    static String ROOT = System.getProperty("vs.root", "");

    static String readSrc(String rel) {
        try {
            return new String(Files.readAllBytes(Paths.get(ROOT, rel.split("/"))),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 剥注释（认字符串字面量；与 {@code PanMediaCookieTest.code} 同一条纪律，见 E47） */
    static String code(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        int i = 0, n = s.length();
        boolean inStr = false, inChr = false;
        while (i < n) {
            char ch = s.charAt(i);
            if (!inStr && !inChr && ch == '"' && i + 2 < n
                    && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                int j = s.indexOf("\"\"\"", i + 3);
                if (j < 0) { b.append(s, i, n); break; }
                b.append(s, i, j + 3);
                i = j + 3;
                continue;
            }
            if (inStr || inChr) {
                b.append(ch);
                if (ch == '\\' && i + 1 < n) { b.append(s.charAt(i + 1)); i += 2; continue; }
                if (inStr && ch == '"') inStr = false;
                if (inChr && ch == '\'') inChr = false;
                i++;
                continue;
            }
            if (ch == '"') { inStr = true; b.append(ch); i++; continue; }
            if (ch == '\'') { inChr = true; b.append(ch); i++; continue; }
            if (ch == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                continue;
            }
            if (ch == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
                b.append(' ');
                continue;
            }
            b.append(ch);
            i++;
        }
        return b.toString();
    }

    public static void main(String[] args) {

        // 形状取自 2026-09-26 落盘看过的那份匿名分享页（`window.yunData` 是 **JS 字面量**：
        // 键名不带引号、值用单/双引号混着写）—— 这正是"不能用 JSONObject"的原因。
        String ANON_PAGE = "<script>window.yunData={skinName:'white',shareid:\"51458864580\","
                + "share_uk:\"1234567890\",bdstoken:'',uk:'0',loginstate:'0'};</script>";

        System.out.println("-- A. shareFields：从 JS 字面量里抠结构（纯函数） --");
        BaiduShare anon = PanBaidu.shareFields(ANON_PAGE);
        ok("A0 匿名页解析得出对象", anon != null, String.valueOf(anon));
        ok("A1 shareid 抠到了（列目录必带）",
                anon != null && "51458864580".equals(anon.getShareid()),
                anon == null ? "null" : anon.getShareid());
        ok("A2 share_uk 抠到了（列目录必带）",
                anon != null && "1234567890".equals(anon.getUk()),
                anon == null ? "null" : anon.getUk());
        // ★ 匿名页的字面量里 `uk:'0'` 是**游客 id**，而 `share_uk` 才是拥有者 ——
        //   认错那个的症状是"列目录回 errno=-21/空表"，看着像"要登录"。
        ok("A3 ★ 取的是 `share_uk`，不是那个游客用的 `uk:'0'`",
                anon != null && !"0".equals(anon.getUk()), anon == null ? "null" : anon.getUk());
        ok("A4 匿名页的 bdstoken 是空串（不是 null，也不是把它整个判失败）",
                anon != null && anon.getBdstoken().isEmpty(), anon == null ? "" : anon.getBdstoken());
        ok("A5 匿名页**没有** sign（实测：sign 只出现在登录态页面上）",
                anon != null && anon.getSign().isEmpty(), anon == null ? "" : anon.getSign());
        ok("A6 匿名页没有 timestamp ⇒ 0（调用方回落到 now）",
                anon != null && anon.getTimestamp() == 0L, "");

        String FULL = "yunData={shareid:\"51458864580\",share_uk:\"1234567890\","
                + "sign:\"a1b2c3d4e5f6\",timestamp:1790252372,bdstoken:\"tok9\"}";
        BaiduShare full = PanBaidu.shareFields(FULL);
        ok("A7 登录态页面里 sign / timestamp / bdstoken 一起抠得出",
                full != null && "a1b2c3d4e5f6".equals(full.getSign())
                        && full.getTimestamp() == 1790252372L
                        && "tok9".equals(full.getBdstoken()),
                full == null ? "null"
                        : (full.getSign() + "/" + full.getTimestamp() + "/" + full.getBdstoken()));

        // ★ `\bsign\b` 这条边界：`sign1`/`sign2` 是**另一套签名材料**，不是我们要的 `sign`
        String SIGN123 = "yunData={shareid:\"51458864580\",share_uk:\"1234567890\","
                + "sign1:\"AAAA1111\",sign2:\"BBBB2222\"}";
        BaiduShare s123 = PanBaidu.shareFields(SIGN123);
        ok("A8 ★ `sign1`/`sign2` 不许被当成 `sign`（`\\b` 边界那一条）",
                s123 != null && s123.getSign().isEmpty(),
                s123 == null ? "null" : s123.getSign());

        // ★ webpack 里 `locals:["sign","servertime"]` 是"这个模块要消费哪些变量"的**元数据**，
        //   不是值 —— 后面跟的是逗号，不是 `:`/`=` ⇒ 正则不该命中（实测的假阳性来源）
        String META = "yunData={shareid:\"51458864580\",share_uk:\"1234567890\"}"
                + "m.exports={locals:[\"sign\",\"servertime\"]}";
        BaiduShare meta = PanBaidu.shareFields(META);
        ok("A9 ★ webpack 的 `[\"sign\",\"servertime\"]` 元数据不算 sign",
                meta != null && meta.getSign().isEmpty(),
                meta == null ? "null" : meta.getSign());

        ok("A10 缺 share_uk ⇒ 整条判失败（列目录至少要它俩）",
                PanBaidu.shareFields("yunData={shareid:\"51458864580\"}") == null, "");
        ok("A11 缺 shareid ⇒ 整条判失败",
                PanBaidu.shareFields("yunData={share_uk:\"1234567890\"}") == null, "");
        ok("A12 空页面不抛异常、返回 null（纯函数不许把上层拖崩）",
                PanBaidu.shareFields("") == null && PanBaidu.shareFields("<html></html>") == null, "");

        System.out.println("-- B. shareIsDead：死链只能由**页面自己说**判定 --");
        // 实测：分享失效时页面照样 200、照样有完整的 yunData（shareid/share_uk 都在），
        // 只是 share_page_type 变成 error、errno=145 —— 见 PITFALLS §4.80。
        String DEAD = "yunData={skinName:'white',shareid:\"51458864580\",share_uk:\"1234567890\","
                + "share_page_type:\"error\",errno:145};";
        ok("B1 `share_page_type:\"error\"` ⇒ 死链", PanBaidu.shareIsDead(DEAD), "");
        ok("B2 只有 `\"errno\":145` 也 ⇒ 死链（两种写法都要认）",
                PanBaidu.shareIsDead("yunData={\"errno\":145}"), "");
        ok("B3 正常页不判死链",
                !PanBaidu.shareIsDead(
                        "yunData={shareid:\"51458864580\",share_page_type:\"public\",errno:0}"), "");
        ok("B4 没有那两个字面量的页面不判死链（宁可漏判也不能把活链报死）",
                !PanBaidu.shareIsDead("yunData={shareid:\"51458864580\",share_uk:\"1\"}"), "");
        // ★ B5 是这套守卫存在的**第一个理由**：错误页上抠字段照样成功 ⇒
        //   "抠到了 shareid + uk" **不构成**"这条分享可用"的证据（旧判据就是这么骗人的）
        ok("B5 ★ 陷阱：错误页上 shareFields **照样**非空（所以它不能当可用判据）",
                PanBaidu.shareFields(DEAD) != null, "");

        System.out.println("-- C. errnoOf：百度用 `{\"errno\":…}` 信封 --");
        ok("C1 errno=0", Integer.valueOf(0).equals(PanBaidu.errnoOf("{\"errno\":0,\"list\":[]}")), "");
        ok("C2 errno=-21（缺 root=1 时实测到的码）",
                Integer.valueOf(-21).equals(PanBaidu.errnoOf("{\"errno\":-21,\"errmsg\":\"请求失败\"}")), "");
        ok("C3 errno=113（匿名取 dlink 实测到的码）",
                Integer.valueOf(113).equals(PanBaidu.errnoOf("{\"errno\":113,\"errmsg\":\"验证码签名错误\"}")), "");
        ok("C4 errno=145（失效分享）",
                Integer.valueOf(145).equals(PanBaidu.errnoOf("{\"errno\":145}")), "");
        ok("C5 HTML 错误页不是信封 ⇒ null",
                PanBaidu.errnoOf("<html><body>404</body></html>") == null, "");
        ok("C6 空体 ⇒ null", PanBaidu.errnoOf("") == null, "");

        System.out.println("-- D. dlinkOf：JSON 里的转义斜杠 --");
        String DL = "{\"errno\":0,\"list\":[{\"dlink\":"
                + "\"https:\\/\\/d.pcs.baidu.com\\/file\\/abc?sign=x&amp;t=1\"}]}";
        String got = PanBaidu.dlinkOf(DL);
        ok("D1 抠出 dlink 并把 `\\/`、`&amp;` 还原成真实 URL",
                "https://d.pcs.baidu.com/file/abc?sign=x&t=1".equals(got), String.valueOf(got));
        ok("D2 相对路径不算直链（必须是 http 开头）",
                PanBaidu.dlinkOf("{\"dlink\":\"\\/file\\/abc\"}") == null, "");
        ok("D3 没有 dlink ⇒ null（调用方据此报 Broken，而不是静默）",
                PanBaidu.dlinkOf("{\"errno\":0,\"list\":[]}") == null, "");
        ok("D4 空体 ⇒ null", PanBaidu.dlinkOf("") == null, "");

        System.out.println("-- E. useRoot：只有分享根带 root=1 --");
        ok("E1 fid=null（分享根）⇒ 带 root=1", PanBaidu.useRoot(null), "");
        ok("E2 fid=\"\"（也当根）⇒ 带", PanBaidu.useRoot(""), "");
        ok("E3 fid=\"/\"（根）⇒ 带", PanBaidu.useRoot("/"), "");
        // ★ E4/E5 是"下钻原地打转"那条事故的机器化表达（§4.80）：
        //   给子目录带 root=1 会让服务端**静默忽略 dir**、把根目录再发一遍。
        ok("E4 ★ 拥有者侧绝对路径（子目录）⇒ **不带** root=1",
                !PanBaidu.useRoot("/2026-YIN/出入"), "");
        ok("E5 ★ 子目录里带中文也照样不带 root=1",
                !PanBaidu.useRoot("/电影/2026/X 消失-的-人呀"), "");

        System.out.println("-- F. 源码级接线守卫（编译都过、跑起来只是「点了没反应」的那些） --");
        String pbd = code(readSrc("app/src/main/java/com/videoshell/data/pan/PanBaidu.kt"));
        String prov = code(readSrc("app/src/main/java/com/videoshell/data/pan/PanProvider.kt"));
        ok("F0 两个源码文件都读得到", pbd != null && pbd.length() > 1000
                && prov != null && prov.length() > 500,
                pbd == null ? "PanBaidu 读不到" : (pbd.length() + " 字符"));
        ok("F1 ★ 百度已注册进 PanProviders（否则整个 Provider 是死代码）",
                prov != null && prov.contains("PanBaidu.baidu().let { m[it.type] = it }"), "");
        ok("F2 `root=1` 只在根目录请求上拼（`if (root) append(\"&root=1\")`）",
                pbd != null && pbd.contains("if (root) append(\"&root=1\")"), "");
        ok("F3 ★ 目录项的 fid 取它**自己的 `path`**（不是按名字拼的相对路径）",
                pbd != null && pbd.contains("if (isDir) it.optString(\"path\")"), "");
        ok("F4 ★ 带 cookie 取分享页时必须**合并 jar**（`BDCLND` 放行票据在 jar 里，"
                        + "显式 Cookie 头会把 jar 顶掉）",
                pbd != null && pbd.contains(
                        "if (ck.isNullOrBlank()) null else PanCloudDrive.mediaCookie(ck, jar, null)"), "");
        ok("F5 取直链的合并用**不筛键**那一份（`keys = null`）",
                pbd != null && pbd.contains(
                        "Http.cookieValuesFor(\"$API/api/sharedownload\"), null"), "");
        ok("F6 取直链表单四件套齐全（product/type/primaryid/fid_list）",
                pbd != null && pbd.contains("put(\"product\", \"share\")")
                        && pbd.contains("put(\"type\", \"dlink\")")
                        && pbd.contains("put(\"primaryid\", sh.shareid)")
                        && pbd.contains("put(\"fid_list\", \"[${ref.fid}]\")"), "");
        ok("F7 凭据校验的结构判据是 `BDUSS`（只有 BAIDUID 是游客）",
                pbd != null && pbd.contains("ck.contains(\"BDUSS=\")"), "");
        ok("F8 ★ `surl` 去掉了那个固定的 `1` 前缀（弄混的症状：分享活着却被报失效）",
                pbd != null && pbd.contains("val surl = link.id.removePrefix(\"1\")"), "");
        ok("F9 ★ 换放行票据那一步**不带**显式 Cookie（否则 `BDCLND` 不进 jar，下一步列目录必空）",
                pbd != null && pbd.contains("null, \"换放行票据\""), "");
        // ★ F10 顺序即语义：页面自称错误页的判据必须**先**于"抠字段"。
        //   反过来的话，错误页也能抠出 shareid/uk ⇒ 死链被当成活链（§4.80 的假结论）。
        int iDead = pbd == null ? -1 : pbd.indexOf("shareIsDead(html)");
        int iFields = pbd == null ? -1 : pbd.indexOf("shareFields(html) ?:");
        ok("F10 ★ pageOf 里「先判死链、再看字段」的顺序",
                iDead >= 0 && iFields > iDead, "shareIsDead@" + iDead + " shareFields@" + iFields);
        ok("F11 HTTP 兜底归因复用 PanCloudDrive.errorForHttp（不各写一份）",
                pbd != null && pbd.contains("PanCloudDrive.errorForHttp(code, type, step)"), "");
        ok("F12 supported = true（匿名展开真实集数那一半要给用户）",
                pbd != null && pbd.contains("override val supported: Boolean get() = true"), "");
        ok("F13 isNeedLogin 只认 `-6` 与「登录」二字（没见过的码不判过期 —— UC §4.79 的反面教材）",
                pbd != null && pbd.contains("errno == -6 || msg.contains(\"登录\")"), "");
        ok("F14 失败文案带「哪一步」（否则用户给了完整自检也定位不到端点）",
                pbd != null && pbd.contains("stepAt()"), "");
        // ★ F15：登录页的"已登录"判据必须与 Provider 的结构判据**同源**。
        //   两处认不同的键 ⇒ 页面说"登录成功"、接口说"未登录"（用户看到"刚登录就过期"，
        //   正是 UC §4.79 那条症状的形状 —— 那边是两个盘共用了夸克的键）。
        String dla = code(readSrc("app/src/main/java/com/videoshell/ui/DriveLoginActivity.kt"));
        ok("F15 ★ 百度登录标记与 verify 的结构判据同源（都是 `BDUSS`）",
                dla != null && dla.contains("PanType.BAIDU -> listOf(\"BDUSS\")")
                        && pbd != null && pbd.contains("ck.contains(\"BDUSS=\")"), "");

        // ★ G 组是 v1.0.80 的真事故（PITFALLS §4.83）：分享页地址被拼成了
        //   `https://pan.baidu.com//s/{id}`（`WEB` 自带尾斜杠，又拼了一个 `/s/`）。
        //   那条路径回 **HTTP 404** + **谎称** `Content-Encoding: gzip`（正文却是明文 HTML）
        //   ⇒ OkHttp 解 gzip 抛 ZipException ⇒ classify 报成「网络请求失败」。
        //   真机症状：BD 线路永远「未展开」，且错误文案把人指向网络问题。
        System.out.println("-- G. sharePageUrl：分享页地址不许出现双斜杠 --");
        String spu = PanBaidu.sharePageUrl("1abc");
        ok("G1 ★ 分享页从 API 拼（**不带尾斜杠**）：pan.baidu.com/s/1abc",
                "https://pan.baidu.com/s/1abc".equals(spu), spu);
        // ★ G2 是这条事故的机器化判据：`//s/` 一旦出现，服务和响应形状就全错，
        //   而它**编译得过、看起来也像个正常 URL**。
        ok("G2 ★ 结果里不许出现 `//s/`（双斜杠那一次事故的判据）",
                !PanBaidu.sharePageUrl("1kzwNTOAPLJqLbGvIU_e3Kg").contains("//s/"),
                PanBaidu.sharePageUrl("1kzwNTOAPLJqLbGvIU_e3Kg"));
        ok("G3 带 `_`/`-` 的分享码原样保留（别顺手编码掉）",
                "https://pan.baidu.com/s/1t1A3ohq6_PqP0QrnoIo-ig"
                        .equals(PanBaidu.sharePageUrl("1t1A3ohq6_PqP0QrnoIo-ig")),
                PanBaidu.sharePageUrl("1t1A3ohq6_PqP0QrnoIo-ig"));
        ok("G4 pageOf 走的就是 sharePageUrl（不许再各拼一份）",
                pbd != null && pbd.contains("val url = sharePageUrl(link.id)"), "");
        // ★ G5：`$WEB` 只该当 Referer。它带尾斜杠，任何 `"$WEB/…"` 都会造出双斜杠。
        ok("G5 ★ 源码里不许再出现 `\"$WEB/s/\"`（旧写法）",
                pbd != null && !pbd.contains("$WEB/s/"), "");

        // ★ H 组是 v1.0.81 补的那一课（PITFALLS §4.84）：用户反馈"百度资源播放失败"，
        //   气泡文案是 `errno 113: \u9a8c\u8bc1\u7801...`。读了百度自己的下载 bundle
        //   （`download-all_*.js` 的 `ajaxGetDlinkShare`）后，113 有两个**能在我们这侧堵住**的成因：
        //   加密分享没回显放行票据（`extra={"sekey":BDCLND}`）、签名时间单位差 1000 倍；
        //   外加一条纯显示问题：错误串是 `\uXXXX`，不还原等于没有文案。
        //   H1~H4 是 `pageStamp`（单位归一），H5~H8 是 `sekey`/`extraOf`（票据回显），
        //   H9~H11 是 `unescape`（把人话还给用户），H12~H16 是**接线守卫** ——
        //   这一组函数"定义了却没人调用"正是它一开始的失败形状（编译全过、症状照旧）。
        System.out.println("-- H. pageStamp/sekey/extraOf/unescape：errno=113 的三个成因（v1.0.81） --");
        ok("H1 `timestamp:1790252372`（秒）原样返回",
                PanBaidu.pageStamp("yunData={timestamp:1790252372}") == 1790252372L,
                String.valueOf(PanBaidu.pageStamp("yunData={timestamp:1790252372}")));
        // ★ H2 是这一组的核心：现网页面写的是 `servertime`（**毫秒**），而请求参数要秒。
        //   混用的症状是"看起来有值、其实差 1000 倍" ⇒ 服务端回 errno=113（与"sign 为空"同码）。
        ok("H2 ★ `servertime`（毫秒）归一到秒 —— 不归一就是 errno=113",
                PanBaidu.pageStamp("locals.set('servertime', 1790669409761)") == 1790669409L,
                String.valueOf(PanBaidu.pageStamp("locals.set('servertime', 1790669409761)")));
        ok("H3 `timestamp`（秒）优先于 `servertime`（毫秒）",
                PanBaidu.pageStamp("servertime:1790669409761,timestamp:1790252372") == 1790252372L,
                String.valueOf(PanBaidu.pageStamp(
                        "servertime:1790669409761,timestamp:1790252372")));
        ok("H4 页面没给签名时间 ⇒ 0（调用方回落 now，不拿 0 当有效值发出去）",
                PanBaidu.pageStamp("yunData={}") == 0L && PanBaidu.pageStamp("") == 0L, "");

        // ★ H5~H8：加密分享（页面 `"public":0`）取 dlink 必须回显放行票据。
        //   票据在 cookie 里是百分号编码的，请求体里要**解码后**的值（bundle 里的 `decodeURIComponent`）。
        String ckc = "BDUSS=abc; BDCLND=j%2FIwOK%3D; STOKEN=z";
        ok("H5 ★ BDCLND 取值并**按 URL 解码**（`j%2FIwOK%3D` → `j/IwOK=`）",
                "j/IwOK=".equals(PanBaidu.sekey(ckc)), PanBaidu.sekey(ckc));
        ok("H6 没有 BDCLND ⇒ 空串（公开分享本来就没有它，空 ≠ 出错）",
                PanBaidu.sekey("BDUSS=abc").isEmpty() && PanBaidu.sekey(null).isEmpty(),
                PanBaidu.sekey("BDUSS=abc"));
        String ex = PanBaidu.extraOf("j/IwOK=");
        ok("H7 ★ extra 的形状 = `{\"sekey\":\"<票据>\"}`",
                "{\"sekey\":\"j/IwOK=\"}".equals(ex), ex);
        ok("H8 extraOf 转义 `\"`（票据里出现引号时不许把 JSON 拼坏）",
                "{\"sekey\":\"a\\\"b\"}".equals(PanBaidu.extraOf("a\"b")), PanBaidu.extraOf("a\"b"));

        // ★ H9~H11：错误文案必须是人话。旧 `msgOf` 把 `\u9a8c\u8bc1...` 原样塞进气泡 ——
        //   用户看到的是一串反斜杠，等于没有文案（这正是截图里的那一行）。
        ok("H9 ★ `\\uXXXX` 还原成中文（`\\u9a8c\\u8bc1\\u7801\\u7b7e\\u540d\\u9519\\u8bef` → 验证码签名错误）",
                "验证码签名错误".equals(PanBaidu.unescape(
                        "\\u9a8c\\u8bc1\\u7801\\u7b7e\\u540d\\u9519\\u8bef")),
                PanBaidu.unescape("\\u9a8c\\u8bc1\\u7801\\u7b7e\\u540d\\u9519\\u8bef"));
        ok("H10 没有转义的串逐字节保留（不许顺手改正文）",
                "errno 113 请求失败".equals(PanBaidu.unescape("errno 113 请求失败")), "");
        ok("H11 `\\n`/`\\t` 也还原（文案里可能带换行）",
                "a\nb\tc".equals(PanBaidu.unescape("a\\nb\\tc")), "");

        // ★ H12~H16 是**接线守卫**：这一组函数"定义了却没人调用"就是它一开始的失败形状
        //   （编译全过、跑起来症状照旧）。它们没有行为观测点，只能钉在源码上。
        ok("H12 ★ stream 的表单真的把票据回显进 `extra`（定义了却没用 = 等于没修）",
                pbd != null && pbd.contains("put(\"extra\", extraOf("), "");
        ok("H13 ★ 只在有票据时发 `extra`（公开分享那边它是 `undefined`，发空串会被按加密校验）",
                pbd != null && pbd.contains("if (ticket.isNotBlank()) put(\"extra\","), "");
        ok("H14 ★ 失败文案走 unescape（`msgOf` 里），不是把 `\\uXXXX` 原样交给用户",
                pbd != null && pbd.contains("return unescape(v).take(60)"), "");
        ok("H15 ★ 页面没给 sign 时回落到账号级签名（`gettemplatevariable`，与页面 `locals` 同源）",
                pbd != null && pbd.contains("accountSign(ck)")
                        && pbd.contains("gettemplatevariable"), "");
        ok("H16 ★ sign 与 timestamp **同源**：回落到账号级时也要用它的时间戳（混用 = 没签名）",
                pbd != null && pbd.contains("if (it.timestamp > 0L) ts = it.timestamp"), "");

        System.out.println();
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            System.out.println("BAD: " + BAD);
            System.exit(1);
        }
    }
}
