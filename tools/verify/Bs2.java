import com.videoshell.data.model.*;
import com.videoshell.data.net.NetLog;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.util.List;

/**
 * 金牌影视（bolyship.com）**真实链路**诊断：
 * 走真实 Http + 真实 suspend 调用，打印每一步**实际请求的 URL**（靠 NetLog）。
 *
 * 为什么要有它：v1.0.10 的 Bs.java 是「喂本地 HTML 样本」——
 * 它绕过了「拼 URL」这一环（直接 doc(dir+"detail.html")）。
 * 而真机上详情失败往往就败在拼接/模板学习上。这里把那一环补上。
 */
public class Bs2 {

    static final String BASE = "https://www.bolyship.com";

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : BASE;

        SiteConfig site = new SiteConfig("bs", "金牌影视", base, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter a = AdapterFactory.INSTANCE.create(site);
        banner("adapter = " + a.getClass().getSimpleName());

        banner("[1] categories()");
        List<Category> cats = block((s, c) -> a.categories((Continuation<? super List<Category>>) c));
        System.out.println("  分类数 = " + cats.size());
        for (int i = 0; i < Math.min(8, cats.size()); i++) {
            System.out.println("   · " + cats.get(i).getName() + "   -> " + cats.get(i).getId());
        }

        banner("[2] browse(首页 / 最新)");
        List<VideoItem> l0 = block((s, c) -> a.browse("", 1, (Continuation<? super List<VideoItem>>) c));
        System.out.println("  条数 = " + l0.size());
        for (int i = 0; i < Math.min(6, l0.size()); i++) {
            System.out.println("   · id=" + l0.get(i).getId() + "   " + l0.get(i).getName());
        }

        banner("[3] browse(第一个分类, 1)");
        if (!cats.isEmpty()) {
            List<VideoItem> l1 = block((s, c) ->
                    a.browse(cats.get(0).getId(), 1, (Continuation<? super List<VideoItem>>) c));
            System.out.println("  typeId = " + cats.get(0).getId());
            System.out.println("  条数 = " + l1.size());
            for (int i = 0; i < Math.min(6, l1.size()); i++) {
                System.out.println("   · id=" + l1.get(i).getId() + "   " + l1.get(i).getName());
            }
        }

        banner("[4] detail(首页第一条)");
        if (!l0.isEmpty()) {
            String id = l0.get(0).getId();
            System.out.println("  id = " + id);
            try {
                VideoDetail d = block((s, c) ->
                        a.detail(id, (Continuation<? super VideoDetail>) c));
                System.out.println("  名称 = " + d.getName());
                System.out.println("  线路 = " + d.getGroups().size());
                for (PlayGroup g : d.getGroups()) {
                    System.out.println("   · " + g.getName() + "  " + g.getEpisodes().size() + " 集");
                    if (!g.getEpisodes().isEmpty()) {
                        System.out.println("       首集 " + g.getEpisodes().get(0).getName()
                                + " -> " + g.getEpisodes().get(0).getUrl());
                    }
                }
            } catch (Throwable t) {
                System.out.println("  !! " + t.getClass().getName() + ": " + t.getMessage());
            }
        }

        banner("[5] detail(分类第一条) —— 模拟「点分类进列表再进详情」");
        if (!cats.isEmpty()) {
            List<VideoItem> l1 = block((s, c) ->
                    a.browse(cats.get(0).getId(), 1, (Continuation<? super List<VideoItem>>) c));
            if (!l1.isEmpty()) {
                String id = l1.get(0).getId();
                System.out.println("  id = " + id);
                try {
                    VideoDetail d = block((s, c) ->
                            a.detail(id, (Continuation<? super VideoDetail>) c));
                    System.out.println("  名称 = " + d.getName());
                    System.out.println("  线路 = " + d.getGroups().size());
                    for (PlayGroup g : d.getGroups()) {
                        System.out.println("   · " + g.getName() + "  " + g.getEpisodes().size() + " 集");
                    }
                } catch (Throwable t) {
                    System.out.println("  !! " + t.getClass().getName() + ": " + t.getMessage());
                }
            }
        }

        banner("[6] NetLog：真实请求轨迹");
        System.out.println(NetLog.INSTANCE.report());
    }

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(72));
        System.out.println(s);
        System.out.println("=".repeat(72));
    }
}
