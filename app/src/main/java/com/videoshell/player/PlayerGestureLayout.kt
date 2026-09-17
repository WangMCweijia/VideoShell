package com.videoshell.player

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 播放器手势层：单击显隐控件、双击播放/暂停、横向拖动快进快退、左半屏上下调亮度、右半屏上下调音量、
 * 长按临时倍速。底部交给控制条的区域不拦截（返回 false，让下层的控制条自己拿到事件）。
 */
class PlayerGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 底部不拦截的高度（px），控件显示时设置 */
    var bottomBlockHeight: Int = 0

    /** 锁定时完全不吃手势，只留解锁按钮 */
    var locked: Boolean = false

    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onSeekPreview: ((Long) -> Unit)? = null
    var onSeekCommit: ((Long) -> Unit)? = null
    var onVolumeDelta: ((Float) -> Unit)? = null
    var onBrightnessDelta: ((Float) -> Unit)? = null
    var onGestureEnd: (() -> Unit)? = null
    var onLongPressStart: (() -> Unit)? = null
    var onLongPressEnd: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var longPressing = false

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (locked) return
            longPressing = true
            onLongPressStart?.invoke()
        }
    })

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var mode = MODE_NONE
    private var seekMs = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return false

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (bottomBlockHeight > 0 && event.y > height - bottomBlockHeight) return false
            downX = event.x
            downY = event.y
            lastX = event.x
            lastY = event.y
            mode = MODE_NONE
            seekMs = 0L
            longPressing = false
            tapDetector.onTouchEvent(event)
            return true
        }

        tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (mode == MODE_NONE && !longPressing) {
                    val ax = abs(event.x - downX)
                    val ay = abs(event.y - downY)
                    if (ax > touchSlop || ay > touchSlop) {
                        mode = if (ax > ay) MODE_H else MODE_V
                    }
                }
                when (mode) {
                    MODE_H -> {
                        val perPx = if (width > 0) 300_000f / width else 0f
                        seekMs += (dx * perPx).toLong()
                        onSeekPreview?.invoke(seekMs)
                    }
                    MODE_V -> {
                        val d = -dy / height.coerceAtLeast(1)
                        if (event.x < width / 2f) onBrightnessDelta?.invoke(d)
                        else onVolumeDelta?.invoke(d)
                    }
                }
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (longPressing) {
                    longPressing = false
                    onLongPressEnd?.invoke()
                }
                if (mode == MODE_H) onSeekCommit?.invoke(seekMs)
                if (mode != MODE_NONE) onGestureEnd?.invoke()
                mode = MODE_NONE
            }
        }
        return true
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_H = 1
        const val MODE_V = 2
    }
}
