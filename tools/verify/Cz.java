import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 打印厂长资源详情页里「播放列表容器」的真实 DOM，用来定位线路名 / 选集分组问题。 */
public class Cz {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "https://czzy.app/movie/849.html";
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true).build();
        Response r = client.newCall(new Request.Builder().url(url)
                .header("User-Agent", UA).header("Accept", "*/*").build()).execute();
        String html = new String(r.body().bytes(), StandardCharsets.UTF_8);
        System.out.println("HTTP " + r.code() + "  " + html.length() + " 字");
        Document doc = Jsoup.parse(html, url);

        String[] sels = {
                "[id^=playlist]", ".tab-pane", ".module-play-list", ".module-play-list-content",
                ".stui-content__playlist", ".play-list", ".playlist", ".ff-playurl",
                ".content-playlist", ".lists-box", ".eplist", ".anthology", ".episode-list",
                ".detail-play-list", ".play-source-list", ".video-playlist", ".num-list",
                ".ep-list", ".paly_list_btn", ".mi_paly_box", "[class*=paly_list]",
                "[class*=play_list]", "#playlist", "dl"
        };
        System.out.println("\n---------- 各候选容器的命中数量 ----------");
        for (String s : sels) {
            int n = doc.select(s).size();
            if (n > 0) System.out.println(String.format("%-34s %d 个", s, n));
        }

        System.out.println("\n---------- 命中容器的结构（前 3 个，各限 900 字）----------");
        int shown = 0;
        for (String s : sels) {
            for (Element e : doc.select(s)) {
                if (shown++ >= 3) break;
                System.out.println("\n>>> [" + s + "]  " + e.tagName() + "." + e.className()
                        + "  a=" + e.select("a[href]").size());
                System.out.println("    class链 = " + ancestors(e));
                String h = e.outerHtml().replaceAll("\\s+", " ");
                System.out.println("    " + (h.length() > 900 ? h.substring(0, 900) + "…" : h));
            }
            if (shown >= 3) break;
        }

        System.out.println("\n---------- 所有 a[href] 里含 v_play / play 的（前 12 条）----------");
        int k = 0;
        for (Element a : doc.select("a[href]")) {
            String h = a.attr("href");
            if (!(h.contains("v_play") || h.contains("/play"))) continue;
            if (k++ >= 12) break;
            System.out.println("  [" + a.text().trim() + "]  title='" + a.attr("title")
                    + "'  href=" + h + "  class=" + a.className()
                    + "\n     祖先链 = " + ancestors(a));
        }
    }

    static String ancestors(Element e) {
        StringBuilder sb = new StringBuilder();
        Element p = e.parent();
        int d = 0;
        while (p != null && d++ < 5) {
            sb.append(p.tagName());
            if (!p.className().isEmpty()) sb.append('.').append(p.className().replace(' ', '.'));
            if (!p.id().isEmpty()) sb.append('#').append(p.id());
            sb.append(" < ");
            p = p.parent();
        }
        return sb.toString();
    }
}
