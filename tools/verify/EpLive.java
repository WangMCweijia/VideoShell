import com.videoshell.data.model.*;
import com.videoshell.data.net.NetLog;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.util.List;

/**
 * 线上核实：金牌影视详情页的分集名到底长什么样。
 *
 * 用**真实 Http（App 同款 OkHttp）+ 真实 HtmlAdapter.detail()** 打线上页面 ——
 * 离线样本只能防回归，证明不了"用户现在看到的"（v1.0.4 教训）。
 */
public class EpLive {

    static final String BASE = "https://www.bolyship.com";

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        String detailUrl = args.length > 0 ? args[0] : BASE + "/bspvd/548165.html";

        SiteConfig site = new SiteConfig("bs", "金牌影视", BASE, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter a = AdapterFactory.INSTANCE.create(site);
        System.out.println("adapter   = " + a.getClass().getSimpleName());
        System.out.println("detailUrl = " + detailUrl);

        System.out.println("\n---- 实时请求详情页 ----");
        VideoDetail d;
        try {
            d = block((s, c) -> a.detail(detailUrl, (Continuation<? super VideoDetail>) c));
        } catch (Throwable t) {
            System.out.println("!! " + t.getClass().getName() + ": " + t.getMessage());
            System.out.println(NetLog.INSTANCE.report());
            return;
        }

        System.out.println("名称 = " + d.getName());
        System.out.println("线路 = " + d.getGroups().size());
        int bad = 0;
        for (PlayGroup g : d.getGroups()) {
            System.out.println(" · " + g.getName() + "   " + g.getEpisodes().size() + " 集");
            for (int i = 0; i < Math.min(8, g.getEpisodes().size()); i++) {
                String n = g.getEpisodes().get(i).getName();
                System.out.println("       [" + i + "] " + n);
            }
            for (Episode e : g.getEpisodes()) {
                if (e.getName().startsWith(d.getName())) bad++;
            }
        }
        System.out.println("\n带剧名前缀的分集数 = " + bad
                + (bad == 0 ? "   ✅ 干净" : "   ❌ 仍带前缀"));
        System.out.println("\n---- NetLog ----");
        System.out.println(NetLog.INSTANCE.report());
    }
}
