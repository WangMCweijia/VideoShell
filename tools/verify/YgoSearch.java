import com.videoshell.data.model.*;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * 野果搜索探针：多个关键词各打一次原生搜索接口，打印返回标题并标注「是否含关键词」。
 *
 * 目的：用户反馈「搜索结果还是错的」，而 YgoLive 只测了单个「爱」。
 * 换一批关键词看真实返回，判断是 (a) 站点接口本身返回无关内容
 * (b) 我们字段映射错了 (c) 分页/参数错。
 */
public class YgoSearch {

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        SiteConfig site = new SiteConfig("yeguo", "野果短剧", "https://www.yeguodj.com",
                "", SiteConfig.MODE_HTML, "", "", 0L);
        CryptRecipe recipe = CryptRecipes.INSTANCE.forUrl("https://www.yeguodj.com");
        System.out.println("recipe = " + recipe);
        if (recipe == null) {
            System.out.println("!! 白名单未命中");
            return;
        }
        SiteAdapter ad = new YeguoAdapter(site, recipe);

        String[] kws = {"爱", "爱情", "都市", "霸总", "复仇", "重生", "穿越", "甜宠", "战神", "玄幻",
                "短剧", "zwxq不存在的词", "总裁"};
        for (String kw : kws) {
            System.out.println("=".repeat(72));
            System.out.println("关键词: " + kw);
            List<VideoItem> items = block((scope, cont) ->
                    ad.search(kw, 1, (Continuation<? super List<VideoItem>>) cont));
            System.out.println("  条数=" + items.size() + "  diag=[" + ad.getLastDiag() + "]");
            int rel = 0;
            int n = Math.min(items.size(), 6);
            for (int i = 0; i < n; i++) {
                VideoItem v = items.get(i);
                boolean hit = v.getName().contains(kw);
                if (hit) rel++;
                System.out.println("   " + (hit ? "*" : " ") + " [" + v.getId() + "] " + v.getName()
                        + "   remark=" + v.getRemarks());
            }
            System.out.println("  前" + n + "条含关键词数=" + rel);
        }

        System.out.println("=".repeat(72));
        System.out.println("搜索结果 -> 详情 往返一致性（点进去必须还是那一部）");
        List<VideoItem> hit = block((scope, cont) ->
                ad.search("爱", 1, (Continuation<? super List<VideoItem>>) cont));
        for (int i = 0; i < Math.min(hit.size(), 4); i++) {
            VideoItem v = hit.get(i);
            String dn;
            try {
                VideoDetail d = block((scope, cont) ->
                        ad.detail(v.getId(), (Continuation<? super VideoDetail>) cont));
                dn = d.getName();
            } catch (Throwable t) {
                dn = "!! " + t.getMessage();
            }
            System.out.println("  列表[" + v.getId() + "] " + v.getName()
                    + "  ->  详情 " + dn + "   一致=" + v.getName().equals(dn));
        }

        System.out.println("=".repeat(72));
        System.out.println("tab 参数探测（我们没传 tab，站点默认 video；看还有没有别的 tab）");
        String TABKW = System.getProperty("tabkw", "AI短剧");
        for (String tab : new String[]{"video", "actor"}) {
            Map<String, String> tp = new LinkedHashMap<>();
            tp.put("keyword", TABKW);
            tp.put("page", "1");
            tp.put("limit", "20");
            if (!tab.isEmpty()) tp.put("tab", tab);
            try {
                com.google.gson.JsonObject o = block((scope, cont) -> CryptApi.INSTANCE.call(
                        recipe, "/api/search/result", tp, "https://www.yeguodj.com",
                        (Continuation<? super com.google.gson.JsonObject>) cont));
                com.google.gson.JsonObject d = o == null ? null : o.getAsJsonObject("data");
                int n = 0;
                String first = "";
                if (d != null && d.get("list") != null && d.get("list").isJsonArray()) {
                    n = d.getAsJsonArray("list").size();
                    if (n > 0) first = d.getAsJsonArray("list").get(0)
                            .getAsJsonObject().get("title").getAsString();
                }
                System.out.println("  tab=" + (tab.isEmpty() ? "(不传)" : tab)
                        + "  status=" + (o == null ? "null" : o.get("status"))
                        + "  msg=" + (o == null ? "-" : o.get("msg"))
                        + "  n=" + n + "  first=" + first);
            } catch (Throwable t) {
                System.out.println("  tab=" + tab + "  !! " + t);
            }
        }

        System.out.println("=".repeat(72));
        System.out.println("tab=actor 原始响应（看演员结果长什么样）");
        Map<String, String> ap = new LinkedHashMap<>();
        ap.put("keyword", "爱");
        ap.put("page", "1");
        ap.put("limit", "20");
        ap.put("tab", "actor");
        try {
            com.google.gson.JsonObject o = block((scope, cont) -> CryptApi.INSTANCE.call(
                    recipe, "/api/search/result", ap, "https://www.yeguodj.com",
                    (Continuation<? super com.google.gson.JsonObject>) cont));
            String s = o == null ? "null" : o.toString();
            System.out.println(s.length() > 1800 ? s.substring(0, 1800) + "…" : s);
        } catch (Throwable t) {
            System.out.println("  !! " + t);
        }

        System.out.println("=".repeat(72));
        System.out.println("原始响应结构（keyword=爱）");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("keyword", "爱");
        p.put("page", "1");
        p.put("limit", "20");
        try {
            JsonObject raw = block((scope, cont) -> CryptApi.INSTANCE.call(
                    recipe, "/api/search/result", p, "https://www.yeguodj.com",
                    (Continuation<? super JsonObject>) cont));
            System.out.println("  top keys = " + (raw == null ? "null" : raw.keySet()));
            JsonObject data = raw == null ? null : raw.getAsJsonObject("data");
            if (data != null) {
                System.out.println("  data keys = " + data.keySet());
                for (String k : data.keySet()) {
                    JsonElement e = data.get(k);
                    String s = e.toString();
                    System.out.println("    " + k + " = "
                            + (s.length() > 200 ? s.substring(0, 200) + "…" : s));
                }
            }
        } catch (Throwable t) {
            System.out.println("  !! " + t);
        }
    }
}
