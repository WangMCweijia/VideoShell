import com.videoshell.data.model.*;
import com.videoshell.data.site.*;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * 野果搜索「相关性」取证探针。
 *
 * 背景：用户反馈「野果搜索还是结果错的」。旧探针（YgoSearch）已显示站点的搜索是
 * **标题 + 标签 + 演员名 的宽匹配** —— 搜「甜宠」「短剧」「玄幻」这类**标签词**时，
 * 返回的片名可以**一个都不含关键词**（0/6）。对用户来说这就是「结果错的」。
 *
 * 要判定「能不能把它变得可解释」，只需回答一个问题：
 * **视频列表项里有没有 `matched_fields` / `highlight_keywords`？**
 *   - 有 ⇒ 可以在卡片上写「命中标签：甜宠」，把"看起来乱"变成"看得出为什么"。
 *   - 没有 ⇒ 只能自己拿关键词去比对 tags/actors 反推，成本更高。
 *
 * 本探针只打印事实，不做断言。
 */
public class YgoItem {

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    static JsonArray listOf(CryptRecipe recipe, String kw) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("keyword", kw);
        p.put("page", "1");
        p.put("limit", "20");
        JsonObject o = block((scope, cont) -> CryptApi.INSTANCE.call(
                recipe, "/api/search/result", p, "https://www.yeguodj.com",
                (Continuation<? super JsonObject>) cont));
        if (o == null) return null;
        JsonObject d = o.getAsJsonObject("data");
        if (d == null || d.get("list") == null || !d.get("list").isJsonArray()) return null;
        return d.getAsJsonArray("list");
    }

    public static void main(String[] args) throws Exception {
        CryptRecipe recipe = CryptRecipes.INSTANCE.forUrl("https://www.yeguodj.com");
        if (recipe == null) {
            System.out.println("!! 白名单未命中");
            return;
        }

        String[] kws = {"甜宠", "玄幻", "短剧", "爱"};
        for (String kw : kws) {
            System.out.println("=".repeat(74));
            System.out.println("关键词: " + kw);
            JsonArray arr = listOf(recipe, kw);
            if (arr == null || arr.size() == 0) {
                System.out.println("  列表为空");
                continue;
            }
            JsonObject first = arr.get(0).getAsJsonObject();
            System.out.println("  条数=" + arr.size());
            System.out.println("  第1条 keys = " + first.keySet());

            // 关键判定：这两组字段在不在 video 项上
            System.out.println("  matched_fields      = " + first.get("matched_fields"));
            System.out.println("  highlight_keywords  = " + first.get("highlight_keywords"));

            // 逐条看：标题命中 / 标签命中 / 演员命中
            int titleHit = 0, tagHit = 0, actorHit = 0, none = 0;
            Set<String> fieldSets = new LinkedHashSet<>();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                String title = o.get("title") == null ? "" : o.get("title").getAsString();
                String tags = o.get("tags") == null ? "" : o.get("tags").toString();
                String actors = o.get("actors") == null ? "" : o.get("actors").getAsString();
                String mf = o.get("matched_fields") == null ? "null" : o.get("matched_fields").toString();
                fieldSets.add(mf);
                boolean t = title.contains(kw);
                boolean g = tags.contains(kw);
                boolean a = actors.contains(kw);
                if (t) titleHit++;
                else if (g) tagHit++;
                else if (a) actorHit++;
                else none++;
                System.out.println("   " + (t ? "标题" : g ? "标签" : a ? "演员" : "？？")
                        + " [" + o.get("id").getAsString() + "] " + title
                        + "   mf=" + mf);
            }
            System.out.println("  小结: 标题命中=" + titleHit + " 标签命中=" + tagHit
                    + " 演员命中=" + actorHit + " 三者都不含=" + none);
            System.out.println("  matched_fields 取值集合 = " + fieldSets);

            // 完整第 1 条（含所有字段），看还有没有别的可展示信息
            System.out.println("  --- 第1条完整 JSON ---");
            String s = first.toString();
            System.out.println("  " + (s.length() > 1200 ? s.substring(0, 1200) + "…" : s));
        }
    }
}
