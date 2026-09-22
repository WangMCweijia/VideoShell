import com.videoshell.data.site.MacPlayer;
import com.videoshell.data.site.Media;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;

/**
 * v1.0.29：maccms 第三方解析源（player_list / encrypt）离线断言。
 *
 * A 段 = 真实播放页夹具；C/E/F 段 = 合成形状（不需要网络）。
 */
public class MacPlayerTest {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + what);
        } else {
            fail++;
            System.out.println("[FAIL] " + what + "   <<< " + detail);
        }
    }

    static String read(File f) throws Exception {
        return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
    }

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "_play";

        // ---------------------------------------------------------- A 真实夹具
        System.out.println("== A 真实播放页夹具 (zqkhmy) ==");
        String html = read(new File(dir, "p1_line8.html"));
        if (html.isEmpty()) {
            System.out.println("  缺少夹具 " + dir + "/p1_line8.html");
            System.out.println("  合计 pass=0 fail=1");
            System.exit(1);
        }
        MacPlayer.Info info = MacPlayer.INSTANCE.parseInfo(html);
        ok("A1 抠到 player_aaaa", info != null, "info=null");
        ok("A2 from 是 co", info != null && "co".equals(info.getFrom()),
                info == null ? "" : info.getFrom());
        ok("A3 url 是令牌", info != null && "co_e5a2tzd6xi4age3dnjrq".equals(info.getUrl()),
                info == null ? "" : info.getUrl());
        ok("A4 encrypt=0", info != null && info.getEncrypt() == 0, "");

        Map<String, MacPlayer.Line> lines = MacPlayer.INSTANCE.parseLines(html);
        // 真实站点的 player_list **不在播放页里**，而在外链的 /static/js/playerconfig.js。
        // 这条断言把这个事实钉住，免得以后有人"顺手"把它删掉。
        ok("A5 播放页内不含 player_list（配置在外链 js）", lines.isEmpty(), "got " + lines.size());
        String cfg = read(new File(dir, "playerconfig.js"));
        ok("A6 找到 playerconfig.js 地址",
                "https://www.zqkhmy.com/static/js/playerconfig.js?t=20260918".equals(
                        MacPlayer.INSTANCE.configScriptUrl(
                                html, "https://www.zqkhmy.com/play/20245-8-181.html")),
                String.valueOf(MacPlayer.INSTANCE.configScriptUrl(
                        html, "https://www.zqkhmy.com/play/20245-8-181.html")));
        lines = MacPlayer.INSTANCE.parseLines(cfg);
        ok("A7 外链配置解析出 15 条线路", lines.size() == 15, "got " + lines.size());
        MacPlayer.Line co = lines.get("co");
        ok("A8 co.ps=1", co != null && co.getPs() == 1, String.valueOf(co));
        ok("A9 co.parse 模板",
                co != null && "https://zzrs.mfdyvip.com/player/?url=".equals(co.getParse()),
                co == null ? "" : co.getParse());
        MacPlayer.Line dytt = lines.get("dyttm3u8");
        ok("A10 dyttm3u8.ps=0（自营直链）", dytt != null && dytt.getPs() == 0, String.valueOf(dytt));

        String page = MacPlayer.INSTANCE.playPage(info, lines, MacPlayer.INSTANCE.parseGlobalParse(html));
        ok("A11 拼出解析页",
                "https://zzrs.mfdyvip.com/player/?url=co_e5a2tzd6xi4age3dnjrq".equals(page),
                String.valueOf(page));

        // 自营线路不允许被"拼"出去（url 本身就是地址，拼了就废）
        MacPlayer.Info selfInfo = MacPlayer.INSTANCE.infoFromJson(
                "{\"from\":\"dyttm3u8\",\"url\":\"https://vip.dytt-tvs.com/a/index.m3u8\",\"encrypt\":0}");
        ok("A12 自营线路不拼解析页",
                MacPlayer.INSTANCE.playPage(selfInfo, lines, "") == null, "");

        // 第二条失败线（vwnet）也要能对上
        String h9 = read(new File(dir, "p3_line9.html"));
        MacPlayer.Info i9 = MacPlayer.INSTANCE.parseInfo(h9);
        String pg9 = MacPlayer.INSTANCE.playPage(i9, lines, MacPlayer.INSTANCE.parseGlobalParse(h9));
        ok("A13 vwnet 也拼得出",
                pg9 != null && pg9.endsWith("vwnet-9e1fa4a05a561ff53348d4cf2cf5f66b"),
                String.valueOf(pg9));

        String h5 = read(new File(dir, "p5_line5.html"));
        MacPlayer.Info i5 = MacPlayer.INSTANCE.parseInfo(h5);
        String pg5 = MacPlayer.INSTANCE.playPage(i5, lines, "");
        ok("A14 JD4K 令牌读到",
                i5 != null && "JD-3e1a68d5f69bd10ed47b9b85c749eab40".equals(i5.getUrl()),
                i5 == null ? "info=null" : i5.getUrl());
        ok("A15 JD4K 用另一套解析服务",
                pg5 != null && pg5.startsWith("https://fgsrg.hzqingshan.com/player/?url="),
                String.valueOf(pg5));

        // ---------------------------------------------------------- C encrypt
        System.out.println("== C encrypt 解码 ==");
        String enc1 = "var player_aaaa={\"flag\":\"play\",\"encrypt\":1,"
                + "\"url\":\"https%3A%2F%2Fcdn.x.com%2Fa%2Findex.m3u8\",\"url_next\":\"\"};</script>";
        ok("C1 encrypt=1 百分号解码",
                "https://cdn.x.com/a/index.m3u8".equals(Media.INSTANCE.extractFromHtml(enc1)),
                String.valueOf(Media.INSTANCE.extractFromHtml(enc1)));

        String raw2 = "https://cdn.y.com/b/index.m3u8";
        String b64 = Base64.getEncoder().encodeToString(raw2.getBytes(StandardCharsets.UTF_8));
        String enc2 = "player_aaaa={\"encrypt\":2,\"url\":\"" + b64 + "\"};</script>";
        ok("C2 encrypt=2 base64 解码", raw2.equals(Media.INSTANCE.extractFromHtml(enc2)),
                String.valueOf(Media.INSTANCE.extractFromHtml(enc2)));

        // encrypt=2 + URL-safe 字母表 + 缺 padding（真实站常见）
        String urlsafe = b64.replace('+', '-').replace('/', '_').replace("=", "");
        String enc3 = "player_aaaa={\"encrypt\":2,\"url\":\"" + urlsafe + "\"};</script>";
        ok("C3 URL-safe/缺 padding 也能解", raw2.equals(Media.INSTANCE.extractFromHtml(enc3)),
                String.valueOf(Media.INSTANCE.extractFromHtml(enc3)));

        // 令牌不许被误判成地址
        String tok = "player_aaaa={\"encrypt\":0,\"from\":\"co\",\"url\":\"co_abc123\"};</script>";
        ok("C4 令牌不被当成直链", Media.INSTANCE.extractFromHtml(tok) == null,
                String.valueOf(Media.INSTANCE.extractFromHtml(tok)));

        // 嵌套对象（vod_data）不能让 JSON 提前截断
        String nest = "player_aaaa={\"flag\":\"play\",\"encrypt\":0,"
                + "\"vod_data\":{\"vod_name\":\"遮天\"},"
                + "\"url\":\"https://cdn.z.com/c/index.m3u8\"};</script>";
        ok("C5 嵌套对象不截断", "https://cdn.z.com/c/index.m3u8".equals(
                Media.INSTANCE.extractFromHtml(nest)),
                String.valueOf(Media.INSTANCE.extractFromHtml(nest)));

        // encrypt=2 时 url_next 也要解
        String encNext = "player_aaaa={\"encrypt\":2,\"url\":\"" + b64 + "\",\"url_next\":\""
                + b64 + "\"};</script>";
        MacPlayer.Info in = MacPlayer.INSTANCE.infoFromJson(
                "{\"encrypt\":2,\"url\":\"" + b64 + "\",\"url_next\":\"" + b64 + "\"}");
        ok("C6 encrypt=2 同时解 url_next",
                in != null && raw2.equals(in.getUrl()) && raw2.equals(in.getUrlNext()),
                in == null ? "null" : in.getUrlNext());

        // ---------------------------------------------------------- D 全局 parse
        System.out.println("== D 全局面板 parse ==");
        ok("D1 zqkhmy 全局 parse 为空",
                MacPlayer.INSTANCE.parseGlobalParse(html).isEmpty(),
                MacPlayer.INSTANCE.parseGlobalParse(html));

        String withGlobal = "MacPlayerConfig={\"parse\":\"https://jx.example.com/?url=\"};"
                + "MacPlayerConfig.player_list={\"foo\":{\"show\":\"x\",\"ps\":\"1\",\"parse\":\"\"}};"
                + "player_aaaa={\"encrypt\":0,\"from\":\"foo\",\"url\":\"TOKEN1\"};</script>";
        ok("D2 线路 parse 为空时回落全局",
                "https://jx.example.com/?url=TOKEN1".equals(MacPlayer.INSTANCE.playPage(
                        MacPlayer.INSTANCE.parseInfo(withGlobal),
                        MacPlayer.INSTANCE.parseLines(withGlobal),
                        MacPlayer.INSTANCE.parseGlobalParse(withGlobal))),
                String.valueOf(MacPlayer.INSTANCE.playPage(
                        MacPlayer.INSTANCE.parseInfo(withGlobal),
                        MacPlayer.INSTANCE.parseLines(withGlobal),
                        MacPlayer.INSTANCE.parseGlobalParse(withGlobal))));

        // `player_list` 也满足 `player_[A-Za-z0-9_]+` —— 必须被负向断言挡掉，
        // 否则 parseInfo 会把线路表当成播放数据对象（from/url 全空）。
        ok("D3 线路表不被误当播放数据",
                MacPlayer.INSTANCE.parseInfo(
                        "MacPlayerConfig.player_list={\"foo\":{\"show\":\"x\",\"ps\":\"1\",\"parse\":\"\"}};</script>") == null,
                String.valueOf(MacPlayer.INSTANCE.parseInfo(
                        "MacPlayerConfig.player_list={\"foo\":{\"show\":\"x\",\"ps\":\"1\",\"parse\":\"\"}};</script>")));

        // ---------------------------------------------------------- F 边界
        System.out.println("== F 边界 ==");
        Map<String, MacPlayer.Line> ls = MacPlayer.INSTANCE.parseLines(withGlobal);
        MacPlayer.Info unknown = MacPlayer.INSTANCE.infoFromJson(
                "{\"from\":\"nope\",\"url\":\"T\",\"encrypt\":0}");
        ok("F1 未知线路不拼", MacPlayer.INSTANCE.playPage(unknown, ls, "") == null, "");
        ok("F2 空 html 不炸", MacPlayer.INSTANCE.parseInfo("") == null
                && MacPlayer.INSTANCE.parseLines("").isEmpty()
                && MacPlayer.INSTANCE.parseGlobalParse(null).isEmpty(), "");
        ok("F3 无 player_aaaa 不炸", MacPlayer.INSTANCE.parseInfo("<html>hi</html>") == null, "");
        ok("F4 坏 JSON 不炸", MacPlayer.INSTANCE.infoFromJson("{oops") == null, "");
        // ps 写成数字（有的站不是字符串）
        String numPs = "player_list={\"bar\":{\"show\":\"y\",\"ps\":1,\"parse\":\"https://p/?url=\"}};";
        MacPlayer.Line bar = MacPlayer.INSTANCE.parseLines(numPs).get("bar");
        ok("F5 ps 为数字也认", bar != null && bar.getPs() == 1, String.valueOf(bar));
        // ps=1 但没有令牌
        MacPlayer.Info emptyTok = MacPlayer.INSTANCE.infoFromJson(
                "{\"from\":\"bar\",\"url\":\"\",\"encrypt\":0}");
        ok("F6 无令牌不拼", MacPlayer.INSTANCE.playPage(emptyTok, MacPlayer.INSTANCE.parseLines(numPs), "") == null, "");
        // 协议相对 / 相对路径补全
        ok("F7 协议相对地址补全",
                "https://cdn.x.com/a.js".equals(MacPlayer.INSTANCE.absUrl(
                        "//cdn.x.com/a.js", "https://www.zqkhmy.com/play/1-1-1.html")),
                MacPlayer.INSTANCE.absUrl("//cdn.x.com/a.js", "https://www.zqkhmy.com/play/1-1-1.html"));
        ok("F8 相对路径补全",
                "https://www.zqkhmy.com/static/js/playerconfig.js".equals(MacPlayer.INSTANCE.absUrl(
                        "/static/js/playerconfig.js", "https://www.zqkhmy.com/play/1-1-1.html")),
                MacPlayer.INSTANCE.absUrl("/static/js/playerconfig.js", "https://www.zqkhmy.com/play/1-1-1.html"));
        ok("F9 已是绝对地址不动",
                "https://a.b/c.js".equals(MacPlayer.INSTANCE.absUrl(
                        "https://a.b/c.js", "https://www.zqkhmy.com/x.html")), "");

        System.out.println("==== pass=" + pass + " fail=" + fail + " ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}
