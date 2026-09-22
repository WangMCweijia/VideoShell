import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.site.*;

import kotlin.Pair;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.util.*;

/**
 * 封面（海报）加载 **线上全链路**回归 —— 真网络 + 真 OkHttp + 真 suspend 链路。
 *
 * 离线断言（`Pic.java`）证明的是「判据对不对」；这条证明的是
 * **「用户那台机器上封面真的出得来」**：
 *   分类 → 列表（统计有封面条数） → 抽查封面真实可达性 → 详情封面
 *
 * 走的是真适配器（`AdapterFactory.create` → `HtmlAdapter`），与 App 里同一条链路。
 */
public class LivePic {

    static int pass = 0, fail = 0;
    static final String BASE = "https://capable.fzchosdi.cc";

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        RecipeStore.INSTANCE.clear(BASE);
        SiteConfig site = new SiteConfig("yg", "野果短剧", BASE, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter ad = AdapterFactory.INSTANCE.create(site);
        System.out.println("适配器 = " + ad.getClass().getSimpleName());

        banner("[2] 分类");
        List<Category> cats = block((scope, cont) ->
                ad.categories((Continuation<? super List<Category>>) cont));
        ok("分类非空", !cats.isEmpty());
        if (cats.isEmpty()) { done(); return; }
        Category first = cats.get(0);
        System.out.println("  用第一个分类：" + first.getName() + "  " + first.getId());

        banner("[3] 列表封面统计（修复前：0 条有封面）");
        List<VideoItem> items = block((scope, cont) ->
                ad.browse(first.getId(), 1, (Continuation<? super List<VideoItem>>) cont));
        int withPic = 0;
        boolean allHttp = true, picHost = true;
        for (VideoItem v : items) {
            if (!v.getPic().isBlank()) withPic++;
            if (!v.getPic().startsWith("http")) allHttp = false;
            if (!v.getPic().contains("pic.")) picHost = false;
        }
        System.out.println("  列表 " + items.size() + " 条，其中 " + withPic + " 条有封面；前 4 条：");
        for (int i = 0; i < Math.min(4, items.size()); i++) {
            System.out.println("      · " + items.get(i).getName() + "   " + shortUrl(items.get(i).getPic()));
        }
        ok("列表非空", !items.isEmpty());
        eq("每条都有封面", items.size(), withPic);
        ok("封面都是绝对 http 地址", allHttp);
        ok("封面都指向图床（pic.*）", picHost);

        banner("[4] 封面真实可达性（真取图，不是只看头像）");
        int n = Math.min(3, items.size()), good = 0;
        for (int i = 0; i < n; i++) {
            String u = items.get(i).getPic();
            Pair<Integer, String> r = block((scope, cont) ->
                    Http.INSTANCE.probe(u, BASE, null,
                            (Continuation<? super Pair<Integer, String>>) cont));
            int code = (r == null) ? -1 : r.getFirst();
            System.out.println("      HTTP " + code + "  " + shortUrl(u));
            if (code == 200 || code == 206) good++;
        }
        eq("抽查封面全部 200/206", n, good);

        banner("[5] 详情页封面");
        VideoDetail d = block((scope, cont) ->
                ad.detail(items.get(0).getId(), (Continuation<? super VideoDetail>) cont));
        System.out.println("  详情封面 = " + d.getPic());
        ok("详情有封面", d.getPic() != null && !d.getPic().isBlank());
        ok("详情封面不是站点默认分享图（social-default）",
                d.getPic() == null || !d.getPic().contains("social-default"));

        done();
    }

    // ---------------------------------------------------------------- 工具

    static String shortUrl(String u) {
        if (u == null) return "null";
        return u.length() <= 88 ? u : u.substring(0, 88) + "…";
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  " + s);
        System.out.println("================================================================");
    }

    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + what); }
        else { fail++; System.out.println("  [FAIL] " + what); }
    }

    static void eq(String what, Object expect, Object got) {
        ok(what + "（期望 " + expect + "，得到 " + got + "）",
                String.valueOf(expect).equals(String.valueOf(got)));
    }

    static void done() {
        System.out.println();
        System.out.println("========================================================");
        System.out.println("  PASS=" + pass + "  FAIL=" + fail);
        System.out.println("========================================================");
        System.exit(fail == 0 ? 0 : 1);
    }
}
