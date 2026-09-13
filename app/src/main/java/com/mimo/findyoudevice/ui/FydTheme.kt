package com.mimo.findyoudevice.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

/**
 * 应用统一主题分发器：按 [style] 在 Material3 与 Miuix 之间切换。
 *  - MD3：Android 12+ 自动动态取色（Monet），低版本回落静态色板；
 *  - Miuix：MonetSystem 模式跟随系统壁纸动态取色。
 */
/** MIUI X 品牌色（用户指定 #FF3382FF） */
private val MiuixBrandBlue = Color(0xFF3382FF)

@Composable
fun FydTheme(style: UiStyle, dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    when (style) {
        UiStyle.MD3 -> {
            val scheme = when {
                dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalUiStyle provides UiStyle.MD3, content = content)
            }
        }
        UiStyle.MIUIX -> {
            if (dynamicColor) {
                val controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.MonetSystem) }
                MiuixTheme(controller = controller) {
                    CompositionLocalProvider(LocalUiStyle provides UiStyle.MIUIX, content = content)
                }
            } else {
                val colors = if (dark) {
                    miuixDarkColorScheme(primary = MiuixBrandBlue, primaryVariant = MiuixBrandBlue)
                } else {
                    miuixLightColorScheme(primary = MiuixBrandBlue, primaryVariant = MiuixBrandBlue)
                }
                MiuixTheme(colors = colors) {
                    CompositionLocalProvider(LocalUiStyle provides UiStyle.MIUIX, content = content)
                }
            }
        }
    }
}
