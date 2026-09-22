import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;

import com.videoshell.data.net.Http;
import com.videoshell.data.net.ImageCipher;
import com.videoshell.data.site.AesCipher;
import com.videoshell.data.site.CryptRecipe;
import com.videoshell.data.site.CryptRecipes;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * v1.0.32 —— 「野果没有封面」的断言套件。
 *
 * 根因不是网络、不是 DNS、不是 Referer、不是 Coil：**图床上放的就不是图片**。
 * 实测 pic.ndhixj.cn 的 .jpeg 返回 68720 B 密文（前 4 字节 3E AA 70 8E，
 * 熵 7.96 bits/byte），站点前端用 media_key/media_iv 做 AES-128-CBC 解密后才显示。
 *
 * 这个套件要证明四件事：
 *   A/B 判据与解密算法**在 App 自己的代码里**成立（不是只在 Python 里成立）；
 *   C   白名单只对「该管的图床 + 图片路径」生效，别的一条都不碰；
 *   E   源码守卫：改动不会被静默回退；
 *   F   **端到端**：走 Http.client（Coil 用的同一条栈）真取封面，拿到的必须是明文 JPEG。
 *
 * 入参：a[0] = 夹具目录（_ygmedia/）   a[1] = 工程根（E 段读源码）
 */
public class YgImage {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond, String got) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + "   实际: " + got);
        }
    }

    static byte[] read(String p) throws IOException {
        return Files.readAllBytes(new File(p).toPath());
    }

    static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }

    static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 造一个「魔数合法」的假图片（只验魔数判据，不验内容） */
    static byte[] magic(int... v) {
        byte[] b = new byte[16];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    static String src(String root, String rel) throws IOException {
        return new String(read(root + File.separator + rel), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void main(String[] a) throws Exception {
        String here = a.length > 0 ? a[0] : ".";
        String proj = a.length > 1 ? a[1] : ".";

        byte[] enc = read(here + "/_ygmedia/cover.enc");       // 图床上真实存的东西
        byte[] jpg = read(here + "/_ygmedia/cover.jpg");       // 真实明文（Python 侧解出来的）
        byte[] notImg = read(here + "/_ygmedia/notimage.bin");

        final String MEDIA_KEY = "f5d965df75336270";
        final String MEDIA_IV = "97b60394abc2fbe1";
        final String API_KEY = "2acf7e91e9864673";
        final String API_IV = "1c29882d3ddfcfd6";
        final String COVER = "https://pic.ndhixj.cn/upload_01/upload/20260918/2026091823370540510.jpeg";
        // 由 _ygmedia_prep.py 用 Python 的 AES 独立算出，用来跨语言互证
        final String PLAIN_SHA = "b0cf777c053fba8c4fc1396a181e30d798eb6f9789b73483eb7b6c3a42374db1";

        // ---------------------------------------------------------------- A 魔数判据
        System.out.println("== A 图片魔数判据（前端 CEpbvVnF.js 那六条） ==");
        ok("A1 明文 JPEG 判为图片", AesCipher.INSTANCE.isImage(jpg), hex(jpg, 8));
        ok("A2 **图床返回的密文判为非图片**（这就是「200 却没图」的根因）",
                !AesCipher.INSTANCE.isImage(enc), hex(enc, 8));
        ok("A3 非图片字节判为非图片", !AesCipher.INSTANCE.isImage(notImg), hex(notImg, 8));
        ok("A4 PNG/GIF/WEBP/BMP/TIFF 魔数都认（判据不能太窄）",
                AesCipher.INSTANCE.isImage(magic(0x89, 0x50, 0x4E, 0x47))
                        && AesCipher.INSTANCE.isImage(magic(0x47, 0x49, 0x46, 0x38))
                        && AesCipher.INSTANCE.isImage(magic(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50))
                        && AesCipher.INSTANCE.isImage(magic(0x42, 0x4D))
                        && AesCipher.INSTANCE.isImage(magic(0x49, 0x49, 0x2A, 0x00)), "");
        ok("A5 太短的输入不越界、判为非图片",
                !AesCipher.INSTANCE.isImage(new byte[3]) && !AesCipher.INSTANCE.isImage(null), "");

        // ---------------------------------------------------------------- B 解密算法
        System.out.println("\n== B AES-CBC 解密（media_key / media_iv） ==");
        byte[] plain = AesCipher.INSTANCE.decryptBytes(enc, MEDIA_KEY, MEDIA_IV, "CBC", "Pkcs7");
        ok("B1 密文 68720 B 能解出结果", plain != null && plain.length > 0,
                plain == null ? "null" : String.valueOf(plain.length));
        ok("B2 解出来**是真的图片**（JPEG）", AesCipher.INSTANCE.isImage(plain),
                plain == null ? "null" : hex(plain, 8));
        ok("B3 sha256 与 Python 侧独立解出的一致（跨语言互证）",
                plain != null && PLAIN_SHA.equals(sha256(plain)),
                plain == null ? "null" : sha256(plain));
        ok("B4 明文长度 68707（扣掉 PKCS7 填充）", plain != null && plain.length == 68707,
                plain == null ? "null" : String.valueOf(plain.length));
        ok("B5 换用接口那组 key/iv ⇒ 解出来不是图片（两组密钥不能混用）",
                !AesCipher.INSTANCE.isImage(AesCipher.INSTANCE.decryptBytes(enc, API_KEY, API_IV, "CBC", "Pkcs7")), "");
        ok("B6 长度不对齐 16 ⇒ 返回 null（不返回一坨乱码冒充成功）",
                AesCipher.INSTANCE.decryptBytes(jpg, MEDIA_KEY, MEDIA_IV, "CBC", "Pkcs7") == null,
                "jpg.len=" + jpg.length + " mod16=" + (jpg.length % 16));
        ok("B7 空输入 ⇒ null", AesCipher.INSTANCE.decryptBytes(new byte[0], MEDIA_KEY, MEDIA_IV, "CBC", "Pkcs7") == null, "");

        // ---------------------------------------------------------------- C 白名单
        System.out.println("\n== C 加密图床白名单 ==");
        CryptRecipe r = CryptRecipes.INSTANCE.mediaRecipeFor(COVER);
        ok("C1 真实封面 URL 命中图床配方", r != null, String.valueOf(r));
        ok("C2 配方里的密钥就是实测那两个值",
                r != null && MEDIA_KEY.equals(r.getMediaKeySpec()) && MEDIA_IV.equals(r.getMediaIvSpec()),
                r == null ? "null" : r.getMediaKeySpec() + "/" + r.getMediaIvSpec());
        ok("C3 同一图床的**非图片路径**不命中（不该解密就别解密）",
                CryptRecipes.INSTANCE.mediaRecipeFor("https://pic.ndhixj.cn/api/report") == null, "");
        ok("C4 别的域名的 .jpg 不命中",
                CryptRecipes.INSTANCE.mediaRecipeFor("https://other-cdn.com/a.jpg") == null, "");
        ok("C5 图片路径判据认得带 query 的写法",
                CryptRecipes.INSTANCE.looksLikeImagePath("https://x/a.jpeg?w=300")
                        && CryptRecipes.INSTANCE.looksLikeImagePath("https://x/a.PNG")
                        && !CryptRecipes.INSTANCE.looksLikeImagePath("https://x/api.php"), "");
        ok("C6 子域也命中（cdn.pic.ndhixj.cn）",
                CryptRecipes.INSTANCE.mediaRecipeFor("https://cdn.pic.ndhixj.cn/a/b.jpg") != null, "");
        ok("C7 图床匹配**不看站点域名**（站点换域名不该让封面失效）",
                CryptRecipes.INSTANCE.mediaRecipeFor(COVER) != null
                        && CryptRecipes.INSTANCE.forUrl("https://agenda.fzchosdi.cc") == null, "");

        // ---------------------------------------------------------------- E 源码守卫
        System.out.println("\n== E 源码守卫（防改动被回退） ==");
        String http = src(proj, "app/src/main/java/com/videoshell/data/net/Http.kt");
        String imgc = src(proj, "app/src/main/java/com/videoshell/data/net/ImageCipher.kt");
        String doc = src(proj, "app/src/main/java/com/videoshell/data/site/SiteDoctor.kt");
        String adap = src(proj, "app/src/main/java/com/videoshell/data/site/HtmlAdapter.kt");
        String base = src(proj, "app/src/main/java/com/videoshell/data/site/SiteAdapter.kt");
        String cal = src(proj, "app/src/main/java/com/videoshell/ui/CalibrateActivity.kt");

        ok("E1 共用客户端（Coil 用的那条）挂了解密拦截器",
                http.contains(".addInterceptor(ImageCipher.interceptor)"), "");
        // ⚠️ 守卫要判**语义**，不要绑**语法**。
        // 这条原本写的是 `mediaRecipeFor(url) ?: return@Interceptor chain.proceed(req)` ——
        // v1.0.34 把"未收录站也过一遍探针"接进来之后，那句被改成了 `if (recipe == null) {...}`，
        // 语义一模一样、守卫却红了（假警报）。**绑死语法的守卫会把每次重构都变成误报**，
        // 于是人开始习惯性忽略它 —— 那守卫就废了。
        ok("E2 三道门都在：白名单匹配 / 明文图短路 / 解密后验魔数（按语义判）",
                imgc.contains("CryptRecipes.mediaRecipeFor(url)")
                        && imgc.contains("if (recipe == null)")
                        && imgc.contains("chain.proceed(req)")
                        && imgc.contains("if (ct.startsWith(\"image/\", true))")
                        && imgc.contains("if (AesCipher.isImage(raw))")
                        && imgc.contains("if (!AesCipher.isImage(plain))"), "");
        ok("E3 206 分片不解密（密文的一段解不出来）",
                imgc.contains("if (resp.code == 206) return@Interceptor resp"), "");
        ok("E4 自检 [3a] 对加密图床改走整段取字节",
                doc.contains("[3a] 封面取图实测") && doc.contains("Http.fetchBytes(cover, site.baseUrl)"), "");
        ok("E5 自检新增 [2a] 校准规则生效性",
                doc.contains("[2a] 校准规则是否生效") && doc.contains("a.calibApplied"), "");
        ok("E6 适配器暴露校准生效性契约",
                base.contains("open val calibDiag") && base.contains("open val calibApplied"), "");
        ok("E7 HtmlAdapter 在形状命中 <2 时**明说**退回了默认逻辑",
                adap.contains("已静默退回默认逻辑") && adap.contains("calibAppliedFlag = true"), "");
        ok("E8 重新校准会清掉旧的分类形状/容器（不再越校越错）",
                cal.contains("catTpl = null") && cal.contains("navSel = null")
                        && cal.contains("private fun restart()"), "");
        ok("E9 试播失败时「取消」不再静默丢弃整场校准",
                cal.contains("confirmDiscardCalib()") && cal.contains("private fun confirmDiscardCalib"), "");
        ok("E10 不在响应头里塞中文（HTTP 头只允许 ASCII —— 塞了每张封面都会抛异常）",
                !imgc.contains("X-VideoShell-Image") && imgc.contains("header(\"Content-Type\", type)"), "");

        // ---------------------------------------------------------------- F 端到端（真网络）
        System.out.println("\n== F 端到端：走 Http.client 真取封面（Coil 同一条栈） ==");
        try {
            OkHttpClient client = Http.INSTANCE.getClient();
            Request req = new Request.Builder().url(COVER)
                    .header("User-Agent", Http.UA)
                    .header("Referer", "https://agenda.fzchosdi.cc/")
                    .build();
            Response resp = client.newCall(req).execute();
            try {
                byte[] body = resp.body().bytes();
                String ct = resp.header("Content-Type");
                ok("F1 HTTP 200", resp.code() == 200, String.valueOf(resp.code()));
                ok("F2 响应体被**换成了明文图片**（Content-Type 变 image/*）",
                        ct != null && ct.startsWith("image/"), String.valueOf(ct));
                ok("F3 字节就是那张封面（sha256 与独立解密结果一致）",
                        PLAIN_SHA.equals(sha256(body)), sha256(body));
                ok("F4 响应体是合法图片", AesCipher.INSTANCE.isImage(body), hex(body, 8));
                ok("F5 长度已扣掉 PKCS7 填充（68707，不是密文的 68720）",
                        body.length == 68707, String.valueOf(body.length));
            } finally {
                resp.close();
            }
        } catch (Exception e) {
            System.out.println("[SKIP] F1-F5 取不到网络（本机离线？）：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        System.out.println("\n==== pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }
}
