import com.videoshell.data.model.Episode;
import com.videoshell.util.EpisodeOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 离线校验：**剧集列表的顺序 / 倒序**（v1.0.28 新增）。
 *
 * 需求原话：「剧集列表增加顺序倒序切换功能」。
 * 站点给分集的顺序各家不同 —— zqkhmy 的「蓝光2k」源是 181、180…1 倒着给的，
 * 用户要选第 1 集得滑到底。
 *
 * 两条铁律，这个套件就钉这两条：
 * ① 取值不猜：全部集都能取出集号才按集号排；有一个取不到（`HD中字`/`预告`）就退回
 *    「站点原顺序 / 反过来」，不许按 0 或字典序瞎排；
 * ② **排的是显示顺序，不是数据顺序** —— [EpisodeOrder.order] 返回的是「显示位置上的
 *    原始序号」，进度记忆 / 上一下一集只认原始序号，排序绝不能把它们带偏。
 */
public class EpOrd {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what);
        if (cond) pass++; else fail++;
    }

    static void eq(String what, Object want, Object got) {
        ok(what + "（期望 " + want + "，实际 " + got + "）", String.valueOf(want).equals(String.valueOf(got)));
    }

    static Episode ep(String name, String url) {
        // Episode 现在有第 3 个字段 pic（FN-1 分集缩略图）；Java 吃不到 Kotlin 默认值，必须显式给。
        return new Episode(name, url, "");
    }

    /** 集名 + 集号（模拟站点给的一列分集） */
    static List<Episode> seq(String[] names, String urlTpl) {
        List<Episode> l = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            l.add(new Episode(names[i], urlTpl + (i + 1) + ".html", ""));
        }
        return l;
    }

    static String idx(List<Integer> l) {
        return l.toString().replace("[", "").replace("]", "").replace(" ", "");
    }

    public static void main(String[] args) {
        System.out.println("=== A. 集号提取（取不到就是 null，不许猜）===");
        eq("第181集 → 181", 181, EpisodeOrder.INSTANCE.noOf(ep("第181集", "https://s.com/play/9-1-181.html")));
        eq("181 → 181", 181, EpisodeOrder.INSTANCE.noOf(ep("181", "https://s.com/play/9-1-181.html")));
        eq("EP12 → 12", 12, EpisodeOrder.INSTANCE.noOf(ep("EP12", "https://s.com/play/9-1-12.html")));
        eq("第02集 → 2", 2, EpisodeOrder.INSTANCE.noOf(ep("第02集", "https://s.com/play/9-1-2.html")));
        // 注意：这几条的地址里也**不能**有数字 —— 名字取不到号时会退到地址去取
        eq("HD中字 → null", null, EpisodeOrder.INSTANCE.noOf(ep("HD中字", "https://s.com/play/hd.html")));
        eq("预告 → null", null, EpisodeOrder.INSTANCE.noOf(ep("预告", "https://s.com/play/trailer.html")));
        eq("名字没数字 → 退到地址（问号后不算）", 2,
                EpisodeOrder.INSTANCE.noOf(ep("正片", "https://s.com/play/9-1-2.html?t=999")));
        // 集名里夹着别的数字时，必须按「第N集」的形状取，而不是取最后一个数字
        eq("「剧名 第1集 1080P」→ 1（不是 1080）", 1,
                EpisodeOrder.INSTANCE.noOf(ep("剧名 第1集 1080P", "https://s.com/play/9-1-1.html")));
        eq("「S01E02」→ 2", 2,
                EpisodeOrder.INSTANCE.noOf(ep("S01E02", "https://s.com/play/9-1-2.html")));

        System.out.println();
        System.out.println("=== B. 站点倒着给（zqkhmy 形状：181→1）===");
        String[] desc = new String[181];
        for (int i = 0; i < 181; i++) desc[i] = "第" + (181 - i) + "集";
        List<Episode> dList = seq(desc, "https://s.com/play/20245-8-");
        List<Integer> asc = EpisodeOrder.INSTANCE.order(dList, false);
        eq("正序：第一项是「第1集」（原始序号 180）", 180, asc.get(0));
        eq("正序：末项是「第181集」（原始序号 0）", 0, asc.get(asc.size() - 1));
        List<Integer> dsc = EpisodeOrder.INSTANCE.order(dList, true);
        eq("倒序：第一项是「第181集」（原始序号 0）", 0, dsc.get(0));
        eq("倒序：末项是「第1集」（原始序号 180）", 180, dsc.get(dsc.size() - 1));
        ok("正序 / 倒序互为逆序（元素一个不多一个不少）",
                asc.size() == 181 && dsc.size() == 181 && idx(reverse(dsc)).equals(idx(asc)));
        List<Episode> arranged = EpisodeOrder.INSTANCE.arrange(dList, false);
        eq("arrange 正序后第一项集名", "第1集", arranged.get(0).getName());
        eq("arrange 正序后末项集名", "第181集", arranged.get(arranged.size() - 1).getName());

        System.out.println();
        System.out.println("=== C. 取不到集号 ⇒ 不猜（保持站点顺序 / 反转）===");
        // 地址里也没有数字：这才是真正的「取不到集号」列
        List<Episode> odd = new ArrayList<>(Arrays.asList(
                ep("预告", "https://s.com/play/trailer.html"),
                ep("正片", "https://s.com/play/main.html"),
                ep("HD中字", "https://s.com/play/hd.html")));
        eq("混合列正序 = 站点原顺序", "预告,正片,HD中字", names(EpisodeOrder.INSTANCE.arrange(odd, false)));
        eq("混合列倒序 = 反过来", "HD中字,正片,预告", names(EpisodeOrder.INSTANCE.arrange(odd, true)));

        System.out.println();
        System.out.println("=== D. 边界 ===");
        eq("空表不炸", 0, EpisodeOrder.INSTANCE.order(new ArrayList<>(), false).size());
        eq("单集不炸", 0, EpisodeOrder.INSTANCE.order(
                Arrays.asList(ep("第1集", "https://s.com/play/1-1-1.html")), true).get(0));
        // 集号重复（站点重复给了「第1集」）→ 稳定排序保持站点顺序
        List<Episode> dup = new ArrayList<>(Arrays.asList(
                ep("第2集", "https://s.com/play/1-1-2.html"),
                ep("第1集", "https://s.com/play/1-1-1a.html"),
                ep("第1集", "https://s.com/play/1-1-1b.html")));
        eq("集号重复：稳定排序保持原顺序", "1,2,0", idx(EpisodeOrder.INSTANCE.order(dup, false)));
        eq("集号重复：倒序是正序的反转", "0,2,1", idx(EpisodeOrder.INSTANCE.order(dup, true)));

        System.out.println();
        System.out.println("==== runepord  pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }

    static List<Integer> reverse(List<Integer> l) {
        List<Integer> r = new ArrayList<>(l);
        java.util.Collections.reverse(r);
        return r;
    }

    static String names(List<Episode> l) {
        List<String> n = new ArrayList<>();
        for (Episode e : l) n.add(e.getName());
        return String.join(",", n);
    }
}
