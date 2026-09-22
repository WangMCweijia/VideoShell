import com.videoshell.data.site.SiteDetector;
import com.videoshell.util.ExtKt;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * zqkhmy.com 识别失败（v1.0.26）的回归断言。
 *
 * 根因：`Http` 的 Accept 里带了 `application/json`，站点的 nginx 按内容协商把**整页 HTML
 * 转义成 JSON 字符串**返回（`"<!DOCTYPE html>…"`，中文变 U+XXXX 转义序列），于是站型判据
 * `looksLikeVideoSite` 一个中文词都匹配不到 ⇒ 判「不是视频站」。
 *
 * 两条修法都要有断言：
 *  1. 请求头对齐浏览器（`Http` 的 Accept 不再广告 application/json）—— 源码守卫在 runner 里；
 *  2. `decodeBody` 里的通用解包 `unwrapJsonHtml` —— 用**真实抓下来的转义响应**断言。
 */
public class ZqFix {

    static int pass = 0, fail = 0;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    static void check(boolean ok, String what, String detail) {
        if (ok) pass++; else fail++;
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + what
                + (detail.isEmpty() ? "" : "   " + detail));
    }

    static void check(boolean ok, String what) { check(ok, what, ""); }

    /** 与 SiteDetector.VIDEO_WORDS 保持一致（那里是 private，Java 侧读不到） */
    static final String[] VIDEO_WORDS = {"视频", "影视", "电影", "电视剧", "在线观看", "在线播放",
            "动漫", "综艺", "追剧", "vodplay", "vodshow", "voddetail", "vodtype",
            "maccms", "苹果cms", "海洋cms", "player_aaaa"};

    /** 复刻 SiteDetector.looksLikeVideoSite 的判据（阈值 2） */
    static boolean looksLikeVideoSite(String html) {
        if (html == null) return false;
        String head = html.substring(0, Math.min(300_000, html.length())).toLowerCase();
        int hit = 0;
        for (String w : VIDEO_WORDS) {
            if (head.contains(w)) {
                hit++;
                if (hit >= 2) return true;
            }
        }
        return false;
    }

    static int wordHits(String s) {
        String head = s.substring(0, Math.min(300_000, s.length())).toLowerCase();
        int hit = 0;
        for (String w : VIDEO_WORDS) if (head.contains(w)) hit++;
        return hit;
    }

    public static void main(String[] args) throws Exception {
        String sampleDir = args.length > 0 ? args[0] : ".";
        String url = args.length > 1 ? args[1] : "https://www.zqkhmy.com/";

        System.out.println("=".repeat(74));
        System.out.println("A. JSON 字符串解转义（纯函数）");
        System.out.println("=".repeat(74));
        check(ExtKt.unescapeJsonString("a\\/b").equals("a/b"), "\\/ 还原成 /");
        check(ExtKt.unescapeJsonString("\\u7535\\u5f71").equals("电影"), "\\uXXXX 还原成中文");
        check(ExtKt.unescapeJsonString("say \\\"hi\\\"").equals("say \"hi\""), "\\\" 还原");
        check(ExtKt.unescapeJsonString("a\\\\b").equals("a\\b"), "\\\\ 还原成一个反斜杠");
        check(ExtKt.unescapeJsonString("x\\ny").equals("x\ny"), "\\n 还原成换行");
        check(ExtKt.unescapeJsonString("\\q").equals("\\q"), "未知转义 \\q 原样保留");
        check(ExtKt.unescapeJsonString("\\uZZZZ").equals("\\uZZZZ"), "非法 \\u 原样保留");
        check(ExtKt.unescapeJsonString("plain text").equals("plain text"), "无转义串原样");

        System.out.println("=".repeat(74));
        System.out.println("B. unwrapJsonHtml 的判据（不能误伤正常响应）");
        System.out.println("=".repeat(74));
        String plainHtml = "<!DOCTYPE html><html><body>电影</body></html>";
        check(ExtKt.unwrapJsonHtml(plainHtml).equals(plainHtml), "裸 HTML 不动");
        String jsonObj = "{\"class\":[],\"list\":[]}";
        check(ExtKt.unwrapJsonHtml(jsonObj).equals(jsonObj), "JSON 接口（{ 开头）不动");
        String quotedNotHtml = "\"abc\\u4e2d\"";
        check(ExtKt.unwrapJsonHtml(quotedNotHtml).equals(quotedNotHtml),
                "引号开头但解出来不是 HTML ⇒ 不动");
        String esc = "\"<!DOCTYPE html>\\u7535\\u5f71\\/detail\\/1.html\"";
        String un = ExtKt.unwrapJsonHtml(esc);
        check(un.startsWith("<!DOCTYPE html>"), "转义 HTML 被解包");
        check(un.contains("电影") && un.contains("/detail/"), "解包后中文与斜杠都还原");

        // ---- 真实样本（runnet 抓下来的线上转义响应）
        Path sp = Paths.get(sampleDir, "zq_home.html");
        if (Files.exists(sp)) {
            byte[] raw = Files.readAllBytes(sp);
            String decoded = ExtKt.decodeBody(raw, "utf-8");
            check(decoded.contains("<!DOCTYPE html>") || decoded.contains("<html"),
                    "真实样本经 decodeBody 后是 HTML", "len=" + decoded.length());
            int h1 = wordHits(decoded);
            check(h1 >= 2, "解包后站型词命中 >= 2 ⇒ 能被识别", "hits=" + h1);
            int h0 = wordHits(new String(raw, StandardCharsets.UTF_8));
            check(h0 < 2, "未解包时命中 < 2 ⇒ 这就是当初误判的原因", "hits=" + h0);
            check(decoded.contains("/detail/") && decoded.contains("maccms"),
                    "解包后能看到 maccms 与 /detail/ 链接");
        } else {
            check(false, "真实样本存在（先跑 runnet.py 抓 " + sp + "）");
        }

        System.out.println("=".repeat(74));
        System.out.println("C. 线上：识别结果（走真实 SiteDetector + 真实 Http）");
        System.out.println("=".repeat(74));
        SiteDetector.Result r = block((scope, cont) ->
                SiteDetector.INSTANCE.detect(url, (Continuation<? super SiteDetector.Result>) cont));
        System.out.println("  message = " + r.getMessage());
        System.out.println("  isVideoSite = " + r.isVideoSite()
                + "  site=" + (r.getSite() == null ? "null" : r.getSite().getBaseUrl()
                + "/" + r.getSite().getApiMode()));
        check(r.isVideoSite(), "站点被识别为视频站");
        check(r.getSite() != null, "产出了站点配置");
        check(r.getMessage().contains("HTML") || r.getMessage().contains("识别成功"),
                "给出可读的识别结论");

        System.out.println("=".repeat(74));
        System.out.println("E. 线上：识别之后真的能用吗（HTML 适配器实跑）");
        System.out.println("=".repeat(74));
        if (r.getSite() == null) {
            check(false, "站点配置为空，跑不了适配器");
        } else {
            com.videoshell.data.model.SiteConfig sc = r.getSite();
            com.videoshell.data.site.SiteAdapter a =
                    com.videoshell.data.site.AdapterFactory.INSTANCE.create(sc);
            System.out.println("  适配器 = " + a.getClass().getSimpleName()
                    + "  apiMode=" + sc.getApiMode());
            // v1.0.35 起未知域名会套**延迟路由**（先判签名种子配置族、再判加密接口族），
            // 命中之前它干的就是网页解析的活 —— 所以要看"谁在干活"，不是"外面包了几层"。
            // ⚠️ 这里原来写死"剥一层 FamilyRouter"，v1.0.53 加了一层就假红。
            //    剥壳判据只留一份：[Chains]（别再在本文件里抄第三遍，E7 就是这么来的）。
            check(Chains.worksAs(a, com.videoshell.data.site.HtmlAdapter.class),
                    "普通影视站走网页解析（不是插件/加密/采集适配器）",
                    a.getClass().getSimpleName() + " → "
                            + Chains.effective(a).getClass().getSimpleName());

            java.util.List<com.videoshell.data.model.Category> cats =
                    block((scope, cont) -> a.categories(
                            (Continuation<? super java.util.List<com.videoshell.data.model.Category>>) cont));
            System.out.println("  分类 = " + cats.size() + "  " + cats);
            check(cats.size() >= 3, "解析出分类栏", "n=" + cats.size());

            java.util.List<com.videoshell.data.model.VideoItem> items =
                    block((scope, cont) -> a.browse("", 1,
                            (Continuation<? super java.util.List<com.videoshell.data.model.VideoItem>>) cont));
            int withPic = 0;
            for (com.videoshell.data.model.VideoItem v : items) if (!v.getPic().isEmpty()) withPic++;
            System.out.println("  首页列表 = " + items.size() + " 条，带封面 " + withPic + " 条");
            for (int i = 0; i < Math.min(items.size(), 5); i++) {
                System.out.println("    [" + items.get(i).getId() + "] " + items.get(i).getName());
            }
            check(!items.isEmpty(), "首页列表非空", "n=" + items.size());
            check(withPic > 0, "卡片带封面", "withPic=" + withPic);
            check(items.size() > 0 && !items.get(0).getName().isEmpty(), "卡片有剧名");

            // 分类页也走一遍：形状识别错了的话这里会空
            if (cats.size() > 1) {
                String tid = cats.get(cats.size() - 1).getId();
                java.util.List<com.videoshell.data.model.VideoItem> sub =
                        block((scope, cont) -> a.browse(tid, 1,
                                (Continuation<? super java.util.List<com.videoshell.data.model.VideoItem>>) cont));
                System.out.println("  分类[" + cats.get(cats.size() - 1).getName() + "] = "
                        + sub.size() + " 条");
                check(!sub.isEmpty(), "分类页能取到内容（形状识别正确）", "n=" + sub.size());
            }
        }

        System.out.println();
        System.out.println("==== pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }
}
