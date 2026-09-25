package com.mimo.findyoudevice

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.security.MessageDigest

/**
 * SharedPreferences 轻量封装。
 * 覆盖四个必要键：
 *  1) mode          当前运行模式："host" / "client"
 *  2) passwordHash  主机 Web 鉴权所需的 SHA-256 密码哈希(十六进制小写)
 *  3) flashPath     主机爆闪的闪光灯设备节点路径(过 Root 写入 echo 1/0)
 *  4) lastFindStamp 主机最近一次被"查找"的时间戳(epoch ms)
 *
 * 与 MainActivity 共用同一 SP 文件与 mode 键，保证首启/切换逻辑读取一致。
 */
object Prefs {

    /** 与 MainActivity.SP_NAME("fyd_prefs") 保持同一文件 */
    const val SP_NAME = "fyd_prefs"

    // ---- 键名常量（供全工程引用，含 DAO/Service 等) ----
    const val KEY_MODE = "mode"              // 值 "host"/"client"
    const val KEY_PASSWORD_HASH = "passwordHash"   // 默认空串 → 视为未设密码
    const val KEY_FLASH_PATH = "flashPath"         // 默认常见节点，可按 Root 设备调整
    const val KEY_LAST_FIND = "lastFindStamp"      // 主机最近一次被查找时间
    const val KEY_RINGTONE_URI = "ringtoneUri"      // 主机报警铃声 Uri（null/空 = 内置报警音）
    const val KEY_OOBE_DONE = "oobeDone"            // OOBE 首启引导已完成

    private const val DEFAULT_FLASH =
        "/sys/class/leds/torch-light/brightness"

    fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    // --------------------------------------------------------------------
    // 模式
    // --------------------------------------------------------------------
    fun getMode(context: Context): String? =
        sp(context).getString(KEY_MODE, null)

    fun setMode(context: Context, mode: String) {
        sp(context).edit().putString(KEY_MODE, mode).apply()
    }

    fun isHost(context: Context): Boolean = getMode(context) == MainActivity.MODE_HOST

    // --------------------------------------------------------------------
    // 密码（SHA-256 十六进制小写），空视为未启用鉴权
    // --------------------------------------------------------------------
    fun getPasswordHash(context: Context): String =
        sp(context).getString(KEY_PASSWORD_HASH, "") ?: ""

    /** 保存哈希，应传入由 [sha256] 计算后的结果 */
    fun setPasswordHash(context: Context, hash: String) {
        sp(context).edit().putString(KEY_PASSWORD_HASH, hash).apply()
    }

    /**
     * Web 触发页 Basic Auth 校验辅助：host 传入已摘出的明文密码，
     * 校验其 SHA-256 是否与存储一致。存储为空时不要求密码。
     */
    fun verifyPassword(context: Context, clearText: String): Boolean {
        val stored = getPasswordHash(context)
        return stored.isBlank() || stored.equals(sha256(clearText ?: ""), ignoreCase = true)
    }

    /** SHA-256 hex（供设置页保存、以及 LanScanner → /find 生成 Basic 头部复用） */
    fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // --------------------------------------------------------------------
    // 闪光灯节点路径
    // --------------------------------------------------------------------
    fun getFlashPath(context: Context): String =
        sp(context).getString(KEY_FLASH_PATH, DEFAULT_FLASH) ?: DEFAULT_FLASH

    fun setFlashPath(context: Context, path: String) {
        sp(context).edit().putString(KEY_FLASH_PATH, path).apply()
    }

    // --------------------------------------------------------------------
    // 主机最近一次被查找时间
    // --------------------------------------------------------------------
    fun getLastFind(context: Context): Long =
        sp(context).getLong(KEY_LAST_FIND, 0L)

    fun setLastFind(context: Context, stamp: Long) {
        sp(context).edit().putLong(KEY_LAST_FIND, stamp).apply()
    }

    // --------------------------------------------------------------------
    // 主机报警铃声（null = 未自定义，播放系统默认闹钟/内置音）
    // --------------------------------------------------------------------
    fun getRingtoneUri(context: Context): Uri? =
        sp(context).getString(KEY_RINGTONE_URI, null)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

    fun setRingtoneUri(context: Context, uri: Uri?) {
        val editor = sp(context).edit()
        if (uri == null || uri.toString().isBlank()) editor.remove(KEY_RINGTONE_URI)
        else editor.putString(KEY_RINGTONE_URI, uri.toString())
        editor.apply()
    }

    // --------------------------------------------------------------------
    // 客户端：按 IP 记忆的设备解锁密码（明文存于本机 SP；为空 = 未记忆/免密）
    // --------------------------------------------------------------------
    private fun passKey(ip: String) = "devPass_" + ip.trim()

    fun getDevicePassword(context: Context, ip: String): String =
        sp(context).getString(passKey(ip), "") ?: ""

    fun setDevicePassword(context: Context, ip: String, password: String) {
        val editor = sp(context).edit()
        if (password.isBlank()) editor.remove(passKey(ip))
        else editor.putString(passKey(ip), password)
        editor.apply()
    }

    // --------------------------------------------------------------------
    // 客户端：按 IP 记忆的“锁定查找”偏好（触发时默认勾选状态；可由客户端随时修改）
    // --------------------------------------------------------------------
    private fun lockKey(ip: String) = "devLock_" + ip.trim()

    fun getDeviceLock(context: Context, ip: String): Boolean =
        sp(context).getBoolean(lockKey(ip), false)

    fun setDeviceLock(context: Context, ip: String, lock: Boolean) {
        sp(context).edit().putBoolean(lockKey(ip), lock).apply()
    }

    // --------------------------------------------------------------------
    // UI 风格：md3（默认，Material Design 3）/ miuix（类小米风格）
    // --------------------------------------------------------------------
    const val KEY_UI_STYLE = "uiStyle"

    fun getUiStyle(context: Context): String =
        sp(context).getString(KEY_UI_STYLE, "md3") ?: "md3"

    fun setUiStyle(context: Context, styleKey: String) {
        sp(context).edit().putString(KEY_UI_STYLE, styleKey).apply()
    }

    // --------------------------------------------------------------------
    // 页面切换翻页效果（0-6，对应经典/淡入淡出/转盘/翻页/层叠/旋转/方块）
    // --------------------------------------------------------------------
    const val KEY_PAGE_TRANSITION = "pageTransition"

    fun getPageTransition(context: Context): Int =
        sp(context).getInt(KEY_PAGE_TRANSITION, 0)

    fun setPageTransition(context: Context, index: Int) {
        sp(context).edit().putInt(KEY_PAGE_TRANSITION, index).apply()
    }

    // --------------------------------------------------------------------
    // 自动控制：网络场景（WiFi 自动开 / 流量、热点自动关）+ 用户意愿
    // --------------------------------------------------------------------
    const val KEY_AUTO_START_WIFI = "autoStartWifi"
    const val KEY_AUTO_STOP_CELLULAR = "autoStopCellular"
    const val KEY_AUTO_STOP_METERED = "autoStopMetered"
    const val KEY_USER_WANT_WEB = "userWantWeb"

    fun getAutoStartWifi(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_START_WIFI, true)

    fun setAutoStartWifi(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_START_WIFI, enabled).apply()
    }

    fun getAutoStopCellular(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_STOP_CELLULAR, true)

    fun setAutoStopCellular(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_STOP_CELLULAR, enabled).apply()
    }

    fun getAutoStopMetered(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_STOP_METERED, true)

    fun setAutoStopMetered(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_STOP_METERED, enabled).apply()
    }

    fun getUserWantWeb(context: Context): Boolean =
        sp(context).getBoolean(KEY_USER_WANT_WEB, false)

    fun setUserWantWeb(context: Context, want: Boolean) {
        sp(context).edit().putBoolean(KEY_USER_WANT_WEB, want).apply()
    }

    // --------------------------------------------------------------------
    // 动态取色（Monet）：MD3 默认开，MIUI X 默认关（切换风格时重置为该默认）
    // --------------------------------------------------------------------
    const val KEY_DYNAMIC_COLOR = "dynamicColor"

    fun getDynamicColor(context: Context): Boolean =
        sp(context).getBoolean(KEY_DYNAMIC_COLOR, true)

    fun setDynamicColor(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    // --------------------------------------------------------------------
    // OOBE：首次启动引导完成标记
    // --------------------------------------------------------------------
    fun isOobeDone(context: Context): Boolean {
        val sp = sp(context)
        // 兼容升级安装：老用户已选过模式则视为完成 OOBE，不再打断
        return sp.getBoolean(KEY_OOBE_DONE, false) || sp.contains(MainActivity.KEY_MODE)
    }

    fun markOobeDone(context: Context) {
        sp(context).edit().putBoolean(KEY_OOBE_DONE, true).apply()
    }
}