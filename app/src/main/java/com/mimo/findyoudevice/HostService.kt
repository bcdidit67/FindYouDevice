package com.mimo.findyoudevice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 主机端前台服务：负责按需创建/绑定并常驻 NanoHTTPD‑等效的 [WebServer]，同时以 ongoing
 * 通知保活。收到停止广播则停服并释放 Server。
 *
 * - START_STICKY：进程被杀后系统重建 Service，onStartCommand 里根据意愿恢复 Web 服务
 *   （此处保守策略：重建后若曾开启则重新拉起）。
 * - Manifest 已声明 foregroundServiceType="specialUse"，并使用对应属性与权限。
 * - 绝不申请定位/CAMERA 权限。
 */
class HostService : Service() {

    private var webServer: WebServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> { // ACTION_START 或系统重建(START_STICKY/null)
                startServer()
                return START_STICKY
            }
        }
    }

    /** 起服务本体并升级为前台 */
    private fun startServer() {
        log("startServer enter")
        startAsForeground()
        val existing = webServer
        if (existing?.isRunning == true) {
            log("already running")
            return
        }
        if (existing != null) {
            stopSafe(existing)
        }
        val srv = WebServer(this, DEFAULT_PORT)
        try {
            srv.start()
            webServer = srv
            Prefs.sp(this).edit().putBoolean(KEY_WEB_RUNNING, true).apply()
            log("server started OK on port $DEFAULT_PORT")
            updateNotificationForeground()
        } catch (e: Exception) {
            log("start fail: ${e.javaClass.simpleName} ${e.message}")
            stopSafe(srv)
            stopSelf()
        }
    }

    /** 轻量诊断日志（应用私有目录 host_service.log） */
    private fun log(msg: String) {
        runCatching {
            java.io.File(filesDir, "host_service.log").appendText(
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + " " + msg + "\n"
            )
        }
    }

    private fun stopServer() {
        log("stopServer")
        stopSafe(webServer)
        Prefs.sp(this).edit().putBoolean(KEY_WEB_RUNNING, false).apply()
    }

    private fun stopSafe(srv: WebServer?) {
        srv?.stop()
        if (webServer === srv) webServer = null
    }

    // ---------------- 前台通知 ----------------

    private fun startAsForeground() {
        val notif = buildNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notif,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else 0,
            )
            log("foreground ok (specialUse)")
        } catch (e: Exception) {
            log("fg specialUse fail: ${e.javaClass.simpleName}")
            runCatching { startForeground(NOTIF_ID, notif) }
                .onFailure { log("fg fallback fail: ${it.javaClass.simpleName}") }
        }
    }

    private fun updateNotificationForeground() {
        // 尝试更新通知文本（端口信息）；失败无碍
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val base = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this, 0, base, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = Intent(this, HostService::class.java)
        val stopPi = PendingIntent.getService(
            this, 1, stop.setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell) // 通知小图标（白色系矢量）
            .setContentTitle("Find You Device · 主机服务运行中")
            .setContentText("已监听局域网端口 $DEFAULT_PORT，等待客户端查找")
            .setContentIntent(contentIntent)
            .addAction(0, "停止服务", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "主机 Web 服务", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "局域网可被发现的服务常驻通道"
                setShowBadge(false)
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSafe(webServer)
        webServer = null
    }

    companion object {
        const val DEFAULT_PORT = 1145

        /** SP 记录 Web 服务开关运行状态：为 START_STICKY 重建后恢复所用 */
        internal const val KEY_WEB_RUNNING = "host_web_running"

        private const val NOTIF_ID = 1041
        private const val CHANNEL_ID = "host_web"
        private const val ACTION_START = "com.mimo.findyoudevice.action.HOST_START"
        private const val ACTION_STOP = "com.mimo.findyoudevice.action.HOST_STOP"

        /** 供 HostFragment 调用来开启 WEB（Kotlin const 归置在类内随 START 一起启动） */
        fun start(context: Context) {
            val i = Intent(context, HostService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        /** 请求停止 */
        fun stop(context: Context) {
            val i = Intent(context, HostService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}