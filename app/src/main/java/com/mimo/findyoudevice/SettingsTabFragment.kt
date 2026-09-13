package com.mimo.findyoudevice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.mimo.findyoudevice.ui.SettingsApp
import com.mimo.findyoudevice.ui.UiStyle

/**
 * 设置 Tab（Compose）：界面风格切换 / 动态取色 / 主机配置（密码、闪光灯节点）/ 组件预览 / 关于。
 * 由 MainActivity 底栏「设置」Tab 加载；风格切换立即生效。
 */
class SettingsTabFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val context = LocalContext.current
                var style by remember { mutableStateOf(UiStyle.fromKey(Prefs.getUiStyle(context))) }
                var dynamicColor by remember { mutableStateOf(Prefs.getDynamicColor(context)) }
                var transition by remember { mutableStateOf(Prefs.getPageTransition(context)) }
                SettingsApp(
                    style = style,
                    dynamicColor = dynamicColor,
                    onStyleChange = { newStyle ->
                        style = newStyle
                        Prefs.setUiStyle(context, newStyle.key)
                        // 切换风格时重置动态取色为该风格默认：MD3=开，MIUI X=关
                        val def = newStyle == UiStyle.MD3
                        Prefs.setDynamicColor(context, def)
                        dynamicColor = def
                    },
                    onDynamicChange = { v ->
                        dynamicColor = v
                        Prefs.setDynamicColor(context, v)
                    },
                    pageTransition = transition,
                    onPageTransitionChange = { i ->
                        transition = i
                        Prefs.setPageTransition(context, i)
                        (requireActivity() as? MainActivity)?.applyPageTransition()
                    },
                )
            }
        }
    }
}
