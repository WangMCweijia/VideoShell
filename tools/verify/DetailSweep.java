import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.site.*;
import com.videoshell.util.ExtKt;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.*;

/**
 * 批量筛查：跨栏目抽查多部影片，看 detail() 在哪些影片上拿不到分集。
 *
 * 动机：用户报「校准后重载站源，获取不到分集列表」。配方矩阵已证明配方本身没问题
 * （10 种组合全 OK），所以嫌疑转向「具体影片的详情页结构不同」。这个 harness 把
 * 各栏目的前几部都过一遍，找出失败样本 —— 一旦找到，就是可以离线复现的真 bug。
 */
public class DetailSweep {

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

    public static void main(String[] args) throws Exception {
        System.out.println("BASE = " + BASE);

        // 先拿分类
        SiteConfig site = new SiteConfig("bs", "金牌影视", BASE, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter a = AdapterFactory.INSTANCE.create(site);

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Category> cats = (List<Category>) block(
                (s, c) -> a.categories((Continuation<? super List<Category>>) c));
        System.out.println("分类 " + cats.size() + " 个");

        // 只看主栏目（名字短的），每个栏目取前 6 部
        int ok = 0, bad = 0;
        List<String> fails = new ArrayList<>();

        for (Category cat : cats) {
            if (cat.getName().length() > 4) continue;          // 只挑主栏目，子分类太多
            final String cid = cat.getId();
            @SuppressWarnings({"unchecked", "rawtypes"})
            List<VideoItem> list = (List<VideoItem>) block(
                    (s, c) -> a.browse(cid, 1, (Continuation<? super List<VideoItem>>) c));
            int take = Math.min(6, list.size());
            System.out.println("\n== " + cat.getName() + "  " + list.size() + " 张，抽 " + take);

            for (int i = 0; i < take; i++) {
                VideoItem it = list.get(i);
                final String vid = it.getId();
                String res;
                try {
                    VideoDetail d = (VideoDetail) block(
                            (s, c) -> a.detail(vid, (Continuation<? super VideoDetail>) c));
                    int eps = 0;
                    for (PlayGroup g : d.getGroups()) eps += g.getEpisodes().size();
                    res = "OK " + d.getGroups().size() + "线路/" + eps + "集";
                    ok++;
                } catch (Throwable t) {
                    res = "❌ " + t.getClass().getSimpleName();
                    bad++;
                    fails.add(cat.getName() + " | " + it.getId() + " | " + it.getName()
                            + " | " + t.getMessage());
                }
                System.out.println("   [" + it.getId() + "] " + pad(it.getName(), 12) + res);
            }
        }

        System.out.println("\n================ 汇总 ================");
        System.out.println("成功 " + ok + "，失败 " + bad);
        for (String f : fails) System.out.println("  FAIL " + f);
    }

    static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }
}
