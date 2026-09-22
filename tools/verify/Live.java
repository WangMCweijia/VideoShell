import com.videoshell.data.model.*;
import com.videoshell.data.site.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import okhttp3.*;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 实时全链路复现：App 同款 OkHttp 抓线上页面 -> 真实解析函数 -> 一路走到播放地址。
 * 目的：定位「分类为空 / 详情+选集为空 / 播放失败」到底断在哪一环。
 */
public class Live {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    static final String ACCEPT = "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8";

    static OkHttpClient client, fast;
    static String outDir;

    public static void main(String[] a) throws Exception {
        outDir = a[0];
        Files.createDirectories(Paths.get(outDir));
        client = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true)
                .followRedirects(true).build();
        fast = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false)
                .followRedirects(true).build();

        site("茶杯狐", "https://www.cupfoxyy.com", "cf");
        site("厂长资源", "https://czzy.app", "cz");
        site("低端影视", "https://ddys.pro", "dd");
        System.out.println("\n==== DONE ====");
    }

    // ---------------------------------------------------------------- 单个站点全链路
    static void site(String label, String base, String tag) {
        banner("站点 " + label + "  " + base);

        Resp home = get(base, base, true);
        log("首页 GET -> " + home.status + " / " + home.len + " 字 / ct=" + home.ct);
        save(tag + "_home.html", home.body);

        if (home.body == null || home.body.isEmpty()) {
            log("!! 首页拿不到，后续全部跳过");
            return;
        }

        Document doc = Jsoup.parse(home.body, base);
        SiteConfig cfg = new SiteConfig(tag, label, base, "", SiteConfig.MODE_HTML, "", "", 0L);
        HtmlAdapter ad = new HtmlAdapter(cfg);

        // 站点形态：有没有独立详情页
        boolean vodIsCategory = false;
        int sigCount = 0;
        for (org.jsoup.nodes.Element e : doc.select("a[href]")) {
            if (HtmlTemplates.INSTANCE.isDetailSignal(e.attr("href"))) { sigCount++; vodIsCategory = true; }
        }
        log("isDetailSignal 命中 " + sigCount + " 个 -> vodIsCategory=" + vodIsCategory);

        // ---- 分类
        List<Category> cats = ad.categoriesFrom(doc);
        log("分类 categoriesFrom -> " + cats.size() + " 个 :: " + names(cats));
        for (Category c : cats) log("    cat: " + pad(c.getName(), 10) + " -> " + c.getId());

        // ---- 首页影片
        List<VideoItem> items = HtmlExtractor.INSTANCE.parseList(doc, base, vodIsCategory);
        log("首页 parseList -> " + items.size() + " 条");
        if (!items.isEmpty()) log("    首条: " + show(items.get(0)));

        // ---- 详情模板
        String tpl = HtmlExtractor.INSTANCE.detailTplHint(doc, base, vodIsCategory);
        log("学到详情模板 -> " + tpl);

        if (items.isEmpty()) { log("!! 首页影片为空，详情/播放链路跳过"); return; }
        String id = items.get(0).getId();
        log("拿首条 id = " + id + " 去请求详情");

        // ---- 详情：真实候选中依次试
        Document ddoc = null;
        String dhtml = null;
        List<String> cands = new ArrayList<>();
        if (tpl != null && !tpl.isBlank()) cands.add(tpl.replace("{id}", id));
        cands.add(base + "/detail/" + id + ".html");
        cands.add(base + "/voddetail/" + id + ".html");
        cands.add(base + "/movie/" + id + ".html");
        for (String u : cands) {
            Resp r = get(u, base, false);
            List<PlayGroup> g = r.body == null ? Collections.emptyList()
                    : HtmlExtractor.INSTANCE.parseGroups(Jsoup.parse(r.body, base), base);
            log("  详情 GET " + tail(u) + " -> " + r.status + " / " + r.len + " 字 / 线路 " + g.size());
            if (!g.isEmpty() && ddoc == null) { ddoc = Jsoup.parse(r.body, base); dhtml = r.body; }
        }
        if (dhtml != null) save(tag + "_detail.html", dhtml);

        if (ddoc == null) {
            log("!! 所有详情候选都没解析出线路 —— 详情环节断了");
            // 就算没线路，也看看页面上有没有 player_aaaa / m3u8 线索
            return;
        }
        List<PlayGroup> groups = HtmlExtractor.INSTANCE.parseGroups(ddoc, base);
        log("详情线路 -> " + groups.size() + " 条");
        for (PlayGroup g : groups) {
            log("    线路 " + pad(g.getName(), 14) + " 集数 " + g.getEpisodes().size()
                    + (g.getEpisodes().isEmpty() ? "" : "  首集=" + g.getEpisodes().get(0).getName()
                    + " " + tail(g.getEpisodes().get(0).getUrl())));
        }

        // ---- 播放：每条线路都走一遍
        int gi = 0;
        for (PlayGroup g : groups) {
            if (g.getEpisodes().isEmpty()) continue;
            Episode ep = g.getEpisodes().get(0);
            log("  线路[" + g.getName() + "] 首集地址: " + ep.getUrl());

            MediaSource ms = resolve(cfg, ep, base);
            String cls = ms.getClass().getSimpleName();
            if (ms instanceof MediaSource.Direct) {
                MediaSource.Direct d = (MediaSource.Direct) ms;
                log("    resolve -> Direct  isHls=" + d.isHls() + "  " + d.getUrl());
                probeMedia(d.getUrl(), base, tag + "_line" + (gi++) + "_media");
            } else if (ms instanceof MediaSource.Sniff) {
                log("    resolve -> Sniff（HTML 抠不到，要开 WebView 嗅探）");
                Resp r = get(ep.getUrl(), base, false);
                save(tag + "_line" + (gi++) + "_play.html", r.body);
                MediaSource.Direct d2 = null;
                if (r.body != null) {
                    String u = Media.INSTANCE.extractFromHtml(r.body);
                    log("    播放页 " + r.status + "/" + r.len + " 字，extractFromHtml=" + d2less(u));
                    if (u != null) probeMedia(u, base, tag + "_line" + (gi++) + "_media");
                }
            } else {
                log("    resolve -> " + cls + "  " + ((MediaSource.Error) ms).getMessage());
            }
        }
    }

    static String d2less(String u) { return u; }

    /** 复刻 SiteAdapter.resolve 的三级逻辑 */
    static MediaSource resolve(SiteConfig cfg, Episode ep, String base) {
        String u = ep.getUrl() == null ? "" : ep.getUrl().trim();
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", base);
        if (u.isEmpty()) return new MediaSource.Error("播放地址为空");
        if (Media.INSTANCE.isDirect(u)) return new MediaSource.Direct(u, h, Media.INSTANCE.isHls(u));
        if (!u.startsWith("http")) return new MediaSource.Error("无法识别的播放地址：" + u);
        Resp r = get(u, base, false);
        if (r.body == null) return new MediaSource.Sniff(u, h);
        String real = Media.INSTANCE.extractFromHtml(r.body);
        if (real != null && !real.isBlank()) return new MediaSource.Direct(real, h, Media.INSTANCE.isHls(real));
        return new MediaSource.Sniff(u, h);
    }

    /** 真的去 GET 一下媒体地址，看 HTTP 状态 —— 直接复现用户看到的 404 */
    static void probeMedia(String url, String referer, String saveTag) {
        Request.Builder b = new Request.Builder().url(url)
                .header("User-Agent", UA).header("Referer", referer)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-2047");
        try (Response resp = client.newCall(b.build()).execute()) {
            String ct = resp.header("Content-Type");
            byte[] body = resp.body() == null ? new byte[0] : resp.body().bytes();
            String head = new String(body, Charset.forName("UTF-8"));
            log("    >>> 媒体 GET " + resp.code() + "  ct=" + ct
                    + "  final=" + tail(resp.request().url().toString()));
            if (!head.isEmpty()) log("        开头: " + head.substring(0, Math.min(160, head.length())).replace("\n", "\\n"));
            save(saveTag + ".txt", "HTTP " + resp.code() + " ct=" + ct + "\nURL " + url + "\n\n" + head);
        } catch (Exception e) {
            log("    >>> 媒体 GET 异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- HTTP
    static class Resp { int status; String body; int len; String ct; }

    static Resp get(String url, String referer, boolean useFast) {
        Resp r = new Resp();
        Request.Builder b = new Request.Builder().url(url)
                .header("User-Agent", UA).header("Accept", ACCEPT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (referer != null && !referer.isBlank()) b.header("Referer", referer);
        try (Response resp = (useFast ? fast : client).newCall(b.build()).execute()) {
            r.status = resp.code();
            r.ct = resp.header("Content-Type");
            byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
            r.body = decodeBytes(bytes, resp.body() == null ? null : resp.body().contentType());
            r.len = r.body == null ? 0 : r.body.length();
        } catch (Exception e) {
            r.status = -1;
            r.ct = e.getClass().getSimpleName() + ": " + e.getMessage();
            r.body = null;
            r.len = 0;
        }
        return r;
    }

    /** 复刻 util.decodeBody */
    static String decodeBytes(byte[] bytes, MediaType mt) {
        if (bytes.length == 0) return "";
        String declared = mt == null ? null : mt.charset() == null ? null : mt.charset().name();
        if (declared != null) {
            try { return new String(bytes, Charset.forName(declared)); } catch (Exception ignore) {}
        }
        String probe = new String(bytes, Charset.forName("ISO-8859-1"));
        probe = probe.substring(0, Math.min(4096, probe.length()));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(probe);
        if (m.find()) {
            String cs = m.group(1);
            if (!"utf-8".equalsIgnoreCase(cs)) {
                try { return new String(bytes, Charset.forName(cs)); } catch (Exception ignore) {}
            }
        }
        String utf8 = new String(bytes, Charset.forName("UTF-8"));
        if (utf8.indexOf('\uFFFD') >= 0) {
            try { return new String(bytes, Charset.forName("GBK")); } catch (Exception ignore) {}
        }
        return utf8;
    }

    // ---------------------------------------------------------------- 小工具
    static void save(String name, String s) {
        if (s == null) return;
        try { Files.write(Paths.get(outDir, name), s.getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) {}
    }

    static String names(List<Category> l) {
        StringBuilder sb = new StringBuilder();
        for (Category c : l) sb.append(c.getName()).append(' ');
        return sb.toString().trim();
    }

    static String show(VideoItem v) {
        return v.getId() + " | " + v.getName() + " | " + v.getRemarks() + " | " + v.getPic();
    }

    static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    static String tail(String s) { return s == null ? "null" : (s.length() <= 110 ? s : "..." + s.substring(s.length() - 110)); }

    static void log(String s) { System.out.println(s); }

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(74));
        System.out.println(s);
        System.out.println("=".repeat(74));
    }
}
