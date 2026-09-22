import com.videoshell.data.net.Http;
import com.videoshell.data.net.UpdateChecker;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

import java.util.Collections;

/**
 * v1.0.55 —— 应用内自更新的**端到端可达性实测**（诊断工具，不是断言套件）。
 *
 * ## 它回答的问题
 *
 * "现在点『检查更新』到底能不能拿到清单、能不能看到新版本？"
 *
 * 这个问题**读代码读不出来** —— 2026-09-22 的实测就是反例：URL 是对的、清单内容是
 * 对的、sha256 是对的、`parse()` 也是对的，可整条链**在真实网络上是废的**：
 * `MANIFEST_URL` 的第一跳是 `github.com`，而那个主机当时连接超时（5s 打不通）。
 * 同一时刻 `api.github.com` 0.30s、`objects.githubusercontent.com` 0.24s、
 * `release-assets.githubusercontent.com` 0.36s 全通。
 *
 * ## 所以这个 harness 必须带**对照组**
 *
 * ```
 * A（对照）  直接用 Http.get 打 MANIFEST_URL          → 期望：失败（就是这个故障）
 * B（实验）  UpdateChecker.check()（双通道）          → 期望：成功，且 apkApiUrl 非空
 * ```
 *
 * 只有 A 红、B 绿才能证明"这次改的通道**是必要的**"；两边都绿说明当时网络本来就通、
 * 这次改动只是兜底；两边都红说明还有别的问题（改法没解决问题）。
 * **没有对照组的绿是读不出结论的** —— 这正是本项目反复强调的那条纪律。
 *
 * 用法：`python tools/verify/runupdlive.py`
 *
 * ⚠️ 它**需要真实网络**，所以不进 `SUITES`（也不进 `NET_SUITES`：那里放的是"有断言
 * 但依赖网络"的套件，这个是排查工具，只输出结果、不产生 PASS/FAIL）。它登记在
 * `runall.py` 头部的「不在回归列表里的『工具』」一节 —— 不登记就会有人以为它跑过了。
 */
public class UpdLive {

    static <T> T block(Function2 fn) throws Exception {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn);
    }

    public static void main(String[] a) throws Exception {
        String code = UpdateChecker.MANIFEST_URL;
        System.out.println("MANIFEST_URL = " + code);
        System.out.println("RELEASE_API  = " + UpdateChecker.RELEASE_API);

        // ---------------------------------------------------------------- A 对照组
        System.out.println("\n================ A 对照组：直接用 App 原来那条路（裸链） ================");
        boolean directOk;
        try {
            String body = block((scope, cont) -> Http.INSTANCE.get(
                    code, null, Http.UA, Collections.emptyMap(), false,
                    (Continuation<? super String>) cont));
            directOk = body != null && !body.isBlank();
            System.out.println("  ✅ 通：拿到 " + (body == null ? 0 : body.length()) + " 字符");
        } catch (Exception e) {
            directOk = false;
            System.out.println("  ❌ 不通：" + e.getClass().getName() + ": " + e.getMessage());
        }

        // ---------------------------------------------------------------- B 实验组
        System.out.println("\n================ B 实验组：UpdateChecker.check()（现在双通道） ================");
        // 先问出"线上最新是哪个 code" —— 后面拿它当基准，harness 就不必每发一版改一次。
        System.out.println("  —— 假装本机是 code=0（比谁都老）⇒ 应当报「有新版本」，这就是**存量用户**看到的");
        UpdateChecker.State s0 = block((scope, cont) -> UpdateChecker.INSTANCE.check(
                0, code, (Continuation<? super UpdateChecker.State>) cont));
        boolean newerPath = report(s0);
        if (s0 instanceof UpdateChecker.State.Newer) {
            final int latest = ((UpdateChecker.State.Newer) s0).getInfo().getVersionCode();

            System.out.println("\n  —— 假装本机**就是**最新（code=" + latest + "）⇒ 应当报「已是最新」");
            report(block((scope, cont) -> UpdateChecker.INSTANCE.check(
                    latest, code, (Continuation<? super UpdateChecker.State>) cont)));

            System.out.println("\n  —— 假装本机差一版（code=" + (latest - 1) + "）⇒ 应当报「有新版本 v"
                    + latest + "」；**这就是手上那一版现在会看到的东西**");
            report(block((scope, cont) -> UpdateChecker.INSTANCE.check(
                    latest - 1, code, (Continuation<? super UpdateChecker.State>) cont)));
        } else {
            System.out.println("\n  （取不到线上版本号，后两组对照跳过）");
        }

        // ---------------------------------------------------------------- 结论
        System.out.println("\n================ 结论 ================");
        System.out.println("  对照组 A（裸链直打）      ：" + (directOk ? "通" : "不通"));
        System.out.println("  实验组 B（双通道 check）  ：" + (newerPath ? "通（且能看到新版本）" : "不通"));
        if (!directOk && newerPath) {
            System.out.println("  ⇒ 这次改动的通道**是必要的**：旧路在这条网络上就是废的，"
                    + "没有对照组的话这个故障读代码看不出来。");
        } else if (directOk && newerPath) {
            System.out.println("  ⇒ 两条路都通（当前网络能到 github.com）。双通道仍是对的 ——"
                    + "弱网/被阻断时才是它起作用的时候。");
        } else {
            System.out.println("  ⇒ 两条都不通：不是通道问题，是**本机网络到不了 GitHub**。"
                    + "换网络再跑，或先确认 DNS/代理。");
        }
    }

    /** 打印一个 State；返回"能看到新版本"是否成立 */
    static boolean report(UpdateChecker.State s) {
        if (s instanceof UpdateChecker.State.Latest) {
            System.out.println("     结果：已是最新（Latest）");
            return false;
        }
        if (s instanceof UpdateChecker.State.Newer) {
            UpdateChecker.UpdateInfo i = ((UpdateChecker.State.Newer) s).getInfo();
            System.out.println("     结果：有新版本 " + i.getVersionName()
                    + "（code=" + i.getVersionCode() + "）");
            System.out.println("     apkUrl（清单裸链）  ：" + i.getApkUrl());
            System.out.println("     apkApiUrl（备用）   ：" + i.getApkApiUrl());
            System.out.println("     ← 备用地址**非空就说明走的是 api.github.com 通道**"
                    + "（= 那个在这条网络上真的通的主机）");
            System.out.println("     sha256              ：" + i.getSha256());
            System.out.println("     size                ：" + i.getSize());
            return i.getApkApiUrl() != null && i.getSha256() != null
                    && i.getSha256().length() == 64;
        }
        if (s instanceof UpdateChecker.State.Failed) {
            System.out.println("     结果：失败 —— " + ((UpdateChecker.State.Failed) s).getReason());
            return false;
        }
        System.out.println("     结果：未知类型 " + s);
        return false;
    }
}
