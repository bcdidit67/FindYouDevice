package com.mimo.findyoudevice

import android.app.Activity
import com.google.android.material.color.DynamicColors
import com.mimo.findyoudevice.ui.UiStyle

/**
 * 统一主题应用：XML Activity 随「界面风格 + 动态取色」偏好切换。
 *
 * 拆分为两步（重要）：
 *  - [applyBaseTheme]：仅 setTheme，必须在 super.onCreate() 之前调用；
 *  - [applyDynamicColors]：应用 Monet 动态色，必须在 super.onCreate() 之后、
 *    首次 setContentView() 之前调用（提前调用会不生效，导致全局回落默认紫色）。
 */
object AppThemeHelper {

    /** 仅应用基础主题（super.onCreate 之前调用） */
    fun applyBaseTheme(activity: Activity) {
        val style = UiStyle.fromKey(Prefs.getUiStyle(activity))
        val dynamic = Prefs.getDynamicColor(activity)
        when {
            // MIUI X + 固定配色 → 小米风格主题（#FF3382FF）
            style == UiStyle.MIUIX && !dynamic ->
                activity.setTheme(R.style.Theme_FindYouDevice_Miuix)

            // 其余：M3 主题（动态色开启时再叠加 Monet）
            else -> activity.setTheme(R.style.Theme_FindYouDevice)
        }
    }

    /** 应用动态取色（super.onCreate 之后、setContentView 之前调用） */
    fun applyDynamicColors(activity: Activity) {
        val dynamic = Prefs.getDynamicColor(activity)
        if (dynamic) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
    }
}
