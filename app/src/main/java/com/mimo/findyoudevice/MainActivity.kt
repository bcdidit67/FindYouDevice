package com.mimo.findyoudevice

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        // 主题/风格切换的重建：直接清空恢复快照（等价于冷启动的干净状态），
        // 页面按当前偏好全新创建。这样可同时避免：
        //  - 强删 Fragment 导致 FragmentStateAdapter 恢复时崩溃（Fragment no longer exists）；
        //  - 旧主题实例 / 状态错配引发的界面异常。
        // 主界面状态均由 Room / Prefs 驱动，可安全重建。
        savedInstanceState?.clear()
        AppThemeHelper.applyBaseTheme(this)
        super.onCreate(savedInstanceState)
        AppThemeHelper.applyDynamicColors(this)
        binding = ActivityMainBinding.inflate(layoutInflater)

        // ---- 首启 OOBE：引导未完成（新装且未跳过/看完）→ 先进引导页 ----
        if (!Prefs.isOobeDone(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }


        setContentView(binding.root)

        renderIpChip()
        registerNetworkMonitor()
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

    override fun onDestroy() {
        unregisterNetworkMonitor()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        runCatching { applyPageTransition() }
        // 网络监听在 onPause 时注销，回前台时补注册并立即刷新一次
        if (::binding.isInitialized) {
            registerNetworkMonitor()
            renderIpChip()
        }
        val key = Prefs.getUiStyle(this) + ":" + Prefs.getDynamicColor(this)
        if (appliedThemeKey != null && appliedThemeKey != key) {
            appliedThemeKey = key
            recreate()
        } else {
            appliedThemeKey = key
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterNetworkMonitor()
    }

    /** 顶部交互与只读信息刷新 */

    /** 依据持久化模式刷新角标（主机/客户端） */

    /** 通过 ConnectivityManager 读活跃网段首个 IPv4 展示在 Chip（不需定位） */
    private fun renderIpChip() {
        val text = resolveNetworkChipText()
        binding.tvTopIp.text = text
    }

    /**
     * 生成顶部 Chip 文案：区分 WiFi / 流量 / 其他承载。
     * 修复点：原先只判断 WiFi/ETH，导致切到移动流量时永远拿到 null → 误显示“未知 WiFi/无网络”。
     */
    private fun resolveNetworkChipText(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "IP: 无网络"
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return "IP: 无网络"
        }
        val ip = active.let { resolveLocalIpv4(it) }
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                if (ip.isNullOrBlank()) "WiFi: 未连接" else "WiFi: $ip"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                if (ip.isNullOrBlank()) "流量: 无 IP" else "流量: $ip"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                if (ip.isNullOrBlank()) "有线: 无 IP" else "有线: $ip"
            else ->
                if (ip.isNullOrBlank()) "IP: 无网络" else "IP: $ip"
        }
    }

    /** 免定位：读取指定网络 LinkProperties 上的非回环 IPv4（IPv6-only 时返回 null） */
    private fun resolveLocalIpv4(network: Network?): String? {
        if (network == null) return null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        for (la in lp.linkAddresses) {
            val a = la.address
            if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
        }
        return null
    }

    // ------------------------------------------------------------------
    // 网络状态实时监听：WiFi/流量切换、断网、IP 变化时即时刷新顶部 Chip
    // ------------------------------------------------------------------
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 注册默认网络 + 全部网络的回调，任一变化都触发 Chip 重绘（合并抖动） */
    private fun registerNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleIpRefresh()
            override fun onLost(network: Network) = scheduleIpRefresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                scheduleIpRefresh()
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) =
                scheduleIpRefresh()
        }
        networkCallback = cb
        runCatching {
            // 关注 WiFi + 蜂窝承载：切换/断连都会回调
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(request, cb)
        }.onFailure {
            runCatching { cm.registerDefaultNetworkCallback(cb) }
        }
    }

    /** 合并 100ms 内的多次回调，避免频繁刷新 UI */
    private fun scheduleIpRefresh() {
        mainHandler.removeCallbacksAndMessages(NET_REFRESH_TOKEN)
        mainHandler.postAtTime({ if (!isFinishing && ::binding.isInitialized) renderIpChip() },
            NET_REFRESH_TOKEN, android.os.SystemClock.uptimeMillis() + 100)
    }

    private fun unregisterNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        mainHandler.removeCallbacksAndMessages(NET_REFRESH_TOKEN)
    }

    /** 当前底部 Tab：0=首页 1=设置 */
    private var currentTab = 0

    /** 两页适配器（首页 / 设置） */
    private lateinit var pagerAdapter: MainPagerAdapter

    /** 切换底部 Tab；仅当主题/风格发生变化（从设置页返回）时重建以整体换肤 */
    private fun switchTab(tab: Int) {
        if (tab == 0) {
            val key = Prefs.getUiStyle(this) + ":" + Prefs.getDynamicColor(this)
            if (appliedThemeKey != null && appliedThemeKey != key) {
                appliedThemeKey = key
                recreate()
                return
            }
        }
        // currentTab 与底栏高亮由 onPageSelected 单一数据源更新，避免动画中两条路径冲突
        if (binding.pager.currentItem != tab) {
            binding.pager.setCurrentItem(tab, true)
        } else {
            currentTab = tab
            renderNavHighlight()
        }
    }

    /** 应用翻页效果偏好（供设置页修改后即时刷新；先清空、下一帧再设置，规避重入异常） */
    fun applyPageTransition() {
        if (binding.pager.isFakeDragging) return
        val t = PageTransforms.get(Prefs.getPageTransition(this))
        binding.pager.setPageTransformer(null)
        binding.pager.post {
            runCatching {
                if (!binding.pager.isFakeDragging) {
                    binding.pager.setPageTransformer(t)
                }
            }
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
        /** Handler 去抖 token（网络回调合并） */
        private val NET_REFRESH_TOKEN = Any()
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