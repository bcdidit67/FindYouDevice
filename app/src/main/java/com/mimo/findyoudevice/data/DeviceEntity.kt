package com.mimo.findyoudevice.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 客户端"已添加的主机设备"实体。
 * 表名 devices；字段严格对应 UI 需求与客户端扫描/查找逻辑。
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "uid")
    val uid: Long = 0L,

    /** 用户自定义"易记名称"，UI 非空校验后写入 */
    @ColumnInfo(name = "alias")
    val alias: String,

    /** 主机局域网 IPv4 地址，客户端扫描/手动添加时设置 */
    @ColumnInfo(name = "ip")
    val ip: String,

    /** 主机型号（扫描 GET /info 自动获取，手动添加可空） */
    @ColumnInfo(name = "model")
    val model: String? = null,

    /** 上次被查找时间戳（epoch milliseconds），默认 0 表示从未 */
    @ColumnInfo(name = "lastFindTime")
    val lastFindTime: Long = 0L,

    /** 在线/离线标识（最近一次探测结果），UI 绿/灰圆点依据 */
    @ColumnInfo(name = "isOnline")
    val isOnline: Boolean = false,
)
