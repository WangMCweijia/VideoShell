import com.videoshell.player.PlayerInsets;

import java.util.Arrays;

/**
 * 离线校验：**覆盖层补安全距离的方位规则**（v1.0.28 修复）。
 *
 * 事故（用户反馈）：「竖屏播放时，顶部信息栏显示不全」—— 截图里标题与一排图标
 * 只剩一条横带，上下都被裁掉。真机像素核对：顶栏 52dp 高，却同时被补上了
 * top（挖孔/状态栏 ~40dp）+ bottom（导航栏）两笔内边距 ⇒ 内容区只剩十几 dp。
 *
 * 规则：**贴哪条边就只补哪条边**；左右永远补（横屏挖孔在左右短边）。
 * 这个套件把规则钉死，谁再写成「四条边一起补」就红。
 */
public class Ins {

    static int pass = 0, fail = 0;

    static void eq(String what, Object want, Object got) {
        boolean ok = String.valueOf(want).equals(String.valueOf(got));
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + what + "（期望 " + want + "，实际 " + got + "）");
        if (ok) pass++; else fail++;
    }

    static String s(int[] a) {
        return Arrays.toString(a).replace(" ", "");
    }

    public static void main(String[] args) {
        // 真机实测的竖屏 insets：顶部挖孔/状态栏 110px（≈40dp），底部导航栏 66px；左右为 0
        int[] portrait = {0, 110, 0, 66};
        int[] landscape = {72, 0, 0, 0};    // 横屏：挖孔在左侧短边

        int[] topBar = {4, 0, 8, 0};
        int[] bottomBar = {10, 4, 10, 4};

        eq("顶栏只吃 top：bottom 不许加", s(new int[]{4, 110, 8, 0}),
                s(PlayerInsets.INSTANCE.pad(topBar, portrait, PlayerInsets.TOP)));
        eq("底栏只吃 bottom：top 不许加", s(new int[]{10, 4, 10, 70}),
                s(PlayerInsets.INSTANCE.pad(bottomBar, portrait, PlayerInsets.BOTTOM)));
        eq("顶栏横屏也要吃左右（挖孔在短边）", s(new int[]{76, 0, 8, 0}),
                s(PlayerInsets.INSTANCE.pad(topBar, landscape, PlayerInsets.TOP)));
        eq("两边都要时四条边都吃", s(new int[]{4, 110, 8, 66}),
                s(PlayerInsets.INSTANCE.pad(topBar, portrait, PlayerInsets.TOP | PlayerInsets.BOTTOM)));
        eq("insets 全 0 时保持基线", s(new int[]{4, 0, 8, 0}),
                s(PlayerInsets.INSTANCE.pad(topBar, new int[]{0, 0, 0, 0}, PlayerInsets.TOP)));
        // 用**基线**重算是这个函数的调用契约（监听器每次回调都会重来一次）；
        // 这里证明同样的输入必得同样的输出 —— 只要调用方别拿去喂自己上一次的输出。
        eq("同输入必同输出（防越撑越大）", s(PlayerInsets.INSTANCE.pad(topBar, portrait, PlayerInsets.TOP)),
                s(PlayerInsets.INSTANCE.pad(topBar, portrait, PlayerInsets.TOP)));

        System.out.println();
        System.out.println("==== runins  pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }
}
