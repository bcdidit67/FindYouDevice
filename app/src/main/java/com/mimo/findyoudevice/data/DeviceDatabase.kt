package com.mimo.findyoudevice.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room 数据库：仅一张 devices 表，version=1。
 * 单例经 invoke 式容器缓存，并在首次创建时传入 applicationContext，
 * 交由 MainApplication.onCreate 预初始化。
 */
@Database(
    entities = [DeviceEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DeviceDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao

    companion object {
        private const val DB_NAME = "find_you_devices.db"

        /** 进程级（懒）单例 */
        @Volatile
        private var INSTANCE: DeviceDatabase? = null

        fun getInstance(context: Context): DeviceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DeviceDatabase::class.java,
                    DB_NAME
                )
                    // fallbackToDestructiveMigration：version 升到 1→2 时为保护开发期不崩而关闭
                    // 该设置仅为开发期使用；正式发布如无 schema 演进应删除此行
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
