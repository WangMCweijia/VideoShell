import com.videoshell.data.model.*;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.io.*;
import java.util.*;

/** 骚火电影：detail() 分组实测 + 原始 HTML 落盘，看两条播放源为什么融成一个列表 */
public class ProbeShdy {

    static final String SITE = "https://shdy5.us";

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    static String get(String url) throws Exception {
        return block((scope, cont) -> com.videoshell.data.net.Http.INSTANCE
                .getOrNull(url, SITE, com.videoshell.data.net.Http.UA, false,
                        (Continuation<? super String>) cont));
    }

    public static void main(String[] args) throws Exception {
        // 50323 的播放页样例是 -1-26 ⇒ 应是一部 26 集的剧
        for (String id : new String[]{"50323", "50347"}) {
            String html = get(SITE + "/movie/" + id + ".html");
            System.out.println("== detail " + id + " 抓取 " + (html == null ? "失败" : html.length() + " 字节"));
            if (html != null) {
                try (Writer w = new OutputStreamWriter(
                        new FileOutputStream("_shdy_detail_" + id + ".html"), "UTF-8")) {
                    w.write(html);
                }
                int src2 = count(html, "-2-");
                int src1 = count(html, "-1-");
                System.out.println("   \"-1-\" 出现 " + src1 + "  \"-2-\" 出现 " + src2);
            }
        }

        SiteConfig site = new SiteConfig("shdy", "shdy", SITE, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter ad = AdapterFactory.INSTANCE.create(site);
        System.out.println("适配器 " + ad.getClass().getSimpleName());

        VideoDetail d = block((scope, cont) ->
                ad.detail("50323", (Continuation<? super VideoDetail>) cont));
        if (d == null) { System.out.println("detail(50323) 返回 null"); return; }
        System.out.println("剧名: " + d.getName() + "   线路数: " + d.getGroups().size());
        int gi = 0;
        for (PlayGroup g : d.getGroups()) {
            List<Episode> eps = g.getEpisodes();
            System.out.println("  线路[" + gi++ + "] name=" + g.getName()
                    + "  集数=" + eps.size());
            for (int i = 0; i < Math.min(4, eps.size()); i++)
                System.out.println("      · " + eps.get(i).getName() + "  " + eps.get(i).getUrl());
            if (eps.size() > 4) System.out.println("      … 共 " + eps.size() + " 集");
        }
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
