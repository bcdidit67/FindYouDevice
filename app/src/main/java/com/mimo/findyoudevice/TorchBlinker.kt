package com.mimo.findyoudevice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 报警闪光灯两层策略（按可用性自动降级，全程不抛异常）：
 *
 *  1. Camera2 setTorchMode 交替开关实现“闪烁”——免 Root、免 sysfs 节点，
 *     仅需 CAMERA 运行时权限（在主机页“报警效果”卡片中引导申请）；
 *  2. Camera2 不可用（无权限/无闪光灯硬件）→ 回退 Root 写 sysfs brightness 0/1 交替，
 *     节点路径取 Prefs.getFlashPath 并尝试常见候选；Root 缺失时静默跳过。
 *
 * 使用方（AlarmController）先调 [tryStart]；返回 false 再调 [blinkSysfs] 兜底，
 * 二者不会同时操作闪光灯，避免硬件冲突。
 */
object TorchBlinker {

    @Volatile private var cameraManager: CameraManager? = null
    @Volatile private var cameraId: String? = null
    @Volatile private var blinkThread: Thread? = null
    private val active = AtomicBoolean(false)

    // ------------------------------------------------------------------
    // 策略一：Camera2 免 Root 闪烁
    // ------------------------------------------------------------------

    /** 尝试用 Camera2 手电模式闪烁 [durationMs]；durationMs<=0=持续到 [stop]。true=已接管闪烁（调用方不要再走 Root 路径）。 */
    fun tryStart(context: Context, durationMs: Long): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
            val id = firstFlashCamera(cm) ?: return false
            // 预检：试亮一次立即关闭，验证硬件可用（不可用则整体回退）
            cm.setTorchMode(id, true)
            cm.setTorchMode(id, false)

            stop() // 清理上一次残留
            if (!active.compareAndSet(false, true)) return false
            cameraManager = cm
            cameraId = id
            blinkThread = Thread { blinkLoop(cm, id, durationMs) }.apply { start() }
            true
        }.getOrDefault(false)
    }

    /** 立即停止并强制熄灭（收尾兜底） */
    fun stop() {
        active.set(false)
        blinkThread?.interrupt()
        blinkThread = null
        val cm = cameraManager
        val id = cameraId
        cameraManager = null
        cameraId = null
        if (cm != null && id != null) {
            runCatching { cm.setTorchMode(id, false) }
        }
    }

    private fun blinkLoop(cm: CameraManager, id: String, durationMs: Long) {
        val endAt = if (durationMs > 0) System.currentTimeMillis() + durationMs else Long.MAX_VALUE
        var on = true
        try {
            while (active.get() && System.currentTimeMillis() < endAt) {
                cm.setTorchMode(id, on)
                on = !on
                Thread.sleep(220)
            }
        } catch (_: InterruptedException) {
            // 主动停止
        } catch (_: Exception) {
            // setTorchMode 偶发硬件忙碌：短暂重试不中断整体闪烁
            runCatching { Thread.sleep(120) }
        } finally {
            active.set(false)
            runCatching { cm.setTorchMode(id, false) }
        }
    }

    /** 找一个带闪光灯单元的摄像头（优先后置）；找不到返回 null */
    private fun firstFlashCamera(cm: CameraManager): String? = runCatching {
        val ids = cm.cameraIdList
        var fallback: String? = null
        for (id in ids) {
            val ch = cm.getCameraCharacteristics(id)
            val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            if (!flash) continue
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
            if (fallback == null) fallback = id
        }
        fallback
    }.getOrNull()

    // ------------------------------------------------------------------
    // 策略二：Root sysfs 0/1 交替（写节点需要 root）
    // ------------------------------------------------------------------

    /**
     * [durationMs] 秒 sysfs 爆闪。节点取 Prefs.getFlashPath，无效则逐个尝试常见候选；
     * 找不到可写节点或 Root 缺失时整体静默返回；结束后兜底写 0 关灯。
     */
    fun blinkSysfs(context: Context, durationMs: Long) {
        val node = writableNode(context) ?: return
        val endAt = if (durationMs > 0) System.currentTimeMillis() + durationMs else Long.MAX_VALUE
        val onVal = maxBrightness(node)
        var on = true
        try {
            while (System.currentTimeMillis() < endAt) {
                RootShell.execOrIgnore("echo ${if (on) onVal else "0"} > $node")
                on = !on
                Thread.sleep(240)
            }
        } catch (_: InterruptedException) {
            // 主动停止
        } catch (_: Exception) {
            // 忽略单次写失败
        } finally {
            runCatching { RootShell.execOrIgnore("echo 0 > $node") }
        }
    }

    private fun writableNode(context: Context): String? {
        val candidates = listOfNotNull(
            Prefs.getFlashPath(context),
            "/sys/class/leds/torch-light/brightness",
            "/sys/class/leds/flashlight/brightness",
            "/sys/class/leds/led:torch_1/brightness",
            "/sys/class/camera_flash/rear_torch/brightness",
        ).distinct()
        return candidates.firstOrNull { p ->
            RootShell.execOrIgnore("test -w $p && echo 1 || echo 0")?.contains("1") == true
        }
    }

    /** 读节点同目录 max_brightness；拿不到则回退 255 */
    private fun maxBrightness(node: String): String {
        val dir = File(node).parent.orEmpty()
        val raw = if (dir.isNotBlank()) RootShell.execOrIgnore("cat $dir/max_brightness") else null
        val v = raw?.trim()
        return if (!v.isNullOrBlank() && v.all { it.isDigit() }) v else "255"
    }
}
