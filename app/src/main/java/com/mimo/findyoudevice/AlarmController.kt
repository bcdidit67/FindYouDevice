package com.mimo.findyoudevice

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 查找（报警）动作的统一控制器，提供“可随时停止”与“锁定无限时长”两种模式：
 *
 *  - 普通查找：15 秒后自动停止（默认；/find 亦可传 dur 覆盖时长）；
 *  - 锁定查找（触发方显式传 lock=true）：持续响铃/闪光，直到被停止——
 *    锁定与否由“触发方”（客户端设备面板 / 网页控制台）在触发时决定，主机无全局锁定开关。
 *
 * 实际执行仍由三个基础模块分工（彼此独立、各自兜底）：
 *  AlarmRinger（响铃+振动） / RootShell 一次性指令（亮屏解锁拉音量） /
 *  TorchBlinker（闪光灯闪烁：Camera2 优先，Root sysfs 兜底）。
 * durationMs<=0 传给 AlarmRinger / TorchBlinker 表示“无限，直到 stop()”，二者均已支持该语义。
 *
 * 线程模型：start 自行起一个 worker 线程（fyd-alarm）；stop 中断它并立即清理全部资源。
 * 用 epoch（代际）计数防止“旧线程 finally 误清新一代已经开始的动作”的竞态。
 */
object AlarmController {

    private val running = AtomicBoolean(false)
    private val epoch = AtomicLong(0)
    @Volatile private var worker: Thread? = null

    // ---- UI 可读状态（HostFragment 展示用）----
    @Volatile private var startedAtMs = 0L   // 本次查找开始时间
    @Volatile private var plannedMs = 0L     // 计划时长；0 = 锁定（无限）
    @Volatile private var lockMode = false   // 是否锁定模式

    /** 一次查找动作是否仍在进行（供 UI 刷新“停止查找”可用性） */
    val isRunning: Boolean get() = running.get()

    /** 锁定模式（无限时长，等待手动/远程停止） */
    val isLocked: Boolean get() = lockMode && running.get()

    /** 有限模式剩余毫秒；锁定/空闲返回 -1 */
    fun remainingMs(): Long {
        if (!running.get() || lockMode) return -1
        val left = plannedMs - (System.currentTimeMillis() - startedAtMs)
        return if (left > 0) left else 0
    }

    /** 显式启动一次查找。可安全重复调用（内部先 stop 旧的）。
     * @param durationMs >0 到时自动停止；<=0 视为锁定模式（与 [lock] 一致），持续到 [stop]。
     */
    fun start(app: Context, lock: Boolean, durationMs: Long) {
        stop()
        if (!running.compareAndSet(false, true)) return

        val myEpoch = epoch.incrementAndGet()
        val useLock = lock || durationMs <= 0
        val dur = if (useLock) 0L else durationMs
        val appCtx = app.applicationContext
        startedAtMs = System.currentTimeMillis()
        plannedMs = dur
        lockMode = useLock

        worker = Thread {
            try {
                // A. 响铃 + 振动（自选/系统默认/内置音逐级兜底；0=锁定不自动停）
                AlarmRinger.start(appCtx, dur, Prefs.getRingtoneUri(appCtx))

                // B. Root 一次性指令：仅亮屏保持（无 Root 静默跳过）；
                //    闹钟流音量由 AlarmRinger 播放前按需抬高（Java API，免 Root 更可靠）
                val cmds = listOf(
                    "input keyevent KEYCODE_WAKEUP",
                    "svc power stayon true",
                    "input keyevent 82",
                    "input keyevent 82",
                )
                cmds.forEach { RootShell.execOrIgnore(it) }

                // C. 闪光灯闪烁：Camera2 免 Root 优先，失败回退 Root sysfs；0=持续至 stop()
                val torchTaken = TorchBlinker.tryStart(appCtx, dur)
                if (!torchTaken) TorchBlinker.blinkSysfs(appCtx, dur)

                // D. 挂起：有限模式到时自动收尾；锁定模式等待 stop() 打断
                val endAt = if (dur > 0) System.currentTimeMillis() + dur else Long.MAX_VALUE
                while (running.get() && System.currentTimeMillis() < endAt) {
                    Thread.sleep(200)
                }
            } catch (_: InterruptedException) {
                // 主动停止
            } catch (_: Exception) {
                // 任一动作异常都不影响整体
            } finally {
                // 仅当仍是本代（期间未被新 start/stop 顶替）才收尾清理，
                // 避免旧线程的 finally 误清新一代已经开始的动作
                if (epoch.get() == myEpoch) {
                    running.set(false)
                    finishAll(appCtx)
                }
            }
        }.apply {
            name = "fyd-alarm"
            start()
        }
    }

    /** 立即停止查找（本机按钮 / 远程 /stop 均调用），可安全重复调用 */
    fun stop() {
        epoch.incrementAndGet() // 作废旧 worker 的 finally 清理权（其资源由本方法立即清理）
        running.set(false)
        worker?.interrupt()
        worker = null
        // 立即清理资源（与线程 finally 幂等）
        AlarmRinger.stop()
        TorchBlinker.stop()
        runCatching { RootShell.execOrIgnore("svc power stayon false") }
        startedAtMs = 0
        plannedMs = 0
        lockMode = false
    }

    private fun finishAll(app: Context) {
        runCatching { AlarmRinger.stop() }
        runCatching { TorchBlinker.stop() }
        runCatching { RootShell.execOrIgnore("svc power stayon false") }
        startedAtMs = 0
        plannedMs = 0
        lockMode = false
    }

    const val DEFAULT_FIND_MS = 15_000L
}
