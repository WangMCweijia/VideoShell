import com.videoshell.data.model.*;
import com.videoshell.data.site.*;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 诊断：野果短剧 **首页**（`browse("")`）这条路的封面。
 *
 * 背景：`SiteActivity` 默认选中「最新」，`currentType = ""` ⇒ `browse("")`
 * ⇒ `browseUrls("")` 返回 `listOf(site.baseUrl)`（首页）。
 * 而 `LivePic` 验的是 `cats.get(0).getId()`（真分类页 /tag/xxx/）——
 * **首页这条路此前从未被验证过**。
 */
public class ProbeHome {

    static final String BASE = "https://capable.fzchosdi.cc";

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0]
                : "_yg";

        for (String f : new String[]{"home.html", "tag.html", "rec.html"}) {
            String p = Paths.get(dir, f).toString();
            if (!Files.exists(Paths.get(p))) { System.out.println("缺样本 " + f); continue; }
            String html = new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("################ " + f + "  (" + html.length() + " 字节) ################");

            SsrPayload.Covers cv = SsrPayload.INSTANCE.covers(html);
            System.out.println("  payload 封面表: byId=" + cv.getById().size() + "  byName=" + cv.getByName().size());
            int i = 0;
            for (Map.Entry<String, String> e : cv.getById().entrySet()) {
                if (i++ >= 3) break;
                System.out.println("      id[" + e.getKey() + "] -> " + tail(e.getValue()));
            }

            Document doc = Jsoup.parse(html, BASE);

            List<VideoItem> noRaw = HtmlExtractor.INSTANCE.parseList(doc, BASE, false, null);
            List<VideoItem> withRaw = HtmlExtractor.INSTANCE.parseList(doc, BASE, false, html);
            System.out.println("  parseList(rawHtml=null) : " + noRaw.size() + " 条，有封面 "
                    + countPic(noRaw) + " 条");
            System.out.println("  parseList(rawHtml=html) : " + withRaw.size() + " 条，有封面 "
                    + countPic(withRaw) + " 条");

            System.out.println("  前 5 条（rawHtml=html）：");
            for (int k = 0; k < Math.min(5, withRaw.size()); k++) {
                VideoItem v = withRaw.get(k);
                System.out.println("      · [" + v.getId() + "] " + v.getName()
                        + "  pic=" + (v.getPic().isBlank() ? "<空>" : tail(v.getPic())));
            }

            // DOM 里 src 是占位图 / 真图的比例
            int cards = 0, placeholder = 0, realSrc = 0;
            for (org.jsoup.nodes.Element a : doc.select("a[href]")) {
                org.jsoup.nodes.Element img = a.selectFirst("img");
                if (img == null) continue;
                cards++;
                String src = img.attr("src").trim();
                if (src.startsWith("data:") || src.isEmpty()) placeholder++; else realSrc++;
            }
            System.out.println("  DOM 里带 <img> 的卡片 " + cards + " 个：占位/空 src " + placeholder
                    + " 个，真 src " + realSrc + " 个");
        }
    }

    static int countPic(List<VideoItem> l) {
        int n = 0;
        for (VideoItem v : l) if (!v.getPic().isBlank()) n++;
        return n;
    }

    static String tail(String u) {
        if (u == null) return "null";
        return u.length() <= 70 ? u : "…" + u.substring(u.length() - 70);
    }
}
