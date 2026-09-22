import com.videoshell.data.model.*;
import com.videoshell.data.site.*;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * v1.0.17 回归 —— 「个别站点加载不出剧集封面」。
 *
 * ## 用户报的现象
 *
 * 装 v1.0.16 后，某些站点列表/详情的封面全是灰底占位图。
 *
 * ## 根因（一句话：封面地址根本不在 DOM 里）
 *
 * 野果短剧那类 **Nuxt3 / Vue SSR 自研站**，卡片写的是
 * `<img class="loading" src="data:image/gif;base64,R0lGOD…" alt="洞洞杂货铺">` ——
 * `src` 是 **1×1 透明占位 GIF**，真地址由客户端 JS 运行时再填，DOM 里连
 * `data-src` 都没有。而 `HtmlExtractor.picOf` 遇到 `data:` 前缀会跳过 ⇒ 返回空 ⇒
 * 整屏封面全空。真封面在 `<script id="__NUXT_DATA__">` 的 `data.list[]` 里。
 *
 * ## 顺带排除的两个"看起来像"的假设（都有实测数据）
 *
 * - **不是防盗链**：图床 `pic.ndhixj.cn` 对 Referer/UA **完全不敏感** ——
 *   裸请求 / Referer=站点 / Referer=图床自身 / 无 UA，**6 种组合全 200**。
 *   所以本次**没有**去改 Coil 的请求头（改了就是无的放矢）。
 * - **不是 og:image 缺失**：详情页 `og:image` 一直是真海报（`parsePic` 早就优先用它），
 *   坏的一直是**列表页**。但反过来发现一个新问题：列表页/首页的 `og:image` 是**站点级
 *   默认分享图** `social-default.png`，认了它整站每部剧都挂同一张图 —— 一并挡掉。
 *
 * 全部离线，断言只用真实产品代码 + 真实站点抓下来的样本（`_yg/`）。
 */
public class Pic {

    static int pass = 0, fail = 0;
    static final String BASE = "https://capable.fzchosdi.cc";
    static String dir;

    public static void main(String[] args) throws Exception {
        dir = args.length > 0 ? args[0] : "_yg";
        String tagHtml = read("tag.html");        // /tag/AI短剧/ 列表页（payload 里 30 张 cover）
        String detailHtml = read("detail.html");  // 详情页（og:image 是真海报）
        String playHtml = read("e1.html");        // 播放页（5 集 episodeAll）

        // ================================================================ A
        banner("A. payload 封面表 —— 封面只能从内嵌 JSON 取（DOM 里是占位 gif）");
        SsrPayload.Covers cv = SsrPayload.INSTANCE.covers(tagHtml);
        System.out.println("  byId=" + cv.getById().size() + " 条   byName=" + cv.getByName().size() + " 条");
        ok("byId 非空", !cv.getById().isEmpty());
        ok("byName 非空", !cv.getByName().isEmpty());
        ok("byId 至少 20 条（本站列表 30 条）", cv.getById().size() >= 20);
        eq("id=3381 的封面", "https://pic.ndhixj.cn/upload_01/upload/20260917/2026091721151270131.jpeg",
                cv.getById().get("3381"));
        eq("名称也能命中（后备通道）", cv.getById().get("3381"), cv.getByName().get("洞洞杂货铺"));

        boolean allHttp = true, anyMedia = false, anyHost = false;
        for (String u : cv.getById().values()) {
            if (!u.startsWith("http")) allHttp = false;
            if (u.contains(".m3u8") || u.contains(".mp4")) anyMedia = true;
            if (u.contains("pic.")) anyHost = true;
        }
        ok("封面地址全是 http(s)", allHttp);
        ok("封面表里没有媒体流地址（与分集判据天然互斥）", !anyMedia);
        ok("封面来自图床域（pic.*）", anyHost);

        // ================================================================ E（放在前面：判据互斥是设计前提）
        banner("E. 两条判据互斥：封面表 ≠ 分集表");
        eq("列表页 payload 不会被误当分集（0 集）", 0, SsrPayload.INSTANCE.episodes(tagHtml).size());
        SsrPayload.Covers cvPlay = SsrPayload.INSTANCE.covers(playHtml);
        boolean noM3u8 = true;
        for (String u : cvPlay.getById().values()) if (u.contains("m3u8")) noM3u8 = false;
        ok("播放页封面表里没有 .m3u8", noM3u8);
        eq("播放页分集不受封面改动影响（仍是 5 集）", 5, SsrPayload.INSTANCE.episodes(playHtml).size());

        // ================================================================ B
        banner("B. fillPics 语义（纯函数，5 条规则）");
        Map<String, String> byId = new LinkedHashMap<>();
        byId.put("3381", "https://x/3381.jpg");
        Map<String, String> byName = new LinkedHashMap<>();
        byName.put("洞洞杂货铺", "https://x/byname.jpg");
        byName.put("单", "https://x/one.jpg");
        SsrPayload.Covers t = new SsrPayload.Covers(byId, byName);

        List<VideoItem> in = new ArrayList<>();
        in.add(item("3381", "洞洞杂货铺", ""));
        in.add(item("9999", "洞洞杂货铺", ""));
        in.add(item("1111", "已有封面", "https://y/keep.jpg"));
        in.add(item("2222", "单", ""));
        in.add(item("3333", "表里没有", ""));

        List<VideoItem> out = HtmlExtractor.INSTANCE.fillPics(in, t);
        eq("① id 命中", "https://x/3381.jpg", out.get(0).getPic());
        eq("② id 不中时按名称匹配", "https://x/byname.jpg", out.get(1).getPic());
        eq("③ 已有封面一律不动", "https://y/keep.jpg", out.get(2).getPic());
        eq("④ 单字标题不参与名称匹配（撞车概率太高）", "", out.get(3).getPic());
        eq("⑤ 表里没有就保持空（UI 显示占位图）", "", out.get(4).getPic());
        eq("条数不变", in.size(), out.size());
        eq("只动 pic，不动名称", "洞洞杂货铺", out.get(0).getName());
        eq("空表 = 原样返回", "",
                HtmlExtractor.INSTANCE.fillPics(in, SsrPayload.Covers.Companion.getEMPTY()).get(1).getPic());

        // ================================================================ C
        banner("C. 真实列表页：parseList 带上 rawHtml 后，封面全部补上");
        Document tagDoc = Jsoup.parse(tagHtml, BASE);
        List<VideoItem> noRaw = HtmlExtractor.INSTANCE.parseList(tagDoc, BASE, false);
        List<VideoItem> withRaw = HtmlExtractor.INSTANCE.parseList(tagDoc, BASE, false, tagHtml);
        System.out.println("  不带 rawHtml : " + countPic(noRaw) + "/" + noRaw.size() + " 张封面");
        System.out.println("  带 rawHtml   : " + countPic(withRaw) + "/" + withRaw.size() + " 张封面");
        ok("卡片集合不受影响（条数一致）", noRaw.size() == withRaw.size());
        ok("两条路径卡片 id 顺序一致", ids(noRaw).equals(ids(withRaw)));
        ok("不传 rawHtml 时 0 张封面（复现用户报的现象）", countPic(noRaw) == 0);
        eq("传 rawHtml 后全部有封面", withRaw.size(), countPic(withRaw));
        boolean allPicHost = true, allPicHttp = true;
        for (VideoItem v : withRaw) {
            if (!v.getPic().contains("pic.")) allPicHost = false;
            if (!v.getPic().startsWith("http")) allPicHttp = false;
        }
        ok("补上的封面都指向图床", allPicHost);
        ok("补上的封面都是绝对 http 地址", allPicHttp);
        String p3381 = null;
        for (VideoItem v : withRaw) if ("3381".equals(v.getId())) p3381 = v.getPic();
        eq("id=3381 补齐的封面与 payload 表一致", cv.getById().get("3381"), p3381);

        // ================================================================ D
        banner("D. 详情页封面：og:image 可用，但站点默认分享图必须挡掉");
        eq("真样本详情页 og:image = 真海报",
                "https://pic.ndhixj.cn/upload_01/upload/20260917/2026091718154646088.jpeg",
                HtmlExtractor.INSTANCE.parsePic(Jsoup.parse(detailHtml, BASE)));
        String listPic = HtmlExtractor.INSTANCE.parsePic(Jsoup.parse(tagHtml, BASE));
        System.out.println("  列表页 parsePic = [" + listPic + "]");
        ok("列表页封面不含 social-default（默认分享图挡掉）", !listPic.contains("social-default"));

        String mix = "<html><head><meta property=\"og:image\" "
                + "content=\"https://a.com/images/social-default.png\"></head>"
                + "<body><div class=\"module-item-pic\"><img src=\"/upload/real.jpg\"></div></body></html>";
        eq("og:image 是默认图时退到 DOM 真图", "/upload/real.jpg",
                HtmlExtractor.INSTANCE.parsePic(Jsoup.parse(mix, BASE)));

        String onlyDef = "<html><head><meta property=\"og:image\" "
                + "content=\"https://a.com/images/social-default.png\"></head><body></body></html>";
        eq("只有默认图时返回空（宁显示占位图，也不张冠李戴）", "",
                HtmlExtractor.INSTANCE.parsePic(Jsoup.parse(onlyDef, BASE)));

        String logo = "<html><head><meta property=\"og:image\" "
                + "content=\"https://a.com/images/logo.png\"></head><body></body></html>";
        eq("logo 也当默认图挡掉", "", HtmlExtractor.INSTANCE.parsePic(Jsoup.parse(logo, BASE)));

        // ================================================================ G
        banner("G. 详情封面回落的前提（钉住「为什么要留一手」）");
        eq("播放页 parsePic 为空（og:image 是 social-default，已被挡）", "",
                HtmlExtractor.INSTANCE.parsePic(Jsoup.parse(playHtml, BASE)));
        ok("而详情页 parsePic 有真海报 ⇒ 回落才有意义",
                !HtmlExtractor.INSTANCE.parsePic(Jsoup.parse(detailHtml, BASE)).isBlank());

        // ================================================================ F
        banner("F. 反例：普通站不受影响（没有内嵌 JSON 时一切照旧）");
        String plain = "<html><head><title>t</title></head><body>"
                + "<div class=\"module-item-pic\"><a href=\"/voddetail/1.html\" title=\"测试影片\">"
                + "<img src=\"/upload/1.jpg\" alt=\"测试影片\"></a></div></body></html>";
        Document plainDoc = Jsoup.parse(plain, "https://plain.com");
        ok("无内嵌 JSON ⇒ 封面表为空", SsrPayload.INSTANCE.covers(plain).isEmpty());
        eq("普通站封面走原路径（相对路径补全）", "https://plain.com/upload/1.jpg",
                HtmlExtractor.INSTANCE.parseList(plainDoc, "https://plain.com", false, plain).get(0).getPic());
        eq("空 HTML 不抛异常", 0, SsrPayload.INSTANCE.covers("").getById().size());
        eq("null 不抛异常", 0, SsrPayload.INSTANCE.covers(null).getById().size());
        eq("坏 JSON 不抛异常", 0,
                SsrPayload.INSTANCE.covers("<script id=\"__NUXT_DATA__\">{oops</script>").getById().size());
        eq("普通嵌套 JSON 页面也不误判", 0,
                SsrPayload.INSTANCE.covers(
                        "<script type=\"application/json\">{\"a\":1,\"b\":[1,2,3]}</script>").getById().size());

        System.out.println();
        System.out.println("========================================================");
        System.out.println("  PASS=" + pass + "  FAIL=" + fail);
        System.out.println("========================================================");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- 工具

    static VideoItem item(String id, String name, String pic) {
        // ⚠️ VideoItem 是 Kotlin data class，Java 只能调**全参**构造：
        // v1.0.37 新增了第 9 个字段 siteKey（聚合搜索的"来源站"），
        // 这里必须同步补一个 ""，否则编译期就报"参数不匹配"。
        // （Kotlin 侧用默认参数写的地方不受影响 —— 只有 Java 位置构造会被这个改动打到。）
        return new VideoItem(id, name, pic, "", "", "", "", "", "");
    }

    static int countPic(List<VideoItem> list) {
        int n = 0;
        for (VideoItem v : list) if (!v.getPic().isBlank()) n++;
        return n;
    }

    static List<String> ids(List<VideoItem> list) {
        List<String> out = new ArrayList<>();
        for (VideoItem v : list) out.add(v.getId());
        return out;
    }

    static String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  " + s);
        System.out.println("================================================================");
    }

    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + what); }
        else { fail++; System.out.println("  [FAIL] " + what); }
    }

    static void eq(String what, Object expect, Object got) {
        ok(what + "（期望 " + expect + "，得到 " + got + "）",
                String.valueOf(expect).equals(String.valueOf(got)));
    }
}
