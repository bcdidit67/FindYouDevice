package com.mimo.findyoudevice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField as M3OutlinedTextField
import androidx.compose.ui.text.input.VisualTransformation
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import androidx.compose.material3.RadioButton as M3RadioButton
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.RadioButton as MiuixRadioButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.SmallTitle as MiuixSmallTitle
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ---- 语义颜色（按当前风格取色） ----

@Composable
fun fydBackgroundColor(): Color = when (LocalUiStyle.current) {
    UiStyle.MD3 -> MaterialTheme.colorScheme.background
    UiStyle.MIUIX -> MiuixTheme.colorScheme.background
}

@Composable
fun fydPrimaryColor(): Color = when (LocalUiStyle.current) {
    UiStyle.MD3 -> MaterialTheme.colorScheme.primary
    UiStyle.MIUIX -> MiuixTheme.colorScheme.primary
}

@Composable
fun fydSecondaryTextColor(): Color = when (LocalUiStyle.current) {
    UiStyle.MD3 -> MaterialTheme.colorScheme.onSurfaceVariant
    UiStyle.MIUIX -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

// ---- 跨风格基础组件 ----

@Composable
fun FydText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    when (LocalUiStyle.current) {
        UiStyle.MD3 -> M3Text(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            lineHeight = lineHeight,
            maxLines = maxLines,
            overflow = overflow,
        )
        UiStyle.MIUIX -> MiuixText(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            lineHeight = lineHeight,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

@Composable
fun FydCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (LocalUiStyle.current) {
        UiStyle.MD3 -> {
            if (onClick != null) {
                M3Card(onClick = onClick, modifier = modifier, content = content)
            } else {
                M3Card(modifier = modifier, content = content)
            }
        }
        UiStyle.MIUIX -> MiuixCard(
            modifier = modifier,
            insideMargin = PaddingValues(0.dp),
            onClick = onClick,
            content = content,
        )
    }
}

@Composable
fun FydButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (LocalUiStyle.current) {
        UiStyle.MD3 -> M3Button(onClick = onClick, modifier = modifier, enabled = enabled) {
            M3Text(text)
        }
        UiStyle.MIUIX -> MiuixButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            MiuixText(text)
        }
    }
}

@Composable
fun FydRadioButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (LocalUiStyle.current) {
        UiStyle.MD3 -> M3RadioButton(selected = selected, onClick = onClick, modifier = modifier)
        UiStyle.MIUIX -> MiuixRadioButton(selected = selected, onClick = onClick, modifier = modifier)
    }
}

@Composable
fun FydSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (LocalUiStyle.current) {
        UiStyle.MD3 -> M3Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
        UiStyle.MIUIX -> MiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }
}

@Composable
fun FydSmallTitle(text: String, modifier: Modifier = Modifier) {
    when (LocalUiStyle.current) {
        UiStyle.MD3 -> M3Text(
            text = text,
            modifier = modifier,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        UiStyle.MIUIX -> MiuixSmallTitle(text = text, modifier = modifier)
    }
}

@Composable
fun FydItemRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingText: String? = null,
    showArrow: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifierModifier(modifier, onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            FydText(title, fontSize = 16.sp)
            if (subtitle != null) {
                Spacer(Modifier.size(2.dp))
                FydText(subtitle, fontSize = 13.sp, color = fydSecondaryTextColor())
            }
        }
        if (trailing != null) {
            trailing()
        } else if (trailingText != null) {
            FydText(trailingText, fontSize = 15.sp, color = fydSecondaryTextColor())
        }
        if (showArrow) {
            FydText("  ›", fontSize = 18.sp, color = fydSecondaryTextColor())
        }
    }
}

private fun modifierModifier(base: Modifier, onClick: (() -> Unit)?): Modifier {
    val m = base.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    return if (onClick != null) m.clickable(onClick = onClick) else m
}

@Composable
fun FydTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    when (LocalUiStyle.current) {
        UiStyle.MD3 -> M3OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = if (label.isEmpty()) null else ({ M3Text(label) }),
            singleLine = singleLine,
            visualTransformation = visualTransformation,
        )
        UiStyle.MIUIX -> MiuixTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = label,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
        )
    }
}
