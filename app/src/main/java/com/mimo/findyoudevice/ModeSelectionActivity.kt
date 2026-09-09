package com.mimo.findyoudevice

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mimo.findyoudevice.databinding.ActivityModeSelectionBinding

/**
 * 首启模式选择页。
 *
 * 首次安装启动（SharedPreferences 无 mode 键）时由 MainActivity 调起；
 * 选择后持久化 mode(host/client) 并跳转 MainActivity 后 finish。
 * 若已存在 mode（异常重入，如从历史栈返回），直接进入 MainActivity。
 *
 * root 判断：仅做提示；真正刷新由 HostFragment 校验 /system/bin/su。
 */
class ModeSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModeSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 非首启进入了本页 → 直接放行到主界面
        if (prefs().contains(MainActivity.KEY_MODE)) {
            goMainAndFinish()
            return
        }

        binding = ActivityModeSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHostMode.setOnClickListener {
            saveMode(MODE_HOST)
            goMainAndFinish()
        }

        binding.btnClientMode.setOnClickListener {
            saveMode(MODE_CLIENT)
            goMainAndFinish()
        }
    }

    /** 保存模式标识并起跳主界面 */
    private fun saveMode(mode: String) {
        prefs().edit().putString(MainActivity.KEY_MODE, mode).apply()
    }

    private fun prefs() =
        getSharedPreferences(MainActivity.SP_NAME, MODE_PRIVATE)

    private fun goMainAndFinish() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        /** 统一在 MainActivity 引用的模式常量 */
        const val MODE_HOST = MainActivity.MODE_HOST
        const val MODE_CLIENT = MainActivity.MODE_CLIENT
    }
}