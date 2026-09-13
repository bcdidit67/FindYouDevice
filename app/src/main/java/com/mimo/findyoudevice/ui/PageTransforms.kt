package com.mimo.findyoudevice.ui

import android.view.View
import androidx.viewpager2.widget.ViewPager2

/** MIUI 桌面风格翻页效果（7 款）：经典 / 淡入淡出 / 转盘 / 翻页 / 层叠 / 旋转 / 方块 */
object PageTransforms {

    val NAMES = listOf("经典", "淡入淡出", "转盘", "翻页", "层叠", "旋转", "方块")

    fun get(index: Int): ViewPager2.PageTransformer? = when (index) {
        0 -> null // 经典 = ViewPager2 默认滑动
        1 -> Fade
        2 -> Wheel
        3 -> Flip
        4 -> Stack
        5 -> Rotate
        6 -> Cube
        else -> null
    }

    object Fade : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            page.alpha = 1f - kotlin.math.abs(position)
            page.translationX = -position * page.width * 0.25f
        }
    }

    object Wheel : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val scale = (1f - kotlin.math.abs(position) * 0.25f).coerceAtLeast(0.7f)
            page.scaleX = scale
            page.scaleY = scale
            page.rotationY = position * -45f
            page.alpha = (1f - kotlin.math.abs(position) * 0.5f).coerceAtLeast(0f)
            page.translationX = -position * page.width * 0.3f
        }
    }

    object Flip : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            page.rotationY = position * 180f
            page.pivotX = if (position >= 0f) 0f else page.width.toFloat()
            page.pivotY = page.height / 2f
            page.alpha = (1f - kotlin.math.abs(position)).coerceIn(0f, 1f)
            page.translationX = -position * page.width
        }
    }

    object Stack : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val absPos = kotlin.math.abs(position)
            page.scaleX = 1f - absPos * 0.25f
            page.scaleY = 1f - absPos * 0.25f
            page.alpha = 1f - absPos
            page.translationX = -position * page.width * 0.35f
        }
    }

    object Rotate : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val absPos = kotlin.math.abs(position)
            page.rotation = position * 90f
            page.scaleX = 1f - absPos * 0.2f
            page.scaleY = 1f - absPos * 0.2f
            page.alpha = (1f - absPos).coerceIn(0f, 1f)
        }
    }

    object Cube : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            page.rotationY = position * -90f
            page.pivotX = if (position >= 0f) 0f else page.width.toFloat()
            page.pivotY = page.height / 2f
            page.alpha = (1f - kotlin.math.abs(position) * 0.5f).coerceIn(0f, 1f)
            page.translationX = -position * page.width * 0.2f
        }
    }
}
