import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.util.*;

/**
 * 按 base 变体走一遍：分类 -> 列表 -> 详情。
 *
 * 动机：站点识别（SiteDetector）产出的 baseUrl 可能是 http / 裸域，
 * 而 categories() 会在 www/裸域/协议之间重试、列表与详情却只认一个地址。
 * 这里逐个变体实测，看哪一种是"分类能出、详情出不来"。
 */
public class VariantLive {

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        String[] bases = {
            "https://www.bolyship.com",
            "https://bolyship.com",
            "http://bolyship.com",
            "http://www.bolyship.com",
        };
        System.out.println("BASE 变体实测：分类 -> 列表 -> 详情\n");
        for (String base : bases) {
            System.out.println("== " + base);
            SiteConfig site = new SiteConfig("bs", "金牌影视", base, "",
                    SiteConfig.MODE_HTML, "", "", 0L);
            try {
                SiteAdapter a = AdapterFactory.INSTANCE.create(site);
                @SuppressWarnings({"unchecked", "rawtypes"})
                List<Category> cats = (List<Category>) block(
                        (s, c) -> a.categories((Continuation<? super List<Category>>) c));
                System.out.println("   分类 " + cats.size());
                if (cats.isEmpty()) { System.out.println("   （分类为空，停）\n"); continue; }

                final Category cat = cats.get(0);
                System.out.println("   进分类 " + cat.getName() + " -> " + cat.getId());
                @SuppressWarnings({"unchecked", "rawtypes"})
                List<VideoItem> list = (List<VideoItem>) block(
                        (s, c) -> a.browse(cat.getId(), 1, (Continuation<? super List<VideoItem>>) c));
                System.out.println("   列表 " + list.size());
                if (list.isEmpty()) { System.out.println("   （列表为空，停）\n"); continue; }

                final String id = list.get(0).getId();
                try {
                    VideoDetail d = (VideoDetail) block(
                            (s, c) -> a.detail(id, (Continuation<? super VideoDetail>) c));
                    int eps = 0;
                    for (PlayGroup g : d.getGroups()) eps += g.getEpisodes().size();
                    System.out.println("   详情 id=" + id + " → OK " + d.getGroups().size()
                            + " 线路 / " + eps + " 集");
                } catch (Throwable t) {
                    System.out.println("   详情 id=" + id + " → ❌ " + t.getMessage());
                }
            } catch (Throwable t) {
                System.out.println("   ❌ 整体失败 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            System.out.println();
        }
    }
}
