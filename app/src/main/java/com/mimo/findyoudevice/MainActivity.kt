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
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.widget.ViewPager2
import com.mimo.findyoudevice.ui.PageTransforms
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
        AppThemeHelper.applyBaseTheme(this)
        super.onCreate(savedInstanceState)
        AppThemeHelper.applyDynamicColors(this)
        // 重建（如主题/风格切换触发 recreate）时：清空被系统恢复的旧 Fragment 实例，
        // 让 ViewPager2 的适配器按当前偏好创建全新页面（避免旧主题实例残留/界面错乱）
        if (savedInstanceState != null) {
            supportFragmentManager.fragments.toList().forEach { f ->
                supportFragmentManager.beginTransaction()
                    .remove(f)
                    .commitNowAllowingStateLoss()
            }
        }
        binding = ActivityMainBinding.inflate(layoutInflater)

        // ---- 首启 OOBE：引导未完成（新装且未跳过/看完）→ 先进引导页 ----
        if (!Prefs.isOobeDone(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }


        setContentView(binding.root)

        renderIpChip()
        pagerAdapter = MainPagerAdapter(this)
        binding.pager.adapter = pagerAdapter
        binding.pager.isUserInputEnabled = false
        binding.pager.offscreenPageLimit = 1
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentTab = position
                renderNavHighlight()
            }
        })
        binding.pager.setPageTransformer(PageTransforms.get(Prefs.getPageTransition(this)))
        binding.navHome.setOnClickListener { switchTab(0) }
        binding.navSettings.setOnClickListener { switchTab(1) }
        switchTab(0)
    }

    /** 记录当前已应用的主题键（风格:动态取色），从设置页返回时检测变化并重建 */
    private var appliedThemeKey: String? = null

    override fun onResume() {
        super.onResume()
        runCatching { applyPageTransition() }
        val key = Prefs.getUiStyle(this) + ":" + Prefs.getDynamicColor(this)
        if (appliedThemeKey != null && appliedThemeKey != key) {
            appliedThemeKey = key
            recreate()
        } else {
            appliedThemeKey = key
        }
    }

    /** 顶部交互与只读信息刷新 */

    /** 依据持久化模式刷新角标（主机/客户端） */

    /** 通过 ConnectivityManager 读活跃网段首个 IPv4 展示在 Chip（不需定位） */
    private fun renderIpChip() {
        val ip = resolveLocalIpv4()
        binding.tvTopIp.text = if (ip.isNullOrBlank()) "IP: 未知 WiFi/无网络" else "IP: $ip"
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

    /** 当前底部 Tab：0=首页 1=设置 */
    private var currentTab = 0

    /** 两页适配器（首页 / 设置） */
    private lateinit var pagerAdapter: MainPagerAdapter

    /** 切换底部 Tab；从设置页返回首页前检测主题变化 → 重建以整体换肤 */
    private fun switchTab(tab: Int) {
        if (tab == 0) {
            val key = Prefs.getUiStyle(this) + ":" + Prefs.getDynamicColor(this)
            if (appliedThemeKey != null && appliedThemeKey != key) {
                appliedThemeKey = key
                recreate()
                return
            }
        }
        currentTab = tab
        renderNavHighlight()
        if (binding.pager.currentItem != tab) {
            binding.pager.setCurrentItem(tab, true)
        }
    }

    /** 应用翻页效果偏好（供设置页修改后即时刷新；先清空再于下一帧设置，规避重入异常） */
    fun applyPageTransition() {
        val t = PageTransforms.get(Prefs.getPageTransition(this))
        binding.pager.setPageTransformer(null)
        binding.pager.post {
            runCatching { binding.pager.setPageTransformer(t) }
        }
    }

    /** 加载设置 Tab（Compose 设置页） */

    /** 底栏配色：选中主色 / 未选中中性色 */
    private fun renderNavHighlight() {
        val active = themeColor(com.google.android.material.R.attr.colorPrimary)
        val inactive = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        binding.ivNavHome.setColorFilter(if (currentTab == 0) active else inactive)
        binding.tvNavHome.setTextColor(if (currentTab == 0) active else inactive)
        binding.ivNavSettings.setColorFilter(if (currentTab == 1) active else inactive)
        binding.tvNavSettings.setTextColor(if (currentTab == 1) active else inactive)
    }

    /** 解析主题属性色 */
    private fun themeColor(attrRes: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attrRes, tv, true)
        return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
    }

    /** 依据当前 mode 在容器内替换对应的 Fragment */

    /** 弹出模式切换弹窗：切到另一模式后保存并 recreate 以便重新注入主/客户逻辑 */


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