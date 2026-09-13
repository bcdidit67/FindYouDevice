package com.mimo.findyoudevice

import android.app.Activity
import com.google.android.material.color.DynamicColors
import com.mimo.findyoudevice.ui.UiStyle

/**
 * 统一主题应用：XML Activity 随「界面风格 + 动态取色」偏好切换。
 * 在 Activity.onCreate() 的 super.onCreate() 之前调用。
 */
object AppThemeHelper {

    fun apply(activity: Activity) {
        val style = UiStyle.fromKey(Prefs.getUiStyle(activity))
        val dynamic = Prefs.getDynamicColor(activity)
        when {
            // MIUI X + 固定配色 → 小米风格主题（#FF3382FF）
            style == UiStyle.MIUIX && !dynamic ->
                activity.setTheme(R.style.Theme_FindYouDevice_Miuix)

            // 动态取色开启（MD3 默认 / MIUI X 手动开启）→ M3 主题 + Monet 动态色
            else -> {
                activity.setTheme(R.style.Theme_FindYouDevice)
                if (dynamic) {
                    DynamicColors.applyToActivityIfAvailable(activity)
                }
            }
        }
    }
}
