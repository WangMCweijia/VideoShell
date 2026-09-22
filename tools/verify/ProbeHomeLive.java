import com.videoshell.data.model.*;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.util.*;

/**
 * 诊断：**App 默认入口（「最新」= `browse("")` = 站点首页）** 的列表与封面。
 *
 * `SiteActivity.onCreate → loadCategories → onCategory(0, Category("", "最新"))`
 * ⇒ `currentType = ""` ⇒ `browseUrls("")` 返回 `listOf(site.baseUrl)` ⇒ **抓首页**。
 *
 * 本诊断把「首页」与「真分类页」两条路并排跑一遍，用同一份真网络 + 真解析代码，
 * 直接对比：条数 / 有封面条数 / 前几条的名字与封面。
 */
public class ProbeHomeLive {

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        SiteConfig site = new SiteConfig("probe", "probe", ProbeResume.SITE, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter ad = AdapterFactory.INSTANCE.create(site);
        System.out.println("站点 " + site.getBaseUrl() + "   适配器 " + ad.getClass().getSimpleName());

        report("【App 默认入口】browse(\"\")  ← 「最新」tab，实际抓的是站点首页", ad, "");
        rawHome(site.getBaseUrl());
        List<Category> cats = block((scope, cont) ->
                ad.categories((Continuation<? super List<Category>>) cont));
        if (!cats.isEmpty()) {
            Category c = cats.get(0);
            for (Category x : cats) if (!isFuncSlug(x.getId())) { c = x; break; }
            report("【真分类页】browse(\"" + c.getId() + "\")", ad, c.getId());
            rawHome(c.getId());
        }
    }

    /** 原始 HTML 里到底有没有封面 / 剧名数据 */
    static void rawHome(String url) throws Exception {
        String html = block((scope, cont) -> com.videoshell.data.net.Http.INSTANCE
                .getOrNull(url, ProbeResume.SITE, com.videoshell.data.net.Http.UA, false,
                        (Continuation<? super String>) cont));
        System.out.println();
        System.out.println("  ---- 原始页分析 " + url);
        if (html == null) { System.out.println("      抓取失败"); return; }
        System.out.println("      字节 " + html.length());
        System.out.println("      pic.ndhixj.cn 出现      : " + count(html, "pic.ndhixj.cn"));
        System.out.println("      \".jpeg\" 出现           : " + count(html, ".jpeg"));
        System.out.println("      \"cover\" 出现           : " + count(html, "cover"));
        System.out.println("      \"img_base\" 出现        : " + count(html, "img_base"));
        System.out.println("      data:image/gif 占位数   : " + count(html, "data:image/gif"));
        System.out.println("      aria-label=\"查看剧集\"   : " + count(html, "aria-label=\"查看剧集\""));
        System.out.println("      __NUXT_DATA__ 存在      : " + html.contains("__NUXT_DATA__"));
        int i = html.indexOf("img_base");
        if (i >= 0) System.out.println("      img_base 片段: " + clip(html.substring(Math.max(0, i - 60),
                Math.min(html.length(), i + 160))));
        int j = html.indexOf("pic.ndhixj.cn");
        if (j >= 0) System.out.println("      首个图床片段: " + clip(html.substring(Math.max(0, j - 120),
                Math.min(html.length(), j + 80))));
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    static String clip(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= 260 ? s : s.substring(0, 260) + "…";
    }

    static void report(String banner, SiteAdapter ad, String typeId) throws Exception {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  " + banner);
        System.out.println("================================================================");
        List<VideoItem> items = block((scope, cont) ->
                ad.browse(typeId, 1, (Continuation<? super List<VideoItem>>) cont));
        int withPic = 0;
        for (VideoItem v : items) if (!v.getPic().isBlank()) withPic++;
        System.out.println("  条数 " + items.size() + "，有封面 " + withPic + " 条");
        for (int i = 0; i < Math.min(6, items.size()); i++) {
            VideoItem v = items.get(i);
            System.out.println("      · [" + v.getId() + "] " + v.getName()
                    + "   pic=" + (v.getPic().isBlank() ? "<空>" : shortUrl(v.getPic())));
        }
    }

    static boolean isFuncSlug(String id) {
        String s = id.toLowerCase();
        for (String k : new String[]{"contact", "about", "search", "faq", "privacy", "terms", "help"}) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    static String shortUrl(String u) {
        if (u == null) return "null";
        return u.length() <= 84 ? u : u.substring(0, 84) + "…";
    }
}
