package tools.verify;

import com.videoshell.data.pan.PanCloudDrive;
import com.videoshell.data.pan.PanError;
import com.videoshell.data.pan.PanResolver;
import com.videoshell.data.pan.PanType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网盘层纯函数守卫（无网络）：
 *
 *  - v1.0.69 / E50「直链 403」：媒体凭据合成（{@link PanCloudDrive#mediaCookie}）
 *    + 403 自愈判域（{@link PanResolver#isPanMediaUrl}）；
 *  - v1.0.70 / E51「选集不显示 / 分享已失效」：HTTP 状态码归类（{@link PanCloudDrive#errorForHttp}）。
 *
 * ## E 组为什么是这一版最值钱的断言
 *
 * 真机把一条**活着的**分享报成"分享链接已失效"，靠的就是 `404 -> PanError.Dead` 这条归因。
 * 它错在两层：
 *  ① 事实层：分享被删时夸克回的是 HTTP **200 + 信封 code:41006**（P0 实测表），而
 *     `file/v2/play` 这些端点匿名打是 **401**（2026-09-24 免凭据探针）—— 端点压根没消失；
 *  ② 语义层：**Dead 是终态**（提示换线路、不再重试）。把可重试的"这次请求被拒"判成终态，
 *     用户唯一能得到的结论就是一个**错误的方向**。
 * ⇒ 所以这里钉一条**全量**断言：任何 HTTP 状态码都不产生 Dead。
 */
public class PanMediaCookieTest {

    static int pass = 0, fail = 0;
    static final List<String> BAD = new ArrayList<>();

    static void ok(String what, boolean cond, String detail) {
        String line = (cond ? "[PASS] " : "[FAIL] ") + what
                + (detail == null || detail.isEmpty() ? "" : "   → " + detail);
        System.out.println(line);
        if (cond) pass++;
        else { fail++; BAD.add(what); }
    }

    static Map<String, String> m(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }

    // ------------------------------------------------------------------ 源码级守卫用的工具

    static String ROOT = System.getProperty("vs.root", "");

    static String readSrc(String rel) {
        try {
            return new String(Files.readAllBytes(Paths.get(ROOT, rel.split("/"))),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 剥注释（**必须认字符串字面量** —— E47 的教训：源码里一个
     * `"…,image/webp,*` + `/` + `*;q=0.8"` 就能让"块注释正则"把几百字符真代码吃掉，
     * 否定断言随即**恒真**）。G 组对剥注释助手本身有自测。
     */
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
        String SNAPSHOT = "__uid=t0ld; __pus=a1b2; __puus=OLDPUUS; __kp=xx; __kps=yy; navCompanyId=z";

        System.out.println("-- A. 合成方向：jar 新值必须覆盖快照同名键 --");
        String r1 = PanCloudDrive.mediaCookie(SNAPSHOT, m("__puus", "NEWPUUS"));
        ok("A1 __puus 取 jar 新值", r1.contains("__puus=NEWPUUS"), r1);
        ok("A2 不再出现旧值 OLDPUUS", !r1.contains("OLDPUUS"), "");
        ok("A3 快照独有的 __pus/__uid 保留", r1.contains("__pus=a1b2") && r1.contains("__uid=t0ld"), r1);
        String r2 = PanCloudDrive.mediaCookie(SNAPSHOT,
                m("__puus", "NEWPUUS", "__pus", "NEWPUS", "__uid", "NEWUID"));
        ok("A4 三键全被新值覆盖", r2.contains("__puus=NEWPUUS") && r2.contains("__pus=NEWPUS")
                && r2.contains("__uid=NEWUID"), r2);
        // E37 的差分事实：__puus 单键就能播 ⇒ 只有它滚动也能修好
        ok("A5 只有 __puus 滚动时其余键不动",
                PanCloudDrive.mediaCookie(SNAPSHOT, m("__puus", "N1")).contains("__pus=a1b2"), "");

        System.out.println("-- B. 白名单：三键之外的不许发 --");
        String r3 = PanCloudDrive.mediaCookie(SNAPSHOT,
                m("__puus", "N1", "__kp", "HACK", "sessionid", "LEAK", "", "v"));
        ok("B1 jar 里的白名单外键被丢弃", !r3.contains("HACK") && !r3.contains("LEAK"), r3);
        ok("B2 快照里的 __kp/__kps/navCompanyId 被丢弃",
                !r3.contains("__kp") && !r3.contains("__kps") && !r3.contains("navCompanyId"), r3);
        ok("B3 空键名的条目不影响合成", r3.contains("__puus=N1"), r3);
        ok("B4 新值为空白不覆盖快照",
                PanCloudDrive.mediaCookie(SNAPSHOT, m("__puus", "  ")).contains("__puus=OLDPUUS"), "");

        System.out.println("-- C. 退路与边界 --");
        // 快照里没见到白名单键但 jar 给出了 __puus ⇒ 键名是认识的，发合成值（最小暴露）；
        // 只有「双方都没有白名单键」才退回整份快照（原实现的"宁多带"语义）。
        String r4 = PanCloudDrive.mediaCookie("a=1; b=2", m("__puus", "N1"));
        ok("C1 快照白名单全空 + jar 有 __puus ⇒ 发 __puus=N1（键名认识，最小暴露）",
                "__puus=N1".equals(r4), r4);
        String r4b = PanCloudDrive.mediaCookie("a=1; b=2", m("zz", "1"));
        ok("C1b 双方皆无白名单键 ⇒ 退回整份快照（宁可多带也不能播不了）",
                "a=1; b=2".equals(r4b), r4b);
        String r5 = PanCloudDrive.mediaCookie("", m("__puus", "N1"));
        ok("C2 快照空 + 只有新 __puus ⇒ 发 __puus 单键（E37：单键就够）",
                "__puus=N1".equals(r5), r5);
        ok("C3 双方皆空 ⇒ 空串（调用方维持原样）",
                PanCloudDrive.mediaCookie("", m()).isEmpty(), "");

        System.out.println("-- D. isPanMediaUrl：403 自愈的判域 --");
        ok("D1 夸克媒体清单域",
                PanResolver.isPanMediaUrl("https://video-play-h-zb.drive.quark.cn/qv/ABC/media.m3u8?x=1"), "");
        ok("D2 另一个 CDN 前缀（前缀会变，按后缀判）",
                PanResolver.isPanMediaUrl("https://video-play-m8.drive.quark.cn/qv/media.ts"), "");
        ok("D3 UC 盘域", PanResolver.isPanMediaUrl("https://video-play-h.drive.uc.cn/qv/a.m3u8"), "");
        ok("D4 分享页不是媒体直链",
                !PanResolver.isPanMediaUrl("https://pan.quark.cn/s/1c0bccc37335"), "");
        ok("D5 夸克官网域不是媒体直链",
                !PanResolver.isPanMediaUrl("https://www.quark.cn/"), "");
        ok("D6 panref:// 引用不是媒体直链（URI host=quark，不匹配盘域）",
                !PanResolver.isPanMediaUrl("panref://quark/1c0bccc37335/e397/fid"), "");
        ok("D7 假前缀域不算：evildrive.quark.cn.example.com 拒绝",
                !PanResolver.isPanMediaUrl("https://evildrive.quark.cn.example.com/a.m3u8"), "");
        ok("D8 空串/null 拒绝", !PanResolver.isPanMediaUrl("") && !PanResolver.isPanMediaUrl(null), "");

        System.out.println("-- E. HTTP 状态码归类：Dead 只留给信封 41006 --");
        ok("E1 404 ⇒ 归到 Broken（真凶：原归因把可重试的问题判成终态）",
                PanCloudDrive.errorForHttp(404, PanType.QUARK) instanceof PanError.Broken,
                PanCloudDrive.errorForHttp(404, PanType.QUARK).getMessage());
        ok("E2 404 文案必须自己说清「不是分享失效」",
                PanCloudDrive.errorForHttp(404, PanType.QUARK).getMessage().contains("不是分享失效"),
                PanCloudDrive.errorForHttp(404, PanType.QUARK).getMessage());
        ok("E3 401 ⇒ NeedLogin（要能引导去「网盘账号」重登）",
                PanCloudDrive.errorForHttp(401, PanType.QUARK) instanceof PanError.NeedLogin, "");
        ok("E4 403 ⇒ NeedLogin（与 E50 的媒体域 403 区分：那是分片，这是接口）",
                PanCloudDrive.errorForHttp(403, PanType.QUARK) instanceof PanError.NeedLogin, "");
        ok("E5 500/503 ⇒ Broken（服务端错误，可稍后重试）",
                PanCloudDrive.errorForHttp(500, PanType.QUARK) instanceof PanError.Broken
                        && PanCloudDrive.errorForHttp(503, PanType.QUARK) instanceof PanError.Broken, "");
        ok("E6 429 ⇒ Broken（限流不是分享问题）",
                PanCloudDrive.errorForHttp(429, PanType.QUARK) instanceof PanError.Broken, "");
        ok("E7 UC 走同一套（同后端）且文案带品牌",
                PanCloudDrive.errorForHttp(404, PanType.UC).getMessage().contains("UC"), "");
        // ★ 全量：100..599 里**没有任何**状态码会产生 Dead。
        //   这是「Dead 是终态、不能由 HTTP 状态码冒充」这条纪律的机器化表达：
        //   以后谁再写 `某状态码 -> PanError.Dead(...)`，这条立刻红。
        List<Integer> deadFromHttp = new ArrayList<>();
        for (int c = 100; c <= 599; c++) {
            if (PanCloudDrive.errorForHttp(c, PanType.QUARK) instanceof PanError.Dead) {
                deadFromHttp.add(c);
            }
        }
        ok("E8 ★ 全量扫描 100..599：没有任何 HTTP 状态码产生 Dead", deadFromHttp.isEmpty(),
                "Dead 来自：" + deadFromHttp);
        ok("E9 每个状态码都给出非空原因（不许静默）",
                PanCloudDrive.errorForHttp(418, PanType.QUARK).getMessage().contains("418"), "");

        System.out.println("-- F. 源码级：那条错误归因不许回来 --");
        String pcd = code(readSrc(
                "app/src/main/java/com/videoshell/data/pan/PanCloudDrive.kt"));
        ok("F0 源码读得到（路径/编码没变）", pcd != null && pcd.length() > 1000,
                pcd == null ? "读不到" : (pcd.length() + " 字符"));
        // 否定断言必须能证明"被否定的东西确实不在"，且正例仍在（否则可能是文件读错/被剥空）
        ok("F1 剥注释后仍含正例 `code == 41006`（说明剥注释没把真代码吃掉）",
                pcd != null && pcd.contains("code == 41006"), "");
        ok("F2 已无 `HTTP 404 ⇒ Dead` 那条归因（剥注释后）",
                pcd != null && !pcd.contains("PanError.Dead(\"分享链接已失效（HTTP 404）\")"), "");
        ok("F3 已无「信封 status 404 即 Dead」的归因（剥注释后）",
                pcd != null && !pcd.contains("code == 41006 || o.optInt(\"status\", 0) == 404"), "");

        System.out.println("-- G. 剥注释助手自测（防 vacuous） --");
        String g1 = code("A // 注释\nB");
        ok("G0a 行注释被剥、真代码留下",
                !g1.contains("注释") && g1.contains("A") && g1.contains("B"), g1.replace("\n", "\\n"));
        ok("G0b 字符串字面量里的 /* 不许当块注释起点（E47 踩过：吞掉 600+ 字符真代码）",
                code("val a = \"x/*y\"; val b = 1").contains("val b = 1"), "");
        String g3 = code("A /* 注释 */ B");
        ok("G0c 块注释被剥、后面的真代码留下",
                g3.contains("B") && !g3.contains("注释"), g3);
        ok("G0d 未闭合的块注释不会抛异常（读到文件尾）", code("A /* 没闭合") != null, "");

        System.out.println("-- H. 取流请求体：形状过时 ⇒ HTTP 404（v1.0.71 的真凶） --");
        // 真机证据（2026-09-24 蜡笔/木偶）：save/task 全 200，而 file/v2/play、file/play、
        // file/delete 三个带 fid 的端点全 404/400 —— 看着像"fid 无效"。
        // 真正的判据来自**当前网页端自己的实现**（cloud-drive-web/4.6.7 的 share.js）：
        //   { fid, resolutions: (res||["low"]).join(","), supports: "fmp4,m3u8" }
        // 而旧代码发的是 { fid, resolution:"normal" }（单数键 + 过时值）。
        ok("H1 DEFAULT_RESOLUTIONS 是复数逗号列表、且不含过时的 normal",
                PanCloudDrive.DEFAULT_RESOLUTIONS.contains("low")
                        && PanCloudDrive.DEFAULT_RESOLUTIONS.contains("super")
                        && PanCloudDrive.DEFAULT_RESOLUTIONS.contains(",")
                        && !PanCloudDrive.DEFAULT_RESOLUTIONS.contains("normal"),
                PanCloudDrive.DEFAULT_RESOLUTIONS);
        ok("H2 取流体用 `resolutions`（复数键）",
                pcd != null && pcd.contains("put(\"resolutions\""), "");
        ok("H3 取流体带 `supports`（当前网页端必带）",
                pcd != null && pcd.contains("put(\"supports\""), "");
        // ★ 否定断言：过时的单数键必须真的不在 —— 它有 0.9 秒就退回老形状的诱惑
        ok("H4 ★ 已无单数键 `put(\"resolution\"`（它会让服务端匹配不到码流 ⇒ 404）",
                pcd != null && !pcd.contains("put(\"resolution\""), "");
        ok("H5 已无过时分辨率值 `normal`",
                pcd != null && !pcd.contains("resolution\", \"normal\""), "");
        ok("H6 退路 file/play 仍在（历史版本兜底；但网页端已 0 引用 ⇒ 别指望它）",
                pcd != null && pcd.contains("/file/play?"), "");

        System.out.println("-- I. 取流要不要补一次（retryablePlay） --");
        ok("I1 404 ⇒ 补（可能刚转存完还没就绪）",
                PanCloudDrive.retryablePlay(PanCloudDrive.errorForHttp(404, PanType.QUARK)), "");
        ok("I2 5xx / 429 ⇒ 补",
                PanCloudDrive.retryablePlay(PanCloudDrive.errorForHttp(503, PanType.QUARK))
                        && PanCloudDrive.retryablePlay(PanCloudDrive.errorForHttp(429, PanType.QUARK)), "");
        ok("I3 401/403 ⇒ 不补（要用户去登录，补多少次结论一样）",
                !PanCloudDrive.retryablePlay(PanCloudDrive.errorForHttp(401, PanType.QUARK))
                        && !PanCloudDrive.retryablePlay(PanCloudDrive.errorForHttp(403, PanType.QUARK)), "");
        ok("I4 Dead（分享真失效）⇒ 不补（终态语义）",
                !PanCloudDrive.retryablePlay(new PanError.Dead("分享链接已失效")), "");
        ok("I5 null ⇒ 不补（没有失败原因就别瞎试）",
                !PanCloudDrive.retryablePlay(null), "");
        ok("I6 Net（抖动）⇒ 补",
                PanCloudDrive.retryablePlay(new PanError.Net("网络请求失败")), "");

        System.out.println();
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            System.out.println("BAD: " + BAD);
            System.exit(1);
        }
    }
}
