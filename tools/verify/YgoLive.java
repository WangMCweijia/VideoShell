import com.videoshell.data.model.*;
import com.videoshell.data.net.Http;
import com.videoshell.data.net.NetLog;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 线上端到端：野果加密接口适配器（真 Http + 真 suspend 链路）。
 *
 * 与离线 `runygo` 的分工：`runygo` 用**存下来的密文夹具**守住解密与字段映射（不联网、能天天跑）；
 * 这里补的是"密文夹具证明不了的东西"——密钥是否还有效、接口参数是否被站方改过、
 * 伪地址在播放前一刻换来的真链到底能不能下。
 */
public class YgoLive {

    static int pass = 0, fail = 0;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        SiteConfig site = new SiteConfig("yg", "野果短剧", "https://www.yeguodj.com", "",
                SiteConfig.MODE_HTML, "", "", 0L);

        banner("白名单命中");
        CryptRecipe r = CryptRecipes.INSTANCE.forHost("www.yeguodj.com");
        ok("forHost 命中配方", r != null);
        YeguoAdapter a = new YeguoAdapter(site, r);

        banner("分类（contentOptions，走加密接口）");
        List<Category> cats = block((s, c) -> a.categories((Continuation<? super List<Category>>) c));
        ok("categories() > 25（实际 " + cats.size() + "）", cats.size() > 25);
        ok("首项「最新」", !cats.isEmpty() && "最新".equals(cats.get(0).getName()));
        ok("次项「热播」", cats.size() > 1 && "热播".equals(cats.get(1).getName()));
        boolean hasTag = false;
        for (Category c : cats) if ("tag:都市".equals(c.getId())) hasTag = true;
        ok("含 tag:都市", hasTag);
        System.out.println("  分类：" + cats.size() + " 个，diag=[" + a.getLastDiag() + "]");

        banner("浏览");
        List<VideoItem> latest = block((s, c) -> a.browse("@latest", 1,
                (Continuation<? super List<VideoItem>>) c));
        ok("browse(@latest) = 20（实际 " + latest.size() + "）", latest.size() == 20);
        ok("每条都有封面", !latest.isEmpty() && allHave(latest, true));
        ok("每条都有名字", !latest.isEmpty() && allHave(latest, false));

        List<VideoItem> tagList = block((s, c) -> a.browse("tag:都市", 1,
                (Continuation<? super List<VideoItem>>) c));
        ok("browse(tag:都市) 非空（实际 " + tagList.size() + "）", !tagList.isEmpty());
        // 注意：不能拿"首条不同"当判据 —— 探索页与标签页都按更新时间排，
        // 最新那条本来就同时是「都市」的第一条（实测就是如此）。要比的是**整批是不是同一批**。
        boolean sameBatch = true;
        if (tagList.size() != latest.size()) sameBatch = false;
        else {
            Set<String> ls = new HashSet<>();
            for (VideoItem v : latest) ls.add(v.getId());
            for (VideoItem v : tagList) if (!ls.contains(v.getId())) sameBatch = false;
        }
        ok("标签筛选与「最新」是两批不同内容（不能整批相同）", !sameBatch);

        List<VideoItem> rank = block((s, c) -> a.browse("@rank", 1,
                (Continuation<? super List<VideoItem>>) c));
        ok("browse(@rank) 非空（实际 " + rank.size() + "）", !rank.isEmpty());

        List<VideoItem> page2 = block((s, c) -> a.browse("@latest", 2,
                (Continuation<? super List<VideoItem>>) c));
        ok("browse(@latest,page2) 与第 1 页不同批",
                !page2.isEmpty() && !latest.isEmpty()
                        && !page2.get(0).getId().equals(latest.get(0).getId()));

        banner("搜索（这是本项修复的核心：以前「搜什么都一样」）");
        List<VideoItem> s1 = block((s, c) -> a.search("爱", 1,
                (Continuation<? super List<VideoItem>>) c));
        ok("search(爱) = 20（实际 " + s1.size() + "）", s1.size() == 20);
        ok("首条标题含「爱」", !s1.isEmpty() && s1.get(0).getName().contains("爱"));
        ok("每条都有封面", !s1.isEmpty() && allHave(s1, true));
        List<VideoItem> s2 = block((s, c) -> a.search("爱", 2,
                (Continuation<? super List<VideoItem>>) c));
        ok("search 第 2 页与第 1 页不同批",
                !s2.isEmpty() && !s1.isEmpty()
                        && !s2.get(0).getId().equals(s1.get(0).getId()));
        List<VideoItem> sNone = block((s, c) -> a.search("", 1,
                (Continuation<? super List<VideoItem>>) c));
        ok("空关键词 -> 空表（不打接口）", sNone.isEmpty());

        // 剧集 tab 是宽匹配：演员名 / 标签也能搜到（这是"搜人名搜得到"的依据，
        // 也是壳里不额外查 tab=actor 的原因 —— 详见 YeguoAdapter.search 的注释）
        List<VideoItem> sActor = block((s, c) -> a.search("木君", 1,
                (Continuation<? super List<VideoItem>>) c));
        ok("search(演员名 木君) 非空（剧集 tab 已覆盖演员名，实际 " + sActor.size() + "）",
                !sActor.isEmpty());
        ok("搜人名返回的是可点影片（有 id 与剧名）",
                !sActor.isEmpty() && !sActor.get(0).getId().isEmpty()
                        && !sActor.get(0).getName().isEmpty());

        // 命中来源角标：站点搜索是宽匹配，标签词搜索会返回"片名一个都不含关键词"的列表。
        // 离线夹具（runygo 段 K）钉住的是**当时的**响应；这条线上断言保证
        // `matched_fields` 这个字段现在还在 —— 站点哪天不发了，角标会静默消失，这里先喊出来。
        List<VideoItem> sTag = block((s, c) -> a.search("甜宠", 1,
                (Continuation<? super List<VideoItem>>) c));
        ok("search(标签词 甜宠) 非空（实际 " + sTag.size() + "）", !sTag.isEmpty());
        int noTitle = 0, hinted = 0;
        for (VideoItem v : sTag) {
            if (!v.getName().contains("甜宠")) noTitle++;
            if (v.getRemarks() != null && v.getRemarks().startsWith("命中")) hinted++;
        }
        ok("标签词搜索确实存在非标题命中（角标有东西可标，实际 " + noTitle + " 条）", noTitle > 0);
        ok("每个非标题命中都带上了命中角标（实际 " + hinted + "/" + noTitle
                        + "）—— 掉了说明站点的 matched_fields 没了",
                noTitle == hinted);

        banner("详情 / 分集 / 伪地址");
        VideoItem first = latest.get(0);
        VideoDetail d = block((s, c) -> a.detail(first.getId(),
                (Continuation<? super VideoDetail>) c));
        ok("detail 有线路（" + d.getGroups().size() + "）", !d.getGroups().isEmpty());
        ok("detail 名字非空", !d.getName().isEmpty());
        ok("detail 封面非空", !d.getPic().isEmpty());
        List<Episode> eps = d.getGroups().get(0).getEpisodes();
        ok("分集 > 0（实际 " + eps.size() + "）", !eps.isEmpty());
        boolean allPseudo = true, namesOk = true;
        Set<String> us = new HashSet<>();
        boolean dup = false;
        for (Episode e : eps) {
            if (!PseudoPlayUrl.INSTANCE.isPseudo(e.getUrl())) allPseudo = false;
            if (e.getName() == null || e.getName().trim().isEmpty()) namesOk = false;
            if (!us.add(e.getUrl())) dup = true;
        }
        ok("分集地址全是伪地址（真链播放时现取）", allPseudo);
        ok("分集都有集名", namesOk);
        ok("分集地址不重复", !dup);

        banner("播放：伪地址 -> 现取真链 -> 真下 512KB");
        MediaSource ms = block((s, c) -> a.resolve(eps.get(0), (Continuation<? super MediaSource>) c));
        ok("resolve 返回 Direct（" + ms.getClass().getSimpleName() + "）", ms instanceof MediaSource.Direct);
        if (ms instanceof MediaSource.Direct) {
            MediaSource.Direct dir = (MediaSource.Direct) ms;
            ok("地址是 m3u8", dir.getUrl().contains(".m3u8"));
            ok("isHls 标记为真", dir.isHls());
            ok("带了 Referer/UA（防盗链）", dir.getHeaders() != null && !dir.getHeaders().isEmpty());
            kotlin.Pair p = block((s, c) -> Http.INSTANCE.probe(dir.getUrl(),
                    site.getBaseUrl(), null, (Continuation<? super kotlin.Pair>) c));
            int code = ((Number) p.getFirst()).intValue();
            ok("清单探测 HTTP " + code, code == 200 || code == 206);

            // ★ 真下分片：清单本身只有几十 KB（那是文本），拿它测速等于没测。
            //   必须解开清单、挑一个分片去下 —— 这才是"能不能播"的真凭据。
            String seg = firstSegment(dir.getUrl(), site.getBaseUrl());
            ok("从清单里取到了分片地址", seg != null && !seg.isEmpty());
            if (seg != null) {
                kotlin.Triple t = block((s, c) -> Http.INSTANCE.sample(seg,
                        site.getBaseUrl(), 512L * 1024L, (Continuation<? super kotlin.Triple>) c));
                int sc = ((Number) t.getFirst()).intValue();
                long bytes = ((Number) t.getSecond()).longValue();
                long ms2 = ((Number) t.getThird()).longValue();
                ok("真下分片 " + bytes + " 字节，耗时 " + ms2 + "ms（HTTP " + sc + "）",
                        bytes > 100_000);
            }
        }

        banner("再换一集（验证每次播放都重新签 key，不依赖上次的链接）");
        if (eps.size() > 1) {
            MediaSource ms2 = block((s, c) -> a.resolve(eps.get(1),
                    (Continuation<? super MediaSource>) c));
            boolean diff = ms2 instanceof MediaSource.Direct
                    && !((MediaSource.Direct) ms2).getUrl().equals(((MediaSource.Direct) ms).getUrl());
            ok("第 2 集拿到的是另一条链", diff);
        } else {
            ok("本剧只有 1 集（跳过）", true);
        }

        banner("诊断");
        System.out.println("  lastDiag = [" + a.getLastDiag() + "]");
        System.out.println("  NetLog.lastFailure = [" + NetLog.INSTANCE.lastFailure() + "]");

        System.out.println("\n" + "=".repeat(72));
        System.out.println(fail == 0 ? ("YgoLive ALL PASSED  (pass=" + pass + ")")
                : ("YgoLive FAILED  pass=" + pass + " fail=" + fail));
        System.out.println("=".repeat(72));
        if (fail != 0) System.exit(1);
    }

    /**
     * 从 m3u8 清单里取第一个**分片**地址。
     * 主清单（master）里躺着的是子清单（子清单 URI 也以 .m3u8 结尾），往下钻一层。
     */
    static String firstSegment(String playlistUrl, String referer) throws Exception {
        String pl = block((s, c) -> Http.INSTANCE.getPlaylistOnce(playlistUrl, referer,
                (Continuation<? super String>) c));
        String uri = pickUri(pl);
        if (uri == null) return null;
        if (uri.contains(".m3u8")) {
            String variant = absolute(uri, playlistUrl);
            String pl2 = block((s, c) -> Http.INSTANCE.getPlaylistOnce(variant, referer,
                    (Continuation<? super String>) c));
            String uri2 = pickUri(pl2);
            return uri2 == null ? null : absolute(uri2, variant);
        }
        return absolute(uri, playlistUrl);
    }

    /** 清单里第一条非注释、非空行 */
    static String pickUri(String playlist) {
        if (playlist == null) return null;
        for (String ln : playlist.split("\n")) {
            String t = ln.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            return t;
        }
        return null;
    }

    /** 相对地址 -> 绝对地址（按 base 的目录拼） */
    static String absolute(String uri, String base) {
        if (uri.startsWith("http")) return uri;
        if (uri.startsWith("//")) return "https:" + uri;
        int i = base.indexOf("://");
        int hostEnd = base.indexOf('/', i + 3);
        String origin = hostEnd < 0 ? base : base.substring(0, hostEnd);
        if (uri.startsWith("/")) return origin + uri;
        String dir = hostEnd < 0 ? base + "/" : base.substring(0, hostEnd + 1);
        return dir + uri;
    }

    static boolean allHave(List<VideoItem> l, boolean needPic) {
        for (VideoItem v : l) {
            if (needPic && (v.getPic() == null || v.getPic().isEmpty())) return false;
            if (!needPic && (v.getName() == null || v.getName().isEmpty())) return false;
        }
        return true;
    }

    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + what); }
        else { fail++; System.out.println("  [FAIL] " + what); }
    }

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(72));
        System.out.println(s);
        System.out.println("=".repeat(72));
    }
}
