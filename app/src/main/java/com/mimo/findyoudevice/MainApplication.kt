package com.mimo.findyoudevice

import android.app.Application
import com.mimo.findyoudevice.data.DeviceDatabase
import com.mimo.findyoudevice.data.DeviceDao

/**
 * 应用入口 Application。
 * 职责：在进程启动即准备全局可达的 Room 单例；供 DAO 在 Fragment/Service 中经
 * [deviceDao] 直接获取。所有子线程安全（懒初始化 + Double-Check）。
 */
class MainApplication : Application() {

    companion object {
        /** 进程级单例句柄 */
        @Volatile
        lateinit var instance: MainApplication
            private set

        /** 便捷：直接拿 Room 单例（经 application Context） */
        fun getDatabase(): DeviceDatabase = instance.database

        /** 便捷：直接拿 DAO */
        fun getDeviceDao(): DeviceDao = getDatabase().deviceDao()
    }

    val database: DeviceDatabase by lazy {
        // 惰性构建；首次真正访问才建库（避免冷启动拖慢首启模式页）
        DeviceDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 网络场景自动控制（WiFi 自动开 / 流量、热点自动关）；幂等注册
        NetworkAutoControl.register(this)
        
        // 主题与动态取色在 Activity 层由 AppThemeHelper 按偏好应用（随界面风格切换）

        
        // 提前触发构建，把首次建库开销挪到进程启动，便于后续 IO 同步读
        database
    }
}
