import com.videoshell.data.site.MacPlayer;
import com.videoshell.data.site.PlayerShell;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * v1.0.31：骚火这一类「播放器外壳」的离线断言。
 *
 * 夹具全部是**真实抓下来的页面/响应**（`_shell31/`）：
 *   shdy_play.html       —— 骚火播放页：没有 player_aaaa、没有 m3u8，只有一个 iframe；
 *   hhjx_boot.html       —— iframe 里那一层：window.__HHJX_BOOTSTRAP__={url,t,key}；
 *   apires_hhjx_ok.json  —— 真接口成功响应（**令牌一次性**，复放第二次就是 403）；
 *   apires_hhjx_403.json —— 复放/校验失败的响应（注意它带 `"url":""`，别把空串当地址）。
 *
 * ⚠️ 令牌是**每次现发、一次性的**：同一集连抓两次 hhjx_boot.html，得到的 `t` 与 `key` 都不一样
 * （实测 t 1789751890→1789752817、key cc2b23f0…→ebd64488…）。所以断言只校验**形状**
 * （96 位 hex / 10 位时间戳 / 64 位 hex），不写死值 —— 也正因为如此，实现里绝不能缓存引导对象，
 * 必须每次现取现用（见 PlayerShell 的 KDoc）。
 *
 * 最重要的一条不是"能取到"，而是**不该发请求时必须零请求**（B 段）：
 * 这类判据一旦放宽，会对所有站白发 POST，比播不出来更糟。
 */
public class ShdyShell {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("[PASS] " + what); }
        else { fail++; System.out.println("[FAIL] " + what + "   <<< " + detail); }
    }

    static String read(File f) throws Exception {
        return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
    }

    public static void main(String[] a) throws Exception {
        String root = a.length > 0 ? a[0] : ".";      // 夹具目录
        String src = a.length > 1 ? a[1] : root;      // 工程根（读源码守卫用）— 两个根别混

        String play = read(new File(root, "_shell31/shdy_play.html"));
        String boot = read(new File(root, "_shell31/hhjx_boot.html"));

        System.out.println("== A iframe 播放器识别（真实骚火播放页） ==");
        String frame = PlayerShell.INSTANCE.iframeOf(play, "https://shdy5.us/play/50318-1-26.html");
        ok("A1 认出播放器 iframe", frame != null && frame.startsWith("https://hhjx.hhplayer.com/?url="),
                String.valueOf(frame));
        ok("A2 播放页本身确实没有 m3u8 / player_aaaa（所以非跟不可）",
                !play.contains(".m3u8") && !play.contains("player_aaaa"), "");
        // 相对地址也得补成绝对地址；但地址里没有播放器字样的一律不跟（免得跟着广告 iframe 乱跳）
        ok("A3 相对 /player/x 按 origin 补全",
                "https://a.com/player/x".equals(PlayerShell.INSTANCE.iframeOf(
                        "<iframe src=\"/player/x\">", "https://a.com/p.html")),
                String.valueOf(PlayerShell.INSTANCE.iframeOf(
                        "<iframe src=\"/player/x\">", "https://a.com/p.html")));
        ok("A3b 没有播放器字样的相对地址不跟",
                PlayerShell.INSTANCE.iframeOf("<iframe src=\"/ad/banner\">", "https://a.com/p.html") == null,
                "被误认");
        ok("A4 协议相对 //host/x 补 https",
                "https://b.com/player/x".equals(PlayerShell.INSTANCE.iframeOf(
                        "<iframe src=\"//b.com/player/x\">", "https://a.com/p.html")), "");
        ok("A5 about:/data: 跳过",
                PlayerShell.INSTANCE.iframeOf("<iframe src=\"about:blank\">", "https://a.com/") == null, "被误认");
        ok("A6 null / 空串 安全",
                PlayerShell.INSTANCE.iframeOf(null, "https://a.com/") == null
                        && PlayerShell.INSTANCE.iframeOf("", "https://a.com/") == null, "");

        System.out.println("== B 门控：不是这种壳就必须返回 null（否则会对所有站白发请求） ==");
        String normal = read(new File(root, "_shell/play_normal.html"));
        ok("B1 普通 maccms 播放页（42 KB）不是引导壳", PlayerShell.INSTANCE.bootOf(normal) == null, "被误认");
        ok("B2 引导页自己不含 iframe（不会再往下跳一层）",
                PlayerShell.INSTANCE.iframeOf(boot, "https://hhjx.hhplayer.com/") == null, "被误认");
        ok("B3 缺 key ⇒ 不认",
                PlayerShell.INSTANCE.bootOf(
                        "<script>window.__X__={\"url\":\"abc\"};</script>") == null, "被误认");
        ok("B4 缺 url ⇒ 不认",
                PlayerShell.INSTANCE.bootOf(
                        "<script>window.__X__={\"key\":\"kk\"};</script>") == null, "被误认");
        ok("B5 null / 空串 安全",
                PlayerShell.INSTANCE.bootOf(null) == null && PlayerShell.INSTANCE.bootOf("") == null, "");

        System.out.println("== C 引导对象解析（真实 hhplayer 页） ==");
        PlayerShell.Boot b = PlayerShell.INSTANCE.bootOf(boot);
        ok("C1 认出引导壳", b != null, "null");
        if (b != null) {
            ok("C2 名字是 HHJX_BOOTSTRAP", "HHJX_BOOTSTRAP".equals(b.getName()), b.getName());
            // 令牌每次都变 ⇒ 只校验形状（96 位十六进制）
            ok("C3 url 令牌 96 位十六进制", b.getUrl().matches("[0-9A-Fa-f]{96}"), b.getUrl());
            ok("C4 t 是 10 位时间戳", b.getT().matches("\\d{10}"), b.getT());
            ok("C5 key 64 位十六进制", b.getKey().matches("[0-9A-Fa-f]{64}"), b.getKey());
        }

        System.out.println("== D 接口地址与请求体 ==");
        ok("D1 与页面同源拼接 /api/parse",
                "https://hhjx.hhplayer.com/api/parse".equals(
                        PlayerShell.INSTANCE.bootApiUrl("https://hhjx.hhplayer.com/?url=x")),
                String.valueOf(PlayerShell.INSTANCE.bootApiUrl("https://hhjx.hhplayer.com/?url=x")));
        ok("D2 path 缺斜杠自动补",
                "https://a.com/api/parse".equals(PlayerShell.INSTANCE.bootApiUrl("https://a.com/x", "api/parse")), "");
        ok("D3 非 http 入参 ⇒ null", PlayerShell.INSTANCE.bootApiUrl("not a url") == null, "");
        if (b != null) {
            String j = PlayerShell.INSTANCE.bootJson(b);
            ok("D4 请求体含 url 与 key", j.contains("\"url\"") && j.contains("\"key\""), j);
            // 页面里 t 是数字，就按数字发：有的接口对 "1789…" 和 1789… 敏感
            ok("D5 t 按数字发（不是 \"t\":\"…\"）",
                    j.matches(".*\"t\":\\d+.*"), j);
            ok("D6 client_fallback 为 false", j.contains("\"client_fallback\":false"), j);
        }

        System.out.println("== E 响应解析 ==");
        String good = read(new File(root, "_shell31/apires_hhjx_ok.json")).trim();
        String got = PlayerShell.INSTANCE.jsonUrl(good);
        ok("E1 真实成功响应取到 m3u8",
                got != null && got.startsWith("http://hhjx.hhplayer.com/playlist/") && got.contains("sign="),
                String.valueOf(got));
        ok("E2 响应带时效参数（⇒ 只当次取用，绝不固化）", got != null && got.contains("expires="), String.valueOf(got));
        String bad = read(new File(root, "_shell31/apires_hhjx_403.json")).trim();
        ok("E3 403 且 url 为空串 ⇒ 必须拒绝", PlayerShell.INSTANCE.jsonUrl(bad) == null,
                String.valueOf(PlayerShell.INSTANCE.jsonUrl(bad)));
        ok("E4 非 JSON ⇒ null", PlayerShell.INSTANCE.jsonUrl("<html>x</html>") == null, "");
        ok("E5 空 / null ⇒ null",
                PlayerShell.INSTANCE.jsonUrl(null) == null && PlayerShell.INSTANCE.jsonUrl("") == null, "");

        System.out.println("== F 源码守卫 ==");
        String adapter = read(new File(src, "app/src/main/java/com/videoshell/data/site/SiteAdapter.kt"));
        ok("F1 resolve 接了 iframe（跟着下一层）", adapter.contains("PlayerShell.iframeOf"), "");
        int cnt = adapter.split("bootDirect\\(", -1).length - 1;
        ok("F2 bootDirect 接了 2 处（本页 + iframe 那层）：实际 " + cnt, cnt >= 3, String.valueOf(cnt));
        String http = read(new File(src, "app/src/main/java/com/videoshell/data/net/Http.kt"));
        ok("F3 有 JSON POST（这类接口只认 JSON 体）", http.contains("postJsonOrNull"), "");
        ok("F4 DNS 结果有缓存（否则污染时每张图一次 DoH ⇒ 网格全超时）", http.contains("dnsCache"), "");
        ok("F5 缓存有 TTL", http.contains("DNS_TTL_MS"), "");
        String doc = read(new File(src, "app/src/main/java/com/videoshell/data/site/SiteDoctor.kt"));
        ok("F6 自检会打印「外壳判定」（新站一眼看出该补哪种）", doc.contains("PlayerShell.describe"), "");
        ok("F7 mui-player 外壳那条老路没被挤掉", MacPlayer.INSTANCE.shellOf(
                "<div id=\"player-data\" data-u=\"u1\" data-te=\"t1\" data-bt=\"/player/\">") != null, "");

        System.out.println("==== pass=" + pass + " fail=" + fail + " ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}
