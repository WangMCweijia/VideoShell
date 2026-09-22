import java.lang.reflect.Method;

/**
 * Seed.java —— v1.0.53「签名种子配置族」判据的离线断言。
 *
 * 打的是**真编译产物** SeedConfig（它刻意不 import 任何 Android 类：
 * base64 是自己写的，因为 java.util.Base64 要 API 26、android.util.Base64 又不可离线跑）。
 * 所以这里能拿真实 config.json 当向量，而不是"照着源码另写一遍判据"。
 *
 * 判据的两侧都要钉：
 *   A 正例 —— 真实 payload 必须解出 api 地址；
 *   B 反例 —— 缺字段/错算法/坏 base64/不是 JSON/没有 api 数组，逐个必须**返回 null**。
 *     反例比正例重要：这一族是"全站兜底路由"的第一站，误判会让**每一个普通站**
 *     都走接口路径（那就从"多认一个站"变成"毁掉所有站"）。
 *   C base64 解码器自己的向量（含缺补位、URL-safe、空白、非法字符）。
 */
public class Seed {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    /** 真实的 https://huangju.net/config.json 响应体（2026-09-22 抓取，原样） */
    static final String REAL_BODY =
        "{\"alg\":\"ed25519\",\"payload\":\"eyJhcGkiOlsiaHR0cHM6Ly9hcGkuaHVhbmdqdS5uZXQiXSwiaXNzdWVk"
      + "QXQiOjE3OTAwMzU0MDcxMTgsInNlZWQiOlsiaHR0cHM6Ly9zZWVkLmh1YW5nanUubmV0L3NlZWQvY29uZmlnIiwiaHR0"
      + "cHM6Ly9odWFuZ2p1Lm5ldC9jb25maWcuanNvbiJdLCJ0cmFjayI6WyJodHRwczovL3RyYWNrLmh1YW5nanUubmV0Il0s"
      + "InZlcnNpb24iOjEsInZpZGVvIjpbImh0dHBzOi8vdmlkZW8uaHVhbmdqdS5uZXQiXX0=\","
      + "\"signature\":\"Er1BMGl10lQM0hgobKmlfy8kDqNoRFloXQ61uNsqCv0kezifhZlDLusy6ERmVEvrvmo9CoHAcLbpcKQ5/SNaAA==\"}";

    static final String REAL_PAYLOAD_B64 =
        "eyJhcGkiOlsiaHR0cHM6Ly9hcGkuaHVhbmdqdS5uZXQiXSwiaXNzdWVkQXQiOjE3OTAwMzU0MDcxMTgs"
      + "InNlZWQiOlsiaHR0cHM6Ly9zZWVkLmh1YW5nanUubmV0L3NlZWQvY29uZmlnIiwiaHR0cHM6Ly9odWFu"
      + "Z2p1Lm5ldC9jb25maWcuanNvbiJdLCJ0cmFjayI6WyJodHRwczovL3RyYWNrLmh1YW5nanUubmV0Il0s"
      + "InZlcnNpb24iOjEsInZpZGVvIjpbImh0dHBzOi8vdmlkZW8uaHVhbmdqdS5uZXQiXX0=";

    // ⚠️ Kotlin 的 `object` 编译出来是**单例类的实例方法**，不是 static ——
    //    reflect 时必须先拿 INSTANCE 当接收者，否则 m.invoke(null, …) 直接 NPE。
    //    另外成员若是 `internal`，JVM 上会被改名成 `apiBase$模块名`，getMethod 找不到 ⇒ 必须 public。
    static Object inst() throws Exception {
        return Class.forName("com.videoshell.data.site.SeedConfig")
                .getField("INSTANCE").get(null);
    }

    static String apiBase(String body) throws Exception {
        Object o = inst();
        Method m = o.getClass().getMethod("apiBase", String.class);
        return (String) m.invoke(o, body);
    }

    static String b64(String s) throws Exception {
        Object o = inst();
        Method m = o.getClass().getMethod("decodeBase64", String.class);
        return (String) m.invoke(o, s);
    }

    public static void main(String[] args) throws Exception {

        System.out.println("== A. 真实 config.json 必须自证成功 ==");
        String api = apiBase(REAL_BODY);
        ok("真实响应体解出 api 基地址", "https://api.huangju.net".equals(api));
        ok("取的是 payload 里的 api，不是别的字段", api != null && !api.contains("huangju.net/config"));
        ok("前后有空白也照样解", "https://api.huangju.net".equals(apiBase("  " + REAL_BODY + "\n")));

        // ⚠️ 不能对 REAL_BODY 做 `replace("https://api.huangju.net", …)` ——
        //    那个地址在**base64 里面**，明文替换是空操作（这条第一版就是空断言）。
        //    必须自己造一个真的带尾斜杠的向量，并拿"不带斜杠"那条做对照。
        String p2 = "{\"api\":[\"https://api.example.com/\"]}";
        String p3 = "{\"api\":[\"https://api.example.com\"]}";
        String body2 = "{\"alg\":\"ed25519\",\"payload\":\""
                + java.util.Base64.getEncoder().encodeToString(p2.getBytes("UTF-8"))
                + "\",\"signature\":\"x\"}";
        String body3 = "{\"alg\":\"ed25519\",\"payload\":\""
                + java.util.Base64.getEncoder().encodeToString(p3.getBytes("UTF-8"))
                + "\",\"signature\":\"x\"}";
        ok("★ 带尾斜杠与不带尾斜杠得到**同一个** api（归一化真的发生了）",
                "https://api.example.com".equals(apiBase(body2))
                        && apiBase(body2).equals(apiBase(body3)));

        System.out.println();
        System.out.println("== B. 反例：不是这一族的必须返回 null（误判会毁掉所有普通站）==");
        ok("null 输入", apiBase(null) == null);
        ok("空串", apiBase("") == null);
        ok("普通 HTML 页面", apiBase("<!DOCTYPE html><html><body>hi</body></html>") == null);
        ok("404 的 JSON", apiBase("{\"error\":\"not found\"}") == null);
        ok("缺 alg", apiBase(REAL_BODY.replace("\"alg\":\"ed25519\",", "")) == null);
        ok("alg 不是 ed25519", apiBase(REAL_BODY.replace("ed25519", "rsa")) == null);
        ok("signature 为空", apiBase(REAL_BODY.replace(
                "\"Er1BMGl10lQM0hgobKmlfy8kDqNoRFloXQ61uNsqCv0kezifhZlDLusy6ERmVEvrvmo9CoHAcLbpcKQ5/SNaAA==\"", "")) == null);
        ok("payload 为空", apiBase(REAL_BODY.replace(REAL_PAYLOAD_B64, "")) == null);
        ok("payload 不是 base64（含非法字符）", apiBase(
                "{\"alg\":\"ed25519\",\"payload\":\"不是base64!!\",\"signature\":\"x\"}") == null);
        ok("payload 解出来不是 JSON", apiBase(
                "{\"alg\":\"ed25519\",\"payload\":\"aGVsbG8gd29ybGQ=\",\"signature\":\"x\"}") == null);
        ok("payload 里没有 api 数组", apiBase(
                "{\"alg\":\"ed25519\",\"payload\":\"eyJ2ZXJzaW9uIjoxfQ==\",\"signature\":\"x\"}") == null);
        ok("api 数组里没有 http 地址", apiBase(
                "{\"alg\":\"ed25519\",\"payload\":\"eyJhcGkiOlsibG9jYWxob3N0OjMwMDEiXX0=\",\"signature\":\"x\"}") == null);
        ok("只有 signature 没有 alg", apiBase("{\"signature\":\"x\",\"payload\":\"" + REAL_PAYLOAD_B64 + "\"}") == null);

        System.out.println();
        System.out.println("== C. base64 解码器自己的向量 ==");
        ok("标准补位 aGVsbG8= -> hello", "hello".equals(b64("aGVsbG8=")));
        ok("缺补位 aGVsbG8 -> hello（长度不整除 4 也照解）", "hello".equals(b64("aGVsbG8")));
        ok("换行与空格被忽略", "hello".equals(b64("aGVsbG\n8 =\n")));
        ok("非法字符返回 null", b64("aGVs*bG8=") == null);
        ok("空串返回 null", b64("") == null);

        // 用 JVM 自己的 Base64 当基准。向量必须**确实含 `+` 或 `/`** ——
        // 否则"URL-safe 与标准等价"这条就是空断言（`-`/`_` 替换没改变任何字符 ⇒ 恒真）。
        // ⚠️ 纯 ASCII 造不出 `+` / `/`：那要 6bit 组等于 62/63，ASCII 可打印区凑不出来。
        //    「俿」U+4FFF 的 UTF-8 是 E4 BF BF ⇒ base64 = `5L+/`，同时含 `+` 与 `/`。
        //    把它嵌进一个"像 payload"的串里，避免为了凑字符写个不真实的向量。
        String sample = "{\"api\":[\"https://俿.net/+/\"],\"v\":1}";
        byte[] raw = sample.getBytes("UTF-8");
        String stdB64 = java.util.Base64.getEncoder().encodeToString(raw);
        String urlB64 = java.util.Base64.getUrlEncoder().encodeToString(raw);
        ok("★ 这段向量确实含 + 或 /（守卫上面那条不是空断言）",
                stdB64.contains("+") || stdB64.contains("/"));
        ok("★ URL-safe 的 - 与 _ 解出来与标准 + 与 / 完全一致",
                b64(stdB64).equals(sample) && b64(urlB64).equals(sample));
        ok("真实 payload 的自写解码器与 JVM 基准逐字节一致",
                b64(REAL_PAYLOAD_B64).equals(
                        new String(java.util.Base64.getDecoder().decode(REAL_PAYLOAD_B64), "UTF-8")));
        ok("真实 payload 解出的 JSON 含 api / seed / track / video 四个键",
                b64(REAL_PAYLOAD_B64).contains("\"api\"")
                && b64(REAL_PAYLOAD_B64).contains("\"seed\"")
                && b64(REAL_PAYLOAD_B64).contains("\"track\"")
                && b64(REAL_PAYLOAD_B64).contains("\"video\""));

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("Seed  pass=" + pass + " fail=" + fail);
        System.out.println("=".repeat(72));
        System.exit(fail == 0 ? 0 : 1);
    }
}
