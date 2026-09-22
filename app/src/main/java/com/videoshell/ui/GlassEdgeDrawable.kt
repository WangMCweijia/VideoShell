package com.videoshell.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.videoshell.R

/**
 * 玻璃浮岛的**边缘折射**层（v1.0.50）。
 *
 * ## 为什么需要这一层
 *
 * 透明度只解决"能不能看见背后"，解决不了"这看起来是不是一块玻璃"。人眼判断玻璃
 * 靠的是**边缘那一圈光**：顶缘掠射进来的高光、紧贴它内侧的一道暗线（玻璃的厚度）、
 * 底部被折射回来的反光。底部导航的 alpha 已经降到 72%/65%，但整块玻璃在视觉上
 * 仍然是**平的** —— 边缘没有任何转折，所以看起来像"一块半透明的色片"而不是玻璃。
 * 这一层补的就是那圈转折。
 *
 * ## 为什么是自定义 Drawable 而不是 layer-list
 *
 * 折射沿**圆角矩形周长**分布：顶缘最亮、左右弱一档、底缘再一档，而且每一带都要
 * **向内衰减到透明**（硬边就变成描边了 —— 边框不是折射）。
 * layer-list 只会叠"整块矩形 + 单一方向的渐变"，做不到"只在边缘 3dp 内衰减"；
 * `android:height` / `android:gravity` 那种写法要 API 23，而本项目 minSdk 21。
 * 所以这里用 Path 裁剪 + 逐边渐变带来画。
 *
 * ## 三支颜色（极性见 values/values-night 两份 colors.xml）
 *
 * 亮色主题必须用**暗**棱（玻璃本身已经浅，再描白 = 边界消失），
 * 暗色主题用**亮**棱。两端（主光 / 次光）+ 内侧一道（厚度）各一支。
 *
 * ⚠️ 它是**叠在** `bg_glass_*` 之上的一层，不是替换 —— 填充、砂质、描边都还在那一份里。
 */
class GlassEdgeDrawable(
    private val radiusPx: Float,
    private val density: Float,
    /** 外棱主光（顶 / 上侧） */
    private val edgeA: Int,
    /** 外棱次光（底 / 下侧），必须比主光弱 —— 四边一样亮就变成"白框"而不是折射 */
    private val edgeB: Int,
    /** 内侧那一道 = 玻璃的厚度 */
    private val innerLine: Int
) : Drawable() {

    private val outer = Path()
    private val inner = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val innerRect = RectF()
    private var drawAlpha = 255

    override fun onBoundsChange(bounds: Rect) {
        val inset = INNER_INSET_DP * density
        rect.set(bounds)
        outer.reset()
        outer.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)

        innerRect.set(bounds)
        innerRect.inset(inset, inset)
        val r = (radiusPx - inset).coerceAtLeast(0f)
        inner.reset()
        inner.addRoundRect(innerRect, r, r, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val bo = b.bottom.toFloat()

        // ① 边缘带：裁到圆角矩形**里面**，逐边画"向内衰减到透明"的渐变。
        //    顺序是"先两侧、后上下"：四带的角部会叠加，把顶/底放在后面画，
        //    顶缘那道主光才能在拐角处压住侧光 —— 否则拐角会比顶缘本身亮，很假。
        val save = canvas.save()
        canvas.clipPath(outer)
        p.style = Paint.Style.FILL

        val sideW = 2f * density
        val sideCol = dim(edgeA, SIDE_GAIN)
        bandV(canvas, l, t, l + sideW, bo, true, sideCol)
        bandV(canvas, r - sideW, t, r, bo, false, sideCol)

        val topH = 3f * density
        bandH(canvas, l, t, r, t + topH, true, edgeA)

        val botH = 2.5f * density
        bandH(canvas, l, bo - botH, r, bo, false, edgeB)

        canvas.restoreToCount(save)

        // ② 外棱：沿周长的渐变（顶左最亮 → 底右次亮）。压着圆角矩形的边走，
        //    内半圈正好落在玻璃的填充上、外半圈与描边重合。
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * density
        p.shader = LinearGradient(
            l, t, r, bo,
            intArrayOf(edgeA, edgeB, edgeA),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        p.alpha = drawAlpha
        canvas.drawPath(outer, p)

        // ③ 内侧那一道：玻璃的厚度。没有它，外面那圈光就只是"发光的边框"；
        //    有它才读得出"光穿过了约 1.5dp 的玻璃"。
        p.shader = null
        p.strokeWidth = 1f * density
        p.color = innerLine
        // ⚠️ 必须在 setColor 之后 —— setColor 会连 alpha 一起覆盖掉
        p.alpha = drawAlpha
        canvas.drawPath(inner, p)
    }

    /** 横向带（上下两条边）：沿 y 从 [y0] 到 [y1] 衰减 */
    private fun bandH(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, fromTop: Boolean, color: Int) {
        val y0 = if (fromTop) t else b
        val y1 = if (fromTop) b else t
        p.shader = LinearGradient(0f, y0, 0f, y1, color, transparent(color), Shader.TileMode.CLAMP)
        p.alpha = drawAlpha
        canvas.drawRect(l, t, r, b, p)
    }

    /** 纵向带（左右两条边）：沿 x 从 [x0] 到 [x1] 衰减 */
    private fun bandV(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, fromLeft: Boolean, color: Int) {
        val x0 = if (fromLeft) l else r
        val x1 = if (fromLeft) r else l
        p.shader = LinearGradient(x0, 0f, x1, 0f, color, transparent(color), Shader.TileMode.CLAMP)
        p.alpha = drawAlpha
        canvas.drawRect(l, t, r, b, p)
    }

    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(cf: ColorFilter?) {
        p.colorFilter = cf
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** 内侧那一道离外缘多远 —— 就是"玻璃的厚度" */
        private const val INNER_INSET_DP = 1.5f

        /** 侧棱相对主光的强度。必须明显小于 1，否则四条边一样亮 */
        private const val SIDE_GAIN = 0.45f

        /** 去掉 alpha 只留 RGB（渐变终点要的是"全透明"，RGB 无所谓） */
        private fun transparent(c: Int) = c and 0x00FFFFFF

        private fun dim(c: Int, f: Float) =
            (((c ushr 24) * f).toInt().coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)

        /** 底部导航浮岛（圆角 radius_xl，最高的一档） */
        fun forNav(ctx: Context): GlassEdgeDrawable = of(ctx, R.dimen.radius_xl)

        /** 通用入口：以后要给别的浮岛（顶栏 / 搜索行 / 播放页底栏）也加折射，改一行即可 */
        fun of(ctx: Context, radiusRes: Int): GlassEdgeDrawable {
            val res = ctx.resources
            return GlassEdgeDrawable(
                radiusPx = res.getDimension(radiusRes),
                density = res.displayMetrics.density,
                edgeA = ContextCompat.getColor(ctx, R.color.glass_edge_a),
                edgeB = ContextCompat.getColor(ctx, R.color.glass_edge_b),
                innerLine = ContextCompat.getColor(ctx, R.color.glass_edge_c)
            )
        }
    }
}
