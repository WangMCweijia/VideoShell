import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * v1.0.55 —— 应用内自更新的**可达性**断言套件（⑥ 此前是零断言覆盖）。
 *
 * ## 为什么要单独一个套件
 *
 * ⑤⑥ 这两块（配方导入导出 / 应用自更新）落进仓库时**一条守卫都没有** —— 它们
 * 只在"人工点一遍"里被验过。而这个项目已经反复栽在同一类事故上：**改了但没生效，
 * 而且没人会知道**（v1.0.53 迁移后每日探针一直跑旧副本；`detailTpl` 被静默替换）。
 * 自更新尤其危险 —— 它坏掉的表现是"用户手上永远是老版本"，而代码看起来毫无问题。
 *
 * ## 它锁的是哪件事
 *
 * 锁的不是"写了什么语法"，而是**清单能不能被取到**这条链上最容易退化的三处：
 *
 *   1. **两条通道都在**（api.github.com 优先、`github.com` 裸链兜底）。
 *      开发机实测：`github.com:443` 连接超时，而 `api.github.com` / `objects.githubusercontent.com`
 *      / `release-assets.githubusercontent.com` 全部 0.2~0.4s 通。只剩一条通道 = 那个
 *      打不通的主机上单点故障，而**症状是"检查更新失败"，不是崩溃**。
 *   2. **取资产内容必须带 `Accept: application/octet-stream`** —— 不带返回的是资产的
 *      JSON 元数据，于是 "apk" 是个几百字节的文本，报出来却是"摘要对不上"。
 *   3. **白名单是用"等于或点子域"比对的**（`host == it || host.endsWith("." + it)`）。
 *      改宽成 `host.endsWith(it)` 会让 `evilgithub.com` 通过 —— 那等于白名单失效，
 *      而测试里不会有人去试这个域名。所以这里**必须**锁住写法。
 *
 * 纯离线源码守卫，不联网 ⇒ 永远不该因为网络抖动变红。
 *
 * 入参：a[0] = 夹具目录（未用）   a[1] = 工程根
 */
public class Upd {

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

    /** 把拆出去的子文件里的顶层 internal 声明**还原成类成员的写法**（见 PITFALLS §4.41）。 */
    static String unwrap(String line, String owner) {
        if (!line.startsWith("internal ")) return line;
        String rest = line.substring("internal ".length());
        String p = "fun " + owner + ".";
        if (rest.startsWith(p)) return "private fun " + rest.substring(p.length());
        p = "suspend fun " + owner + ".";
        if (rest.startsWith(p)) return "private suspend fun " + rest.substring(p.length());
        if (rest.startsWith("val ")) return "private val " + rest.substring(4);
        if (rest.startsWith("var ")) return "private var " + rest.substring(4);
        return line;
    }

    /** 读一个 Kotlin 类的**全部**源码 = 主文件 + `<主名>_*.kt`（拼接不复制，计数断言语义不变）。 */
    static String src(String root, String rel) throws IOException {
        String p = root + File.separator + rel;
        String text = new String(read(p), java.nio.charset.StandardCharsets.UTF_8);
        File f = new File(p);
        String name = f.getName();
        if (!name.endsWith(".kt")) return text;
        File dir = f.getParentFile();
        String[] ns = dir == null ? null : dir.list();
        if (ns == null) return text;
        Arrays.sort(ns);
        String pre = name.substring(0, name.length() - 3) + "_";
        StringBuilder sb = new StringBuilder(text);
        for (String n : ns) {
            if (n.startsWith(pre) && n.endsWith(".kt")) {
                String sub = new String(
                        read(new File(dir, n).getPath()), java.nio.charset.StandardCharsets.UTF_8);
                for (String sl : sub.split("\n", -1)) {
                    sb.append(unwrap(sl, pre.substring(0, pre.length() - 1))).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 取 `t[a..b)`（找不到 a 返回 ""；找不到 b 则到末尾） */
    static String seg(String t, String a, String b) {
        int i = t.indexOf(a);
        if (i < 0) return "";
        int j = b == null ? -1 : t.indexOf(b, i);
        return t.substring(i, j < 0 ? t.length() : j);
    }

    public static void main(String[] a) throws Exception {
        String proj = a.length > 1 ? a[1] : ".";
        String check = src(proj, "app/src/main/java/com/videoshell/data/net/UpdateChecker.kt");
        String down = src(proj, "app/src/main/java/com/videoshell/data/net/UpdateDownloader.kt");

        // 先证明读到的**确实是那两个文件**：读不到时下面每一条 contains 都会假绿（vacuous）。
        ok("U0 读到 UpdateChecker.kt / UpdateDownloader.kt（守卫不能空转）",
                check.length() > 3000 && down.length() > 3000,
                "check=" + check.length() + " down=" + down.length());

        // ------------------------------------------------------------ 通道
        System.out.println("== U 更新清单：两条通道都在，且顺序正确 ==");
        ok("U1 第二个通道常量存在：api.github.com 的 latest Release",
                check.contains("\"https://api.github.com/repos/") && check.contains("RELEASE_API"), "");
        ok("U2 fetchManifest **先 api 后裸链**（顺序反了等于白搭：先撞那个打不通的主机）",
                check.contains("return viaApi()")
                        && check.contains("Http.get(directUrl)")
                        && check.indexOf("return viaApi()") < check.indexOf("Http.get(directUrl)"),
                "viaApi@" + check.indexOf("return viaApi()")
                        + " direct@" + check.indexOf("Http.get(directUrl)"));
        ok("U3 两条都失败时**分别点名**两条通道（用户唯一的下一步是换网络，得让他知道是网络）",
                check.contains("① api.github.com") && check.contains("② github.com")
                        && check.contains("换"), "");
        ok("U4 报错/自检要说清**走的是哪条通道**（决策要可观测）",
                check.contains("enum class Route { Api, Direct, Mirror }") && check.contains("${got.route}"), "");
        ok("U5 解析通道：按名字在 assets 里找 version.json 与 app-release.apk",
                check.contains("ASSET_MANIFEST") && check.contains("ASSET_APK")
                        && check.contains("byName[ASSET_MANIFEST]") && check.contains("byName[ASSET_APK]"), "");
        ok("U6 取**资产内容**带 Accept: application/octet-stream"
                        + "（不带就下到一段 JSON 元数据，症状却报成\"摘要对不上\"）",
                check.contains("ACCEPT_OCTET = \"application/octet-stream\"")
                        && check.contains("mapOf(\"Accept\" to ACCEPT_OCTET)"), "");
        ok("U7 同一次发布的**独立来源**交叉校验：GitHub 的 digest 必须等于清单里的 sha256",
                check.contains("apkDigest") && check.contains("parsed.sha256.equals(d, ignoreCase = true)"), "");
        ok("U8 备用下载地址随清单一起传下去（UpdateInfo.apkApiUrl + parse 透传）",
                check.contains("val apkApiUrl: String? = null")
                        && check.contains("UpdateInfo(code, name, apk, sha, size, notes, apkApiUrl)"), "");

        // ------------------------------------------------------------ 下载
        System.out.println("\n== U 下载：候选顺序 + 半截文件的处理 ==");
        ok("U9 下载候选来自 UpdateMirror.candidates（v1.0.56 起直连在前 + 镜像在后，顺序由探测定）",
                down.contains("UpdateMirror.candidates(info.apkUrl, info.apkApiUrl)")
                        && down.contains("UpdateMirror.rank(cands, speed)"), "");
        ok("U10 下载请求带 Accept: application/octet-stream（与 U6 是同一件事的两端）",
                down.contains("header(\"Accept\", \"application/octet-stream\")"), "");
        String loop = seg(down, "for (u in ordered)", "if (!ok)");
        ok("U11 换下一条地址前**先删半截文件**（否则第二条失败时报的是"
                        + "\"摘要对不上\"，真实原因被盖住）",
                loop.contains("target.delete()") && loop.contains("lastErr"), "len=" + loop.length());
        ok("U12 全部候选失败时抛的是**真实原因**，不是空指针/占位话",
                down.contains("throw lastErr ?: IOException(\"没有可用的下载地址\")"), "");

        // ------------------------------------------------------------ 反例：不许为了"能下成"把安全放宽
        System.out.println("\n== U 反例（这次改动最容易顺手放宽的东西） ==");
        ok("U13 白名单仍是\"等于或**点子域**\"—— 写成 endsWith(it) 会让 evilgithub.com 过关",
                check.contains("host == it || host.endsWith(\".$it\")"), "");
        ok("U14 非 https 一律拒绝（清单与备用地址两条都要过）",
                check.contains("下载地址不是 https") && check.contains("备用下载地址不是 https"), "");
        ok("U15 sha256 校验在**候选循环之内**且与\"不符即删\"没被顺手删掉"
                        + "（在循环外时，说谎镜像耗掉唯一一次校验机会后整个更新失败；"
                        + "这是唯一能区分\"下全了\"与\"HTTP 200 但给的不是包\"的判据）",
                loop.contains("!got.equals(info.sha256, ignoreCase = true)")
                        && loop.contains("target.delete()"), "");
        ok("U16 api.github.com 进了白名单（否则 parse 会把备用地址判为非法域名，改了个寂寞）",
                check.contains("\"api.github.com\""), "");

        System.out.println("\n==== pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }
}
