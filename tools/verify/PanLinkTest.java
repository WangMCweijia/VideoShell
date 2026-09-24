import com.videoshell.data.pan.*;
import com.videoshell.data.site.*;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.nio.file.*;
import java.util.*;

/**
 * v1.0.65「网盘分享站族 + 网盘层」的**离线**断言（不打网络、不碰 Android 运行时）。
 *
 * 覆盖四类东西，其中前两类是**行为**断言、后两类是**源码守卫**：
 *
 * | 段 | 打什么 | 为什么非得有它 |
 * |---|---|---|
 * | A | [PanLink.parse] 的 9 类分享链接 + 负例 | 这是整个网盘能力**唯一的入口判据**（自门控）。少一条规则 = 某类分享链接静默无法播放 |
 * | B | `refOf` → `parseRef` 往返 | 每一集的 url 都从这里来。token 是 base64（含 `/` `+` `=`），编码一漏就"列得出集数、点进去取不到流" |
 * | C | [PanShareExtract] 打在**两个真实样本**上 | 快映 / 玩偶两族 HTML 同构是"一个适配器吃两族"的全部依据；样本来自真站截取，站点改版时这份断言会先红 |
 * | D | [PanResolver] 的自然序（纯函数） | `第100集 < 第10集` 是选集乱序的唯一成因，且只在两位/三位数混排时出现 |
 * | E | 接线 / 顺序 / 纪律的源码守卫 | 这些**没有行为观测点**：顺序错了、白名单混进来、直链被落盘，全都会"照样编译、照样自检全绿" |
 *
 * ## 为什么不能断言 [PanCloudDrive] 的行为
 *
 * 它用 `org.json`。离线 harness 挂的是 `android.jar` 的**桩**，`new JSONObject(…)` 在
 * JVM 上直接抛 `Stub!` —— 那不是我们的 bug，是"没有 Android 运行时"。所以这一层的
 * **判据只能落在源码上**（E 段），行为验证留给真机/联网。
 *
 * ## 源码守卫的前提：**先去掉注释**
 *
 * E4 要断的是"判据是形状、不是域名"。而这三个文件的 **KDoc 里就写着**真域名
 * （`快映 http://xsayang.fun 会 302 到 http://43.248.128.118:12512` —— 那是实测记录，
 * 必须有）。所以按裸文本 `contains("xsayang")` 判，红的是守卫自己。
 * 于是先 `stripComments` 再去匹配**字符串字面量里的域名**：
 * 判据若真的依赖域名，它必然以字面量形式出现在代码里 —— 注释里的域名不做任何判定。
 *
 * ⚠️ `stripComments` 是**行级**简易剥离（不处理字符串里的 `//`），只对这三个文件安全；
 * 为此 E4 自带一条**守卫自测**（裸文本必须含 `xsayang`，剥离后必须不含），
 * 免得剥离器哪天失灵、把这条守卫变成恒真。
 */
public class PanLinkTest {

    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            fails.add(name);
            System.out.println("  [FAIL] " + name + "   → " + detail);
        }
    }

    static void eq(String name, Object got, Object want) {
        ok(name, Objects.equals(got, want), "got=" + got + " want=" + want);
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("========== " + s + " ==========");
    }

    static String read(String p) {
        try {
            Path q = Paths.get(p);
            if (!Files.exists(q)) return null;
            return new String(Files.readAllBytes(q), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** 去 Kotlin 的块注释与行注释（行级简易剥离；用途与限制见文件头） */
    static String stripComments(String s) {
        StringBuilder b = new StringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    /** 去注释后的代码里，有没有哪个**字符串字面量**长得像域名 */
    static final java.util.regex.Pattern DOM = java.util.regex.Pattern.compile(
            "[A-Za-z0-9-]+\\.(?:com|cn|net|org|fun|live|cc|tv|top|xyz|io|me)(?![A-Za-z0-9])");

    static String domainLiteral(String code) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"([^\"\n]*)\"").matcher(code);
        while (m.find()) {
            if (DOM.matcher(m.group(1)).find()) return m.group(1);
        }
        return null;
    }

    static String pan(PanLink l) {
        return l == null ? "null" : (l.getType().getKey() + ":" + l.getId()
                + (l.getPwd().isEmpty() ? "" : "?" + l.getPwd()));
    }

    public static void main(String[] args) throws Exception {
        String SAMPLES = args.length > 0 ? args[0] : ".";
        String PROJ = args.length > 1 ? args[1] : ".";

        PanLink.Companion L = PanLink.Companion;

        // ------------------------------------------------------------------ A
        banner("A. PanLink.parse —— 各类分享链接（自门控入口的唯一判据）");

        Object[][] pos = {
                // 链接, 期望 type, 期望 id, 期望 pwd
                {"https://pan.quark.cn/s/9cb3db399337", PanType.QUARK, "9cb3db399337", ""},
                {"https://pan.quark.cn/s/dbc851025443", PanType.QUARK, "dbc851025443", ""},
                {"https://pan.quark.cn/s/abcdef012345?pwd=1234", PanType.QUARK, "abcdef012345", "1234"},
                {"https://drive.uc.cn/s/dbc851025443", PanType.UC, "dbc851025443", ""},
                {"https://www.alipan.com/s/xYz1234567890", PanType.ALI, "xYz1234567890", ""},
                {"https://www.aliyundrive.com/s/xYz1234567890", PanType.ALI, "xYz1234567890", ""},
                {"https://www.123pan.com/s/abcdEFgh", PanType.CLOUD123, "abcdEFgh", ""},
                {"https://www.123865.com/s/abcdEFgh", PanType.CLOUD123, "abcdEFgh", ""},
                {"https://cloud.189.cn/t/AbCdEf123", PanType.CLOUD189, "AbCdEf123", ""},
                {"https://cloud.189.cn/share.html#/t/AbCdEf123", PanType.CLOUD189, "AbCdEf123", ""},
                {"https://pan.xunlei.com/s/VN_abc-123XYZ", PanType.XUNLEI, "VN_abc-123XYZ", ""},
                {"https://www.guangyapan.com/s/abcd1234efgh", PanType.GUANGYA, "abcd1234efgh", ""},
                // 百度：`s/1{code}` 的那个 `1` 是 surl 固定前缀，必须拼回 id 里
                {"https://pan.baidu.com/s/1P4_20eORxopHzDUgW9weew?pwd=6107",
                        PanType.BAIDU, "1P4_20eORxopHzDUgW9weew", "6107"},
                {"https://yun.139.com/w/i/abc123xyz", PanType.MOBILE, "abc123xyz", ""},
                // 115（v1.0.72 补）：木偶站「115臻享」分类贴的全是这一家，且提取码参数叫
                // `password` 而不是 `pwd` —— 认不出它会让**整站**被判成「不是网盘分享站族」（E54）
                {"https://115cdn.com/s/swsagii36dh?password=f9e3",
                        PanType.CLOUD115, "swsagii36dh", "f9e3"},
                {"https://115.com/s/swsagii36dh", PanType.CLOUD115, "swsagii36dh", ""},
        };
        // 每个类型**至少**有一条正例 —— 少一条就是"某类分享链接静默无法播放"
        Set<PanType> covered = new HashSet<>();
        for (Object[] c : pos) {
            PanLink l = L.parse((String) c[0]);
            String want = ((PanType) c[1]).getKey() + ":" + c[2]
                    + (((String) c[3]).isEmpty() ? "" : "?" + c[3]);
            eq("A " + c[1] + " ⟵ " + ((String) c[0]).substring(0, Math.min(46, ((String) c[0]).length())),
                    pan(l), want);
            if (l != null) covered.add(l.getType());
        }
        eq("A 覆盖了全部 " + PanType.values().length + " 类网盘", covered.size(), PanType.values().length);

        // 负例：**一个都不能误判** —— 自门控入口误判的代价是正常站点被拖进网盘链路
        String[] neg = {
                null, "", "   ",
                "https://www.example.com/vod/detail/id/465377.html",
                // ★ 这两条锁的是"分享 id 的字符类排除 `.`"（离线套件抓出来的真误判）：
                //   旧字符类下 `s/1.html` 会被解析成 `baidu:1.html`（长度过闸）⇒ 任何引用了
                //   百度页面的普通站点都被拖进网盘链路；排除 `.` 后 id 只剩 `1`/`abc`，长度不够 ⇒ 挡掉。
                //   ⚠️ 刻意**不**断言 `pan.quark.cn/s/{合法id}.html`：那种地址会被当成合法分享
                //   （多出来的 `.html` 只是路径噪声，接口只认 id）—— 那是对的，不该锁成红的。
                "https://pan.baidu.com/s/1.html",
                "https://pan.quark.cn/s/abc.html",
                "https://pan.quark.cn/",
                "https://pan.quark.cn/s/ab",                       // id 太短（<4）
                "https://drive.uc.cn/",                            // 没有 /s/
                "https://www.123pan.com/",                         // 没有分享段
                "https://115.com/",                                // 115 表里没有 /s/ 段 → 拒
                "https://115.com/s/ab",                            // 115 分享 id 太短（<4）→ 拒
                "https://115cdn.com/s/",                           // 115 表里 id 为空 → 拒
                "139.com/linkID=1234567890",                       // ★ 移动云盘正则刻意收紧挡掉的那一类
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                "https://cdn.example.com/video/第01集/index.m3u8",
        };
        for (String s : neg) {
            PanLink l = L.parse(s);
            ok("A 负例不被认成网盘链接：" + (s == null ? "null" : s.length() > 40 ? s.substring(0, 40) + "…" : s),
                    l == null, "got=" + pan(l));
            ok("A isPan 与 parse 一致（" + (s == null ? "null" : "…") + "）",
                    L.isPan(s) == (l != null), "isPan=" + L.isPan(s));
        }

        // ------------------------------------------------------------------ B
        banner("B. 站内引用 refOf → parseRef 往返（每一集的 url 都从这里来）");

        PanLink q = L.parse("https://pan.quark.cn/s/9cb3db399337");
        String fid = "1a88470264f34a26aa0360f9e1944cb8";
        // ★ base64 里同时带 `/` `+` `=`：不编码就会把引用切成 5 段 ⇒ parseRef 直接失败
        String tok = "477ca06e09b139d312a518d5bb6303eb";
        String tokB64 = "HznWcxkfEKexDgTSAx9/Joenbq3nRdOnjA4KVaE0T2c=";
        String ref = L.refOf(q, fid, tokB64);
        ok("B refOf 产出的引用被 isRef 认出来", L.isRef(ref), ref);
        PanRef r1 = L.parseRef(ref);
        ok("B parseRef 解得开", r1 != null, "token=" + tokB64);
        if (r1 != null) {
            eq("B 类型/分享 id 还原", pan(r1.getLink()), "quark:9cb3db399337");
            eq("B fid 还原", r1.getFid(), fid);
            eq("B token 还原（base64 的 / + = 必须原样回来）", r1.getToken(), tokB64);
        }
        String ref2 = L.refOf(q, fid, tok) + "?pwd=1234";
        eq("B 带提取码的引用（pwd 段）", L.parseRef(ref2) == null ? "null"
                : pan(L.parseRef(ref2).getLink()), "quark:9cb3db399337?1234");

        ok("B isRef 不认普通 http 链接", !L.isRef("https://pan.quark.cn/s/9cb3db399337"), "");
        ok("B isRef(null) = false", !L.isRef(null), "");
        ok("B parseRef 对非引用返回 null", L.parseRef("https://pan.quark.cn/s/x") == null, "");
        ok("B parseRef 对未知网盘类型返回 null",
                L.parseRef("panref://nope/1234567890/fid/tok") == null, "");
        ok("B parseRef 段数不足返回 null",
                L.parseRef("panref://quark/1234567890/fid") == null, "");
        ok("B parseRef 对空 fid 返回 null",
                L.parseRef("panref://quark/1234567890//tok") == null, "");

        // ------------------------------------------------------------------ C
        banner("C. PanShareExtract —— 打在两个**真实样本**上（快映 / 玩偶 HTML 同构）");

        Document ky = doc(SAMPLES, "ky_detail.html");
        Document wg = doc(SAMPLES, "wg_detail.html");
        if (ky == null || wg == null) {
            ok("C 样本齐备（samples/panshare/{ky,wg}_detail.html）", false,
                    "缺样本：ky=" + (ky != null) + " wg=" + (wg != null) + " dir=" + SAMPLES);
        } else {
            List<String> kn = PanShareExtract.INSTANCE.lineNames(ky);
            List<String> kl = PanShareExtract.INSTANCE.shareLinks(ky);
            eq("C 快映 D1 线路名（按序、保留重复）", kn, Arrays.asList("百度", "夸克网盘"));
            eq("C 快映 D2 分享链接**按值去重**后 = 2（原始 4 处，每条在行内出现两次）",
                    kl.size(), 2);
            eq("C 快映首条 = 百度（顺序不能反）", kl.isEmpty() ? "" : kl.get(0),
                    "https://pan.baidu.com/s/1P4_20eORxopHzDUgW9weew?pwd=6107");
            eq("C 快映次条 = 夸克", kl.size() < 2 ? "" : kl.get(1),
                    "https://pan.quark.cn/s/9cb3db399337");
            ok("C 快映 isPanSharePage（D1 && D2）",
                    PanShareExtract.INSTANCE.isPanSharePage(ky), "");
            String ke = PanShareExtract.INSTANCE.evidence(ky, read(samplePath(SAMPLES, "ky_detail.html")));
            ok("C 快映证据串含 D1=2 / D2=2 / D3 player_aaaa=0 ：" + ke,
                    ke.contains("D1 线路=2") && ke.contains("D2 分享链接=2")
                            && ke.contains("D3 player_aaaa=0"), ke);

            List<String> wn = PanShareExtract.INSTANCE.lineNames(wg);
            List<String> wl = PanShareExtract.INSTANCE.shareLinks(wg);
            // ★ 这条锁的是"**刻意不去重**"这个设计：玩偶两条线路都叫"夸克网盘"
            eq("C 玩偶 D1 保留两条同名线路（去重会让名字数(1)与链接数(2)对不上）",
                    wn, Arrays.asList("夸克网盘", "夸克网盘"));
            eq("C 玩偶 D2 分享链接 = 2", wl, Arrays.asList(
                    "https://pan.quark.cn/s/dbc851025443",
                    "https://pan.quark.cn/s/e2ff7b4cb566"));
            ok("C 玩偶 isPanSharePage", PanShareExtract.INSTANCE.isPanSharePage(wg), "");
            String we = PanShareExtract.INSTANCE.evidence(wg, read(samplePath(SAMPLES, "wg_detail.html")));
            ok("C 玩偶证据串含 D4 module-item>0 ：" + we,
                    we.contains("D2 分享链接=2") && !we.contains("D4 module-item=0 处"), we);
        }

        // 负例：不是网盘分享页的三种形态，一条都不能认
        String plain = "<html><body><div class=\"module-list\">"
                + "<a data-clipboard-text=\"https://www.example.com/not-a-pan\">复制</a>"
                + "<div class=\"module-tab-item\"><span data-dropdown-value=\"线路一\">1</span></div>"
                + "</div></body></html>";
        ok("C 负例：有复制按钮但**值不是网盘链接** ⇒ 不算网盘分享页",
                !PanShareExtract.INSTANCE.isPanSharePage(Jsoup.parse(plain)), "");
        String noTab = "<html><body><div class=\"module-row-one\">"
                + "<a data-clipboard-text=\"https://pan.quark.cn/s/9cb3db399337\">复制</a>"
                + "</div></body></html>";
        ok("C 负例：有网盘链接但没有 downtab 线路（D1 缺） ⇒ 不算网盘分享页",
                !PanShareExtract.INSTANCE.isPanSharePage(Jsoup.parse(noTab)), "");
        String maccms = "<html><body><script>var player_aaaa={url:'https://a.b/c.m3u8'}</script>"
                + "<div class=\"module-item\"><a href=\"/vod/play/id/1.html\">第01集</a></div>"
                + "</body></html>";
        ok("C 负例：普通 maccms 详情页（player_aaaa 有值、无 downtab）⇒ 不算网盘分享页",
                !PanShareExtract.INSTANCE.isPanSharePage(Jsoup.parse(maccms)), "");

        // ------------------------------------------------------------------ P
        // v1.0.72 / E54：木偶站的形状 + 那条把整站挡在门外的「兜底假线路」。
        // 木偶页面的 D1/D2 长这样（实测 2026-09-24，`/index.php/vod/detail/id/8623.html`）：
        // 一个「立刻播放」锚点指向**本站播放页**，剪贴板里挂着 **115 网盘**分享链接
        // （而 115 曾经不在 `PanLink` 的识别表里 ⇒ D2 不成立 ⇒ **整站**被判「不是网盘分享站族」）。
        banner("P. 木偶站的形状：只贴 115 的页面也必须被认成网盘分享页 + 兜底假线路的对照组");
        String moHead = "<html><body>"
                + "<h1>话事人</h1>"
                + "<div class=\"video-cover\"><div class=\"module-item-pic\">"
                + "<a href=\"/index.php/vod/play/id/8623/sid/1/nid/1.html\" title=\"立刻播放话事人\">"
                + "<i class=\"icon-play\"></i></a></div></div>";
        String moDownload = "<div class=\"module\" id=\"download-list\">"
                + "<div class=\"module-tab-content\">"
                + "<div class=\"module-tab-item downtab-item selected\">"
                + "<span data-dropdown-value=\"115网盘\">115网盘</span></div></div>"
                + "<div class=\"module-list module-downlist selected\"><div class=\"scroll-box-y\">"
                + "<div class=\"module-row-one\">"
                + "<a class=\"module-row-text copy\" "
                + "data-clipboard-text=\"https://115cdn.com/s/swsagii36dh?password=f9e3\">115网盘</a>"
                + "<a class=\"btn-copyurl copy\" "
                + "data-clipboard-text=\"https://115cdn.com/s/swsagii36dh?password=f9e3\">复制链接</a>"
                + "</div>"
                // 诱饵：真实页面上还有一个"复制本页链接"的剪贴板（值指向本站详情页）——
                // 它绝不能被当成一条线路（PanDrift D4 在真样本上锁的是同一件事）
                + "<div class=\"module-row-one\"><a class=\"copy\" "
                + "data-clipboard-text=\"/index.php/vod/detail/id/8623.html 我正在自用求大佬不要爬\">"
                + "复制本页</a></div>"
                + "</div></div></div></body></html>";
        String moA = moHead + moDownload;
        String moB = moDownload + "</body></html>";      // ★ 对照组：只删掉那个「立刻播放」锚点

        PanLink mo115 = L.parse("https://115cdn.com/s/swsagii36dh?password=f9e3");
        ok("P1 115 分享能被认出来（415cdn 那条实测链接）",
                mo115 != null && mo115.getType() == PanType.CLOUD115
                        && "swsagii36dh".equals(mo115.getId())
                        && "f9e3".equals(mo115.getPwd()),
                "got=" + pan(mo115));
        Document dmoA = Jsoup.parse(moA, "https://666.666291.xyz");
        ok("P2 ★ 只贴 115 的页面也算网盘分享页（否则整站被判「不是这一族」，连累它贴夸克的标题）",
                PanShareExtract.INSTANCE.isPanSharePage(dmoA), "");
        eq("P2b 那一条诱饵剪贴板不算线路（按值去重后只有 1 条）",
                PanShareExtract.INSTANCE.shareLinks(dmoA).size(), 1);

        List<com.videoshell.data.model.PlayGroup> gA =
                HtmlExtractor.INSTANCE.parseGroups(dmoA, "https://666.666291.xyz");
        eq("P3 原链路在这个页面上「成功」了 —— 但只成功了一条兜底线路", gA.size(), 1);
        ok("P4 ★ 那条兜底线路的名字就是 FALLBACK_LINE（PanShareAdapter 的判据锚在它上面）",
                gA.size() == 1 && HtmlExtractor.FALLBACK_LINE.equals(gA.get(0).getName()),
                gA.isEmpty() ? "(空)" : gA.get(0).getName());
        // ★ 对照组：**只删掉那个播放页锚点**，兜底就不该再产出任何线路 ——
        //   证明 P3/P4 观测到的是"那个锚点造成的兜底"，不是这个方法无脑返回一条线路。
        List<com.videoshell.data.model.PlayGroup> gB =
                HtmlExtractor.INSTANCE.parseGroups(Jsoup.parse(moB, "https://666.666291.xyz"),
                        "https://666.666291.xyz");
        eq("P5 ★ 对照组：删掉播放页锚点后 parseGroups 不再产出线路", gB.size(), 0);
        // 字面量锁：`buildDetail` 是靠 == 比较这个名字的，改了常量而这里没跟着改就会**静默失效**
        eq("P6 FALLBACK_LINE 的值被锁住（改名必须同时改这里，否则判据静默失效）",
                HtmlExtractor.FALLBACK_LINE, "默认线路");

        // ------------------------------------------------------------------ D
        banner("D. PanResolver 自然序（纯函数：第100集 < 第10集 是选集乱序的唯一成因）");

        List<PanFile> files = new ArrayList<>(Arrays.asList(
                f("f1", "第1集.mp4"), f("f2", "第10集.mp4"),
                f("f3", "第2集.mp4"), f("f4", "第100集.mp4"),
                f("f5", "第9集.mp4")));
        List<PanFile> sorted = PanResolver.INSTANCE.naturalSort(files);
        List<String> got = new ArrayList<>();
        for (PanFile x : sorted) got.add(x.getName());
        eq("D 自然序：1 < 2 < 9 < 10 < 100（纯字典序会排成 1 < 10 < 100 < 2 < 9）",
                got, Arrays.asList("第1集.mp4", "第2集.mp4", "第9集.mp4",
                        "第10集.mp4", "第100集.mp4"));
        ok("D naturalCompare(第9集, 第10集) < 0",
                PanResolver.INSTANCE.naturalCompare("第9集.mp4", "第10集.mp4") < 0, "");
        ok("D naturalCompare(第100集, 第99集) > 0",
                PanResolver.INSTANCE.naturalCompare("第100集.mp4", "第99集.mp4") > 0, "");
        ok("D 前导零不算数（第01集 == 第1集）",
                PanResolver.INSTANCE.naturalCompare("第01集.mp4", "第1集.mp4") == 0, "");
        ok("D 超长数字不溢出（不比 toInt）",
                PanResolver.INSTANCE.naturalCompare(
                        "第" + "9".repeat(30) + "集.mp4", "第" + "9".repeat(29) + "集.mp4") > 0, "");
        ok("D 大小写不敏感", PanResolver.INSTANCE.naturalCompare("A.mp4", "a.mp4") == 0, "");

        ok("D 视频后缀判据：mp4/mkv/ts 算视频", f("x", "第1集.MKV").isVideo()
                && f("x", "a.ts").isVideo() && f("x", "a.mp4").isVideo(), "");
        ok("D 视频后缀判据：txt/nfo/目录 不算视频",
                !f("x", "readme.txt").isVideo() && !f("x", "a.nfo").isVideo()
                        && !new PanFile("x", "第1集.mp4", true, 0L, "").isVideo(), "");

        // ------------------------------------------------------------------ E
        banner("E. 接线 / 顺序 / 纪律的源码守卫（没有行为观测点的那几条）");

        String at = read(PROJ + "/app/src/main/java/com/videoshell/data/site/SiteAdapter.kt");
        int h = at == null ? -1 : at.indexOf("PanResolver.handles(u)");
        int d = at == null ? -1 : at.indexOf("Media.isDirect(u)");
        ok("E1 SiteAdapter.resolve 第 0 步是网盘判据，且在 Media.isDirect 之前（顺序守卫）",
                h > 0 && d > 0 && h < d, "handles@" + h + " isDirect@" + d);

        String af = read(PROJ + "/app/src/main/java/com/videoshell/data/site/AdapterFactory.kt");
        int pa = af == null ? -1 : af.indexOf("PanShareAdapter(site)");
        int api = af == null ? -1 : af.indexOf("site.apiUrl.isNotBlank()");
        int sr = af == null ? -1 : af.indexOf("SeedRouter(site)");
        ok("E2 AdapterFactory 先读网盘族三态缓存（只读缓存 ⇒ 零网络）",
                af != null && af.contains("PanShareFamily.cachedState")
                        && af.contains("PanShareFamily.State.Hit") && pa > 0, "");
        ok("E2 AdapterFactory 的网盘分支在**采集接口之前**（快映的伪 maccms 接口会让兜底链永远走不到）",
                pa > 0 && api > 0 && sr > 0 && pa < api && pa < sr,
                "PanShare@" + pa + " apiUrl@" + api + " SeedRouter@" + sr);

        String fr2 = read(PROJ + "/app/src/main/java/com/videoshell/data/site/FamilyRouter.kt");
        ok("E3 FamilyRouter 的兜底从 HtmlAdapter 换成 PanShareAdapter（装饰器）",
                fr2 != null && fr2.contains("PanShareAdapter(site)"), "");

        String psr = read(PROJ + "/app/src/main/java/com/videoshell/data/pan/PanResolver.kt");
        String pcd = read(PROJ + "/app/src/main/java/com/videoshell/data/pan/PanCloudDrive.kt");
        boolean leak = hasAny(psr, "RecipeStore", "ListCache", "HistEntry", "FavEntry")
                || hasAny(pcd, "RecipeStore", "ListCache", "HistEntry", "FavEntry");
        ok("E4 网盘层不碰任何落盘存储（**直链带时效 ⇒ 绝不落盘**）", !leak, "有落盘引用");

        String psaPath = PROJ + "/app/src/main/java/com/videoshell/data/site/PanShareAdapter.kt";
        String psfPath = PROJ + "/app/src/main/java/com/videoshell/data/site/PanShareFamily.kt";
        String psePath = PROJ + "/app/src/main/java/com/videoshell/data/site/PanShareExtract.kt";
        String psa = read(psaPath), psf = read(psfPath), pse = read(psePath);
        // 守卫自测：裸文本里**确实**有实测域名（否则下面那条断言会退化成恒真）
        ok("E5 守卫自测：这三个文件的**注释**里确实写着实测域名（否则 E5 是恒真断言）",
                psf != null && psf.contains("xsayang"), "剥离器/样本都变了就得复查这条");
        StringBuilder bad = new StringBuilder();
        String[] names = {"PanShareAdapter", "PanShareFamily", "PanShareExtract"};
        String[] texts = {psa, psf, pse};
        for (int i = 0; i < names.length; i++) {
            if (texts[i] == null) {
                bad.append(names[i]).append("(读不到) ");
                continue;
            }
            String hit = domainLiteral(stripComments(texts[i]));
            if (hit != null) bad.append(names[i]).append("→").append(hit).append(' ');
        }
        ok("E5 判据是**形状**不是域名：去注释后的代码里没有任何域名串面量（域名就是跳板）",
                bad.length() == 0, "命中：" + bad);

        String models = read(PROJ + "/app/src/main/java/com/videoshell/data/model/Models.kt");
        String pm = read(PROJ + "/app/src/main/java/com/videoshell/player/PlayerActivity_Media.kt");
        ok("E6 网盘直链的内容类型有管道可走（没扩展名的直链会被 media3 判成 progressive）",
                models != null && models.contains("val mimeType: String? = null")
                        && pm != null && pm.contains("setMimeType"), "");

        String det = read(PROJ + "/app/src/main/java/com/videoshell/ui/DetailActivity.kt");
        String pl = read(PROJ + "/app/src/main/java/com/videoshell/player/PlayerActivity_Play.kt");
        String da = read(PROJ + "/app/src/main/java/com/videoshell/ui/DriveAccountsActivity.kt");
        String ma = read(PROJ + "/app/src/main/java/com/videoshell/ui/MainActivity.kt");
        String mf = read(PROJ + "/app/src/main/AndroidManifest.xml");
        ok("E7 「未登录」有可执行的下一步（详情页/播放页都弹去登录，账号页有入口）",
                det != null && det.contains("MediaSource.NeedLogin")
                        && pl != null && pl.contains("MediaSource.NeedLogin")
                        && da != null && da.contains("fun Activity.askDriveLogin")
                        && ma != null && ma.contains("DriveAccountsActivity.intent")
                        && mf != null && mf.contains(".ui.DriveAccountsActivity"), "");

        ok("E8 失败原因三分类不丢（未登录 / 分享失效 / 接口变了）",
                pcd != null && pcd.contains("31001") && pcd.contains("41006")
                        && pcd.contains("require login")
                        && pcd.contains("PanError.Dead") && pcd.contains("PanError.Broken"), "");

        ok("E9 取流头必须显式带 Cookie（播放器分片走另一个 OkHttp，不会自动带凭据）",
                pcd != null && pcd.contains("\"Cookie\" to ck"), "");

        ok("E10 PanShareAdapter 暴露 underlying（离线 harness 的 Chains 靠它剥到底）",
                psa != null && psa.contains("val underlying: SiteAdapter get() = html"), "");

        int hd = psa == null ? -1 : psa.indexOf("html.detail(id)");
        int ab = psa == null ? -1 : psa.indexOf("PanShareFamily.markAbsent");
        ok("E11 否定只在**原链路成功之后**才写（顺序守卫：展开失败不能给已命中站点翻案）",
                hd > 0 && ab > 0 && hd < ab, "detail@" + hd + " markAbsent@" + ab);
        ok("E12 命中落盘 + 装饰器真的在委托（不是重写一遍 HtmlAdapter）",
                psa != null && psa.contains("PanShareFamily.markHit(site.baseUrl)")
                        && psa.contains("html.buildDetail(") && psa.contains("html.detailUrlFor(id)"), "");

        // ------------------------------------------------- 取流入口（v1.0.65 实测校正）
        // 这几条守的是**实测换来的事实**，不是风格：改回去就静默坏掉（23018 / 14001 都不
        // 会抛异常，只会"解析成功但没有流"或"用户网盘悄悄堆满转存产物"）。
        // ⚠️ 必须去注释：KDoc 里**故意**写着 `file/download`（记录"这是死路，别再试"），
        // 按裸文本判会让守卫自己红 —— 与 E4/E5 同一个坑。
        String pcdCode = pcd == null ? null : stripComments(pcd);
        ok("E13a 守卫自测：KDoc 里**保留**着 file/download 的实测记录（删了它 E13 就退化成恒真）",
                pcd != null && pcd.contains("/file/download"),
                "KDoc 里的实测记录没了 ⇒ 复查 E13 还有没有意义");
        ok("E13 取流走 file/v2/play（`file/download` 是死路：带登录凭据也一律 400 code:23018）",
                pcdCode != null && pcdCode.contains("/file/v2/play?")
                        && pcdCode.contains("default_resolution")
                        && !pcdCode.contains("/file/download"),
                pcdCode != null && pcdCode.contains("/file/download") ? "代码里仍调用 file/download" : "");
        ok("E13b 有 GET /file/play 退路（两档 raw/low 实测都能出 URL）",
                pcdCode != null && pcdCode.contains("/file/play?") && pcdCode.contains("\"raw\"")
                        && pcdCode.contains("\"low\""), "");

        ok("E14 删除转存产物的 body 是 filelist（原用 fids ⇒ 恒 400 code:14001，静默失败）",
                pcd != null && pcd.contains("put(\"filelist\"")
                        && !pcd.contains("put(\"fids\"")
                        && !pcd.contains("put(\"current_dir_fid\""), "");
        int mkUrl = pcd == null ? -1 : pcd.indexOf("playUrl(saved, ck)");
        int del = pcd == null ? -1 : pcd.indexOf("delete(saved, ck)");
        ok("E14b 先取 URL 再删产物（实测：删掉之后已签发的 m3u8/ts 仍 200 ⇒ 可以立刻清）",
                mkUrl > 0 && del > 0 && mkUrl < del, "playUrl@" + mkUrl + " delete@" + del);
        // v1.0.65 追加：清理这件事的三个**静默**性质，只有实测能发现
        ok("E14c 清理不许进重试自旋、也不许报成「播放失败」（delete 走 Http.postJsonOnceRaw）",
                pcdCode != null && pcdCode.contains("Http.postJsonOnceRaw(")
                        && !pcdCode.contains("postJson(\"$apiBase/file/delete"),
                "清理又走了会写 err 的包装 / 会自旋的包装 ⇒ 要么误报播放失败，要么白等 2.4s");
        ok("E14d KDoc 保留实测记录（异步任务 / 播放会话锁定 / code:15000）",
                pcd != null && pcd.contains("15000") && pcd.contains("异步任务")
                        && pcd.contains("45 秒") && pcd.contains("2 分钟"),
                "实测记录被删 ⇒ 下次又会写成「拿到 URL 就立刻删」");
        // 这条是本轮**唯一**一个"必败路径"的守卫：v2/play 打开播放会话后几百秒内删不掉，
        // 所以"记账 + 下次顺手清"是功能正确性的一部分，不是优化。
        int sweepCall = pcdCode == null ? -1 : pcdCode.indexOf("sweepPending(ck)");
        int saveCall = pcdCode == null ? -1 : pcdCode.indexOf("saveToMyDrive(ref, ck)");
        ok("E14e 转存产物是**记账 + 下次顺手清**，不是拿到 URL 就硬删（实测硬删必败：500 ×4）",
                pcdCode != null && pcdCode.contains("rememberPending(saved)")
                        && pcdCode.contains("sweepPending(")
                        && sweepCall > 0 && saveCall > 0 && sweepCall < saveCall,
                "sweepPending@" + sweepCall + " saveToMyDrive@" + saveCall);
        // 4xx（23004 已删除 / 14001 参数错）必须出队：否则"本来就没问题"的条目会永远重试
        ok("E14f 4xx 直接出队、只有 5xx/网络才留队列（否则 23004 会永久重试）",
                pcdCode != null && pcdCode.contains("in 400..499")
                        && pcdCode.contains("postJsonOnceRaw"),
                "又变成「失败就一直重试」⇒ 每次取流都白花 0.3s");

        ok("E15 播放头只带白名单 Cookie 键（__puus 单键即够；__pus/__uid 单独都是 412）",
                pcd != null && pcd.contains("MEDIA_COOKIE_KEYS")
                        && pcd.contains("\"__pus\"") && pcd.contains("\"__puus\""), "");
        ok("E15b 白名单一个都没命中时退回整份 Cookie（键名没见过也不能直接播不了）",
                pcd != null && pcd.contains("mapOf(\"Cookie\" to ck)"), "");

        ok("E16 PanStream 标成 HLS 且自报 MIME（media3 靠 MIME 才会建 HlsMediaSource）",
                pcd != null && pcd.contains("hls = true") && pcd.contains("mime = MIME_HLS")
                        && pcd.contains("application/x-mpegURL"), "");
        String pmr = read(PROJ + "/app/src/main/java/com/videoshell/data/pan/PanResolver.kt");
        ok("E16b PanResolver 把 hls/mime 透传给 MediaSource.Direct",
                pmr != null && pmr.contains("s.headers, s.hls, s.mime"), "");

        // ------------------------------------------- 扫码登录（v1.0.65，用户明确要求）
        // 两句话必须同时成立：① **cookie 绝不内置进软件**，只能用"在软件里登录"拿到的；
        // ② 登录方式**必须支持扫码**。下面几条守的就是这两句话的形状。
        String dlPath = PROJ + "/app/src/main/java/com/videoshell/ui/DriveLoginActivity.kt";
        String dl = read(dlPath);
        String dlCode = dl == null ? null : stripComments(dl);
        String daCode = da == null ? null : stripComments(da);

        ok("E17a 登录是**全屏独立页**（网页版默认就是扫码，塞进对话框二维码已扫不动）",
                dl != null && dl.contains("class DriveLoginActivity")
                        && dl.contains("ActivityDriveLoginBinding")
                        && mf != null && mf.contains(".ui.DriveLoginActivity"), "");
        ok("E17b 账号页不再自己造 WebView，只负责跳过去（同一件事一处实现，改一处不会漏另一处）",
                daCode != null && daCode.contains("DriveLoginActivity.intent")
                        && !daCode.contains("WebViewClient")
                        && !daCode.contains("settings.userAgentString"), "");

        // 三个"必须显式打开"的开关，少任何一个都是**静默**失败：扫了没反应 / 白页 / 不跳转
        ok("E17c 三个开关一个都不能少（第三方 Cookie / window.open 内联 / DOM Storage）",
                dlCode != null && dlCode.contains("setAcceptThirdPartyCookies")
                        && dlCode.contains("setSupportMultipleWindows(false)")
                        && dlCode.contains("domStorageEnabled = true"),
                "少一个的症状分别是：扫完仍显示未登录 / 登录页卡白屏 / 点登录没反应");
        ok("E17d UA 固定桌面版（走 PC 接口；且 PC 网页才有二维码，移动 UA 会往 App 里跳）",
                dlCode != null && dlCode.contains("userAgentString = DESKTOP_UA")
                        && dlCode.contains("Windows NT"), "");

        // E37 那个"看着登录了、一播就 401"就是"点早了"造成的：写入之前必须先体检
        int chk = dlCode == null ? -1 : dlCode.indexOf("markers.none");
        int put = dlCode == null ? -1 : dlCode.indexOf("DriveStore.setCookie");
        ok("E17e 落盘前先体检且顺序在前（没有登录标记键就不写，免得存下一份空壳）",
                dlCode != null && dlCode.contains("loginMarkers(type)")
                        && chk > 0 && put > 0 && chk < put,
                "check@" + chk + " setCookie@" + put);
        ok("E17f 存完要作废目录缓存（换账号 ⇒ 权限不同，留着旧树就是「看得见、取不到」）",
                dlCode != null && dlCode.contains("PanResolver.invalidate()"), "");
        ok("E17g 手动兜底仍在（标记键万一被改名，用户还有路可走）",
                dlCode != null && dlCode.contains("captureAndFinish(manual = true)"), "");
        ok("E17h 域名表只有一份（账号页只做转发，自己不留任何 URL 字面量）——两处各写一份必漂移",
                daCode != null && daCode.contains("DriveLoginActivity.hostsOf(t)")
                        && !daCode.contains("\"https://"), "");

        // 「cookie 不内置」的硬证据：源码里可以出现**键名**，绝不能出现 `键=值`
        java.util.List<java.io.File> srcAll = srcFiles(new java.io.File(PROJ + "/app/src/main/java"));
        int keySeen = 0;
        for (java.io.File f : srcAll) {
            String t = read(f.getAbsolutePath());
            if (t != null && t.contains("\"__puus\"")) keySeen++;
        }
        // 自测（对照组）：既确认扫到了足够多的文件，又确认**键名**确实存在 ——
        // 否则 E17i 会因为"路径写错、一个文件都没读到"而恒真通过
        ok("E17i0 守卫自测：扫描确实覆盖源码且能读到键名（否则 E17i 是恒真断言）",
                srcAll.size() > 100 && keySeen > 0,
                "扫到 " + srcAll.size() + " 个文件，其中含「键名」的 " + keySeen + " 个");

        StringBuilder leakWhere = new StringBuilder();
        for (java.io.File f : srcAll) {
            String t = read(f.getAbsolutePath());
            if (t == null) continue;
            for (String k : new String[]{"__puus=", "__pus=", "__uid=", "b-user-id="}) {
                if (t.contains(k)) leakWhere.append(f.getName()).append('/').append(k).append(' ');
            }
        }
        ok("E17i 源码里没有任何 Cookie **值**（键名可以，`键=` 不行）—— 凭据只能来自登录",
                leakWhere.length() == 0, "命中：" + leakWhere);

        // ------------------------------------------------------------------ 汇总
        // ------------------------------------------------ 网盘凭据不能被 CookieJar 覆盖（v1.0.66）
        // 症状：扫码登录**当次**校验通过，之后每次请求都 401 ⇒ 账号页显示"登录已过期"。
        // 机理与判定见 tools/verify/OkHttpCookieJarTest.java（本机起 HTTP 服务**真跑**，
        // 不联网）：OkHttp 的 BridgeInterceptor 会用 CookieJar 无条件覆盖手写的 Cookie 头，
        // 第一次请求后 jar 被服务端 Set-Cookie 填上 ⇒ 整份登录态被替换。
        // 这里守的是**接线**：两个拦截器都在，且 restore 必须挂 network 位。
        String netCode = read(PROJ + "/app/src/main/java/com/videoshell/data/net/Http.kt");
        netCode = netCode == null ? null : stripComments(netCode);
        ok("E18a Http.kt 有「显式 Cookie 优先」的两个拦截器（stash + restore）",
                netCode != null && netCode.contains("stashExplicitCookie")
                        && netCode.contains("restoreExplicitCookie"), "");

        // ★ 位置守卫：restore 必须在 **network** 位。挂成 application 位**不报错、编译也过**，
        //   只是完全无效（它跑在 BridgeInterceptor 之前，照旧被覆盖）——
        //   OkHttpCookieJarTest 的 E1 组就是专门把这条钉死的对照组。
        ok("E18b restoreExplicitCookie 挂在 **network** 位（挂 application 位＝静默失效）",
                netCode != null && netCode.contains("addNetworkInterceptor(restoreExplicitCookie)")
                        && !netCode.contains("addInterceptor(restoreExplicitCookie)"), "");

        ok("E18c stash 挂在 application 位（必须早于 BridgeInterceptor 才拿得到手写值）",
                netCode != null && netCode.contains("addInterceptor(stashExplicitCookie)"), "");

        // 两个自己建的 client 都要挂：client（网盘接口 / 站点解析）+ fastClient（探测）。
        // mediaClient 是 client.newBuilder() 派生的 ⇒ 自动继承，所以播放侧不用再挂一遍
        //（但派生关系一旦被改成"新 Builder"，播放侧的网盘凭据就会静默丢）。
        int stashHooks = netCode == null ? 0 : countOf(netCode, ".addInterceptor(stashExplicitCookie)");
        ok("E18d client 与 fastClient 两个挂载点都在（少一个，那条路径上的凭据就静默丢）",
                stashHooks == 2, "挂载点=" + stashHooks);
        ok("E18e mediaClient 由 client 派生（写死成独立 Builder 会漏掉播放侧）",
                netCode != null && netCode.contains("client.newBuilder()"), "");

        // ------------------------------------------------- 失败文案的可定位性（v1.0.74）
        // 用户手上只有两个出口：**截图**，或**站点自检的报告尾部**。而自检**只能跑站点、
        // 不能指定某一部** ⇒ 失败文案本身必须自证「哪一步 + 信封 code」；解析失败也必须
        // 落进 PlayLog，否则"某一部"的失败原因**没有任何出口**（2026-09-24 真机症状：
        // 一句「分享已失效(file not found…)」，链路上 5 个端点都会报，无从定位）。
        // ⚠️ 必须去注释：本轮新加的注释里就写着「解析失败 / PlayLog」这些字，按裸文本判
        // 会让守卫自己红 —— 与 E13/E4/E5 同一个坑。
        String plCode = pl == null ? null : stripComments(pl);
        ok("E19a 守卫自测：Dead 分支与 PlayLog 记录点都还在（否则 E19/E19b 是恒真断言）",
                pcdCode != null && pcdCode.contains("PanError.Dead(")
                        && plCode != null && plCode.contains("PlayLog.record("),
                "两个记录点有一个没了 ⇒ 复查 E19/E19b 还有没有意义");
        int deadAt = pcdCode == null ? -1 : pcdCode.indexOf("PanError.Dead(");
        String deadTail = deadAt < 0 ? ""
                : pcdCode.substring(deadAt, Math.min(pcdCode.length(), deadAt + 120));
        // ⚠️ 必须断 `${stepAt()}`（**带花括号的调用**），不能只断 `stepAt()`：
        //    Kotlin 的 `$stepAt()` 里 `$stepAt` 只是个**变量引用**、`()` 是字面量文本
        //    ⇒ 编译不过（`e: Function invocation 'stepAt()' expected`）。而 `contains("stepAt()")`
        //    对**两种写法都成立** —— v1.0.74 首轮就是这样"守卫全绿、Build APKs 红"，
        //    成了一条只会粉饰的恒真断言（见 docs/PITFALLS.md §4.74）。
        ok("E19 Dead 文案必须带「哪一步」+ 信封 code（终态不重试 ⇒ 文案是唯一的定位线索）",
                deadTail.contains("${stepAt()}") && deadTail.contains("code $code"),
                "Dead 文案退回成只说「分享已失效」⇒ 5 个端点都会报它，用户截回来也定位不了");
        ok("E19b 解析失败要落 PlayLog（站点自检的报告尾部会带上它 —— 「某一部」唯一的出口）",
                plCode != null && plCode.contains("PlayLog.record(\"✗ 解析失败：${r.message}\")"),
                "解析失败只弹 toast ⇒ 用户没有任何办法把「某一部」的失败原因带回来");

        System.out.println();
        System.out.println("==== pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) {
            System.out.println("失败项：");
            for (String s : fails) System.out.println("  - " + s);
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    static boolean hasAny(String s, String... keys) {
        if (s == null) return false;
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    /** 子串出现次数（用来钉"挂载点有几个"这类计数不变量） */
    static int countOf(String s, String needle) {
        if (s == null || needle == null || needle.isEmpty()) return 0;
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    /** 递归列出源码文件（用来做"全仓范围内不许出现 Cookie 值"这类扫描） */
    static java.util.List<java.io.File> srcFiles(java.io.File dir) {
        java.util.List<java.io.File> out = new java.util.ArrayList<>();
        java.io.File[] ch = dir.listFiles();
        if (ch == null) return out;
        for (java.io.File f : ch) {
            if (f.isDirectory()) out.addAll(srcFiles(f));
            else if (f.getName().endsWith(".kt") || f.getName().endsWith(".xml")) out.add(f);
        }
        return out;
    }

    static PanFile f(String fid, String name) {
        return new PanFile(fid, name, false, 0L, "");
    }

    static String samplePath(String dir, String name) {
        return dir + java.io.File.separator + name;
    }

    static Document doc(String dir, String name) {
        String t = read(samplePath(dir, name));
        return t == null ? null : Jsoup.parse(t);
    }
}
