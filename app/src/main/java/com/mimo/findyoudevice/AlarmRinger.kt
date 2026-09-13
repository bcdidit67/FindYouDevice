package com.mimo.findyoudevice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 查找报警响铃器（免 Root 也能发声）。
 *
 * 音源顺序（保证任何机型/权限组合下都能响）：
 *  1. 用户在主机页自选的报警铃声（Prefs 持久化的 Uri；读媒体库需 READ_MEDIA_AUDIO，
 *     未授权读取失败时自动落入下一级）；
 *  2. 应用内置 assets/alarm_alert.m4a —— 默认报警音（不依赖系统默认闹钟，
 *     读取无需任何权限，不同 ROM 上均可稳定播放）；
 *  3. 系统默认闹钟铃声（仅作兜底，部分 ROM 该 Uri 无法用 MediaPlayer 打开时自动跳过）；
 *  4. ToneGenerator 内置音（系统 API 直接合成，无音频文件依赖）。
 * 播放失败/权限不足绝不崩溃，逐级静默降级。
 *
 * 叠加 Vibrator 振动（600ms/400ms 循环）；[durationMs] 结束或 [stop] 后释放全部资源。
 * 用法：任意线程调 [start]，重复调用先停旧的。
 */
object AlarmRinger {

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var toneGen: ToneGenerator? = null
    @Volatile private var vibrator: Vibrator? = null
    /** 最近一次 start 的 app context（stop 时恢复音量用） */
    @Volatile private var appRef: Context? = null
    /** 报警前被临时抬高的闹钟流原音量；-1 = 未被抬高 */
    @Volatile private var boostedAlarmVol = -1
    private val active = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 开始报警：[customUri] 为 null 时从系统默认/内置音兜底。durationMs<=0 = 无限响铃直到 [stop]。 */
    fun start(context: Context, durationMs: Long = 30_000L, customUri: Uri? = null) {
        stop()
        val app = context.applicationContext
        appRef = app
        // 兜底：闹钟流音量过低会表现为"铃声不响/无声"（MediaPlayer 与 Tone 都走该流），
        // 播放前若低于 75% 则临时抬高，停止时恢复原值
        ensureAlarmVolume(app)
        if (!active.compareAndSet(false, true)) return

        val attrs = alarmAttributes()

        // ---- 候选音源依次尝试（Uri 类播放失败自动降级）----
        // 默认（未自选）直接使用内置报警音：不依赖 ROM 的系统默认闹钟，可稳定播放
        var played = customUri?.let { tryPlayUri(app, attrs, it) } == true
        if (!played) played = tryPlayAsset(app, attrs)
        if (!played) {
            played = runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) }
                .getOrNull()?.let { tryPlayUri(app, attrs, it) } == true
        }
        if (!played) startToneFallback(app)

        // ---- 振动叠加 ----
        vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibratePattern(true)

        // durationMs<=0：锁定模式，持续响铃直到外部 stop()
        if (durationMs > 0) {
            scope.launch {
                delay(durationMs)
                stop()
            }
        }
    }

    /** 停止播放与振动，释放全部资源（可安全重复调用） */
    fun stop() {
        active.set(false)
        restoreAlarmVolume()
        appRef = null
        vibratePattern(false)
        vibrator = null
        runCatching { toneGen?.stopTone() }
        runCatching { toneGen?.release() }
        toneGen = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    // ------------------------------------------------------------------
    // 播放器实现
    // ------------------------------------------------------------------

    private fun alarmAttributes(): AudioAttributes? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        } else null

    /** content:// 铃声播放；成功返回 true。读取失败（如缺媒体权限）抛异常 → false 降级 */
    private fun tryPlayUri(app: Context, attrs: AudioAttributes?, uri: Uri): Boolean {
        if (uri.toString().isBlank()) return false
        val mp = MediaPlayer()
        try {
            configure(mp, attrs)
            val failed = AtomicBoolean(false)
            mp.setOnErrorListener { _, _, _ ->
                failed.set(true)
                true
            }
            mp.setDataSource(app, uri)
            mp.isLooping = true
            mp.prepare()
            if (failed.get()) return false
            mp.start()
            player = mp
            return true
        } catch (_: Exception) {
            runCatching { mp.release() }
            return false
        }
    }

    /** assets 内置报警音（免权限读取；noCompress 保证 openFd 可用） */
    private fun tryPlayAsset(app: Context, attrs: AudioAttributes?): Boolean {
        return runCatching {
            val fd = app.assets.openFd("alarm_alert.m4a")
            val mp = MediaPlayer()
            try {
                configure(mp, attrs)
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                true
            } catch (e: Exception) {
                runCatching { fd.close() }
                runCatching { mp.release() }
                false
            }
        }.getOrDefault(false)
    }

    private fun configure(mp: MediaPlayer, attrs: AudioAttributes?) {
        if (attrs != null) mp.setAudioAttributes(attrs)
        else {
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(AudioManager.STREAM_ALARM)
        }
    }

    /** 最终兜底：ToneGenerator 循环鸣叫（无文件依赖） */
    private fun startToneFallback(app: Context) {
        val gen = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull() ?: return
        toneGen = gen
        scope.launch {
            while (isActive && active.get()) {
                runCatching {
                    gen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
                }
                delay(1700)
            }
        }
    }

    // ------------------------------------------------------------------
    // 闹钟流音量兜底
    // ------------------------------------------------------------------

    /**
     * 确保闹钟流音量足够听到：低于最大值 75% 时临时抬高到 75%，
     * 记录原值供 [restoreAlarmVolume] 在停止时还原（避免用户闹钟被永久改大声）。
     */
    private fun ensureAlarmVolume(app: Context) {
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (max <= 0) return
            val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = (max * 0.75f).toInt().coerceAtLeast(1)
            if (cur < target) {
                boostedAlarmVol = cur
                am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            }
        }
    }

    /** 停止时还原被临时抬高的闹钟流音量 */
    private fun restoreAlarmVolume() {
        val orig = boostedAlarmVol
        if (orig < 0) return
        boostedAlarmVol = -1
        val app = appRef ?: return
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.setStreamVolume(AudioManager.STREAM_ALARM, orig, 0)
        }
    }

    // ------------------------------------------------------------------
    // 振动
    // ------------------------------------------------------------------

    private fun vibratePattern(on: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (on) {
                val pattern = longArrayOf(0, 600, 400, 600, 400)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, 0)
                }
            } else {
                v.cancel()
            }
        }
    }
}