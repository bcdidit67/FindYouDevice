package com.mimo.findyoudevice.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 客户端设备列表 DAO。
 * 使用 Flow 以便 RecyclerView 列表随 Room 变化自动刷新。
 */
@Dao
interface DeviceDao {

    /** 插入；若 ip 已存在则更新（保证扫描不产生重复行） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(device: DeviceEntity): Long

    /** 以 ip 为依据的 upsert：先删后插，避免唯一性约束冲突 */
    @Query("DELETE FROM devices WHERE ip = :ip")
    suspend fun deleteByIp(ip: String)

    @Query("SELECT COUNT(*) FROM devices WHERE ip = :ip")
    suspend fun countByIp(ip: String): Int

    /** 全量变更流：客户端列表的单一数据源 */
    @Query("SELECT * FROM devices ORDER BY alias ASC")
    fun getAllFlow(): Flow<List<DeviceEntity>>

    /** 便捷同步读取（如扫描自定义时预读已有 ip） */
    @Query("SELECT * FROM devices ORDER BY alias ASC")
    suspend fun getAllOnce(): List<DeviceEntity>

    /** 更新设备在线/离线状态 */
    @Update
    suspend fun update(device: DeviceEntity)

    /** 依据主键更新在线标记 */
    @Query("UPDATE devices SET isOnline = :online WHERE uid = :uid")
    suspend fun updateOnline(uid: Long, online: Boolean)

    /** 依据 ip 更新在线标记 */
    @Query("UPDATE devices SET isOnline = :online WHERE ip = :ip")
    suspend fun updateOnlineByIp(ip: String, online: Boolean)

    /** 记录一次"已被查找"时间戳并顺带联机 */
    @Query("UPDATE devices SET lastFindTime = :stamp, isOnline = 1 WHERE uid = :uid")
    suspend fun updateLastFind(uid: Long, stamp: Long)

    /** 依据 ip 查找（手动找设备 / 扫描后防重用） */
    @Query("SELECT * FROM devices WHERE ip = :ip LIMIT 1")
    suspend fun findByIp(ip: String): DeviceEntity?

    /** 依据主键查找 */
    @Query("SELECT * FROM devices WHERE uid = :uid LIMIT 1")
    suspend fun findById(uid: Long): DeviceEntity?

    /** 删除单台设备 */
    @Query("DELETE FROM devices WHERE uid = :uid")
    suspend fun delete(uid: Long)

    /** 清空全部 */
    @Query("DELETE FROM devices")
    suspend fun deleteAll()
}
