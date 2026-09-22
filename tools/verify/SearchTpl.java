import com.videoshell.data.site.HtmlTemplates;
import com.videoshell.data.site.SiteCalib;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * 离线校验：**搜索模板的两条学习路**（v1.0.20）。
 *
 * 背景：固定搜索候选全是 maccms 形状，自研站对不上 ——
 *  - 厂长  czzy.app   表单 action=/nimasile name=q
 *  - 骚火  shdy5.us   表单 action=/s----------.html name=wd
 *  - 金牌  现象：搜什么都出同一批内容（候选打歪进推荐位）
 *  - 野果  搜索页纯客户端渲染，SSR 无结果（另案）
 *
 * 两条路：
 *  A. 从首页搜索表单反推（HtmlTemplates.searchTplFromForm）
 *  B. 校准第 4 步：结果页地址 + 用户搜的词 反推（SiteCalib.searchTplFromUrl）
 */
public class SearchTpl {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what);
        if (cond) pass++; else fail++;
    }

    static Document doc(String html) {
        return Jsoup.parse(html, "https://example.com");
    }

    public static void main(String[] args) {
        System.out.println("==== 搜索模板学习（离线） ====");
        System.out.println();

        // ---------- A. 表单反推 ----------
        System.out.println("-- A. searchTplFromForm --");

        // 厂长真实形状（2026-09-18 实测首页）
        Document cz = doc("<form action=\"https://czzy.app/nimasile\" method=\"get\">" +
                "<input type=\"text\" name=\"q\" placeholder=\"电影名称/明星/导演/年份\"></form>");
        String czTpl = HtmlTemplates.INSTANCE.searchTplFromForm(cz, "https://czzy.app");
        ok("厂长：action=/nimasile + name=q ⇒ ?q={kw}",
                "https://czzy.app/nimasile?q={kw}".equals(czTpl));

        // 骚火真实形状（样本 _shdy_detail_50323.html 里的表单）
        Document sh = doc("<form id=\"search_form\" action=\"/s----------.html\" method=\"get\" autocomplete=\"off\">" +
                "<input value=\"\" type=\"search\" name=\"wd\" placeholder=\"想看什么，搜一搜\"></form>");
        String shTpl = HtmlTemplates.INSTANCE.searchTplFromForm(sh, "https://shdy5.us");
        ok("骚火：相对 action + name=wd ⇒ /s----------.html?wd={kw}",
                "https://shdy5.us/s----------.html?wd={kw}".equals(shTpl));

        // action 已带 query ⇒ 用 & 连接
        Document q = doc("<form action=\"/search.html?a=1\" method=\"get\"><input name=\"wd\"></form>");
        String qTpl = HtmlTemplates.INSTANCE.searchTplFromForm(q, "https://example.com");
        ok("action 已带 query ⇒ 追加 &wd={kw}",
                "https://example.com/search.html?a=1&wd={kw}".equals(qTpl));

        // POST 表单不认（拼不出 GET URL）
        Document post = doc("<form action=\"/search\" method=\"post\"><input name=\"wd\"></form>");
        ok("POST 表单学不到（返回 null）",
                HtmlTemplates.INSTANCE.searchTplFromForm(post, "https://example.com") == null);

        // 输入框没有 name ⇒ 学不到
        Document noname = doc("<form action=\"/search\" method=\"get\"><input type=\"text\"></form>");
        ok("输入框无 name ⇒ null",
                HtmlTemplates.INSTANCE.searchTplFromForm(noname, "https://example.com") == null);

        // 第一个表单不可用、第二个可用 ⇒ 跳过不可用的继续找
        Document second = doc("<form action=\"/x\" method=\"post\"><input name=\"a\"></form>" +
                "<form action=\"/s.html\" method=\"get\"><input name=\"wd\"></form>");
        ok("第一个表单是 POST 时继续找下一个",
                "https://example.com/s.html?wd={kw}".equals(
                        HtmlTemplates.INSTANCE.searchTplFromForm(second, "https://example.com")));

        // ---------- B. 校准第 4 步：URL + 关键词反推 ----------
        System.out.println();
        System.out.println("-- B. searchTplFromUrl --");

        ok("query 里的百分号编码词 ⇒ 解码后替换",
                "https://shdy5.us/s----------.html?wd={kw}".equals(
                        SiteCalib.INSTANCE.searchTplFromUrl(
                                "https://shdy5.us/s----------.html?wd=%E6%B5%8B%E8%AF%95", "测试")));

        ok("路径式（/search/测试/）也能反推",
                "https://capable.fzchosdi.cc/search/{kw}/".equals(
                        SiteCalib.INSTANCE.searchTplFromUrl(
                                "https://capable.fzchosdi.cc/search/%E6%B5%8B%E8%AF%95/", "测试")));

        ok("词原样出现在 URL（未编码）⇒ 直接替换",
                "https://example.com/search?kw={kw}".equals(
                        SiteCalib.INSTANCE.searchTplFromUrl("https://example.com/search?kw=abc", "abc")));

        ok("地址里找不到词 ⇒ null（界面上要明说，不静默）",
                SiteCalib.INSTANCE.searchTplFromUrl("https://example.com/search", "测试") == null);

        ok("空词 / 非法地址 ⇒ null",
                SiteCalib.INSTANCE.searchTplFromUrl("https://example.com/s", "") == null &&
                SiteCalib.INSTANCE.searchTplFromUrl("", "测试") == null);

        // ---------- C. 构建回填 ----------
        System.out.println();
        System.out.println("-- C. 模板回填语义（build 端约定） --");
        ok("模板里的 {kw} 占位符会被 enc(关键词) 回填（约定断言：模板必须含 {kw} 字面量）",
                czTpl != null && czTpl.contains("{kw}") && shTpl != null && shTpl.contains("{kw}"));

        // ---------- D. 金牌（zanpian 形状）：为什么必须有"强制重学"路径 ----------
        System.out.println();
        System.out.println("-- D. 金牌 bolyship.com（zanpian CMS）--");
        // 2026-09-18 实测首页：<form ... method="get" action="/bspvc/-----------s-.html"
        //                  onSubmit="return qrsearch();"><input name="wd">
        Document jp = doc("<form class=\"zanpian-search\" id=\"search\" method=\"get\"" +
                " action=\"/bspvc/-----------s-.html\" onSubmit=\"return qrsearch();\">" +
                "<input type=\"text\" name=\"wd\" placeholder=\"请在此处输入影片名\"></form>");
        String jpTpl = HtmlTemplates.INSTANCE.searchTplFromForm(jp, "https://bolyship.com");
        ok("金牌：action=/bspvc/-----------s-.html + name=wd ⇒ ?wd={kw}",
                "https://bolyship.com/bspvc/-----------s-.html?wd={kw}".equals(jpTpl));
        boolean jpInFixed = jpTpl != null &&
                HtmlTemplates.INSTANCE.searchCandidates("https://bolyship.com").contains(jpTpl);
        ok("表单形状不在固定候选里（固化模板打歪时固定候选救不回来 ⇒ 必须强制重学）", !jpInFixed);

        System.out.println();
        System.out.println("==== SearchTpl PASS=" + pass + " FAIL=" + fail + " ====");
        System.exit(fail == 0 ? 0 : 1);
    }
}
