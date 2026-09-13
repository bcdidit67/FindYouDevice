package com.mimo.findyoudevice.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** 界面风格 */
enum class UiStyle(val key: String) {
    /** Material Design 3（默认） */
    MD3("md3"),

    /** Miuix 类小米风格 */
    MIUIX("miuix");

    companion object {
        fun fromKey(key: String?): UiStyle = entries.firstOrNull { it.key == key } ?: MD3
    }
}

/** 当前组合树中的界面风格 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.MD3 }
