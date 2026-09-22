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

/**
 * v1.0.31 现场取证（两条一起查，别分两次跑）：
 *
 *   A. 骚火 https://shdy5.us —— 用户报「也要网页嗅探才能播」。
 *      走真实 Http + 真实 resolve()，逐线路看 Direct / Sniff；对每条 Sniff 把播放页落盘，
 *      直接看链断在哪一层。
 *   B. 野果（当前域名 agenda.fzchosdi.cc）—— 用户报「仍然没有剧集封面」。
 *      看列表 pic、详情 pic，以及**详情页 HTML 里分集区到底有没有 <img>**。
 *      （Episode(name,url) 目前根本没有 pic 字段 ⇒ 先证明站点给不给。)
 */
public class Live31 {

    static final String SHDY = System.getProperty("vs.shdy", "https://shdy5.us");
    static final String YG = System.getProperty("vs.yg", "https://agenda.fzchosdi.cc");
    static final File OUT = new File("_live31");

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

    static String trim(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static void dump(String name, String s) throws Exception {
        if (s == null) { System.out.println("      (抓取失败，未落盘 " + name + ")"); return; }
        Files.write(new File(OUT, name).toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    static SiteAdapter adapter(String key, String name, String base) throws Exception {
        RecipeStore.INSTANCE.clear(base);
        MacPlayer.INSTANCE.clearCache();
        SiteConfig site = new SiteConfig(key, name, base, "", SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter a = AdapterFactory.INSTANCE.create(site);
        System.out.println("  adapter = " + a.getClass().getSimpleName());
        return a;
    }

    public static void main(String[] args) throws Exception {
        OUT.mkdirs();
        System.out.println("################## A. 骚火 " + SHDY + " ##################");
        saohuo();
        System.out.println("\n################## B. 野果 " + YG + " ##################");
        yeguo();
    }

    // ============================================================ A. 骚火
    static void saohuo() throws Exception {
        SiteAdapter a = adapter("shdy", "骚火", SHDY);
        try {
            List<Category> cats = block((s, c) -> a.categories((Continuation<? super List<Category>>) c));
            System.out.println("  分类数 = " + cats.size());
        } catch (Throwable t) {
            System.out.println("  分类失败 " + t);
        }
        List<VideoItem> items;
        try {
            items = block((s, c) -> a.browse("", 1, (Continuation<? super List<VideoItem>>) c));
        } catch (Throwable t) {
            System.out.println("  首页失败 " + t);
            return;
        }
        System.out.println("  首页列表 = " + items.size() + " 条");
        if (items.isEmpty()) return;
        for (int i = 0; i < Math.min(3, items.size()); i++)
            System.out.println("     #" + i + " id=" + items.get(i).getId() + " " + trim(items.get(i).getName(), 30));

        VideoDetail d;
        try {
            d = block((s, c) -> a.detail(items.get(0).getId(), (Continuation<? super VideoDetail>) c));
        } catch (Throwable t) {
            System.out.println("  detail 失败 " + t);
            return;
        }
        System.out.println("  详情 " + trim(d.getName(), 40) + " 线路=" + d.getGroups().size()
                + " pic=" + trim(d.getPic(), 70));

        System.out.println("\n  ---- 逐线路 resolve ----");
        int idx = 0;
        List<String> sniffPages = new ArrayList<>();
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
                System.out.println("  " + tag + " " + g.getName() + " EX " + t);
                continue;
            }
            String verdict;
            if (r instanceof MediaSource.Direct) {
                direct++;
                verdict = "Direct hls=" + ((MediaSource.Direct) r).isHls() + " " + trim(((MediaSource.Direct) r).getUrl(), 80);
            } else if (r instanceof MediaSource.Sniff) {
                sniff++;
                String pu = ((MediaSource.Sniff) r).getPageUrl();
                verdict = "Sniff  " + trim(pu, 80);
                sniffPages.add(tag + "\t" + pu);
            } else {
                err++;
                verdict = "Error  " + trim(((MediaSource.Error) r).getMessage(), 70);
            }
            System.out.println("  " + tag + " " + pad(g.getName(), 12) + " 集" + g.getEpisodes().size()
                    + "  " + verdict);
            System.out.println("        集0: " + trim(ep.getName(), 16) + " @ " + trim(ep.getUrl(), 80));
        }
        System.out.println("  === 骚火汇总：Direct=" + direct + " Sniff=" + sniff + " Error=" + err + " ===");

        // 对每条 Sniff：把播放页抓下来落盘
        for (String s : sniffPages) {
            String[] kv = s.split("\t");
            String page = get(kv[1], SHDY + "/");
            dump("shdy_" + kv[0] + "_play.html", page);
            System.out.println("  [" + kv[0] + "] 播放页 bytes=" + (page == null ? -1 : page.length())
                    + "  → _live31/shdy_" + kv[0] + "_play.html");
            if (page != null) {
                // 关键判据：有没有 player_aaaa / m3u8 / iframe / script src
                System.out.println("       player_aaaa=" + page.contains("player_aaaa")
                        + "  m3u8=" + page.contains(".m3u8")
                        + "  iframe=" + page.toLowerCase().contains("<iframe")
                        + "  url= 出现" + count(page, "url=") + "次");
            }
        }
    }

    // ============================================================ B. 野果
    static void yeguo() throws Exception {
        SiteAdapter a = adapter("yg", "野果", YG);
        List<VideoItem> items;
        try {
            items = block((s, c) -> a.browse("", 1, (Continuation<? super List<VideoItem>>) c));
        } catch (Throwable t) {
            System.out.println("  首页失败 " + t);
            return;
        }
        System.out.println("  列表 = " + items.size() + " 条");
        for (int i = 0; i < Math.min(3, items.size()); i++)
            System.out.println("     #" + i + " id=" + items.get(i).getId() + " "
                    + trim(items.get(i).getName(), 24) + "  pic=" + trim(items.get(i).getPic(), 80));

        VideoDetail d;
        try {
            d = block((s, c) -> a.detail(items.get(0).getId(), (Continuation<? super VideoDetail>) c));
        } catch (Throwable t) {
            System.out.println("  detail 失败 " + t);
            return;
        }
        System.out.println("  详情 " + trim(d.getName(), 30) + " 线路=" + d.getGroups().size()
                + "  pic=" + trim(d.getPic(), 80));
        for (PlayGroup g : d.getGroups()) {
            System.out.println("     线路 " + g.getName() + " 集数=" + g.getEpisodes().size());
            for (int i = 0; i < Math.min(3, g.getEpisodes().size()); i++)
                System.out.println("        · " + trim(g.getEpisodes().get(i).getName(), 20)
                        + "  " + trim(g.getEpisodes().get(i).getUrl(), 70));
        }

        // 详情页原文：看分集区有没有 <img>
        String url = YG + "/video/" + items.get(0).getId() + ".html";
        String html = get(url, YG + "/");
        if (html == null) {
            // 兜底：用 detail 第一条分集的绝对地址
            html = get(d.getGroups().get(0).getEpisodes().get(0).getUrl(), YG + "/");
            url = d.getGroups().get(0).getEpisodes().get(0).getUrl();
        }
        dump("yg_detail.html", html);
        System.out.println("\n  详情页 " + trim(url, 80) + " bytes=" + (html == null ? -1 : html.length())
                + " → _live31/yg_detail.html");
        if (html != null) {
            System.out.println("     <img 共 " + count(html, "<img") + " 处");
            int p = 0, n = 0;
            while ((p = html.indexOf("<img", p)) >= 0 && n < 6) {
                System.out.println("       …" + trim(html.substring(p, Math.min(html.length(), p + 170)).replaceAll("\\s+", " "), 170));
                p += 4; n++;
            }
        }
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s == null ? "" : s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }
}
