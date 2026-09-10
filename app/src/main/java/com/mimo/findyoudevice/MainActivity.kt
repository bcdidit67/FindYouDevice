package com.mimo.findyoudevice

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mimo.findyoudevice.databinding.ActivityMainBinding
import java.net.Inet4Address

/**
 * 应用统一入口 / 双模容器 Activity。
 *
 * 职责：
 *  - 依据 SharedPreferences 的 mode(host/client) 决定首次进入模式选择页 / 加载对应 Fragment；
 *  - 恒定的顶部：设备图标 + 标题（点击弹版本号 Toast）、模式角标 Chip（点击切换模式）、
 *    本机局域网 IP Chip(ConnectivityManager 读取，仅需 ACCESS_NETWORK_STATE，无任何定位权限)；
 *  - 托管一个 activity 作用域共享 ViewModel（MainViewModel）供 Host/Client Fragment 复用；
 *    Web(NanoHTTPD)前台服务生命周期由 HostFragment 依据开关启停，此处仅占位 VM 状态。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val sharedViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)

        // ---- 首启 OOBE：引导未完成（新装且未跳过/看完）→ 先进引导页 ----
        if (!Prefs.isOobeDone(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // ---- 首次启动：无 mode 键 → 先选择模式 ----
        if (prefs().getString(KEY_MODE, null) == null) {
            startActivity(Intent(this, ModeSelectionActivity::class.java))
            finish()
            return
        }

        setContentView(binding.root)

        setupTopBar()
        renderModeChip()
        renderIpChip()
        loadModeFragment()
    }

    /** 顶部交互与只读信息刷新 */
    private fun setupTopBar() {
        // 标题行点击 → 简单版本号 Toast（不做复杂关于页）
        binding.headerRow.setOnClickListener { showVersionToast() }
        // 模式角标点击 → 切换模式弹窗（含重启确认）
        binding.chipMode.setOnClickListener { showModeSwitchDialog() }
    }

    /** 依据持久化模式刷新角标（主机/客户端） */
    private fun renderModeChip() {
        val host = currentMode() == MODE_HOST
        binding.chipMode.setText(
            if (host) R.string.mode_badge_host else R.string.mode_badge_client
        )
        binding.chipMode.setChipIconResource(
            if (host) R.drawable.ic_desktop else R.drawable.ic_phone
        )
    }

    /** 通过 ConnectivityManager 读活跃网段首个 IPv4 展示在 Chip（不需定位） */
    private fun renderIpChip() {
        val ip = resolveLocalIpv4()
        binding.chipIp.text = if (ip.isNullOrBlank()) "IP: 未知 WiFi/无网络" else "IP: $ip"
    }

    /** 免定位：遍历活动网络取其 LinkProperties 上的非回环 IPv4 */
    private fun resolveLocalIpv4(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        runCatching {
            cm.allNetworks.forEach { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@forEach
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isEth = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!isWifi && !isEth) return@forEach
                cm.getLinkProperties(net)?.linkAddresses?.forEach { la ->
                    val a = la.address
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress
                    }
                }
            }
        }
        return null
    }

    /** 依据当前 mode 在容器内替换对应的 Fragment */
    private fun loadModeFragment() {
        val mode = currentMode()
        val fragment = if (mode == MODE_HOST) HostFragment() else ClientFragment()
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /** 弹出模式切换弹窗：切到另一模式后保存并 recreate 以便重新注入主/客户逻辑 */
    private fun showModeSwitchDialog() {
        val labels = arrayOf(getString(R.string.mode_host), getString(R.string.mode_client))
        val current = if (currentMode() == MODE_HOST) 0 else 1
        var checked = current

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mode_pick_title)
            .setSingleChoiceItems(labels, current) { _: DialogInterface, which: Int ->
                checked = which
            }
            .setPositiveButton("切换到该模式") { dialog, _ ->
                dialog.dismiss()
                val newMode = if (checked == 0) MODE_HOST else MODE_CLIENT
                if (newMode != currentMode()) {
                    // 保存新模式并重建 Activity，以加载对应的 Fragment
                    prefs().edit().putString(KEY_MODE, newMode).apply()
                    recreate()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showVersionToast() {
        val v = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
        Toast.makeText(this, "Find You Device v$v", Toast.LENGTH_SHORT).show()
    }

    private fun currentMode(): String = prefs().getString(KEY_MODE, MODE_HOST) ?: MODE_HOST

    private fun prefs(): SharedPreferences =
        getSharedPreferences(SP_NAME, MODE_PRIVATE)

    companion object {
        /** 持久化：SharedPreferences 文件名 */
        const val SP_NAME = "fyd_prefs"
        /** mode 键：存字符串 host | client */
        const val KEY_MODE = "mode"
        const val MODE_HOST = "host"
        const val MODE_CLIENT = "client"
    }
}

/**
 * activity 作用域共享 ViewModel。
 * 供 HostFragment / ClientFragment 复用（通过 activityViewModels() 获取）。
 * 预留主机 WebSocket/NanoHTTPD 运行状态，具体启停逻辑由 HostFragment+HostService 承载。
 */
class MainViewModel(app: android.app.Application) : AndroidViewModel(app) {

    /** 主机 Web 服务是否处于运行状态（由 HostFragment 回写） */
    val webServerRunning = MutableLiveData(false)

    /** 当前 Web 服务监听端口 */
    val webServerPort = MutableLiveData(1145)

    /** 主机的 /info 下一次需下发的电量/型号缓存，供爆闪结束后恢复显示（占位） */
    val flashlightActive = MutableLiveData(false)

    fun setWebServerState(running: Boolean, port: Int = 1145) {
        webServerRunning.postValue(running)
        webServerPort.postValue(port)
    }
}