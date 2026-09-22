import com.videoshell.data.model.VideoItem;
import com.videoshell.data.model.VideoRow;
import com.videoshell.data.site.AggSearch;
import com.videoshell.player.PlayRetry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * v1.0.39 断言套件。钉三件事（都是"错了不崩、只会悄悄错"的地方）：
 *
 *   A ★ 聚合搜索的**分组标题行**：[AggSearch.rows] 的第一行必须是站名 + 条数，
 *     空块不许产出标题（否则界面上会出现一个下面什么都没有的站名）
 *   B ★ 行视图与卡片视图**同源**：[mergeRows] 的卡片投影必须逐条等于 [merge]
 *   C ★ 播放器"瞬时失败重试"判据 [PlayRetry]：402/429/5xx 要重试，
 *     403/404 绝不能重试（重试白等，还会把该换源的情况拖成转圈）；最坏代价必须有界
 *   D 搜索展示时**不显示分类标签**：[setSearchKeyword] 是唯一入口，
 *     且 `highlight` / `hideTags` 在源码里各只有一个写入点
 *   E ★ 播放「卡住」自愈：非嗅探源卡住要**重新解析本集**（换取新令牌），
 *     且次数上限不能被重解析自己清零（否则一次卡住会无限重解析）
 *   F 源码守卫：判据不能只在注释里成立（含「必须真的被调用」）
 *
 * 入参：a[0] = 夹具目录  a[1] = 工程根
 */
public class Agg39 {

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

    static int countLive(String src, String needle) {
        if (src == null) return 0;
        int n = 0;
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            if (line.contains(needle)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ 夹具

    static VideoItem item(String id, String name) {
        return new VideoItem(id, name, "https://pic/" + id + ".jpg", "", "", "", "", "", "");
    }

    static AggSearch.SiteHits hits(String key, String name, List<VideoItem> items) {
        return new AggSearch.SiteHits(key, name, items, null);
    }

    static List<VideoItem> items(String... names) {
        List<VideoItem> out = new ArrayList<>();
        int i = 1;
        for (String n : names) out.add(item(String.valueOf(i++), n));
        return out;
    }

    static List<String> cardNames(List<VideoItem> l) {
        List<String> out = new ArrayList<>();
        for (VideoItem v : l) out.add(v.getName() + "@" + v.getSiteKey());
        return out;
    }

    static List<String> cards(List<VideoRow> l) {
        List<String> out = new ArrayList<>();
        for (VideoRow r : l) {
            if (r instanceof VideoRow.Card) {
                VideoItem v = ((VideoRow.Card) r).getItem();
                out.add(v.getName() + "@" + v.getSiteKey());
            }
        }
        return out;
    }

    static List<String> rowNames(List<VideoRow> l) {
        List<String> out = new ArrayList<>();
        for (VideoRow r : l) {
            if (r instanceof VideoRow.Header) {
                VideoRow.Header h = (VideoRow.Header) r;
                out.add("== " + h.getName() + "(" + h.getCount() + ")");
            } else {
                VideoItem v = ((VideoRow.Card) r).getItem();
                out.add(v.getName() + "@" + v.getSiteKey());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        String proj = args.length > 1 ? args[1] : ".";

        aGroupHeader();
        bProjection();
        cPlayRetry();
        dSearchTags();
        eStallHeal(proj);
        fGuards(proj);

        System.out.println();
        System.out.println("================ Agg39  pass=" + pass + "  fail=" + fail
                + " ================");
        if (!fails.isEmpty()) {
            System.out.println("失败项：");
            for (String s : fails) System.out.println("  - " + s);
        }
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ A

    static void aGroupHeader() {
        banner("A. ★ AggSearch.rows：第一行是分组标题（站名 + 条数），空块不出标题");

        AggSearch.SiteHits h = hits("k1", "甲站", items("A", "B", "C"));
        List<VideoRow> r = AggSearch.INSTANCE.rows(h, AggSearch.PER_SITE);

        eq("3 张卡 ⇒ 1 行标题 + 3 行卡片 = 4 行", 4, r.size());
        ok("第一行就是分组标题", r.get(0) instanceof VideoRow.Header);
        VideoRow.Header hd = (VideoRow.Header) r.get(0);
        eq("标题写的是站名", "甲站", hd.getName());
        eq("★ 标题写的条数是**卡片数**（不含标题自己）", 3, hd.getCount());
        eq("标题带着站 key（与卡片 siteKey 同源）", "k1", hd.getSiteKey());
        eq("标题后面紧跟着就是那几张卡", Arrays.asList("A@k1", "B@k1", "C@k1"),
                cards(r));

        // 站名为空 ⇒ 用 key 兜底（界面上不能出现一行没有名字的标题）
        List<VideoRow> noName = AggSearch.INSTANCE.rows(
                hits("k9", "   ", items("X")), AggSearch.PER_SITE);
        eq("站名为空时标题回落到 key", "k9",
                ((VideoRow.Header) noName.get(0)).getName());

        // ★ 空块不产出标题：否则界面上会出现一个下面什么都没有的站名，
        //   用户会以为"这个站的结果没显示出来"
        eq("空结果站不产出标题", 0,
                AggSearch.INSTANCE.rows(hits("k2", "乙站", new ArrayList<VideoItem>()),
                        AggSearch.PER_SITE).size());
        eq("key 为空的批次整批丢掉（连标题也不出）", 0,
                AggSearch.INSTANCE.rows(hits("", "无key", items("A")), AggSearch.PER_SITE).size());
        eq("卡片全无名（点开必然是空页）⇒ 连标题也不出", 0,
                AggSearch.INSTANCE.rows(hits("k3", "丙站", items("")), AggSearch.PER_SITE).size());

        // 限量后标题上的条数必须跟着变 —— 否则标题写"20 条"而下面只有 2 张
        List<VideoRow> capped = AggSearch.INSTANCE.rows(
                hits("k4", "丁站", items("A", "B", "C", "D", "E")), 2);
        eq("限量 2 ⇒ 2 行卡片 + 1 行标题", 3, capped.size());
        eq("★ 标题条数跟着限量走（不然标题说 5 条、下面只有 2 张）", 2,
                ((VideoRow.Header) capped.get(0)).getCount());
    }

    // ------------------------------------------------------------------ B

    static void bProjection() {
        banner("B. ★ 行视图与卡片视图同源：mergeRows 的卡片投影必须逐条等于 merge");

        List<AggSearch.SiteHits> hs = new ArrayList<>();
        hs.add(hits("k1", "甲站", items("A", "B", "C")));
        hs.add(hits("k2", "乙站", items("D", "E")));
        hs.add(new AggSearch.SiteHits("k3", "丙站", new ArrayList<VideoItem>(), "boom"));

        eq("★ 卡片投影 == merge（两份判据同源，不是两套实现）",
                cardNames(AggSearch.INSTANCE.merge(hs, AggSearch.PER_SITE)),
                cards(AggSearch.INSTANCE.mergeRows(hs, AggSearch.PER_SITE)));
        eq("失败站不占行", 5 + 2, AggSearch.INSTANCE.mergeRows(hs, AggSearch.PER_SITE).size());
        List<String> rn = rowNames(AggSearch.INSTANCE.mergeRows(hs, AggSearch.PER_SITE));
        eq("两个有结果的站各一行标题", Arrays.asList("== 甲站(3)", "== 乙站(2)"),
                Arrays.asList(rn.get(0), rn.get(4)));

        // 流式"还没到达"的占位版
        List<AggSearch.SiteHits> slots = new ArrayList<>();
        slots.add(hits("k1", "甲站", items("A")));
        slots.add(null);
        slots.add(hits("k3", "丙站", items("C")));
        eq("★ mergeArrived（卡片）== mergeRowsArrived 的卡片投影",
                cardNames(AggSearch.INSTANCE.mergeArrived(slots, AggSearch.PER_SITE)),
                cards(AggSearch.INSTANCE.mergeRowsArrived(slots, AggSearch.PER_SITE)));
        eq("还没到的站不占行", 4, AggSearch.INSTANCE.mergeRowsArrived(
                slots, AggSearch.PER_SITE).size());
    }

    // ------------------------------------------------------------------ C

    static void cPlayRetry() {
        banner("C. ★ PlayRetry：瞬时状态码才重试，404/403 绝不重试，最坏代价有界");

        // 必须重试的：限流 / 边缘抖动 / 超时
        int[] yes = {402, 408, 425, 429, 500, 502, 503, 504};
        for (int c : yes) {
            ok("HTTP " + c + " 算瞬时（值得原样再试）", PlayRetry.INSTANCE.transientStatus(c),
                    "code=" + c);
        }
        // 绝不能重试的：内容的错 —— 重试只会白等，还会把"该换源"拖成转圈
        int[] no = {200, 201, 204, 206, 301, 302, 400, 401, 403, 404, 410, 416, 451};
        for (int c : no) {
            ok("HTTP " + c + " 不算瞬时（不重试）", !PlayRetry.INSTANCE.transientStatus(c),
                    "code=" + c);
        }

        eq("第 1 次拿到 402 ⇒ 还要再试", true, PlayRetry.INSTANCE.shouldRetry(0, 402));
        eq("第 2 次拿到 402 ⇒ 还要再试", true, PlayRetry.INSTANCE.shouldRetry(1, 402));
        eq("★ 已达上限（MAX_RETRY=2）⇒ 不再试，交给上层换源", false,
                PlayRetry.INSTANCE.shouldRetry(2, 402));
        eq("404 第一次就不试", false, PlayRetry.INSTANCE.shouldRetry(0, 404));
        eq("403 第一次就不试", false, PlayRetry.INSTANCE.shouldRetry(0, 403));

        eq("MAX_RETRY 是 2（重试次数不能无限）", 2, PlayRetry.MAX_RETRY);
        ok("退避时长递增", PlayRetry.INSTANCE.delayMs(1) >= PlayRetry.INSTANCE.delayMs(0));
        ok("负下标不炸", PlayRetry.INSTANCE.delayMs(-5) > 0, "d=" + PlayRetry.INSTANCE.delayMs(-5));
        ok("超大下标取最后一档（不越界）",
                PlayRetry.INSTANCE.delayMs(99) == PlayRetry.INSTANCE.delayMs(1));
        // ★ 最坏代价必须**小到用户感觉不到卡**。否则这个机制自己就成了新的抱怨源：
        //   用户报的是"一直转圈"，我们不能用一个"等 90 秒再试"来修它。
        ok("★ 最坏多等 < 3 秒（越小越好，不能把瞬时故障变成用户眼里的卡住）",
                PlayRetry.INSTANCE.worstExtraMs() < 3000,
                "worstExtraMs=" + PlayRetry.INSTANCE.worstExtraMs());
        eq("worstExtraMs 与逐档相加一致", PlayRetry.INSTANCE.delayMs(0)
                + PlayRetry.INSTANCE.delayMs(1), PlayRetry.INSTANCE.worstExtraMs());
    }

    // ------------------------------------------------------------------ D

    static void dSearchTags() {
        banner("D. ★ 搜索展示不显示分类标签：setSearchKeyword 是唯一入口");
        // 这里**故意不放断言**：渲染效果要真实 Android 环境才验得到，
        // 而"写一条 ok(…, true)"只会给出一行什么都不证明的 PASS ——
        // 本项目最忌讳的就是"绿了但没验到"。
        // 这条判据的可离线部分（一个入口、各一个写入点、副标题里真的按 flag 分流）
        // 全部落在 F 段的源码守卫里，那里数的是**活代码**。
        System.out.println("  [SKIP] 渲染行为需设备 —— 契约与写入点见 F 段（无 vacuous PASS）");
    }

    // ------------------------------------------------------------------ E

    static void eStallHeal(String proj) {
        banner("E. ★ 卡住自愈：非嗅探源要重新解析本集，且次数上限不能被自己清零");

        String root = proj + "/app/src/main/java/com/videoshell/";
        String player = read(root + "player/PlayerActivity.kt");
        ok("PlayerActivity.kt 可读", player != null && player.length() > 2000);

        // 自愈必须真的接在"卡住"这条路上
        ok("★ 卡住分支里真的调用了「重新解析本集」",
                live(player, "playEpisode(PlayQueue.episodeIndex, autoHeal = true)"));
        ok("★ 自愈只在**非嗅探**源上做（嗅探有自己的换源路径，别互相打架）",
                live(player, "if (!fromSniff && stallReResolveTries < maxReResolve)"));
        ok("★ 有次数上限变量", live(player, "stallReResolveTries"));
        // ★ 关键：playEpisode 的**自愈分支不能清零计数**，否则重解析会无限自我复制
        ok("★ 计数只在用户发起时清零（autoHeal 时才不清）",
                live(player, "if (!autoHeal) stallReResolveTries = 0"));
        ok("★ playEpisode 有 autoHeal 参数（自愈与用户换集必须能区分）",
                live(player, "private fun playEpisode(index: Int, autoHeal: Boolean = false)"));
        // 卡住面板要说清"最近一条失败的请求"，否则"转圈"两个字里查不出任何东西
        ok("★ 卡住面板带出最近一条失败请求（转圈才有可查的东西）",
                live(player, "NetLog.lastFailure()"));
    }

    // ------------------------------------------------------------------ F

    static void fGuards(String proj) {
        banner("F. 源码守卫：判据不能只在注释里成立（含「必须真的被调用」）");

        String root = proj + "/app/src/main/java/com/videoshell/";
        String res = proj + "/app/src/main/res/";
        String adapter = read(root + "ui/adapter/VideoAdapter.kt");
        String browser = read(root + "ui/SiteBrowser.kt");
        String agg = read(root + "data/site/AggSearch.kt");
        String retry = read(root + "player/PlayRetry.kt");
        String media = read(root + "player/OkHttpMediaSource.kt");
        String player = read(root + "player/PlayerActivity.kt");
        String rowKt = read(root + "data/model/VideoRow.kt");
        String headXml = read(res + "layout/item_site_header.xml");
        String strings = read(res + "values/strings.xml");

        for (String[] f : new String[][]{
                {"VideoAdapter.kt", adapter}, {"SiteBrowser.kt", browser}, {"AggSearch.kt", agg},
                {"PlayRetry.kt", retry}, {"OkHttpMediaSource.kt", media},
                {"PlayerActivity.kt", player}, {"VideoRow.kt", rowKt},
                {"item_site_header.xml", headXml}, {"strings.xml", strings}}) {
            ok(f[0] + " 可读", f[1] != null && f[1].length() > 200);
        }

        // ---- #2 分组标题：模型 + 适配器两种行 + 独占整行 ----
        ok("★ VideoRow 有 Header 与 Card 两种行",
                live(rowKt, "data class Header(") && live(rowKt, "data class Card("));
        ok("★ 适配器有两种 viewType", live(adapter, "const val TYPE_HEADER")
                && live(adapter, "const val TYPE_CARD"));
        ok("★ 适配器按行类型分派 ViewHolder", live(adapter, "is VideoRow.Header"));
        ok("★ 有「这个位置是不是标题」的判据（SpanSizeLookup 要用）",
                live(adapter, "fun isHeader(position: Int)"));
        ok("★ SiteBrowser 真的把标题设成了独占整行",
                live(browser, "spanSizeLookup") && live(browser, "getSpanSize"));
        ok("★ 标题布局有站名与条数两个控件",
                live(headXml, "tvHeaderSite") && live(headXml, "tvHeaderCount"));
        ok("★ 标题必须整行宽（否则在 3/5 列网格里会和卡片挤在一行）",
                live(headXml, "match_parent"));
        ok("stall_reparse 文案存在（自愈的那句提示）", live(strings, "stall_reparse"));

        // ---- 铺网格用的是**行**，不再是卡片 ----
        ok("★ SiteBrowser 用 AggSearch.rows( 铺块", live(browser, "AggSearch.rows("));
        ok("★ SiteBrowser 翻页用 AggSearch.mergeRows(", live(browser, "AggSearch.mergeRows("));
        ok("★ 判据只有一份：merge 由 mergeRows 投影出来",
                live(agg, "cards(mergeRows(hits, perSite))"));
        ok("★ mergeArrived 也由行视图投影（不留第二套实现）",
                live(agg, "cards(mergeRowsArrived(slots, perSite))"));

        // ---- #1 搜索不显示分类标签：一个入口 + 各一个写入点 ----
        ok("★ 适配器有 setSearchKeyword", live(adapter, "fun setSearchKeyword(keyword: String)"));
        ok("★ 搜索展示时副标题不拼分类标签",
                live(adapter, "if (hideTags) {"));
        eq("★ SiteBrowser 真的调用了这个入口（≥2 处：流式 + 提交）", true,
                countLive(browser, "setSearchKeyword(") >= 2);
        // 反向断言：不能再有第二处直接写 highlight —— 那就会出现"只设了一半"
        eq("★ SiteBrowser 里不再直接写 videoAdapter.highlight（防复活第二个入口）", 0,
                countLive(browser, "videoAdapter.highlight"));
        eq("★ 适配器里 highlight 只有一个写入点", 1, countLive(adapter, "highlight = "));
        eq("★ 适配器里 hideTags 只有一个写入点", 1, countLive(adapter, "hideTags = "));

        // ---- #3 瞬时重试真的接在请求路径上（不是只写了个对象）----
        ok("★ OkHttpDataSource 用了 PlayRetry.shouldRetry(",
                live(media, "PlayRetry.shouldRetry("));
        ok("★ OkHttpDataSource 用了 PlayRetry.delayMs(",
                live(media, "PlayRetry.delayMs("));
        ok("★ 重试上限用了常量（不是写死的数字）", live(media, "PlayRetry.MAX_RETRY"));
        ok("★ 402 在瞬时表里（实测到过的那个码）", live(retry, "402"));

        // ---- 分组标题不能把"卡片投影"弄丢数量 ----
        ok("★ 适配器保留 snapshot()（卡片投影），断言与自检仍以卡片计数",
                live(adapter, "fun snapshot(): List<VideoItem>"));
        ok("★ 适配器给出 size() 的口径（卡片数，不含标题）",
                live(adapter, "rows.count { it is VideoRow.Card }"));
    }
}
