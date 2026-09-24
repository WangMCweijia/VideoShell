package tools.verify;

import com.videoshell.data.pan.PanCloudDrive;
import com.videoshell.data.pan.PanEnvelope;
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
 *  - v1.0.70 / E51「选集不显示」：HTTP 状态码归类（{@link PanCloudDrive#errorForHttp}）；
 *  - v1.0.71 / E52「取流 404」：取流请求体形状（`resolutions` 复数 + `supports`）；
 *  - **v1.0.72 / E54「个别剧集播不了 + 整站播不了」**：Dead 的判据改成
 *    {@link PanCloudDrive#deadEnvelope}（**按服务端信封说的那句**）。
 *
 * ## E 组为什么是这两版最值钱的断言
 *
 * 真机把一条**活着的**分享报成"分享链接已失效"，靠的就是 `404 -> PanError.Dead` 这条归因。
 * 于是一整条被反转：**任何 HTTP 状态码都不产生 Dead**，404 的文案还写死了
 * 「不是分享失效」。这条反转**又被真机证伪了一次**（同一个"404"）：
 *
 * | 服务端原话 | HTTP | code | 谁在说 |
 * |---|---|---|---|
 * | `分享地址已失效` | 404 | 41011 | 夸克，分享被删/取消 |
 * | `文件不存在` | 404 | 41004 | 夸克，分享根指向的东西没了 |
 * | `分享不存在` | 404 | 41006 | 夸克，id 本身不存在 |
 * | `分享不存在` | **403** | 41027 | **UC**（所以 403 也不能默认当"要登录"） |
 *
 * ⇒ 两次翻车的共同点是**想用一个 HTTP 状态码回答一个状态码回答不了的问题**。
 * 现在的纪律：**归因只看服务端信封里说的那句**（J 组），状态码只在"服务端一个字都没说"
 * 时兜底（E 组），且兜底文案**不许断言任何一端**、必须给出可执行动作。
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

        System.out.println("-- E. HTTP 状态码归类（**兜底**：服务端一个字都没说时才轮到它） --");
        ok("E1 404 ⇒ 归到 Broken，不是 Dead（终态语义不能被状态码冒充）",
                PanCloudDrive.errorForHttp(404, PanType.QUARK) instanceof PanError.Broken,
                PanCloudDrive.errorForHttp(404, PanType.QUARK).getMessage());
        // ★ E2 是 v1.0.72 改掉的一条断言：它原先要求 404 文案**写死**「不是分享失效」——
        //   而实测 404 恰恰就是"分享已失效/文件不存在"的形状（41011 / 41004）。
        //   没有服务端的话时，唯一诚实的话是"不知道" + "该怎么办"。
        String m404 = PanCloudDrive.errorForHttp(404, PanType.QUARK).getMessage();
        ok("E2 404 文案不许断言任何一端（既不说「失效」也不说「不是失效」）",
                !m404.contains("失效"), m404);
        ok("E2b 404 文案必须给出可执行动作（重试 / 换线路）",
                m404.contains("重试") && m404.contains("换线路"), m404);
        ok("E2c 404 文案必须带上「哪一步」（否则用户给了完整自检，我们仍不知道是哪个接口）",
                PanCloudDrive.errorForHttp(404, PanType.QUARK, "取分享令牌").getMessage()
                        .contains("取分享令牌"),
                PanCloudDrive.errorForHttp(404, PanType.QUARK, "取分享令牌").getMessage());
        ok("E3 401 ⇒ NeedLogin（要能引导去「网盘账号」重登）",
                PanCloudDrive.errorForHttp(401, PanType.QUARK) instanceof PanError.NeedLogin, "");
        ok("E4 403（**没有信封时**）⇒ NeedLogin 兜底；有信封时由信封说了算（见 J 组）",
                PanCloudDrive.errorForHttp(403, PanType.QUARK) instanceof PanError.NeedLogin, "");
        ok("E5 500/503 ⇒ Broken（服务端错误，可稍后重试）",
                PanCloudDrive.errorForHttp(500, PanType.QUARK) instanceof PanError.Broken
                        && PanCloudDrive.errorForHttp(503, PanType.QUARK) instanceof PanError.Broken, "");
        ok("E6 429 ⇒ Broken（限流不是分享问题）",
                PanCloudDrive.errorForHttp(429, PanType.QUARK) instanceof PanError.Broken, "");
        ok("E7 UC 走同一套（同后端）且文案带品牌",
                PanCloudDrive.errorForHttp(404, PanType.UC).getMessage().contains("UC"), "");
        // ★ 全量：100..599 里**没有任何**状态码会产生 Dead。
        //   这是「Dead 是终态、只能由服务端信封说了算」这条纪律的机器化表达：
        //   以后谁再写 `某状态码 -> PanError.Dead(...)`，这条立刻红。
        //   （注意它守的是**兜底函数**；信封那条路在 J 组。）
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
        // 正例锚点：**剥注释后**必须还能看到真实判定代码（否则下面的否定断言可能只是"文件被剥空了"）
        ok("F1 剥注释后仍含正例 `deadEnvelope(code, msg)`（说明剥注释没把真代码吃掉）",
                pcd != null && pcd.contains("deadEnvelope(code, msg) ->"), "");
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

        // ---------------------------------------------------------------- J
        // Dead 的判据：**服务端信封里说的那句**。这张表是 2026-09-24 逐条打真接口得到的，
        // 不是推理出来的 —— 每一条都可以用 `panquark_spike.py` 重放。
        System.out.println("-- J. deadEnvelope：Dead 只能由服务端的话判定（v1.0.72 的真凶） --");
        int[][] deadCases = {
                {41004, 0},   // 文件不存在（蜡笔「天赐的声音第二季」实测：HTTP 404 + 这句）
                {41006, 0},   // 分享不存在（id 打错）
                {41011, 0},   // 分享地址已失效（分享被删 —— 蜡笔「第四季」实测）
                {41027, 0},   // 分享不存在（**UC 用 HTTP 403 说这件事**）
        };
        for (int[] c : deadCases) {
            ok("J" + c[0] + " 信封 code " + c[0] + " ⇒ Dead", PanCloudDrive.deadEnvelope(c[0], ""), "");
        }
        // ★ 对照组：不能把"没毛病"的码也判成终态 —— 那会让用户被引导去换线路
        for (int c : new int[]{0, 31001, 14001, 23004, 15000, 41000, 41001, 41002, 41003}) {
            ok("J-neg code " + c + " 不 ⇒ Dead", !PanCloudDrive.deadEnvelope(c, ""), "");
        }
        // 认话不认码：码会加、话不会乱说（但必须"没了" + "是谁"两个词同时出现）
        ok("J-msg 「分享地址已失效」（码没见过）⇒ Dead",
                PanCloudDrive.deadEnvelope(49999, "分享地址已失效"), "");
        ok("J-msg 「该文件已被删除」⇒ Dead",
                PanCloudDrive.deadEnvelope(49999, "该文件已被删除"), "");
        ok("J-msg-neg 「require login [guest]」不 ⇒ Dead",
                !PanCloudDrive.deadEnvelope(49999, "require login [guest]"), "");
        ok("J-msg-neg 「Bad Parameter: [fid]」不 ⇒ Dead",
                !PanCloudDrive.deadEnvelope(49999, "Bad Parameter: [fid]"), "");
        ok("J-msg-neg 只说「失效」不说是什么 ⇒ 不判 Dead（半句话不算）",
                !PanCloudDrive.deadEnvelope(49999, "签名已失效"), "签名已失效");
        ok("J-msg-neg 空 message 不 ⇒ Dead", !PanCloudDrive.deadEnvelope(49999, ""), "");

        // ---------------------------------------------------------------- K
        System.out.println("-- K. 源码级：v1.0.72 的四条新纪律 --");
        ok("K1 Dead 的码表只有一处定义（deadEnvelope 在 PanCloudDrive 内）",
                pcd != null && pcd.contains("private val DEAD_CODES = setOf(")
                        && pcd.contains("fun deadEnvelope("), "");
        ok("K1b 四个实测码都在那张表里（写错任一个，J 组会红）",
                pcd != null && pcd.contains("41004") && pcd.contains("41006")
                        && pcd.contains("41011") && pcd.contains("41027"), "");
        ok("K1c 403 不再无条件落「凭据过期」（UC 用 403 说「分享不存在」）",
                pcd != null && !pcd.contains("if (code == 401 || code == 403) DriveStore.markExpired")
                        && pcd.contains("if (code == 401) DriveStore.markExpired"), "");
        ok("K1d 每个请求都带「哪一步」标签（失败文案才定位得了端点）",
                pcd != null && pcd.contains("\"取分享令牌\"") && pcd.contains("\"列目录\"")
                        && pcd.contains("\"取播放入口\""), "");
        String psa = code(readSrc("app/src/main/java/com/videoshell/data/site/PanShareAdapter.kt"));
        ok("K2 ★ 兜底假线路不再充当「这一页不是网盘分享页」的证据",
                psa != null && psa.contains("lastDetailFlatFallback"), "");
        String hd = code(readSrc(
                "app/src/main/java/com/videoshell/data/site/HtmlAdapter_Detail.kt"));
        ok("K2b 那个标记来自唯一的成功出口 buildDetail（三处成功都经过它）",
                hd != null && hd.contains("lastDetailFlatFallback = groups.size == 1"), "");
        String he = code(readSrc("app/src/main/java/com/videoshell/data/site/HtmlExtractor.kt"));
        ok("K3 兜底线路名是常量（可断言），不再各处拼字面量",
                he != null && he.contains("const val FALLBACK_LINE"), "");
        ok("K4 展开的「补一次」与取流的「补一次」共用终态判据",
                code(readSrc("app/src/main/java/com/videoshell/data/pan/PanResolver.kt"))
                        .contains("!p.lastError.isTerminal()"), "");
        ok("K5 115 已进 PanLink 的识别表（否则整站会被判成「不是网盘分享站族」）",
                code(readSrc("app/src/main/java/com/videoshell/data/pan/PanLink.kt"))
                        .contains("PanType.CLOUD115"), "");

        // ---------------------------------------------------------------- L
        // v1.0.73 / E56「蜡笔仍然有剧集获取不到列表」：
        //   v1.0.72 写了 deadEnvelope，但**真机上一次都没生效** —— 失败响应体经 HttpError
        //   只留前 200 字符（snippetOf），而夸克错误信封约 335 字节 ⇒ JSON 截断、JSONObject 必抛。
        //   于是"服务端说了什么"永远读不到，真失效被当成"可重试"。
        //   下面三条 body 是**逐字**取自真接口的 200 字符片段（尾部被 snippetOf 切断）。
        System.out.println("-- L. 被截断的失败信封仍要能判 Dead（v1.0.73 的真凶） --");
        String t41004 = "{\"status\":404,\"code\":41004,\"message\":\"文件不存在\",\"req_id\":\"9aa1-2b19240a3a5cf2\",\"timestamp\":1790252372,\"metadata\":{\"_t_group\":\"0:_s_vp:1\",\"_g_group\":\"3:_s_vtp:1;7:_s_goback_app_pop:1;8:_s_goback_auto_sa";
        String t41011 = "{\"status\":404,\"code\":41011,\"message\":\"分享地址已失效\",\"req_id\":\"9aa2-2b19240a3a5cf2\",\"timestamp\":1790252372,\"metadata\":{\"_t_group\":\"0:_s_vp:1\",\"_g_group\":\"3:_s_vtp:1;7:_s_goback_app_pop:1;8:_s_goback_auto_sa";
        String t41027 = "{\"status\":403,\"code\":41027,\"message\":\"分享不存在\",\"req_id\":\"9aa3-2b19240a3a5cf2\",\"timestamp\":1790252372,\"metadata\":{\"_t_group\":\"0:_s_vp:1\",\"_g_group\":\"3:_s_vtp:1;7:_s_goback_app_pop:1;8:_s_goback_auto_sa";
        ok("L0 前提：这三条片段确实是**被截断**的（不以 } 结尾 ⇒ JSONObject 必抛）",
                !t41004.endsWith("}") && !t41011.endsWith("}") && !t41027.endsWith("}"), "");
        PanEnvelope e4 = PanCloudDrive.envelopeFields(t41004);
        ok("L1 ★ 截断的 404 信封仍抠得出 code=41004（原先这里恒为 null）",
                e4 != null && e4.getCode() == 41004, String.valueOf(e4));
        ok("L2 同时抠出 message（Dead 文案要用它）",
                e4 != null && "文件不存在".equals(e4.getMessage()),
                e4 == null ? "null" : e4.getMessage());
        ok("L3 抠出的 code 交给 deadEnvelope ⇒ Dead（这正是原先走不到的一步）",
                e4 != null && PanCloudDrive.deadEnvelope(e4.getCode(), e4.getMessage()), "");
        PanEnvelope e11 = PanCloudDrive.envelopeFields(t41011);
        ok("L4 41011「分享地址已失效」同样抠得出 ⇒ Dead",
                e11 != null && e11.getCode() == 41011
                        && PanCloudDrive.deadEnvelope(e11.getCode(), e11.getMessage()), "");
        PanEnvelope e27 = PanCloudDrive.envelopeFields(t41027);
        ok("L5 UC 的 403 + 41027 也抠得出（403 不能默认当「要登录」）",
                e27 != null && e27.getCode() == 41027 && e27.getStatus() == 403
                        && PanCloudDrive.deadEnvelope(e27.getCode(), e27.getMessage()), "");
        ok("L6 status 也抠出来（「未识别 404」那条分支要用）",
                e4 != null && e4.getStatus() == 404, "");
        // 对照组：不是信封的东西不许被硬当成信封
        ok("L7 非 JSON（HTML 错误页）不产生信封",
                PanCloudDrive.envelopeFields("<html><body>404 Not Found</body></html>") == null, "");
        ok("L8 空体不产生信封", PanCloudDrive.envelopeFields("") == null, "");
        ok("L9 有 message 没有 code 不算信封（code 是主判据）",
                PanCloudDrive.envelopeFields("{\"message\":\"文件不存在\"}") == null, "");
        // 源码级：归因处**必须**用 envelopeFields 读失败体 —— 不许再回去用 JSONObject（它必抛）
        ok("L10 ★ 源码级：classify 用 envelopeFields 读失败体（不许再用 JSONObject 解它）",
                pcd != null && pcd.contains("?.let { envelopeFields(it) }"), "");

        System.out.println();
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            System.out.println("BAD: " + BAD);
            System.exit(1);
        }
    }
}
