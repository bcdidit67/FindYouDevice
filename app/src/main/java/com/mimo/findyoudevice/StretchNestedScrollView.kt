package com.mimo.findyoudevice

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import androidx.core.widget.NestedScrollView

/**
 * 整页弹性拉伸滚动容器（MIUI / Compose 式 overscroll）。
 *
 * - 短内容也可拉出：在顶部继续下拉 / 在底部继续上拉时，整个内容子 View 随手指位移；
 * - 松手后以弹性动画（Overshoot）回弹归位，丝滑顺畅；
 * - 拉伸作用于整个内容（含页面标题），而非仅列表区域。
 */
class StretchNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    private var startRawY = 0f
    private var overScrolling = false

    /** 阻尼系数：位移 = 拉动距离 × 该值（越小越"硬"） */
    private val damping = 0.55f

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawY = ev.rawY
                overScrolling = false
                getChildAt(0)?.animate()?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - startRawY
                if (!overScrolling) {
                    val pullingDown = dy > 0f && !canScrollVertically(-1)
                    val pullingUp = dy < 0f && !canScrollVertically(1)
                    if (pullingDown || pullingUp) overScrolling = true
                }
                if (overScrolling) {
                    getChildAt(0)?.let { child ->
                        child.translationY = dy * damping
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (overScrolling) {
                    getChildAt(0)?.animate()
                        ?.translationY(0f)
                        ?.setDuration(460)
                        ?.setInterpolator(OvershootInterpolator(0.85f))
                        ?.start()
                    overScrolling = false
                }
            }
        }
        return super.onTouchEvent(ev)
    }
}
