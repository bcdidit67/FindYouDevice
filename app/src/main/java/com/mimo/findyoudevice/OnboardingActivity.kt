package com.mimo.findyoudevice

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mimo.findyoudevice.ui.OnboardingApp
import com.mimo.findyoudevice.ui.UiStyle

/**
 * OOBE 首启引导（Compose + Miuix 重写版）。
 *
 * 首次安装且引导未完成时由 MainActivity 调起；信息页 x3 + 风格选择页。
 * 「跳过」或「开始使用」均写入完成标记与风格偏好（默认 MD3），随后：
 *  - 老用户（已有 mode 键，如升级安装）→ 直接进 MainActivity；
 *  - 新用户 → 进入 ModeSelectionActivity 选择主机/客户端模式。
 * 本页不申请任何运行时权限，仅做产品功能讲解。
 */
class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        setContent {
            OnboardingApp(onDone = { style -> finishOobe(style) })
        }
    }

    /** 保存风格选择 + 标记完成，并按是否已有 mode 分流 */
    private fun finishOobe(style: UiStyle) {
        Prefs.setUiStyle(this, style.key)
        // 动态取色随风格重置默认：MD3=开，MIUI X=关
        Prefs.setDynamicColor(this, style == UiStyle.MD3)
        Prefs.markOobeDone(this)
        val target = Intent(this, MainActivity::class.java)
        startActivity(target)
        finish()
    }
}
