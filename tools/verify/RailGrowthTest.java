import com.videoshell.data.model.VideoItem;
import com.videoshell.data.site.AggSearch;
import com.videoshell.data.site.RailGrowth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 聚合搜索左栏"随结果长出来"的离线回归（v1.0.67，无需网络）。
 *
 * 真跑 {@link RailGrowth} 的三个纯决策 —— 它们是这一版唯一会出错的地方：
 * 判错了就是「空格子回来了」「左栏站名与右边结果错位」「没结果的站再也点不回去」。
 *
 * ⚠️ 判据用 **AggSearch.SiteHits** 真造，不写桩：上栏与否必须真的走
 * `AggSearch.block()`（那条「有内容」的定义），否则这个测试只证明了一份平行实现。
 *
 * ⚠️ Kotlin 的默认参数对 Java 不可见 —— 所有带默认值的参数都要**显式传满**
 * （SiteHits 的 `error`、block 的 `perSite`）。少传一个不是"用默认值"，是编译不过。
 */
public class RailGrowthTest {

    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            fails.add(name + "  <<< " + detail);
            System.out.println("  FAIL  " + name + "   " + detail);
        }
    }

    static List<VideoItem> items(String... names) {
        List<VideoItem> out = new ArrayList<>();
        for (String n : names) out.add(new VideoItem(n, n, "", "", "", "", "", "", ""));
        return out;
    }

    static AggSearch.SiteHits hits(String key, String... names) {
        return new AggSearch.SiteHits(key, key.toUpperCase(), items(names), null);
    }

    static AggSearch.SiteHits failed(String key) {
        return new AggSearch.SiteHits(key, key.toUpperCase(),
                new ArrayList<VideoItem>(), "IOException: HTTP 502");
    }

    public static void main(String[] args) {
        System.out.println("== RailGrowth（左栏渐进式）离线回归 ==");

        // ---------------------------------------------------------------- A shouldShow
        System.out.println("\n-- A 该不该上栏 --");

        ok("A1 有内容的站上栏",
                RailGrowth.INSTANCE.shouldShow(hits("a", "片1", "片2")), "");

        // 这条是本版的核心诉求：没结果 = 不占格子
        ok("A2 【核心】没结果的站不上栏",
                !RailGrowth.INSTANCE.shouldShow(hits("a")), "空块竟然上栏了");

        // 失败站与"没结果"是两回事，但两者都不上栏 —— 失败由状态行那条提示说清，
        // 不能让它变成一个「这个站没有这部片」的假结论挂在左栏上
        ok("A3 失败的站不上栏（它的空块不是结论）",
                !RailGrowth.INSTANCE.shouldShow(failed("a")), "");

        ok("A4 名字为空的条目被滤掉后也算没内容（与 block 同源）",
                !RailGrowth.INSTANCE.shouldShow(hits("a", "")), "无名条目被当成了内容");

        ok("A5 key 为空的站不上栏（block 对空 key 恒空）",
                !RailGrowth.INSTANCE.shouldShow(hits("", "片1")), "");

        // ---------------------------------------------------------------- B insertAt
        System.out.println("\n-- B 插在哪一格 --");

        List<String> all = Arrays.asList("a", "b", "c", "d");
        List<String> none = new ArrayList<>();

        ok("B1 一个都没上栏时，第一个上栏的插在 1 号位（0 号位是「聚合」）",
                RailGrowth.INSTANCE.insertAt(all, none, "b") == 1,
                "got " + RailGrowth.INSTANCE.insertAt(all, none, "b"));

        ok("B2 排在后面的站插到已上栏的站之后",
                RailGrowth.INSTANCE.insertAt(all, Arrays.asList("b"), "c") == 2, "");

        // 关键：**按站点顺序**排，不按到达顺序。c 先到、b 后到 ⇒ b 仍要插到 c **前面**
        ok("B3 后到但站点顺序靠前的站，要插在前面（顺序按站点，不按到达）",
                RailGrowth.INSTANCE.insertAt(all, Arrays.asList("c"), "b") == 1,
                "got " + RailGrowth.INSTANCE.insertAt(all, Arrays.asList("c"), "b"));

        ok("B4 已上栏的站分散在两头时也按站点顺序算",
                RailGrowth.INSTANCE.insertAt(all, Arrays.asList("a", "d"), "c") == 2,
                "got " + RailGrowth.INSTANCE.insertAt(all, Arrays.asList("a", "d"), "c"));

        // 认不出 key 时插末尾：位置不理想 << 插到中间导致「点左边、右边是另一个站」
        ok("B5 认不出的 key 插到末尾（宁可位置不理想，也不许错位）",
                RailGrowth.INSTANCE.insertAt(all, Arrays.asList("a", "b"), "zzz") == 3,
                "got " + RailGrowth.INSTANCE.insertAt(all, Arrays.asList("a", "b"), "zzz"));

        ok("B6 allKeys 为空时也插到末尾（不抛异常）",
                RailGrowth.INSTANCE.insertAt(none, none, "a") == 1, "");

        // ---------------------------------------------------------------- C needFooter
        System.out.println("\n-- C 尾巴 --");

        ok("C1 有站被藏着 ⇒ 要尾巴（它是点回没结果站的唯一入口）",
                RailGrowth.INSTANCE.needFooter(Arrays.asList("a"), all), "");

        ok("C2 全部上栏 ⇒ 不要尾巴",
                !RailGrowth.INSTANCE.needFooter(all, all), "");

        ok("C3 一个站都没有 ⇒ 不要尾巴（否则像「有东西被藏了」）",
                !RailGrowth.INSTANCE.needFooter(none, none), "");

        // ---------------------------------------------------------------- D 与 AggSearch 同源
        System.out.println("\n-- D 与 AggSearch 同源（防止各写一份判据）--");

        AggSearch.SiteHits big = hits("a", "片1", "片2", "片3");
        ok("D1 shouldShow 与 block(h).size>0 完全一致（判据只有一处实现）",
                RailGrowth.INSTANCE.shouldShow(big)
                        == (AggSearch.INSTANCE.block(big, 24).size() > 0), "");

        // 被 PER_SITE 截断的站仍然是「有内容」：上栏看的是"有没有东西可看"，不是"是不是整页"
        List<VideoItem> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(new VideoItem("i" + i, "片" + i, "", "", "", "", "", "", ""));
        }
        AggSearch.SiteHits truncated =
                new AggSearch.SiteHits("a", "A", many, null);
        ok("D2 被 PER_SITE 截断的站照样上栏",
                RailGrowth.INSTANCE.shouldShow(truncated), "");

        // 对照：把 block 的限量抛到无穷，结论必须一致（证明上栏与否与限量无关）
        ok("D3 PER_SITE 大小不影响上栏结论（对照）",
                RailGrowth.INSTANCE.shouldShow(truncated)
                        == (AggSearch.INSTANCE.block(truncated, 0).size() > 0), "");

        System.out.println();
        System.out.println("==== RailGrowth pass=" + pass + " fail=" + fail + " ====");
        for (String f : fails) System.out.println("  FAIL " + f);
        System.exit(fail == 0 ? 0 : 1);
    }
}
