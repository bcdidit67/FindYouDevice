package com.mimo.findyoudevice.ui

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

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

    /** 复位到静止状态（对滑动范围外的页面显式归位，避免残影/黑边） */
    private fun restState(page: View) {
        page.alpha = 1f
        page.scaleX = 1f
        page.scaleY = 1f
        page.rotation = 0f
        page.rotationY = 0f
        page.translationX = 0f
    }

    object Fade : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            if (page.width == 0) return
            val pos = position.coerceIn(-1f, 1f)
            if (abs(pos) >= 1f) {
                restState(page)
                if (abs(pos) > 1f) page.alpha = 0f
                return
            }
            page.alpha = 1f - abs(pos)
            page.translationX = -pos * page.width * 0.25f
        }
    }

    object Wheel : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            if (page.width == 0) return
            val pos = position.coerceIn(-1f, 1f)
            if (abs(pos) >= 1f) {
                restState(page)
                if (abs(pos) > 1f) page.alpha = 0f
                return
            }
            val scale = (1f - abs(pos) * 0.25f).coerceAtLeast(0.7f)
            page.scaleX = scale
            page.scaleY = scale
            page.rotationY = pos * -45f
            page.alpha = (1f - abs(pos) * 0.5f).coerceAtLeast(0f)
            page.translationX = -pos * page.width * 0.3f
        }
    }

    object Flip : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            if (page.width == 0) return
            val pos = position.coerceIn(-1f, 1f)
            if (abs(pos) >= 1f) {
                restState(page)
                if (abs(pos) > 1f) page.alpha = 0f
                return
            }
            val density = page.resources.displayMetrics.density
            page.cameraDistance = 8000f * density
            page.pivotX = page.width / 2f
            page.pivotY = page.height / 2f
            page.rotationY = pos * 90f
            page.alpha = 1f - abs(pos)
        }
    }

    object Stack : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            if (page.width == 0) return
            val pos = position.coerceIn(-1f, 1f)
            if (abs(pos) >= 1f) {
                restState(page)
                if (abs(pos) > 1f) page.alpha = 0f
                return
            }
            val absPos = abs(pos)
            page.scaleX = 1f - absPos * 0.25f
            page.scaleY = 1f - absPos * 0.25f
            page.alpha = 1f - absPos
            page.translationX = -pos * page.width * 0.35f
        }
    }

    object Rotate : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            if (page.width == 0) return
            val pos = position.coerceIn(-1f, 1f)
            if (abs(pos) >= 1f) {
                restState(page)
                if (abs(pos) > 1f) page.alpha = 0f
                return
            }
            val absPos = abs(pos)
            page.rotation = pos * 90f
            page.scaleX = 1f - absPos * 0.2f
            page.scaleY = 1f - absPos * 0.2f
            page.alpha = 1f - absPos
        }
    }

    object Cube : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            if (page.width == 0) return
            val pos = position.coerceIn(-1f, 1f)
            if (abs(pos) >= 1f) {
                restState(page)
                if (abs(pos) > 1f) page.alpha = 0f
                return
            }
            val density = page.resources.displayMetrics.density
            page.cameraDistance = 8000f * density
            page.pivotX = page.width / 2f
            page.pivotY = page.height / 2f
            page.rotationY = pos * -90f
            page.alpha = (1f - abs(pos) * 0.6f).coerceIn(0f, 1f)
        }
    }
}
