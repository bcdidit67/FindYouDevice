package com.mimo.findyoudevice

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * 网络场景自动控制：
 *  - 连接 WiFi（非计量）→ 自动开启服务（仅当用户此前希望开启，见 [Prefs.getUserWantWeb]）；
 *  - 连接移动流量 → 自动关闭服务；
 *  - 连接热点 / 计量网络 → 自动关闭服务。
 *
 * 说明：三个动作分别受设置页开关控制；自动开/关不会改写"用户意愿"，因此
 * 「借流量自动关 → 回到 WiFi 自动恢复」形成闭环；进程存活期间持续监听
 * （注册于 MainApplication，配合服务的 START_STICKY 重建）。
 */
object NetworkAutoControl {

    private var registered = false
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastDecisionMs = 0L

    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = schedule(app)
            override fun onLost(network: Network) = schedule(app)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = schedule(app)
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess {
            callback = cb
            registered = true
            schedule(app) // 注册后做一次初始评估
        }
    }

    fun unregister(context: Context) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
        registered = false
    }

    /** 延迟 400ms 后在主线程评估（等待网络栈就绪；500ms 防抖去重） */
    private fun schedule(app: Context) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ evaluate(app) }, 400)
    }

    /** 核心决策：按当前默认网络类型与设置开关，自动开/关服务 */
    fun evaluate(context: Context) {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val now = System.currentTimeMillis()
        if (now - lastDecisionMs < 500) return
        lastDecisionMs = now

        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isEth = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val serviceRunning = Prefs.sp(app).getBoolean(HostService.KEY_WEB_RUNNING, false)

        when {
            // ① 自动开：WiFi（非计量）+ 用户希望开启 + 服务未运行
            isWifi && !metered &&
                Prefs.getAutoStartWifi(app) && Prefs.getUserWantWeb(app) && !serviceRunning -> {
                runCatching { HostService.start(app) }
            }
            // ② 自动关：移动流量
            isCellular && Prefs.getAutoStopCellular(app) && serviceRunning -> {
                runCatching { HostService.stop(app) }
            }
            // ③ 自动关：热点 / 计量网络（WiFi 或以太但计量）
            (isWifi || isEth) && metered && Prefs.getAutoStopMetered(app) && serviceRunning -> {
                runCatching { HostService.stop(app) }
            }
        }
    }
}
