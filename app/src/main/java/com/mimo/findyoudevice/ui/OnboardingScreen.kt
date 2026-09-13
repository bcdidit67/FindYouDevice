package com.mimo.findyoudevice.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton as M3RadioButton
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mimo.findyoudevice.R
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.RadioButton as MiuixRadioButton
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

private data class OobeInfo(val iconRes: Int, val title: String, val desc: String)

private val oobePages = listOf(
    OobeInfo(
        R.drawable.ic_search,
        "局域网一键查找",
        "两台设备连入同一 WiFi 即可互找。客户端自动扫描网段，发现目标后一键触发响铃报警。",
    ),
    OobeInfo(
        R.drawable.ic_desktop,
        "主机模式 · 远程触发",
        "开启主机 Web 服务后后台常驻、息屏也能响应：局域网内任意浏览器输入密码即可触发报警。",
    ),
    OobeInfo(
        R.drawable.ic_bell,
        "清晰报警 · 触发方掌控",
        "报警默认使用应用内置音源，不依赖系统闹钟。响铃时长、闪光与锁定查找均由触发方决定，可随时停止。",
    ),
)

/** OOBE 首启引导：信息页 x3 + 风格选择页（默认 MD3） */
@Composable
fun OnboardingApp(onDone: (UiStyle) -> Unit) {
    var page by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(UiStyle.MD3) }
    val pageCount = oobePages.size + 1
    FydTheme(selected, dynamicColor = selected == UiStyle.MD3) {
        Box(Modifier.fillMaxSize().background(fydBackgroundColor())) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (page < pageCount - 1) {
                        FydText(
                            "跳过",
                            Modifier.clickable { onDone(selected) }.padding(8.dp),
                            color = fydSecondaryTextColor(),
                            fontSize = 15.sp,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith
                                (slideOutHorizontally(tween(260)) { -it / 3 } + fadeOut(tween(180)))
                        } else {
                            (slideInHorizontally(tween(260)) { -it / 2 } + fadeIn(tween(260))) togetherWith
                                (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(180)))
                        }
                    },
                    label = "oobe_page",
                ) { p ->
                    when (p) {
                        in oobePages.indices -> OobeInfoPage(oobePages[p])
                        else -> StyleSelectPage(selected, onSelect = { selected = it })
                    }
                }
                Spacer(Modifier.weight(1f))
                DotsIndicator(page, pageCount)
                Spacer(Modifier.size(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (page > 0) {
                        FydButton("上一步", { page-- }, Modifier.weight(1f))
                        Spacer(Modifier.size(12.dp))
                    }
                    FydButton(
                        if (page < pageCount - 1) "下一步" else "开始使用",
                        { if (page < pageCount - 1) page++ else onDone(selected) },
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun OobeInfoPage(info: OobeInfo) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(id = info.iconRes),
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            colorFilter = ColorFilter.tint(fydPrimaryColor()),
        )
        Spacer(Modifier.size(36.dp))
        FydText(info.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.size(16.dp))
        FydText(
            info.desc,
            fontSize = 15.sp,
            color = fydSecondaryTextColor(),
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun StyleSelectPage(selected: UiStyle, onSelect: (UiStyle) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        FydText("选择界面风格", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        FydText(
            "默认 Material Design 3，可随时在设置中更改",
            fontSize = 14.sp,
            color = fydSecondaryTextColor(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(28.dp))
        StyleOptionCard(UiStyle.MD3, "Material Design 3", "谷歌原生风格 · 动态取色", selected == UiStyle.MD3) { onSelect(UiStyle.MD3) }
        Spacer(Modifier.size(12.dp))
        StyleOptionCard(UiStyle.MIUIX, "Miuix", "类小米风格组件库 · 小米配色", selected == UiStyle.MIUIX) { onSelect(UiStyle.MIUIX) }
    }
}

@Composable
private fun StyleOptionCard(
    style: UiStyle,
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) fydPrimaryColor() else Color.Transparent
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(2.dp, borderColor, shape)
            .clickable(onClick = onClick),
    ) {
        when (style) {
            UiStyle.MD3 -> {
                val ctx = LocalContext.current
                val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicLightColorScheme(ctx)
                } else {
                    lightColorScheme()
                }
                MaterialTheme(colorScheme = scheme) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                M3Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                M3Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                            M3RadioButton(selected = selected, onClick = onClick)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            M3Switch(checked = true, onCheckedChange = null)
                            Spacer(Modifier.size(12.dp))
                            M3Button(onClick = onClick) { M3Text("按钮") }
                        }
                    }
                }
            }
            UiStyle.MIUIX -> {
                MiuixTheme(colors = miuixLightColorScheme()) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                MiuixText(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                MiuixText(desc, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
                            }
                            MiuixRadioButton(selected = selected, onClick = onClick)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MiuixSwitch(checked = true, onCheckedChange = null)
                            Spacer(Modifier.size(12.dp))
                            MiuixButton(onClick = onClick) { MiuixText("按钮") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DotsIndicator(current: Int, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val active = i == current
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (active) fydPrimaryColor() else fydSecondaryTextColor().copy(alpha = 0.4f)),
            )
        }
    }
}
