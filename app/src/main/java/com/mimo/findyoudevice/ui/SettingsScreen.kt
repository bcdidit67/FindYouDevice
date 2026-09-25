package com.mimo.findyoudevice.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton as M3RadioButton
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.mimo.findyoudevice.Prefs

/**
 * 设置页（Compose）：MIUI X 风格布局（大标题 / 分组卡片 / 行式条目）。
 * 内容：通用（运行模式）/ 外观（界面风格、动态取色、翻页效果）/ 主机配置 / 组件预览 / 关于。
 */
@Composable
fun SettingsApp(
    style: UiStyle,
    dynamicColor: Boolean,
    pageTransition: Int,
    autoStartWifi: Boolean,
    autoStopCellular: Boolean,
    autoStopMetered: Boolean,
    onStyleChange: (UiStyle) -> Unit,
    onDynamicChange: (Boolean) -> Unit,
    onPageTransitionChange: (Int) -> Unit,
    onAutoStartWifiChange: (Boolean) -> Unit,
    onAutoStopCellularChange: (Boolean) -> Unit,
    onAutoStopMeteredChange: (Boolean) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var switchOn by remember { mutableStateOf(true) }
    var pickerVisible by remember { mutableStateOf(false) }
    var transitionSheetVisible by remember { mutableStateOf(false) }
    var styleRowBounds by remember { mutableStateOf<Rect?>(null) }
    val context = LocalContext.current
    var pwd by remember { mutableStateOf("") }
    var flashPath by remember { mutableStateOf(Prefs.getFlashPath(context)) }
    var ringtoneName by remember { mutableStateOf(currentRingtoneName(context)) }
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            Prefs.setRingtoneUri(context, uri)
            ringtoneName = uri.lastPathSegment ?: "自定义铃声"
            Toast.makeText(context, "铃声已保存", Toast.LENGTH_SHORT).show()
        }
    }
    FydTheme(style, dynamicColor) {
        Box(Modifier.fillMaxSize().background(fydBackgroundColor())) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                if (onBack != null) {
                    FydText(
                        "← 返回",
                        Modifier.clickable(onClick = onBack).padding(4.dp),
                        color = fydPrimaryColor(),
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.size(12.dp))
                }
                FydText("设置", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(2.dp))
                FydText("Find You Device v1.1.1", fontSize = 13.sp, color = fydSecondaryTextColor())
                Spacer(Modifier.size(20.dp))

                FydSmallTitle("外观")
                Spacer(Modifier.size(8.dp))
                FydCard(modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.onGloballyPositioned { styleRowBounds = it.boundsInWindow() }) {
                        FydItemRow(
                            title = "界面风格",
                            subtitle = "选择应用的界面风格",
                            trailingText = if (style == UiStyle.MIUIX) "Miuix" else "Material Design",
                            showArrow = true,
                            onClick = { pickerVisible = true },
                        )
                    }
                    FydItemRow(
                        title = "动态取色",
                        subtitle = "跟随系统壁纸自动取色（Monet）",
                        trailing = { FydSwitch(checked = dynamicColor, onCheckedChange = onDynamicChange) },
                    )
                    FydItemRow(
                        title = "翻页效果",
                        subtitle = "Tab 切换动画（7 款）",
                        trailingText = PageTransforms.NAMES[pageTransition.coerceIn(0, 6)],
                        showArrow = true,
                        onClick = { transitionSheetVisible = true },
                    )
                }
                Spacer(Modifier.size(20.dp))

                FydSmallTitle("服务")
                Spacer(Modifier.size(8.dp))
                FydCard(modifier = Modifier.fillMaxWidth()) {
                    FydItemRow(
                        title = "连接 WiFi 时自动开启",
                        subtitle = "连接普通 WiFi 后自动启动 Web 服务（需您曾开启过服务）",
                        trailing = {
                            FydSwitch(checked = autoStartWifi, onCheckedChange = onAutoStartWifiChange)
                        },
                    )
                    FydItemRow(
                        title = "连接移动流量时自动关闭",
                        subtitle = "检测到正在使用移动数据时自动停止服务",
                        trailing = {
                            FydSwitch(checked = autoStopCellular, onCheckedChange = onAutoStopCellularChange)
                        },
                    )
                    FydItemRow(
                        title = "连接热点 / 计量网络时自动关闭",
                        subtitle = "连接他人热点等计量网络时自动停止服务",
                        trailing = {
                            FydSwitch(checked = autoStopMetered, onCheckedChange = onAutoStopMeteredChange)
                        },
                    )
                }
                Spacer(Modifier.size(20.dp))

                FydSmallTitle("主机配置")
                    Spacer(Modifier.size(8.dp))
                    FydCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            FydText("密码", fontSize = 16.sp)
                            Spacer(Modifier.size(2.dp))
                            FydText(
                                "Web 控制台与客户端查找的鉴权密码（加密存储，留空则清除）",
                                fontSize = 13.sp,
                                color = fydSecondaryTextColor(),
                            )
                            Spacer(Modifier.size(10.dp))
                            FydTextField(
                                value = pwd,
                                onValueChange = { pwd = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = "新密码",
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            Spacer(Modifier.size(10.dp))
                            FydButton(
                                "保存密码",
                                onClick = {
                                    Prefs.setPasswordHash(context, if (pwd.isBlank()) "" else Prefs.sha256(pwd))
                                    val cleared = pwd.isBlank()
                                    pwd = ""
                                    Toast.makeText(
                                        context,
                                        if (cleared) "已清除密码" else "密码已保存",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.size(20.dp))
                            FydText("闪光灯节点", fontSize = 16.sp)
                            Spacer(Modifier.size(2.dp))
                            FydText(
                                "sysfs 节点路径（需 Root）",
                                fontSize = 13.sp,
                                color = fydSecondaryTextColor(),
                            )
                            Spacer(Modifier.size(10.dp))
                            FydTextField(
                                value = flashPath,
                                onValueChange = { flashPath = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = "/sys/class/leds/.../brightness",
                                singleLine = true,
                            )
                            Spacer(Modifier.size(10.dp))
                            FydButton(
                                "保存节点",
                                onClick = {
                                    Prefs.setFlashPath(context, flashPath.trim())
                                    Toast.makeText(context, "闪光灯节点已保存", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.size(20.dp))
                            FydText("报警铃声", fontSize = 16.sp)
                            Spacer(Modifier.size(2.dp))
                            FydText(
                                "当前：" + ringtoneName,
                                fontSize = 13.sp,
                                color = fydSecondaryTextColor(),
                            )
                            Spacer(Modifier.size(10.dp))
                            FydButton(
                                "选择铃声",
                                onClick = { ringtoneLauncher.launch(AUDIO_MIME) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.size(20.dp))

                FydSmallTitle("组件预览 · 当前风格")
                Spacer(Modifier.size(8.dp))
                FydCard(modifier = Modifier.fillMaxWidth()) {
                    FydItemRow(
                        title = "开关",
                        subtitle = "两种风格形态不同",
                        trailing = { FydSwitch(checked = switchOn, onCheckedChange = { switchOn = it }) },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FydButton("主按钮", onClick = { }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.size(20.dp))

                FydSmallTitle("关于")
                Spacer(Modifier.size(8.dp))
                FydCard(modifier = Modifier.fillMaxWidth()) {
                    FydItemRow(title = "版本", trailingText = "1.1.1")
                    FydItemRow(
                        title = "项目地址",
                        subtitle = "github.com/bcdidit67/FindYouDevice",
                        showArrow = true,
                        onClick = { openUrl(context, "https://github.com/bcdidit67/FindYouDevice") },
                    )
                    FydItemRow(
                        title = "联系作者 · QQ",
                        subtitle = "3891605032（点击复制）",
                        showArrow = true,
                        onClick = { copyText(context, "QQ 号", "3891605032") },
                    )
                    FydItemRow(
                        title = "联系作者 · 邮箱",
                        subtitle = "fxxkhw676767@outlook.com",
                        showArrow = true,
                        onClick = {
                            openUrl(
                                context,
                                "mailto:fxxkhw676767@outlook.com",
                                fallbackTip = "邮箱：fxxkhw676767@outlook.com",
                            )
                        },
                    )
                    FydItemRow(
                        title = "开源许可",
                        subtitle = "MIT License · 含 Miuix 组件库 (Apache-2.0)",
                    )
                }
                Spacer(Modifier.size(16.dp))
                FydText(
                    "切换立即生效：主界面、OOBE 与设置页将整体换肤。MIUI X 默认使用小米配色（#3382FF），可开启动态取色跟随壁纸。",
                    fontSize = 12.sp,
                    color = fydSecondaryTextColor(),
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.size(24.dp))
            }

            // ===== 选择器层 =====
            if (style == UiStyle.MIUIX) {
                MiuixAnchorPicker(
                    visible = pickerVisible,
                    anchor = styleRowBounds,
                    items = listOf("Miuix", "Material"),
                    selectedIndex = if (style == UiStyle.MIUIX) 0 else 1,
                    onPick = { idx ->
                        pickerVisible = false
                        onStyleChange(if (idx == 0) UiStyle.MIUIX else UiStyle.MD3)
                    },
                    onDismiss = { pickerVisible = false },
                )
            } else {
                if (pickerVisible) {
                    M3PickerDialog(
                        title = "界面风格",
                        items = listOf("Miuix", "Material Design"),
                        selectedIndex = if (style == UiStyle.MIUIX) 0 else 1,
                        onPick = { idx ->
                            pickerVisible = false
                            onStyleChange(if (idx == 0) UiStyle.MIUIX else UiStyle.MD3)
                        },
                        onDismiss = { pickerVisible = false },
                    )
                }
            }
            if (transitionSheetVisible) {
                FydTransitionSheet(
                    currentIndex = pageTransition,
                    onPick = { i ->
                        onPageTransitionChange(i)
                    },
                    onDismiss = { transitionSheetVisible = false },
                )
            }
        }
    }
}

/** 通用：MIUI X 锚定飘窗（全窗口压暗 + 弹性动画），用于风格 / 模式等选项 */
@Composable
private fun MiuixAnchorPicker(
    visible: Boolean,
    anchor: Rect?,
    items: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val density = LocalDensity.current
    val dialogBg = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
    var cardVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cardVisible = true }
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val windowW = constraints.maxWidth
            AnimatedVisibility(
                visible = cardVisible,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(160)),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
            }
            if (anchor != null) {
                val marginPx = with(density) { 20.dp.roundToPx() }
                val gapPx = with(density) { 6.dp.roundToPx() }
                val cardMinWidthPx = with(density) { 240.dp.roundToPx() }
                val xPx = (windowW - cardMinWidthPx - marginPx).coerceAtLeast(marginPx)
                val yPx = anchor.bottom.toInt() + gapPx
                AnimatedVisibility(
                    visible = cardVisible,
                    enter = fadeIn(tween(160)) + scaleIn(
                        initialScale = 0.86f,
                        transformOrigin = TransformOrigin(1f, 0f),
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
                    ) + slideInVertically(tween(200)) { -it / 10 },
                    exit = fadeOut(tween(130)) + scaleOut(
                        targetScale = 0.92f,
                        transformOrigin = TransformOrigin(1f, 0f),
                        animationSpec = tween(150),
                    ),
                    modifier = Modifier.offset { IntOffset(xPx, yPx) },
                ) {
                    Column(
                        Modifier
                            .width(240.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(dialogBg)
                            .padding(vertical = 6.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { /* 拦截点击 */ },
                    ) {
                        items.forEachIndexed { i, label ->
                            PickerRow(label, selected = i == selectedIndex) { onPick(i) }
                        }
                    }
                }
            }
        }
    }
}

/** 通用：MD3 居中对话框（单选列表） */
@Composable
private fun M3PickerDialog(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    M3AlertDialog(
        onDismissRequest = onDismiss,
        title = { M3Text(title) },
        text = {
            Column {
                items.forEachIndexed { i, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(i) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        M3RadioButton(selected = i == selectedIndex, onClick = { onPick(i) })
                        Spacer(Modifier.size(4.dp))
                        M3Text(label, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {
            M3TextButton(onClick = onDismiss) { M3Text("完成") }
        },
    )
}

/** 翻页效果底部弹层（7 款横向卡片 + 完成按钮，MIUI 桌面样式） */
@Composable
private fun FydTransitionSheet(
    currentIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetBg = when (LocalUiStyle.current) {
        UiStyle.MD3 -> MaterialTheme.colorScheme.surfaceContainerLow
        UiStyle.MIUIX -> top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = shown, enter = fadeIn(tween(200))) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
            }
            AnimatedVisibility(
                visible = shown,
                enter = slideInVertically(tween(280)) { it } + fadeIn(tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(sheetBg)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(fydSecondaryTextColor().copy(alpha = 0.4f)),
                    )
                    Spacer(Modifier.size(14.dp))
                    FydText("翻页效果", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(14.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(PageTransforms.NAMES.size) { i ->
                            val selected = i == currentIndex
                            Column(
                                Modifier
                                    .width(92.dp)
                                    .border(
                                        width = if (selected) 2.dp else 0.dp,
                                        color = if (selected) fydPrimaryColor() else Color.Transparent,
                                        shape = RoundedCornerShape(18.dp),
                                    )
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (selected) fydPrimaryColor().copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable { onPick(i) }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(70.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(fydSecondaryTextColor().copy(alpha = 0.15f)),
                                )
                                Spacer(Modifier.size(6.dp))
                                FydText(
                                    PageTransforms.NAMES[i],
                                    fontSize = 13.sp,
                                    color = if (selected) fydPrimaryColor() else Color.Unspecified,
                                    fontWeight = if (selected) FontWeight.SemiBold else null,
                                )
                                Spacer(Modifier.size(4.dp))
                            }
                        }
                    }
                    Spacer(Modifier.size(18.dp))
                    FydButton("完成", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.size(8.dp))
                }
            }
        }
    }
}

/** 飘窗内选项行（对勾标记） */
@Composable
private fun PickerRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FydText(
            text,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = if (selected) fydPrimaryColor() else Color.Unspecified,
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            FydText("✓", fontSize = 16.sp, color = fydPrimaryColor(), fontWeight = FontWeight.Bold)
        }
    }
}

/** 当前报警铃声显示名 */
private fun currentRingtoneName(context: android.content.Context): String {
    val uri = Prefs.getRingtoneUri(context) ?: return "内置报警音"
    return uri.lastPathSegment ?: "自定义铃声"
}

/** SAF 选择器支持的音频 MIME 白名单 */
private val AUDIO_MIME = arrayOf(
    "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/wav",
    "audio/x-wav", "audio/ogg", "audio/flac", "audio/amr", "audio/3gpp",
)

/** 用系统浏览器打开链接（一键跳转项目地址等） */
private fun openUrl(context: android.content.Context, url: String, fallbackTip: String? = null) {
    runCatching {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url),
        )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure {
        val msg = fallbackTip?.let { "无法打开链接，$it" } ?: "无法打开链接：$url"
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}

/** 复制文本到剪贴板并提示（QQ 号等） */
private fun copyText(context: android.content.Context, label: String, text: String) {
    runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        android.widget.Toast.makeText(context, "已复制 $label：$text", android.widget.Toast.LENGTH_SHORT).show()
    }.onFailure {
        android.widget.Toast.makeText(context, "$label：$text", android.widget.Toast.LENGTH_LONG).show()
    }
}
