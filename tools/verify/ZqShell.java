import com.videoshell.data.site.MacPlayer;
import com.videoshell.data.site.Media;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * v1.0.30：第三方解析源「mui-player 外壳」的离线断言。
 *
 * 夹具全部是**真实抓下来的页面/响应**（`_shell/`）：
 *   shell_co.html / shell_vwnet.html —— zqkhmy 的 co / vwnet 两条线路跟一层后的解析页；
 *   play_normal.html                  —— 普通 maccms 播放页（必须**认不出**外壳，防止误发请求）；
 *   apiresp_co.json / apiresp_403.json —— 接口的成功与失败响应。
 *
 * 顺带守一道源码门：DNS「有答案但连不上」的兜底必须真的在。
 */
public class ZqShell {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("[PASS] " + what); }
        else { fail++; System.out.println("[FAIL] " + what + "   <<< " + detail); }
    }

    static String read(File f) throws Exception {
        return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
    }

    public static void main(String[] a) throws Exception {
        String root = a.length > 0 ? a[0] : ".";      // 夹具目录（_shell/）
        String src  = a.length > 1 ? a[1] : root;     // 工程根（读源码用）——两者不是一回事！

        System.out.println("== A 外壳识别（真实解析页） ==");
        String co = read(new File(root, "_shell/shell_co.html"));
        MacPlayer.Shell s = MacPlayer.INSTANCE.shellOf(co);
        ok("A1 co 页认出外壳", s != null, "null");
        if (s != null) {
            ok("A2 data-u 正确", "co_yumkvzd6xi4agezyhm".equals(s.getU()), s.getU());
            ok("A3 data-te 正确", "4gKYGsatHXaYnnMKsyLYM7AprjdEBl_lhc-taAOenys".equals(s.getTe()), s.getTe());
            ok("A4 data-bt 正确", "/player/".equals(s.getBt()), s.getBt());
        }
        MacPlayer.Shell s2 = MacPlayer.INSTANCE.shellOf(read(new File(root, "_shell/shell_vwnet.html")));
        ok("A5 vwnet 页也认得出且 u 不同",
                s2 != null && "vwnet-9e5e65c4e9b42e6b0244dd61fbd6c73e".equals(s2.getU()),
                s2 == null ? "null" : s2.getU());

        System.out.println("== B 门控：不是外壳就必须返回 null（否则会对所有站白发请求） ==");
        String normal = read(new File(root, "_shell/play_normal.html"));
        ok("B1 普通播放页（42 KB，有 player_aaaa）不认", MacPlayer.INSTANCE.shellOf(normal) == null, "被误认");
        ok("B2 null 安全", MacPlayer.INSTANCE.shellOf(null) == null, "");
        ok("B3 空串安全", MacPlayer.INSTANCE.shellOf("") == null, "");
        ok("B4 有 #player-data 但缺 data-u ⇒ 不认",
                MacPlayer.INSTANCE.shellOf("<div id=\"player-data\" data-te=\"x\"></div>") == null, "被误认");
        ok("B5 属性跨行也能读到",
                MacPlayer.INSTANCE.shellOf("<div\n  id=\"player-data\"\n  data-u=\"abc\"\n  data-bt=\"/p/\"\n  style=\"x\">")
                        != null, "跨行失败");

        System.out.println("== C 接口地址 ==");
        ok("C1 常规拼接",
                "https://zzrs.mfdyvip.com/player/mplayer.php".equals(
                        MacPlayer.INSTANCE.shellApiUrl("https://zzrs.mfdyvip.com/player/?url=x", "/player/")),
                String.valueOf(MacPlayer.INSTANCE.shellApiUrl("https://zzrs.mfdyvip.com/player/?url=x", "/player/")));
        ok("C2 bt 为空 ⇒ 按 /player/ 兜底",
                "https://zzrs.mfdyvip.com/player/mplayer.php".equals(
                        MacPlayer.INSTANCE.shellApiUrl("https://zzrs.mfdyvip.com/player/?url=x", "")), "");
        ok("C3 bt 无尾斜杠自动补",
                "https://a.com/p/mplayer.php".equals(MacPlayer.INSTANCE.shellApiUrl("https://a.com/x", "/p")),
                String.valueOf(MacPlayer.INSTANCE.shellApiUrl("https://a.com/x", "/p")));
        ok("C4 另一家主机的解析服务各用各的 origin（fgsrg）",
                "https://fgsrg.hzqingshan.com/player/mplayer.php".equals(
                        MacPlayer.INSTANCE.shellApiUrl("https://fgsrg.hzqingshan.com/player/?url=JD4K-x", "/player/")), "");
        ok("C5 非 http 入参 ⇒ null", MacPlayer.INSTANCE.shellApiUrl("not a url", "/player/") == null, "");

        System.out.println("== D 接口响应 ==");
        String good = read(new File(root, "_shell/apiresp_co.json")).trim();
        String got = MacPlayer.INSTANCE.jsonUrl(good);
        ok("D1 真实成功响应取到 m3u8",
                got != null && got.startsWith("https://cibn-edge-5g.1ljx.com/") && got.contains("auth_key="),
                String.valueOf(got));
        ok("D2 403 响应必须拒绝（不能把错当对）",
                MacPlayer.INSTANCE.jsonUrl(read(new File(root, "_shell/apiresp_403.json")).trim()) == null,
                String.valueOf(MacPlayer.INSTANCE.jsonUrl(read(new File(root, "_shell/apiresp_403.json")).trim())));
        ok("D3 非 JSON ⇒ null", MacPlayer.INSTANCE.jsonUrl("<html>x</html>") == null, "");
        ok("D4 空/null ⇒ null", MacPlayer.INSTANCE.jsonUrl(null) == null && MacPlayer.INSTANCE.jsonUrl("") == null, "");
        ok("D5 url 不是 http ⇒ null", MacPlayer.INSTANCE.jsonUrl("{\"code\":200,\"url\":\"abc\"}") == null, "");
        ok("D6 缺 code 只有 url ⇒ 宽容接受",
                "https://x.com/a.m3u8".equals(MacPlayer.INSTANCE.jsonUrl("{\"url\":\"https://x.com/a.m3u8\"}")), "");
        ok("D7 取到的地址 Media 也认得出（闭环）",
                Media.INSTANCE.extractFromHtml(good) != null, "Media 抠不出");

        System.out.println("== E 源码守卫 ==");
        String adapter = read(new File(src, "app/src/main/java/com/videoshell/data/site/SiteAdapter.kt"));
        int cnt = adapter.split("shellDirect\\(", -1).length - 1;
        ok("E1 resolve 两处都接了外壳（首页 + 跟随层）：实际 " + cnt + " 次", cnt >= 3, String.valueOf(cnt));
        ok("E2 走 postFormOrNull（失败不抛，退回嗅探）", adapter.contains("Http.postFormOrNull(api"), "");
        ok("E3 嗅探兜底仍在（不引入回归）", adapter.contains("return MediaSource.Sniff(u, playHeaders())"), "");

        String http = read(new File(src, "app/src/main/java/com/videoshell/data/net/Http.kt"));
        ok("E4 DNS 有「连不上」判定", http.contains("sysDnsUsable"), "");
        ok("E5 判定结果有缓存（常态零开销）", http.contains("dnsVerdict"), "");
        // v1.0.31 那段改过写法（加了结果缓存），别再按旧字面量找
        ok("E6 系统答案不可用/连不上时回落 DoH",
                http.contains("sys.isNotEmpty() && sysDnsUsable(hostname, sys)")
                        && http.contains("dohDns.lookup(hostname)"), "");
        ok("E6b DoH 结果有缓存（污染时每张图一次 DoH ⇒ 网格全超时）", http.contains("dnsCache"), "");
        ok("E7 dnsReport 点名「连不上」这一态", http.contains("系统答案连不上"), "");

        String doc = read(new File(src, "app/src/main/java/com/videoshell/data/site/SiteDoctor.kt"));
        ok("E8 自检 [3a] 已写清两种污染形态", doc.contains("同样算污染") && doc.contains("图片加载层"), "");

        System.out.println("==== pass=" + pass + " fail=" + fail + " ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}
