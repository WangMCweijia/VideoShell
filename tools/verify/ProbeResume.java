import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.util.*;

/**
 * 诊断：**播放进度记忆串台**。
 *
 * App 里的 key 是 `resume_${url.hashCode()}`，其中 url 是 `Media.encodeUrl(媒体直链)`
 * （见 `PlayerActivity.resumeKey`）。本诊断沿真实链路走一遍：
 *
 *   分类 → 列表 → 取 **两部不同影片** → 各自 detail → 逐集 resolve
 *   → 打印「媒体地址 + App 会用的 resume key」
 *   → 检查：① 不同影片的地址是否相同（相同 ⇒ key 必串）
 *             ② resume key 是否真的重复
 *             ③ 同一集重复 resolve 两次，地址是否稳定（带 auth_key 时效签名 ⇒ 每次不同 ⇒ 进度会丢）
 */
public class ProbeResume {

    static final String SITE = System.getProperty("site", "https://capable.fzchosdi.cc");

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        SiteConfig site = new SiteConfig("probe", "probe", SITE, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter ad = AdapterFactory.INSTANCE.create(site);
        System.out.println("站点 " + SITE + "   适配器 " + ad.getClass().getSimpleName());

        List<Category> cats = block((scope, cont) ->
                ad.categories((Continuation<? super List<Category>>) cont));
        System.out.println("分类 " + cats.size() + " 个");

        // 收集若干「详情能解析出来」的影片（detail 失败的跳过，不中断）
        List<VideoDetail> picks = new ArrayList<>();
        outer:
        for (Category c : cats) {
            List<VideoItem> items = block((scope, cont) ->
                    ad.browse(c.getId(), 1, (Continuation<? super List<VideoItem>>) cont));
            for (VideoItem v : items) {
                if (picks.size() >= 3) break outer;
                boolean dup = false;
                for (VideoDetail p : picks) if (p.getId().equals(v.getId())) dup = true;
                if (dup) continue;
                VideoDetail d = null;
                try {
                    d = block((scope, cont) ->
                            ad.detail(v.getId(), (Continuation<? super VideoDetail>) cont));
                } catch (Exception e) {
                    continue;
                }
                if (d != null && !d.getGroups().isEmpty()) picks.add(d);
            }
        }
        System.out.println("可用样本影片 " + picks.size() + " 部");

        Map<String, List<String>> byKey = new LinkedHashMap<>();
        Map<String, List<String>> byUrl = new LinkedHashMap<>();

        for (VideoDetail d : picks) {
            System.out.println();
            System.out.println("==================================================================");
            System.out.println("  影片 [" + d.getId() + "] " + d.getName());
            System.out.println("==================================================================");
            PlayGroup g = d.getGroups().get(0);
            System.out.println("  线路「" + g.getName() + "」共 " + g.getEpisodes().size() + " 集");

            for (int i = 0; i < Math.min(3, g.getEpisodes().size()); i++) {
                Episode ep = g.getEpisodes().get(i);
                MediaSource ms = block((scope, cont) ->
                        ad.resolve(ep, (Continuation<? super MediaSource>) cont));
                String media = (ms instanceof MediaSource.Direct) ? ((MediaSource.Direct) ms).getUrl()
                        : (ms instanceof MediaSource.Sniff) ? "<嗅探 " + ((MediaSource.Sniff) ms).getPageUrl() + ">"
                        : "<" + ms.getClass().getSimpleName() + ">";
                String key = "resume_" + jhash(media);
                System.out.println("    " + ep.getName());
                System.out.println("       分集url: " + shortUrl(ep.getUrl()));
                System.out.println("       媒体址 : " + shortUrl(media));
                System.out.println("       key    : " + key);
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(d.getName() + " " + ep.getName());
                byUrl.computeIfAbsent(stripQuery(media), k -> new ArrayList<>()).add(d.getName() + " " + ep.getName());
            }
        }

        System.out.println();
        System.out.println("==================== 结论 ====================");
        System.out.println("① resume key 重复的：");
        boolean any = false;
        for (Map.Entry<String, List<String>> e : byKey.entrySet()) {
            if (e.getValue().size() > 1) { any = true; System.out.println("   " + e.getKey() + " -> " + e.getValue()); }
        }
        if (!any) System.out.println("   （无）");

        System.out.println("② 去掉 query 后**地址本身**重复的（会必然串台）：");
        any = false;
        for (Map.Entry<String, List<String>> e : byUrl.entrySet()) {
            if (e.getValue().size() > 1) { any = true; System.out.println("   " + shortUrl(e.getKey()) + " -> " + e.getValue()); }
        }
        if (!any) System.out.println("   （无）");
    }

    static int jhash(String s) {
        if (s == null) return 0;
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }

    static String stripQuery(String u) {
        if (u == null) return "";
        int i = u.indexOf('?');
        return i < 0 ? u : u.substring(0, i);
    }

    static String shortUrl(String u) {
        if (u == null) return "null";
        if (u.length() <= 96) return u;
        return u.substring(0, 60) + "…" + u.substring(u.length() - 34);
    }
}
