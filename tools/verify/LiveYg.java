import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.site.*;

import kotlin.Pair;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.util.List;

/**
 * 野果短剧（capable.fzchosdi.cc）**线上全链路**回归 —— 真网络 + 真 OkHttp + 真 suspend 链路。
 *
 * 离线断言证明的是「判据对不对」，这条证明的是「用户那台机器上这条路走不走得通」：
 *   分类 → 列表 → 详情（分集） → 播放地址 → 媒体请求
 *
 * 这条路径正是用户报「重载站源拿不到列表/分集」的那条路。
 */
public class LiveYg {

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

        // ---- 分类 ----
        banner("[2] 分类");
        List<Category> cats = block((scope, cont) ->
                ad.categories((Continuation<? super List<Category>>) cont));
        System.out.println("分类 " + cats.size() + " 个；前 8：");
        for (int i = 0; i < Math.min(8, cats.size()); i++) {
            System.out.println("    · " + cats.get(i).getName() + "  " + cats.get(i).getId());
        }
        ok("分类 ≥ 10 个（修复前是 5 个页脚功能页）", cats.size() >= 10);
        boolean allTag = !cats.isEmpty();
        for (Category c : cats) if (!c.getId().contains("/tag/")) allTag = false;
        ok("分类全部指向 /tag/", allTag);

        if (cats.isEmpty()) { done(); return; }
        Category first = cats.get(0);

        // ---- 列表 ----
        banner("[3] 列表（" + first.getName() + "）");
        List<VideoItem> items = block((scope, cont) ->
                ad.browse(first.getId(), 1, (Continuation<? super List<VideoItem>>) cont));
        System.out.println("列表 " + items.size() + " 条；前 3：");
        for (int i = 0; i < Math.min(3, items.size()); i++) {
            System.out.println("    · " + items.get(i).getName() + "  id=" + items.get(i).getId());
        }
        ok("列表 > 0 条（修复前点分类是 0 条）", !items.isEmpty());
        if (items.isEmpty()) { done(); return; }

        // ---- 详情 / 分集 ----
        banner("[4] 详情 + 分集");
        VideoItem it = items.get(0);
        VideoDetail d = block((scope, cont) ->
                ad.detail(it.getId(), (Continuation<? super VideoDetail>) cont));
        int epTotal = 0;
        for (PlayGroup g : d.getGroups()) epTotal += g.getEpisodes().size();
        System.out.println("名称 = " + d.getName());
        System.out.println("线路 " + d.getGroups().size() + " 条，共 " + epTotal + " 集");
        for (PlayGroup g : d.getGroups()) {
            System.out.println("    · 线路「" + g.getName() + "」 " + g.getEpisodes().size() + " 集");
            for (int i = 0; i < Math.min(4, g.getEpisodes().size()); i++) {
                Episode e = g.getEpisodes().get(i);
                System.out.println("        " + e.getName() + " -> " + shortUrl(e.getUrl()));
            }
        }
        ok("详情能拿到分集（修复前 0 组）", epTotal >= 1);

        // ---- 播放地址 ----
        banner("[5] 播放地址解析");
        Episode ep = d.getGroups().get(0).getEpisodes().get(
                d.getGroups().get(0).getEpisodes().size() - 1);   // 取最后一集，最能暴露"只认第一集"的问题
        System.out.println("取最后一集：" + ep.getName());
        MediaSource ms = block((scope, cont) ->
                ad.resolve(ep, (Continuation<? super MediaSource>) cont));
        System.out.println("解析结果 = " + ms.getClass().getSimpleName());
        String mediaUrl = null;
        if (ms instanceof MediaSource.Direct) {
            mediaUrl = ((MediaSource.Direct) ms).getUrl();
        } else if (ms instanceof MediaSource.Sniff) {
            mediaUrl = ((MediaSource.Sniff) ms).getPageUrl();
        } else if (ms instanceof MediaSource.Error) {
            System.out.println("错误 = " + ((MediaSource.Error) ms).getMessage());
        }
        System.out.println("地址 = " + shortUrl(mediaUrl));
        ok("播放地址解析成直链（不是嗅探兜底）", ms instanceof MediaSource.Direct);

        final String mUrl = mediaUrl;
        if (mUrl != null) {
            Pair<Integer, String> pr = block((scope, cont) ->
                    Http.INSTANCE.probe(mUrl, BASE, null,
                            (Continuation<? super Pair<Integer, String>>) cont));
            System.out.println("[6] 媒体请求 HTTP " + (pr == null ? "null" : pr.getFirst())
                    + "  " + (pr == null ? "" : pr.getSecond()));
            ok("媒体请求 200/206", pr != null && (pr.getFirst() == 200 || pr.getFirst() == 206));

            // 分片：真取一段
            String playlist = block((scope, cont) ->
                    Http.INSTANCE.getPlaylistOnce(mUrl, BASE,
                            (Continuation<? super String>) cont));
            System.out.println("[7] playlist 长度 = " + (playlist == null ? 0 : playlist.length()));
            ok("playlist 能下下来且非空", playlist != null && playlist.contains("#EXTM3U"));
        }

        done();
    }

    static String shortUrl(String u) {
        if (u == null) return "null";
        return u.length() <= 96 ? u : u.substring(0, 96) + "…";
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

    static void done() {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  PASS=" + pass + "  FAIL=" + fail);
        System.out.println("================================================================");
        System.exit(fail == 0 ? 0 : 1);
    }
}
