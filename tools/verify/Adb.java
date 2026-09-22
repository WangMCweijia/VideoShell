import com.videoshell.data.net.AdBlock;

import java.util.List;

/**
 * 去广告判据断言（v1.0.52）。
 *
 * 这一版的判据有一个特点：**它自己不产生结果，只决定"什么东西不该出现"**，
 * 所以断言的重点不是"拦到了没有"，而是 **"有没有拦错"**。四块：
 *
 *  A. 反例优先 —— `download` / `load` / `head` / `upload` / `thumb` / `sprite` /
 *     `notice` / `addon` / `static` / `tj` / `adobe` 这些"看着含广告词"的正常资源
 *     一个都不许被判成广告。含 `ad` 子串的写法会把它们全部误杀，而一个被误杀的
 *     分片就是"播放挂掉"。
 *  B. 正例 —— 真广告（广告域名 / 广告路径段 / 广告文件名 / 广告查询值）必须抓到。
 *  C. **媒体安全阀** —— 哪怕路径里明明白白写着 `/ad/`，只要它是 m3u8/mp4/ts/m4s，
 *     就必须放行。判据的两侧代价不对称，这一条排在所有规则之前。
 *  D. 顶层跳转 —— 站内/子域/有手势/服务端重定向一律放行（域名轮换的站全靠它们），
 *     只有"跨站 + 无手势 + 非重定向"和"目标本身就是广告/跳 App"才拦。
 *  E. 注入样式 —— ★ 不许出现广谱选择器（`[class*=ad]` 那种会把正文容器一起藏掉，
 *     用户点不到任何东西且界面不会给任何解释）；所有 iframe 选择器必须**逐个**来自
 *     `AD_HOSTS`（判据与产出同源，不许偷偷多加一个没判过的域名）。
 */
public class Adb {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        final AdBlock A = AdBlock.INSTANCE;

        banner("A. 反例优先：这些「看着像广告」的正常资源，一个都不许拦");
        String[] mustNotBlock = {
                "https://site.com/wp-content/themes/mibt/assets/js/jquery.lazyload.min.js",
                "https://cdn.x.com/download/video/1.mp4",              // download 含 "ad"
                "https://cdn.x.com/load/playlist.m3u8",                // load 含 "ad"
                "https://cdn.x.com/video/head/main.m3u8",              // head 含 "ad"
                "https://cdn.x.com/upload/1.jpg",                      // upload 含 "ad"
                "https://cdn.x.com/radar/1.js",                        // radar 含 "ad"
                "https://cdn.x.com/thread/movie.webp",                 // thread 含 "ad"
                "https://cdn.x.com/shadow/1.js",                       // shadow 含 "ad"
                "https://cdn.x.com/ready/main.js",                     // ready 含 "ad"
                "https://cdn.x.com/broadcast/1.js",                    // broadcast 含 "ad"
                "https://cdn.x.com/addon/playlist.js",                 // addon 含 "ad"
                "https://cdn.x.com/thumb/123.jpg",                     // 封面常在 /thumb/ 下
                "https://cdn.x.com/sprite/icons.png",
                "https://cdn.x.com/notice/1.html",                     // 公告页
                "https://cdn.x.com/tips/1.js",
                "https://site.com/static/js/app.js",                   // static ≠ stat
                "https://site.com/assets/ads-container.css",           // 整段不等于 ad/ads
                "https://site.com/loadmore.js",
                "https://site.com/gj/1.js",                            // gj ≠ gg
                "https://site.com/ad-1080/hls/index.m3u8",             // ad-1080 是一段，不是 "ad"
        };
        for (String u : mustNotBlock) ok("不误伤 " + tail(u), !A.blockedResource(u));

        String[] badHosts = {
                "https://radar.x.com/x.js", "https://status.x.com/x.js",
                "https://statics.x.com/x.js", "https://tj.x.com/x.js",
                "https://adobe.com/x.js", "https://ggstatic.com/x.js",
                "https://download.x.com/x.js", "https://addons.x.com/x.js",
        };
        for (String u : badHosts) ok("主机也不误伤 " + tail(u), !A.hostBlocked(u));

        banner("B. 正例：真广告必须抓到（主机 / 路径段 / 文件名 / 查询值四条路）");
        String[] mustBlock = {
                "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                "https://www.google-analytics.com/analytics.js",
                "https://hm.baidu.com/hm.js?f3c1",
                "https://pos.baidu.com/pos.php",
                "https://cnzz.com/z_stat.php",
                "https://adservice.google.com/adsid/integrator.js",
                "https://ads.example.com/x.js",
                "https://adx.cdn.com/x.js",
                "https://stats.example.com/collect",
                "https://tracker.example.com/x",
                "https://x.com/ad/1.js",
                "https://x.com/ads/banner.jpg",
                "https://x.com/js/ad.js",
                "https://x.com/js/ads.js",
                "https://x.com/guanggao/v.js",
                "https://x.com/preroll/load.js",
                "https://x.com/popunder/1.js",
                "https://x.com/f.js?type=preroll",
                "https://x.com/f.js?ad=1",
        };
        for (String u : mustBlock) ok("抓到 " + tail(u), A.blockedResource(u));

        banner("C. ★ 媒体安全阀：路径里写着 /ad/ 也照放行");
        String[] mustPassMedia = {
                "https://x.com/ad/movie/index.m3u8",            // 路径段 ad，但它是片子
                "https://ads.example.com/preroll/index.m3u8",   // 连广告域名下的媒体都不拦
                "https://x.com/x.ts",
                "https://x.com/ad/seg.m4s",
                "https://x.com/ad/movie.mpd",
                "https://x.com/ad/movie.mp4",
                "https://x.com/ad/seg.mkv",
                "data:image/png;base64,iVBORw0KGgo=",
                "blob:https://x.com/9d0a",
                "about:blank",
        };
        for (String u : mustPassMedia) ok("放行媒体/非 http " + tail(u), !A.blockedResource(u));

        banner("D. 顶层跳转：能放的全放，该拦的才拦");
        nav("同域内跳转放行", A, "https://a.com/p", "https://a.com/q", false, false, false);
        nav("子域放行", A, "https://a.com/p", "https://cdn.a.com/x", false, false, false);
        nav("www ↔ m 放行", A, "https://www.a.com/p", "https://m.a.com/play", false, false, false);
        nav("★ 用户自己点的跨站跳转放行", A, "https://a.com/p", "https://b.com/x", true, false, false);
        nav("★ 服务端 302 放行（域名轮换的站全靠它）", A, "https://a.com/p", "https://b.com/x", false, true, false);
        nav("★ 跨站 + 无手势 + 非重定向 = 弹窗，拦", A, "https://a.com/p", "https://b.com/x", false, false, true);
        nav("跨站 + 无手势 + 是重定向 ⇒ 放", A, "https://a.com/p", "https://b.com/x", false, true, false);
        nav("★ 目标是广告域名 ⇒ 有手势也拦", A, "https://a.com/p", "https://pos.baidu.com/x", true, false, true);
        nav("★ 目标是广告路径 ⇒ 拦", A, "https://a.com/p", "https://b.com/ads/1", true, false, true);
        nav("★ 跳 App ⇒ 拦", A, "https://a.com/p", "intent://x#Intent;package=com.taobao.taobao;end", false, false, true);
        nav("★ taobao scheme ⇒ 拦", A, "https://a.com/p", "taobao://item.taobao.com/1", true, false, true);
        nav("★ 判不出来 from（空）⇒ 不拦", A, "", "https://b.com/x", false, false, false);
        nav("★ 二级后缀要当一层：同站放行", A, "https://www.x.com.cn/p", "https://cdn.x.com.cn/x", false, false, false);
        nav("★ 二级后缀要当一层：跨站才拦", A, "https://x.com.cn/p", "https://y.com.cn/x", false, false, true);
        nav("空目标不拦", A, "https://a.com/p", "", false, false, false);

        banner("E. 主域工具");
        ok("www.abc.com.cn 的主域是 abc.com.cn", A.rootOf("www.abc.com.cn").equals("abc.com.cn"));
        ok("cdn.yzzy31-play.com 的主域是 yzzy31-play.com",
                A.rootOf("cdn.yzzy31-play.com").equals("yzzy31-play.com"));
        ok("hostOf 统一小写并去掉端口", "a.b.com".equals(A.hostOf("https://A.B.com:8080/x")));

        banner("F. 注入的样式：★ 不许有广谱选择器");
        String css = A.hideCss();
        List<String> hosts = A.getAD_HOSTS();
        ok("含 adsbygoogle 这两个行业公认容器",
                css.contains("ins.adsbygoogle") && css.contains(".adsbygoogle"));
        ok("★ 没有任何 [class*= 选择器（会连正文容器一起藏）", !css.contains("[class*="));
        ok("★ 没有裸 .ad / .ads / #ad 这类选择器",
                !css.contains(".ad{") && !css.contains(".ad,") && !css.contains(".ads{"));
        ok("★ 没有 div[ 这种按属性广谱命中的写法", !css.contains("div["));
        ok("结尾是一个 display:none!important 声明块（选择器拼在一起）",
                css.endsWith("{display:none!important}"));
        ok("★ iframe 选择器个数 == AD_HOSTS 条数（不许偷偷多加一个没判过的域名）",
                count(css, "iframe[src*='") == hosts.size());
        boolean allFromList = true;
        for (String h : hosts) {
            if (!css.contains("iframe[src*='" + h + "']")) allFromList = false;
        }
        ok("★ 每个 iframe 选择器都逐个来自 AD_HOSTS（判据与产出同源）", allFromList);

        String js = A.hideJs();
        ok("JS 里造了一个 style 节点", js.contains("document.createElement('style')"));
        ok("JS 带着幂等闸（window.__vsAdCss）", js.contains("window.__vsAdCss"));
        ok("JS 包在 try/catch 里（注入失败不能把页面带崩）",
                js.contains("try{") && js.contains("catch(e)"));
        ok("JS 里带上了 hideCss 的内容", js.contains("display:none!important"));
        ok("★ JS 里的样式字符串是合法双引号字面量（没漏转义）",
                js.contains("s.textContent=\"ins.adsbygoogle"));

        banner("G. DOM 清扫脚本（v1.0.57）：宽幅外链图幅 / 大浮层，★ 防误伤约束必须都在");
        String sw = A.sweepJs();
        ok("幂等标记 data-vs-ad（杀过的节点不许反复处理）", sw.contains("data-vs-ad"));
        ok("图幅判据带**外链闸**（正片海报是同站链接，外链是第一道排除）",
                sw.contains("CROSS(href)") && sw.contains("ROOT(h)!==ROOT(location.hostname)"));
        ok("二级后缀表要当一层（com.cn 站内 ≠ 跨站）", sw.contains("'com.cn'"));
        ok("图幅判据带**形状闸**：宽≥200 且宽高比≥2.5（海报是竖版，天然不过线）",
                sw.contains("r.width<200") && sw.contains("r.width/r.height<2.5"));
        ok("浮层判据：fixed/sticky + z≥90 + 盖半屏（返回顶部按钮是小面积，天然不过线）",
                sw.contains("'fixed'") && sw.contains("z>=90") && sw.contains("innerWidth*0.5"));
        ok("★ 含 <video> 的元素一律豁免（播控层不能被当浮层杀掉）",
                sw.contains("querySelector('video')"));
        ok("★ 含**站内链接**的浮层一律豁免（v1.0.58：导航栏和广告浮层在"
                        + "位置/层级/尺寸上同形，只有内容能区分 —— 杀导航=分类全空）",
                sw.contains("var na=el.querySelector&&el.querySelector('a[href]')")
                        && sw.contains("if(na&&!CROSS(na.getAttribute('href')))continue;"));
        ok("锚点 / javascript: 链接不参与（没有目标可判就别动）",
                sw.contains("javascript:") && sw.contains("charAt(0)==='#'"));
        ok("MutationObserver 兜住「广告比正文晚到」的路径", sw.contains("MutationObserver"));
        ok("返回清扫计数（留痕用）", sw.contains("__vsN"));
        ok("★ 不许按 class 广谱命中（和 hideCss 同一条红线）",
                !sw.contains("querySelectorAll('[class"));
        ok("整体包在 try/catch 里（清扫失败不能把页面带崩）",
                sw.contains("try{") && sw.contains("catch(e)"));

        System.out.println();
        System.out.println("================ pass=" + pass + " fail=" + fail + " ================");
        if (fail > 0) System.exit(1);
    }

    static void nav(String what, AdBlock A, String from, String to,
                    boolean gesture, boolean redirect, boolean want) {
        boolean got = A.blockNav(from, to, gesture, redirect);
        ok(what + (want ? "（拦）" : "（放）"), got == want);
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    static String tail(String u) {
        if (u.length() <= 58) return u;
        return "…" + u.substring(u.length() - 56);
    }

    static void banner(String s) {
        System.out.println();
        System.out.println("================ " + s + " ================");
    }

    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + what); }
        else { fail++; System.out.println("  [FAIL] " + what); }
    }
}
