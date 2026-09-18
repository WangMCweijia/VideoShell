package com.videoshell.player

/**
 * 覆盖层（顶栏 / 底栏 / 面板）补「挖孔 + 系统栏」安全距离的**纯计算**。
 *
 * 抽成纯函数是为了能在 JVM 上直接断言 —— 这段逻辑出过一次真事故：
 * v1.0.26 把窗口改成 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`（画面用满整屏），
 * 控件靠 padding 让位，但当时是**四条边一起补**：顶栏被同时加上 top（挖孔/状态栏 ~40dp）
 * 与 bottom（导航栏）两笔 padding，而它是固定 52dp 高 ⇒ 内容区被压成十几 dp，
 * 标题和图标只剩一条横带（用户反馈「竖屏播放时顶部信息栏显示不全」）。
 *
 * 规则只有一条：**一个控件贴哪条边，就只补哪条边**；左右永远补
 *（横屏时挖孔就在左右短边上，那是用户明确要求「不避让」的那一侧）。
 */
object PlayerInsets {

    /** 贴屏幕上边（顶栏）：只吃 `top` */
    const val TOP = 1

    /** 贴屏幕下边（底部控制条 / 选集面板 / 诊断面板）：只吃 `bottom` */
    const val BOTTOM = 2

    /**
     * 把安全距离加到基线 padding 上。
     *
     * @param base   该控件的**原始** padding，顺序 `[start, top, end, bottom]`。
     *               必须是原始值：监听器每次回调都会重算，在上一轮结果上累加会越撑越大。
     * @param insets 系统给的 insets，顺序同上。
     * @param edges  [TOP] 或 [BOTTOM]，可以 `or` 组合。
     */
    fun pad(base: IntArray, insets: IntArray, edges: Int): IntArray = intArrayOf(
        base[0] + insets[0],
        base[1] + if (edges and TOP != 0) insets[1] else 0,
        base[2] + insets[2],
        base[3] + if (edges and BOTTOM != 0) insets[3] else 0
    )
}
