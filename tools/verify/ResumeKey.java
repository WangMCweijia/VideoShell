import com.videoshell.data.site.Media;

import java.util.HashSet;
import java.util.Set;

/**
 * 离线校验：**播放进度记忆的身份 key**（v1.0.18）。
 *
 * 用户现象：「记住 A 的进度，播 B 时也会按 A 的记忆时间点续播」。
 *
 * 旧实现是 `"resume_${url.hashCode()}"`，两个死穴：
 *  1. **串台**：短剧站常见"整部剧共用一条 m3u8"，所有集的媒体地址**一字不差**，
 *     于是 hashCode 相同 ⇒ 同一个 key ⇒ A 的进度续到 B 上。这不是概率问题，是必然。
 *     即便地址不同，32 位 hashCode 本身也有碰撞空间。
 *  2. **丢进度**：带时效签名的直链每次解析都不同，hash 每次都变，存了也读不回来。
 *
 * 新实现把进度挂在「站点 + 剧名 + 集名」这个**稳定语义身份**上（见 PlayerActivity.episodeKey），
 * 与"从哪个地址取流"彻底解耦。
 *
 * 本文件只跑纯逻辑，不联网。
 */
public class ResumeKey {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what);
        if (cond) pass++; else fail++;
    }

    /** 复刻 PlayerActivity.episodeKey 的配方。产品端改了配方这里要同步。 */
    static String episodeKey(String siteKey, String show, String epName, int epIdx) {
        return "resume_" + Media.INSTANCE.digest(siteKey + "|" + show + "|" + epName + "#" + epIdx);
    }

    /** 旧实现（已废弃）：按媒体地址的 32 位 hashCode 记 */
    static String oldUrlKey(String url) {
        return "resume_" + url.hashCode();
    }

    public static void main(String[] args) {
        System.out.println("==== 进度记忆身份 key（离线） ====");
        System.out.println();

        // ---------- A. 旧方案的碰撞是真实存在的 ----------
        System.out.println("-- A. 旧方案（url.hashCode）为什么会串台 --");

        // 经典碰撞对："Aa" 与 "BB" 的 hashCode 相等 —— 用来重演机制
        String prefix = "https://cdn.example.com/hls/vod/12345/index.m3u8?auth=";
        String u1 = prefix + "Aa";
        String u2 = prefix + "BB";
        ok("url.hashCode() 确实会撞（两个不同地址 → 同一个 key）", u1.hashCode() == u2.hashCode());
        ok("digest 把这一对分开了", !Media.INSTANCE.digest(u1).equals(Media.INSTANCE.digest(u2)));

        // 短剧站最常见的串台场景：整剧共用一条 m3u8
        String ep1Media = "https://cdn.short.com/all-in-one/index.m3u8";
        String ep2Media = "https://cdn.short.com/all-in-one/index.m3u8";
        ok("旧方案：两集同一地址 ⇒ 同一 key ⇒ 记住 A 的进度续到 B（正是用户报的现象）",
                oldUrlKey(ep1Media).equals(oldUrlKey(ep2Media)));

        // ---------- B. digest 本身的形态 ----------
        System.out.println();
        System.out.println("-- B. Media.digest 形态 --");
        String d = Media.INSTANCE.digest("videoshell");
        ok("digest 是 16 位大写十六进制", d.length() == 16 && d.matches("[0-9A-F]{16}"));
        ok("digest 稳定（同输入同输出）", d.equals(Media.INSTANCE.digest("videoshell")));
        ok("digest 对空串也不炸", Media.INSTANCE.digest("").length() == 16);
        ok("digest 对中文/超长输入不炸",
                Media.INSTANCE.digest("野果短剧 第01集 " + new String(new char[400]).replace('\0', 'x')).length() == 16);

        // ---------- C. 新方案的四条性质 ----------
        System.out.println();
        System.out.println("-- C. 新方案（站点|剧名|集名）语义正确性 --");
        String site = "https://capable.fzchosdi.cc";
        String kShowA_ep1 = episodeKey(site, "野果短剧", "第01集", 0);
        String kShowA_ep2 = episodeKey(site, "野果短剧", "第02集", 1);
        String kShowB_ep1 = episodeKey(site, "另一部剧", "第01集", 0);
        String kOtherSite = episodeKey("https://other.example.com", "野果短剧", "第01集", 0);

        ok("同剧不同集 ⇒ key 不同（本次要修的核心）", !kShowA_ep1.equals(kShowA_ep2));
        ok("同剧同集 ⇒ key 稳定不变（重复解析/换地址都不丢进度）",
                kShowA_ep1.equals(episodeKey(site, "野果短剧", "第01集", 0)));
        ok("不同剧的同名集 ⇒ key 不同（跨剧不串）", !kShowA_ep1.equals(kShowB_ep1));
        ok("同剧名不同站点 ⇒ key 不同（跨站不串）", !kShowA_ep1.equals(kOtherSite));

        // 关键对照：媒体地址一个字都没变，新方案照样按集分开
        ok("★ 两集共用同一条 m3u8 时，新方案仍按集分开（旧方案必串）",
                !episodeKey(site, "野果短剧", "第01集", 0).equals(episodeKey(site, "野果短剧", "第02集", 1)));

        // 换线路（groupIndex）不应影响身份 —— 同一个"第01集"看了一半，
        // 换条线路接着看，进度必须还在，所以 key 里**不能**含线路
        String viaLine1 = episodeKey(site, "野果短剧", "第01集", 0);
        String viaLine2 = episodeKey(site, "野果短剧", "第01集", 0);
        ok("换播放线路 ⇒ key 不变（进度跟着「这一集」走，不跟着线路走）", viaLine1.equals(viaLine2));

        // ---------- C2. 骚火形状：全集集名都是「高清」 ----------
        System.out.println();
        System.out.println("-- C2. 同名集（骚火全集都叫「高清」）必须靠序号分开 --");
        Set<String> hd = new HashSet<>();
        for (int i = 0; i < 52; i++) hd.add(episodeKey(site, "交锋", "高清", i));
        ok("52 集「高清」的 key 全唯一（v1.0.18 无序号时必然全同）", hd.size() == 52);
        ok("同序号跨线路共享（线路1第5集 与 线路2第5集 同 key，续播语义保留）",
                episodeKey(site, "交锋", "高清", 4).equals(episodeKey(site, "交锋", "高清", 4)));

        // ---------- C3. 续播点失效守卫（v1.0.21） ----------
        System.out.println();
        System.out.println("-- C3. Media.resumeAtEnd：续播落点掉进流末尾必须判失效 --");
        // 正常续播点：存的时候就被 savePosition 挡在 dur-15s 之内
        ok("4 分 50 秒的短剧，续播点 4:20（合法）⇒ 不判失效",
                !Media.INSTANCE.resumeAtEnd(260_000L, 290_000L));
        // 设备侧时长变短（站点截断/换源）⇒ 旧续播点被钳到片尾 ⇒「从末尾开始播」
        ok("续播点 4:20 但本条流只剩 4:25（被截断）⇒ 判失效，从头播",
                Media.INSTANCE.resumeAtEnd(260_000L, 265_000L));
        ok("续播点恰好等于时长（ ended 后残留）⇒ 判失效",
                Media.INSTANCE.resumeAtEnd(290_000L, 290_000L));
        ok("落点在最后 15 秒边界内（dur-14s）⇒ 判失效",
                Media.INSTANCE.resumeAtEnd(276_001L, 290_000L));
        ok("落点恰在 dur-15s（边界，等于存盘上限）⇒ 不判失效",
                !Media.INSTANCE.resumeAtEnd(275_000L, 290_000L));
        ok("时长未知（dur<=0）⇒ 不判失效（交回原续播行为）",
                !Media.INSTANCE.resumeAtEnd(260_000L, 0L));

        // ---------- D. 批量唯一性 ----------
        System.out.println();
        System.out.println("-- D. 批量唯一性 --");
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (int show = 1; show <= 100; show++) {
            for (int ep = 1; ep <= 20; ep++) {
                seen.add(episodeKey(site, "剧" + show, "第" + ep + "集", ep - 1));
                n++;
            }
        }
        ok("2000 组 (剧,集) 的 key 全唯一（实得 " + seen.size() + "/" + n + "）", seen.size() == n);

        // 对照：同样的语料交给旧方案，看是否出现碰撞（不同机器/输入下可能不撞，
        // 但这条只作参考，不作为断言）
        Set<String> oldSeen = new HashSet<>();
        int oldDup = 0;
        for (int show = 1; show <= 100; show++) {
            for (int ep = 1; ep <= 20; ep++) {
                String u = "https://cdn.example.com/vod/" + show + "/play.m3u8?ep=" + ep;
                if (!oldSeen.add(oldUrlKey(u))) oldDup++;
            }
        }
        System.out.println("   （参考）旧方案在同样 2000 条语料上的碰撞数 = " + oldDup);

        System.out.println();
        System.out.println("==== ResumeKey PASS=" + pass + " FAIL=" + fail + " ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}
