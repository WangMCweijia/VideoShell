import com.videoshell.data.net.UpdateMirror;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * v1.0.56 —— 自更新**高速镜像层**的守卫（源码守卫 + 对真编译产物的行为断言）。
 *
 * ## 锁哪些事
 *
 * 镜像层的安全边界有三条（见 UpdateMirror 的类注释），这里各立一条断言：
 *
 *   1. **白名单先剥皮再判**（`UpdateMirror.innerOf(url) ?: url`）—— 镜像主机不进白名单，
 *      `wrap(镜像, https://evil.com/x.apk)` 必须被拒。写成"镜像主机也进白名单"等于开洞。
 *   2. **探测必须验 ZIP 魔数** —— 实测 `ghps.cc` / `gh-proxy.net` 对任何地址都返回
 *      `200 + HTML`。只看 HTTP 状态码它们是"最快的线路"；说谎者必须靠
 *      `looksLikeApk` 在浪费整次下载之前揪出来。
 *   3. **镜像清单要双源一致** —— 单镜像给出的清单可能被它篡改，必须拒绝。
 *
 * 行为断言走 `_cp.classpath()` 的**真编译产物**（与 Adb.java 同口径）——
 * 源码守卫锁" wiring 还在"，行为断言锁"判据本身判得对"。
 *
 * 纯离线（行为断言只调纯函数，不联网）⇒ 不该因网络抖动变红。
 *
 * 入参：a[0] = 编译类输出目录   a[1] = 工程根   a[2] = classpath
 */
public class UpdMirror {

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

    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(new File(p).toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void main(String[] a) throws Exception {
        String classes = a[0], proj = a[1], cp = a[2];

        // ================= 行为断言（真编译产物） =================
        Class<?> cls = java.lang.Class.forName("com.videoshell.data.net.UpdateMirror", true,
                new java.net.URLClassLoader(toUrls(classes, cp),
                        UpdMirror.class.getClassLoader()));
        Object M = cls.getField("INSTANCE").get(null);
        java.lang.reflect.Method wrap = cls.getMethod("wrap", String.class, String.class);
        java.lang.reflect.Method innerOf = cls.getMethod("innerOf", String.class);
        java.lang.reflect.Method prefixOf = cls.getMethod("prefixOf", String.class);
        java.lang.reflect.Method looksLikeApk = cls.getMethod("looksLikeApk", byte[].class);
        java.lang.reflect.Method candidates = cls.getMethod("candidates", String.class, String.class);
        java.lang.reflect.Method rank = cls.getMethod("rank", List.class, java.util.Map.class);
        @SuppressWarnings("unchecked")
        List<String> prefixes = (List<String>) cls.getMethod("getMIRROR_PREFIXES").invoke(M);

        System.out.println("== M 镜像表本身 ==");
        ok("M1 候选镜像 ≥5 个（太少 = 又一个单点；镜像来去很快，必须留余量）",
                prefixes.size() >= 5, "size=" + prefixes.size());
        boolean allHttps = true, allSlash = true;
        for (String p : prefixes) {
            if (!p.startsWith("https://")) allHttps = false;
            if (!p.endsWith("/")) allSlash = false;
        }
        ok("M2 全部是 https 且以 / 结尾（wrap 直接拼接，少斜杠会拼出坏地址）",
                allHttps && allSlash, "https=" + allHttps + " slash=" + allSlash);
        ok("M3 实测的两个\"说谎镜像\"不在表里（返回 200+HTML，靠魔数也该防，但表要保持干净）",
                !prefixes.contains("https://ghps.cc/") && !prefixes.contains("https://gh-proxy.net/"),
                prefixes.toString());

        System.out.println("\n== M wrap / 剥皮 ==");
        String w = (String) wrap.invoke(M, prefixes.get(0), "https://github.com/x/y.apk");
        ok("M4 wrap 拼出 `前缀 + 内层完整地址`",
                w.equals(prefixes.get(0) + "https://github.com/x/y.apk"), w);
        ok("M5 innerOf 能剥回内层地址（往返一致）",
                ("https://github.com/x/y.apk".equals(innerOf.invoke(M, w))), String.valueOf(innerOf.invoke(M, w)));
        ok("M6 非镜像地址 innerOf = null / prefixOf = null",
                innerOf.invoke(M, "https://github.com/x/y.apk") == null
                        && prefixOf.invoke(M, "https://github.com/x/y.apk") == null, "");
        ok("M7 大小写不敏感地识别前缀（URL 主机部分本就不分大小写）",
                prefixOf.invoke(M, "HTTPS://GH-PROXY.COM/https://github.com/a") != null, "");

        System.out.println("\n== M ZIP 魔数（说谎镜像的克星） ==");
        ok("M8 APK 头 PK\\x03\\x04 被认成 APK",
                (Boolean) looksLikeApk.invoke(M, new byte[]{'P', 'K', 3, 4, 0, 0}), "");
        ok("M9 HTML 开头（说谎镜像的 200 响应）不被认成 APK",
                !(Boolean) looksLikeApk.invoke(M, "<html><body".getBytes("UTF-8")), "");
        ok("M10 空数组 / 不足 4 字节一律 false",
                !(Boolean) looksLikeApk.invoke(M, new byte[0])
                        && !(Boolean) looksLikeApk.invoke(M, new byte[]{'P', 'K'}), "");

        System.out.println("\n== M 候选与排序 ==");
        @SuppressWarnings("unchecked")
        List<String> cands = (List<String>) candidates.invoke(M,
                "https://github.com/W/repo/releases/download/v1/app-release.apk",
                "https://api.github.com/repos/W/repo/releases/assets/123");
        ok("M11 候选 = 直连两条 + 每个镜像一层（共 " + (2 + prefixes.size()) + " 条）",
                cands.size() == 2 + prefixes.size(), "size=" + cands.size());
        ok("M12 直连两条在前（探测失败时它们是唯一确定的退路）",
                cands.get(0).startsWith("https://api.github.com/")
                        && cands.get(1).startsWith("https://github.com/"), cands.toString());
        boolean allWrapRaw = true;
        for (int i = 2; i < cands.size(); i++) {
            if (!cands.get(i).startsWith(prefixes.get(i - 2))
                    || !cands.get(i).endsWith("releases/download/v1/app-release.apk")) allWrapRaw = false;
        }
        ok("M13 每个镜像各包一层**裸链**地址（api 资产形式没有镜像支持证据，不进候选）",
                allWrapRaw, cands.subList(2, cands.size()).toString());

        java.util.Map<String, Double> speed = new java.util.HashMap<>();
        speed.put("fast", 1000.0);
        speed.put("slow", 100.0);
        List<String> ranked = (List<String>) rank.invoke(M,
                java.util.Arrays.asList("slow", "unmeasured1", "fast", "unmeasured2"), speed);
        ok("M14 测出速度的在前、快的在前；没测出的保持原顺序垫底",
                ranked.equals(java.util.Arrays.asList("fast", "slow", "unmeasured1", "unmeasured2")),
                ranked.toString());

        // ================= 源码守卫（wiring 不能被顺手删掉） =================
        System.out.println("\n== M 源码守卫 ==");
        String check = read(proj + "/app/src/main/java/com/videoshell/data/net/UpdateChecker.kt");
        String down = read(proj + "/app/src/main/java/com/videoshell/data/net/UpdateDownloader.kt");
        ok("M15 白名单**先剥皮再判**（镜像主机不进白名单；内层主机才受白名单约束）",
                check.contains("UpdateMirror.innerOf(url) ?: url"), "");
        ok("M16 清单有第三层镜像兜底，且**双源一致才接受**",
                check.contains("viaMirrors") && check.contains("双源一致")
                        && check.contains("单镜像清单不可信"), "");
        ok("M17 两个镜像内容矛盾 ⇒ 拒绝（不能取\"最后一个\"或\"第一个\"）",
                check.contains("互相矛盾"), "");
        ok("M18 镜像请求**只试一次**（Http.getOnce；get 的 3 次重试 × 8 镜像 = 两分钟假死）",
                check.contains("Http.getOnce") && check.contains("fast = true"), "");
        ok("M19 下载候选来自 UpdateMirror.candidates + rank（探测结果参与排序）",
                down.contains("UpdateMirror.candidates(info.apkUrl, info.apkApiUrl)")
                        && down.contains("UpdateMirror.rank(cands, speed)"), "");
        String loop = seg(down, "for (u in ordered)", "if (!ok)");
        ok("M20 sha256 校验在**候选循环之内**（在循环外时，说谎镜像耗掉唯一一次校验后整个更新失败）",
                loop.contains("sha256(target)") && loop.contains("ignoreCase = true"),
                "loop len=" + loop.length());
        ok("M21 探测请求带 Range 且验魔数（不带 Range = 每个候选真下整包，测速变下载）",
                down.contains("bytes=0-131071") && down.contains("looksLikeApk"), "");
        String flow = read(proj + "/app/src/main/java/com/videoshell/ui/UpdateFlow.kt");
        ok("M22 线路标签透传到确认弹窗（镜像清单的信任语义变了，必须可见）",
                flow.contains("update_via") && check.contains("viaLabel(got)"), "");

        System.out.println("\n==== pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }

    static String seg(String t, String a, String b) {
        int i = t.indexOf(a);
        if (i < 0) return "";
        int j = b == null ? -1 : t.indexOf(b, i);
        return t.substring(i, j < 0 ? t.length() : j);
    }

    static java.net.URL[] toUrls(String classes, String cp) throws Exception {
        java.util.List<java.net.URL> urls = new java.util.ArrayList<>();
        urls.add(new File(classes).toURI().toURL());
        for (String p : cp.split(File.pathSeparator)) {
            if (!p.isBlank()) urls.add(new File(p).toURI().toURL());
        }
        return urls.toArray(new java.net.URL[0]);
    }
}
