import com.videoshell.data.model.*;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.util.*;

/** 野果：detail() 的集名到底是不是空的 —— 空 ⇒ episodeKey 全同 ⇒ 进度照样串台 */
public class ProbeYaguoDetail {

    static final String SITE = "https://capable.fzchosdi.cc";

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        SiteConfig site = new SiteConfig("yg", "yg", SITE, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter ad = AdapterFactory.INSTANCE.create(site);

        // 3379 = 庆余年 第三季（探针里第一台）
        VideoDetail d = block((scope, cont) ->
                ad.detail(args.length>0?args[0]:"3237", (Continuation<? super VideoDetail>) cont));
        if (d == null) { System.out.println("detail 返回 null"); return; }
        System.out.println("剧名: " + d.getName() + "   线路数: " + d.getGroups().size());
        int gi = 0;
        for (PlayGroup g : d.getGroups()) {
            List<Episode> eps = g.getEpisodes();
            System.out.println("  线路[" + gi++ + "] name=" + g.getName() + "  集数=" + eps.size());
            for (int i = 0; i < Math.min(5, eps.size()); i++)
                System.out.println("      · name=[" + eps.get(i).getName() + "]  " + eps.get(i).getUrl());
        }
    }
}
