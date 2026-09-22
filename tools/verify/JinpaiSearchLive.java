import com.videoshell.data.model.*;
import com.videoshell.data.site.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 金牌影视搜索线上探针：搜什么都是无关内容 ——
 * 看Adapter 的 search() 到底学到/用到哪个模板、解析出什么。
 * 用法：JinpaiSearchLive [关键词]
 */
public class JinpaiSearchLive {

    static final String SITE = "https://bolyship.com";

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] args) throws Exception {
        // A. 离线：搜索结果页样本能不能被 parseList 认出来
        try {
            String html = new String(Files.readAllBytes(Paths.get("_jp_search.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(html, SITE);
            java.util.List<VideoItem> items = HtmlExtractor.INSTANCE.parseList(doc, SITE, false, html);
            System.out.println("A. parseList(搜索结果页样本) = " + items.size() + " 条");
            for (int i = 0; i < Math.min(5, items.size()); i++)
                System.out.println("      · " + items.get(i).getName() + "  " + items.get(i).getId());
        } catch (Exception e) {
            System.out.println("A. 样本解析异常: " + e);
        }

        // B. 离线：表单学习
        try {
            String home = new String(Files.readAllBytes(Paths.get("_jp_home.html")),
                    java.nio.charset.StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(home, SITE);
            String tpl = HtmlTemplates.INSTANCE.searchTplFromForm(doc, SITE);
            System.out.println("B. searchTplFromForm(首页样本) = " + tpl);
        } catch (Exception e) {
            System.out.println("B. 表单学习异常: " + e);
        }

        // C. 线上：真跑一次 search()
        String kw = args.length > 0 ? args[0] : "庆余年";
        SiteConfig site = new SiteConfig("jp", "金牌", SITE, "",
                SiteConfig.MODE_HTML, "", "", 0L);
        SiteAdapter ad = AdapterFactory.INSTANCE.create(site);
        java.util.List<VideoItem> r = block((scope, cont) ->
                ad.search(kw, 1, (Continuation<? super java.util.List<VideoItem>>) cont));
        System.out.println("C. search(\"" + kw + "\") = " + (r == null ? "null" : r.size()) + " 条");
        if (r != null) {
            for (int i = 0; i < Math.min(8, r.size()); i++)
                System.out.println("      · " + r.get(i).getName() + "  " + r.get(i).getId());
        }
    }
}
