import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 现场复核（v1.0.29 之后）：用户报「枫叶影视 zqkhmy 点某一集仍不能直接播，只能网页嗅探」。
 *
 * 走**真实 Http + 真实 SiteAdapter.resolve()**（runBlocking 驱动），逐线路打印它到底解析成了什么：
 *   Direct = 能直接播；Sniff = 只能嗅探（＝用户看到的现象）；Error = 报错。
 *
 * 对每一条「只能嗅探」的线路，再把链路每一层摊开落盘（播放页 → playerconfig.js → 解析页），
 * 直接看是哪一层断的 —— 不猜。
 */
public class ZqLive29 {

    static final String BASE = System.getProperty("vs.base", "https://www.zqkhmy.com");
    static final File OUT = new File("_zq29");
    static int direct = 0, sniff = 0, err = 0;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    static String get(String url, String ref) {
        try {
            return block((s, c) -> Http.INSTANCE.get(url, ref, Http.UA,
                    Collections.<String, String>emptyMap(), false,
                    (Continuation<? super String>) c));
        } catch (Throwable t) {
            return null;
        }
    }

    static String n(String s) { return s == null ? "null" : s; }

    static String trim(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public static void main(String[] args) throws Exception {
        OUT.mkdirs();
        System.out.println("BASE = " + BASE);

        // 模拟「全新安装 + 未校准」：清掉配方与线路表缓存，确保测的是识别路径而不是校准成果
        RecipeStore.INSTANCE.clear(BASE);
        MacPlayer.INSTANCE.clearCache();

        SiteConfig site = new SiteConfig("zq", "枫叶影视", BASE, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter a = AdapterFactory.INSTANCE.create(site);
        System.out.println("adapter = " + a.getClass().getSimpleName());

        List<Category> cats = block((s, c) -> a.categories((Continuation<? super List<Category>>) c));
        System.out.println("分类数 = " + cats.size());

        List<VideoItem> items = block((s, c) -> a.browse("", 1, (Continuation<? super List<VideoItem>>) c));
        System.out.println("首页列表 = " + items.size() + " 条");
        if (items.isEmpty()) { System.out.println("!! 首页无卡片，无法继续"); return; }
        for (int i = 0; i < Math.min(3, items.size()); i++) {
            VideoItem v = items.get(i);
            System.out.println("   item#" + i + " id=" + v.getId() + " name=" + v.getName()
                    + " pic=" + trim(v.getPic(), 70));
        }

        String vid = items.get(0).getId();
        VideoDetail d = block((s, c) -> a.detail(vid, (Continuation<? super VideoDetail>) c));
        System.out.println("\n详情 id=" + vid + " name=" + d.getName() + " 线路数=" + d.getGroups().size()
                + " pic=" + trim(d.getPic(), 70));
        int total = 0;
        for (PlayGroup g : d.getGroups()) total += g.getEpisodes().size();
        System.out.println("总集数 = " + total);

        // ============================================================ 逐线路 resolve
        System.out.println("\n================ resolve() 逐线路结果 ================");
        System.out.printf("%-4s %-14s %-6s %s%n", "#", "线路", "集数", "resolve 结果");
        System.out.println("-".repeat(100));

        List<String[]> sniffed = new ArrayList<>();   // {tag, playUrl}
        int idx = 0;
        for (PlayGroup g : d.getGroups()) {
            if (g.getEpisodes().isEmpty()) continue;
            idx++;
            Episode ep = g.getEpisodes().get(0);
            String tag = "L" + idx;
            Object r;
            try {
                r = block((s, c) -> a.resolve(ep, (Continuation<? super MediaSource>) c));
            } catch (Throwable t) {
                err++;
                System.out.printf("%-4s %-14s %-6d EX %s%n", tag, g.getName(),
                        g.getEpisodes().size(), t.getClass().getSimpleName() + ": " + trim(t.getMessage(), 60));
                continue;
            }
            String verdict;
            if (r instanceof MediaSource.Direct) {
                MediaSource.Direct dd = (MediaSource.Direct) r;
                direct++;
                verdict = "Direct  hls=" + dd.isHls() + "  " + trim(dd.getUrl(), 90);
            } else if (r instanceof MediaSource.Sniff) {
                MediaSource.Sniff sn = (MediaSource.Sniff) r;
                sniff++;
                verdict = "Sniff   " + trim(sn.getPageUrl(), 80);
                sniffed.add(new String[]{tag, sn.getPageUrl()});
            } else {
                err++;
                verdict = "Error   " + trim(((MediaSource.Error) r).getMessage(), 80);
            }
            System.out.printf("%-4s %-14s %-6d %s%n", tag, g.getName(), g.getEpisodes().size(), verdict);
            System.out.println("     集0: " + trim(ep.getName(), 20) + " @ " + trim(ep.getUrl(), 90));

            // 逐层取证（只对前 4 条做，避免打太多请求）
            if (r instanceof MediaSource.Sniff && idx <= 4) diag(tag, ep.getUrl());
        }

        System.out.println("\n==== 汇总：Direct=" + direct + " Sniff=" + sniff + " Error=" + err + " ====");
    }

    // ================================================================ 逐层取证

    /**
     * 把「只能嗅探」这条链路摊开：播放页 → playerconfig.js → 解析页，每层都落盘 + 打印关键判据。
     */
    static void diag(String tag, String playUrl) throws Exception {
        System.out.println("\n  ---------- " + tag + " 逐层取证 ----------");
        String ref = BASE + "/";

        String page = get(playUrl, ref);
        dump(tag + "_play.html", page);
        System.out.println("    [1] 播放页 bytes=" + bytes(page));

        MacPlayer.Info info = MacPlayer.INSTANCE.parseInfo(page);
        if (info == null) {
            System.out.println("    [1] ✗ 页面里**没有** player_xxxx 播放数据 ⇒ resolve 从没见过它，"
                    + "自然只能嗅探。检查是不是播放页 html 变了 / 需要登录 / 换了别的容器。");
            System.out.println("        页面里 player_ 出现次数 = " + count(page, "player_"));
            System.out.println("        页面里 iframe 数 = " + count(page, "<iframe"));
            System.out.println("        页面里 m3u8 数 = " + count(page, "m3u8"));
            return;
        }
        System.out.println("    [1] player_aaaa: from=" + info.getFrom() + " encrypt=" + info.getEncrypt()
                + " url=" + trim(info.getUrl(), 70));

        Map<String, MacPlayer.Line> inline = MacPlayer.INSTANCE.parseLines(page);
        System.out.println("    [2] 播放页内联 player_list = " + inline.size() + " 条");

        Map<String, MacPlayer.Line> lines = inline;
        if (lines.isEmpty()) {
            String js = MacPlayer.INSTANCE.configScriptUrl(page, playUrl);
            System.out.println("    [2] playerconfig.js = " + n(js));
            if (js != null) {
                String cfg = get(js, playUrl);
                dump(tag + "_playerconfig.js", cfg);
                lines = MacPlayer.INSTANCE.parseLines(cfg);
                System.out.println("    [2] 外链 player_list = " + lines.size() + " 条"
                        + (lines.isEmpty() ? "   ← 抓到了 js 但解析不出线路表！" : ""));
            }
        }
        for (Map.Entry<String, MacPlayer.Line> e : lines.entrySet()) {
            System.out.println("          from=" + pad(e.getKey(), 12) + " ps=" + e.getValue().getPs()
                    + " parse=" + trim(e.getValue().getParse(), 60));
        }

        String gp = MacPlayer.INSTANCE.parseGlobalParse(page);
        System.out.println("    [2] 全局 MacPlayerConfig.parse = " + (gp.isEmpty() ? "(空)" : gp));

        MacPlayer.Line mine = lines.get(info.getFrom());
        System.out.println("    [3] 本线路来自 from=" + info.getFrom() + " ⇒ ps="
                + (mine == null ? "?(线路表里没这条)" : String.valueOf(mine.getPs())));

        String next = MacPlayer.INSTANCE.playPage(info, lines, gp);
        System.out.println("    [3] 推出的解析页 = " + n(next));
        if (next == null) {
            System.out.println("        ⇒ playPage() 返回 null：自营线路应已由 Media 抠出直链；"
                    + "若上面 [1] 的 url 不是媒体地址，就是「本线路是 ps:1 但模板/令牌缺失」。");
            return;
        }

        String ph = get(next, playUrl);
        dump(tag + "_parse.html", ph);
        System.out.println("    [4] 解析页 bytes=" + bytes(ph));
        String real = Media.INSTANCE.extractFromHtml(ph);
        System.out.println("    [4] Media.extractFromHtml = " + n(real));
        MacPlayer.Info pi = MacPlayer.INSTANCE.parseInfo(ph);
        System.out.println("    [4] 解析页 player_aaaa = " + (pi == null ? "无"
                : ("from=" + pi.getFrom() + " url=" + trim(pi.getUrl(), 70))));
        System.out.println("    [4] 解析页 m3u8 出现次数 = " + count(ph, "m3u8")
                + "，iframe 数 = " + count(ph, "<iframe")
                + "，<script 数 = " + count(ph, "<script")
                + "，js 混淆特征 = " + (contains(ph, "jsjiami") || contains(ph, "hex_md5")
                        || contains(ph, "eval(") ? "有" : "无"));
    }

    static void dump(String name, String content) {
        if (content == null) return;
        try {
            Files.write(new File(OUT, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) { }
    }

    static int bytes(String s) { return s == null ? -1 : s.getBytes(StandardCharsets.UTF_8).length; }

    static boolean contains(String h, String k) { return h != null && h.contains(k); }

    static int count(String h, String k) {
        if (h == null) return -1;
        Matcher m = Pattern.compile(Pattern.quote(k), Pattern.CASE_INSENSITIVE).matcher(h);
        int c = 0;
        while (m.find()) c++;
        return c;
    }

    static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }
}
