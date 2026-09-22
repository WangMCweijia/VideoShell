import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;

/**
 * 单部影片的详情页剖析：页面里有多少个分集锚点 vs parseGroups 认出多少集。
 * 两者差得远 = 解析漏了。
 */
public class OneDetail {

    static final String BASE = System.getProperty("vs.base", "https://www.bolyship.com");

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    static String get(String url, String ref) {
        try {
            return block((s, c) -> Http.INSTANCE.get(
                    url, ref, Http.UA, Collections.<String, String>emptyMap(), false,
                    (Continuation<? super String>) c));
        } catch (Throwable t) { return null; }
    }

    static final String[] SEL = {
        "ul.stui-content__playlist", ".stui-content__playlist", "[id^=playlist]",
        ".tab-pane", ".module-play-list", ".module-play-list-content", ".play-list",
        ".playlist", ".content-playlist", ".stui-pannel_bd", "ul", "dl"
    };

    public static void main(String[] args) throws Exception {
        String id = args.length > 0 ? args[0] : "548573";
        String url = BASE + "/bspvd/" + id + ".html";
        System.out.println("detail url = " + url);

        String html = get(url, BASE);
        if (html == null) { System.out.println("!! 抓取失败"); return; }
        System.out.println("页面 " + html.length() + " 字");

        Document doc = Jsoup.parse(html, BASE);
        Elements anchors = doc.select("a[href]");
        int play = 0, epi = 0;
        for (Element a : anchors) {
            String h = a.attr("href").trim();
            if (HtmlTemplates.INSTANCE.isPlayLink(h)) play++;
            else if (HtmlTemplates.INSTANCE.isEpisodeLink(h)) epi++;
        }
        System.out.println("锚点总数 " + anchors.size() + "，isPlayLink " + play
                + "，isEpisodeLink(非play) " + epi);

        System.out.println("\n-- 容器命中 --");
        for (String s : SEL) {
            Elements es = doc.select(s);
            int n = 0;
            for (Element e : es) n += e.select("a[href]").size();
            if (!es.isEmpty()) System.out.println("  " + pad(s, 34) + es.size() + " 个元素 / 内含锚点 " + n);
        }

        System.out.println("\n-- parseGroups --");
        List<PlayGroup> gs = HtmlExtractor.INSTANCE.parseGroups(doc, BASE);
        int total = 0;
        for (PlayGroup g : gs) {
            total += g.getEpisodes().size();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(5, g.getEpisodes().size()); i++) {
                sb.append('「').append(g.getEpisodes().get(i).getName()).append('」');
            }
            System.out.println("  「" + g.getName() + "」 " + g.getEpisodes().size() + " 集  " + sb);
        }
        System.out.println("合计 " + gs.size() + " 线路 / " + total + " 集");

        System.out.println("\n-- 前 6 个 isPlayLink 锚点原文 --");
        int c = 0;
        for (Element a : anchors) {
            String h = a.attr("href").trim();
            if (!HtmlTemplates.INSTANCE.isPlayLink(h)) continue;
            System.out.println("  " + a.outerHtml().replaceAll("\\s+", " ").substring(0,
                    Math.min(200, a.outerHtml().replaceAll("\\s+", " ").length())));
            if (++c >= 6) break;
        }

        System.out.println("\n-- 该片播放页（playTpl 形状）--");
        String p = get(BASE + "/bspvp/" + id + "-1-1.html", BASE);
        if (p == null) System.out.println("  播放页抓取失败");
        else {
            Document pd = Jsoup.parse(p, BASE);
            int pdPlay = 0;
            for (Element a : pd.select("a[href]")) {
                if (HtmlTemplates.INSTANCE.isPlayLink(a.attr("href").trim())) pdPlay++;
            }
            List<PlayGroup> pg = HtmlExtractor.INSTANCE.parseGroups(pd, BASE);
            int pt = 0;
            for (PlayGroup g : pg) pt += g.getEpisodes().size();
            System.out.println("  播放页 " + p.length() + " 字，isPlayLink " + pdPlay
                    + "，parseGroups " + pg.size() + " 线路 / " + pt + " 集");
        }
    }

    static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }
}
