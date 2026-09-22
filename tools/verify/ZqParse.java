import com.videoshell.data.net.Http;
import com.videoshell.data.site.MacPlayer;
import com.videoshell.data.site.Media;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 解析服务（zzrs / hzqingshan 一类 maccms 第三方解析源）协议实测。
 *
 * 背景：Node 沙箱跑通它的混淆脚本后拿到确切接口 —— 解析页是「mui-player 外壳」，
 *   真实取流请求 = POST `{data-bt}mplayer.php`，表单 `url=<data-u>&token=<data-te>`，期望 JSON。
 * 两个参数**直接来自页面**，不需要重算签名。
 *
 * 这里用 App 自己的 Http（OkHttp + CookieJar + Chrome 文档头）复现，因为
 * urllib 会被该服务喂百度诱饵页（实测 190KB）⇒ cookie 全错 ⇒ 403 {"code":403,"msg":"m3"}。
 */
public class ZqParse {

    static final String BASE = "https://www.zqkhmy.com";

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    static String get(String url, String ref) {
        try {
            return block((s, c) -> Http.INSTANCE.get(url, ref, Http.UA,
                    Collections.<String, String>emptyMap(), false, (Continuation<? super String>) c));
        } catch (Throwable t) {
            System.out.println("   !! GET " + url + " -> " + t);
            return null;
        }
    }

    static String post(String url, Map<String, String> p, String ref) {
        try {
            return block((s, c) -> Http.INSTANCE.postForm(url, p, ref, Http.UA, false,
                    (Continuation<? super String>) c));
        } catch (Throwable t) {
            System.out.println("   !! POST " + url + " -> " + t);
            return null;
        }
    }

    public static void main(String[] a) throws Exception {
        new File("_zq29").mkdirs();
        String[] playUrls = a.length > 0 ? a : new String[]{
                BASE + "/play/93662-3-30.html",   // from=co      → zzrs
                BASE + "/play/93662-5-30.html",   // from=vwnet   → zzrs
        };
        for (String playUrl : playUrls) {
            System.out.println("\n================ " + playUrl + " ================");
            one(playUrl);
        }
    }

    static void one(String playUrl) throws Exception {
        String ref = BASE + "/";
        String play = get(playUrl, ref);
        System.out.println("[1] 播放页 bytes=" + len(play));
        MacPlayer.Info info = MacPlayer.INSTANCE.parseInfo(play);
        if (info == null) { System.out.println("    没有 player_aaaa"); return; }
        System.out.println("    from=" + info.getFrom() + " url=" + info.getUrl());

        Map<String, MacPlayer.Line> lines = MacPlayer.INSTANCE.parseLines(play);
        if (lines.isEmpty()) {
            String js = MacPlayer.INSTANCE.configScriptUrl(play, playUrl);
            String cfg = js == null ? null : get(js, playUrl);
            lines = MacPlayer.INSTANCE.parseLines(cfg);
        }
        String parseUrl = MacPlayer.INSTANCE.playPage(info, lines,
                MacPlayer.INSTANCE.parseGlobalParse(play));
        System.out.println("[2] 解析页 = " + parseUrl);
        if (parseUrl == null) return;

        String ph = get(parseUrl, playUrl);
        System.out.println("[3] 解析页 bytes=" + len(ph));
        if (ph == null) return;
        try {
            Files.write(new File("_zq29/parse_live.html").toPath(), ph.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) { }

        Document doc = Jsoup.parse(ph, parseUrl);
        Element pd = doc.selectFirst("#player-data");
        if (pd == null) { System.out.println("    ✗ 没有 #player-data"); return; }
        String u = pd.attr("data-u"), te = pd.attr("data-te"), v = pd.attr("data-v");
        String bt = pd.attr("data-bt");
        System.out.println("    data-u=" + u + "\n    data-te=" + te + "\n    data-v=" + v
                + "\n    data-bt=" + bt);

        String origin = parseUrl.replaceAll("^(https?://[^/]+).*$", "$1");
        String api = origin + (bt.isEmpty() ? "/player/" : bt) + "mplayer.php";
        System.out.println("[4] 取流接口 = " + api);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("url", u);
        p.put("token", te);

        System.out.println("--- 变体 A：只 url+token");
        show(post(api, p, parseUrl));

        System.out.println("--- 变体 B：url+token+v");
        Map<String, String> p2 = new LinkedHashMap<>(p);
        p2.put("v", v);
        show(post(api, p2, parseUrl));

        System.out.println("--- 变体 C：带 X-Requested-With（表单里塞不下，用 header 版在下一轮验证）");
    }

    static void show(String r) {
        if (r == null) { System.out.println("    (null)"); return; }
        System.out.println("    bytes=" + r.getBytes(StandardCharsets.UTF_8).length + "  " + r);
        String found = Media.INSTANCE.extractFromHtml(r);
        System.out.println("    Media.extractFromHtml -> " + found);
    }

    static int len(String s) { return s == null ? -1 : s.getBytes(StandardCharsets.UTF_8).length; }
}
