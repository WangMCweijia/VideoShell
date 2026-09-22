import com.videoshell.player.HlsPlaylistFixer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * v1.0.40 断言套件：**相对分片的解析基准必须是「重定向后的最终地址」**。
 *
 * 真机症状（v1.0.39，枫叶影院第 3 线路 =「蓝光2k」，from=co）：
 *   清单 GET 200、时长读得到，但**每个分片都 402**，每片退避重试 2 次仍 402，一直转圈。
 * 实测根因（12/12 次确定性，与请求头无关）：
 *   清单 ….m3u8?auth_key=…  302 → …/ufile/flv/qq/&lt;hash&gt;/x_@lirose_tv.m3u8  ⇒ 200
 *   分片是**相对地址**，以【请求地址】为基准 ⇒ 走到 …/cloud/flv/… ⇒ 再被 302 甩去
 *   `https://www.aibox.eu.org/110`（无关第三方域名，其 Vercel 部署已停用）⇒ 402。
 *   以【最终地址】为基准 ⇒ 200、1 690 120 B、`video/MP2T`、首字节 0x47（真分片）。
 *   ⇒ 网页端（hls.js）用后者，所以"网页能播、App 转圈"。
 *
 * 钉住的四件事：
 *   A [HlsPlaylistFixer.baseFor] 的判定表（拿不到最终地址要**退回**请求地址，不能变空）
 *   B 用真实的两条地址回放：fix 后分片必须落在**最终目录**
 *   C 反向断言：两种基准的结果必须**不同**（防 baseFor 被改成恒等后仍然全绿）
 *   D 源码守卫：数据源必须取 `upstream.uri` 并交给 baseFor；旧的错误写法不得复活
 *
 * 入参：a[0] = 夹具目录  a[1] = 工程根
 */
public class Redir40 {

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

    static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    static void eq(String name, Object want, Object got) {
        ok(name, Objects.equals(got, want), "want=" + want + " got=" + got);
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("========== " + s + " ==========");
    }

    static String read(String p) {
        try {
            Path q = Paths.get(p);
            return Files.exists(q) ? new String(Files.readAllBytes(q), "UTF-8") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 源码里在**注释之外**是否出现某个片段。注释不算证据 */
    static boolean live(String src, String needle) {
        if (src == null || needle.isEmpty()) return false;
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            if (line.contains(needle)) return true;
        }
        return false;
    }

    // ==================================================================== 真地址（实测原文，勿改）

    /** 用户真机里发出去的那个清单地址 */
    static final String REQ =
            "https://cibn-edge-5g.1ljx.com/cloud/flv/89d56dff5ebf193e9ed88079f02bd5d0/"
                    + "68b929b442e1b8a60f66c8abfb2267c7/6aaf90a4f35dc.m3u8"
                    + "?auth_key=1789890724.996828-1-150513167.info-internet-2-llq-5029262bd46037d771f2a57ce1cdcd67";

    /** 服务器 302 之后真正给清单的那个地址 */
    static final String FIN =
            "https://cibn-edge-5g.1ljx.com/ufile/flv/qq/"
                    + "580d12a77b98a155ca3f3ea9896067a9ff8f3973484b2d4dea16e4cba79a21f9755adb01ac54f339f9304cee2fed97ea64e7370a0ffa77fb7c11a8ca06cc2e97/"
                    + "779b86476f9eb63686f1d9007d562a75_@lirose_tv.m3u8";

    /** 清单原文（分片行是相对地址，这点是整件事的关键） */
    static final String BODY =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-TARGETDURATION:15\n#EXT-X-PLAYLIST-TYPE:VOD\n"
                    + "#EXTINF:11.080,\nc6d2dbaaabfd35f464f68e47416cf7e5-0.ts?tg=@lirose_tv\n"
                    + "#EXTINF:10.000,\nc6d2dbaaabfd35f464f68e47416cf7e5-1.ts?tg=@lirose_tv\n"
                    + "#EXTINF:8.920,\nc6d2dbaaabfd35f464f68e47416cf7e5-2.ts?tg=@lirose_tv\n"
                    + "#EXT-X-ENDLIST\n";

    public static void main(String[] args) {
        String proj = args.length > 1 ? args[1] : ".";
        System.out.println("Redir40  v1.0.40  相对分片基准 = 重定向后的最终地址");

        aBaseFor();
        bRealReplay();
        cNotIdentity();
        dSourceGuards(proj);

        System.out.println();
        System.out.println("==== Redir40 PASS=" + pass + " FAIL=" + fail + " ====");
        if (!fails.isEmpty()) {
            System.out.println("失败项：");
            for (String f : fails) System.out.println("  - " + f);
        }
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ A

    static void aBaseFor() {
        banner("A. baseFor 判定表：有最终地址就用它，拿不到要【退回】请求地址（不能变空）");

        eq("★ 有最终地址 ⇒ 用最终地址", FIN, HlsPlaylistFixer.INSTANCE.baseFor(REQ, FIN));
        eq("最终地址为空串 ⇒ 退回请求地址", REQ, HlsPlaylistFixer.INSTANCE.baseFor(REQ, ""));
        eq("最终地址为 null ⇒ 退回请求地址", REQ, HlsPlaylistFixer.INSTANCE.baseFor(REQ, null));
        eq("最终地址是空白串 ⇒ 退回请求地址", REQ, HlsPlaylistFixer.INSTANCE.baseFor(REQ, "   "));
        eq("★ 没发生重定向（两者相同）⇒ 结果就是它本身", REQ,
                HlsPlaylistFixer.INSTANCE.baseFor(REQ, REQ));
        ok("★ 退回时不能把 query 丢掉（auth_key 在里面）",
                HlsPlaylistFixer.INSTANCE.baseFor(REQ, null).contains("auth_key="));
    }

    // ------------------------------------------------------------------ B

    static void bRealReplay() {
        banner("B. 真地址回放：分片必须落在【最终目录】");

        String fixed = HlsPlaylistFixer.INSTANCE.fix(BODY,
                HlsPlaylistFixer.INSTANCE.baseFor(REQ, FIN));

        String segDir = FIN.split("\\?")[0].substring(0, FIN.split("\\?")[0].lastIndexOf('/'));
        String reqDir = REQ.split("\\?")[0].substring(0, REQ.split("\\?")[0].lastIndexOf('/'));

        ok("★ 分片以最终目录开头", fixed.contains(segDir + "/c6d2dbaaabfd35f464f68e47416cf7e5-0.ts"),
                "缺 " + segDir);
        ok("★ 分片不再落在请求目录（那会被再 302 到死域名 ⇒ 402）",
                !fixed.contains(reqDir + "/"), "仍含请求目录 " + reqDir);
        ok("分片仍是相对地址被绝对化后的完整 URL（不是原样透传）",
                !fixed.contains("\nc6d2dbaaabfd35f464f68e47416cf7e5-0.ts"));
        ok("分片自带的 ?tg= 参数没被吃掉（CDN 依赖它）",
                fixed.contains("-0.ts?tg=@lirose_tv"));
        ok("三条分片都在", fixed.contains("-0.ts") && fixed.contains("-1.ts")
                && fixed.contains("-2.ts"));
        ok("EXTINF 没丢", fixed.contains("#EXTINF:11.080")
                && fixed.contains("#EXTINF:10.000") && fixed.contains("#EXTINF:8.920"));
    }

    // ------------------------------------------------------------------ C

    static void cNotIdentity() {
        banner("C. 反向断言：两种基准的结果【必须不同】—— 防 baseFor 退化成恒等");

        String withFinal = HlsPlaylistFixer.INSTANCE.fix(BODY, FIN);
        String withReq = HlsPlaylistFixer.INSTANCE.fix(BODY, REQ);
        String withIdentity = HlsPlaylistFixer.INSTANCE.fix(BODY,
                HlsPlaylistFixer.INSTANCE.baseFor(REQ, REQ));

        ok("★ 用最终地址 / 用请求地址 ⇒ 产出不同（否则这条判据根本没在做事）",
                !withFinal.equals(withReq));
        ok("★ baseFor(REQ, REQ) 必须等于「用请求地址」那一版（恒等是**唯一**允许的退化）",
                withIdentity.equals(withReq));
        ok("旧行为（用请求地址）确实会指向 /cloud/flv/",
                withReq.contains("/cloud/flv/"));
        ok("新行为（用最终地址）指向 /ufile/flv/qq/",
                withFinal.contains("/ufile/flv/qq/"));
    }

    // ------------------------------------------------------------------ D

    static void dSourceGuards(String proj) {
        banner("D. 源码守卫：数据源必须取 upstream.uri，并交给 baseFor");

        String root = proj + "/app/src/main/java/com/videoshell/";
        String fix = read(root + "player/HlsFix.kt");
        String media = read(root + "player/OkHttpMediaSource.kt");

        ok("HlsFix.kt 可读", fix != null && fix.length() > 3000);
        ok("OkHttpMediaSource.kt 可读", media != null && media.length() > 2000);

        // ① 回归守卫：必须真的取了"服务器最后给的那个地址"
        //    ⚠️ 注意别写成笼统的 `upstream.uri` —— 改动前 `getUri()` 里就有 `upstream.uri`，
        //    那种写法**改动前后都绿**，属于恒真断言（本项目已把它列为负资产）。
        //    所以这里只认"open 之后立刻取值"那一整行。
        ok("★ [回归] servedUrl 在 open 之后立刻取 upstream.uri（不是零散地在后面某处再读）",
                live(fix, "val servedUrl = runCatching { upstream.uri?.toString() }.getOrNull()"));
        ok("★ [回归] 真的调用了 baseFor", live(fix, "HlsPlaylistFixer.baseFor("));
        ok("★ [回归] fix() 用的是 base 而不是 dataSpec.uri",
                live(fix, "HlsPlaylistFixer.fix(text, base)"));
        ok("★ [回归] 旧写法 fix(text, dataSpec.uri.toString()) 不得复活",
                !live(fix, "fix(text, dataSpec.uri.toString())"));
        ok("★ [回归] 换了基准要写进播放记录（否则下次还是只能凭症状猜）",
                live(fix, "清单被重定向"));
        // ② 不变量守卫：最终地址的**来源**（改掉它这条修复会静默失效）
        ok("[不变量] currentUri 由 r.request.url 赋值（重定向后地址的唯一来源）",
                live(media, "r.request.url"));
        ok("[不变量] OkHttpDataSource.getUri 返回 currentUri",
                live(media, "override fun getUri(): Uri? = currentUri"));
    }
}
