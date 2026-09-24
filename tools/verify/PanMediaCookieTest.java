package tools.verify;

import com.videoshell.data.pan.PanCloudDrive;
import com.videoshell.data.pan.PanResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网盘媒体凭据合成（v1.0.69，E50「直链 403」）的纯函数守卫 —— 无网络。
 *
 * 背景：夸克 `__puus` 是**滚动凭据**（每次 API 响应 Set-Cookie 下发新值），
 * 落盘快照必然越来越旧；媒体域校验它 ⇒ 过期后全分片 403。
 * 修复 = 媒体头用「快照打底 + jar 最新值覆盖」（{@link PanCloudDrive#mediaCookie}），
 * 外加「网盘直链 403 ⇒ 自动重新解析一次」自愈（判据 {@link PanResolver#isPanMediaUrl}）。
 *
 * 判据三条腿：
 *   A) 合成方向：jar 新值**覆盖**快照同名键（方向错了就等于没修）；
 *   B) 白名单：只有 __pus/__puus/__uid 三键能出现 —— 不把整份账号凭据平铺到 1000+ 分片上；
 *   C) 退路：快照白名单全空 ⇒ 退回**整份**快照（宁可多带也不能播不了）；isPanMediaUrl 判域。
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

        System.out.println();
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            System.out.println("BAD: " + BAD);
            System.exit(1);
        }
    }
}
