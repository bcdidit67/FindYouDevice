package com.mimo.findyoudevice

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.mimo.findyoudevice.databinding.FragmentHostBinding
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主机模式（需 Root 可用，但报警响铃/闪烁免 Root 也能工作）。
 *
 * 职责：
 *  - 顶部信息卡：型号/设备名/电量（优先 Root dumpsys battery，回退 BatteryManager）；
 *  - Web 开关：开启即 start HostService（前台服务承载 WebServer :1145）；
 *    开启后异步自检真实监听，失败自动回滚开关并提示（杜绝“开关开了却没监听”）；
 *  - 5 秒轮询：刷新电量、上次被查找时间、Web 端口真实状态（与开关状态失配时自动纠正）；
 *  - 报警效果：系统文件选择器(SAF)自选音频做报警铃声（存 Prefs）+ 本地“测试报警效果”按钮
 *    （响铃+振动+闪光约 12s；首次申请 CAMERA/READ_MEDIA_AUDIO，拒绝自动降级）；
 *  - 密码/闪光灯节点配置沿用。
 */
class HostFragment : Fragment() {

    private var _binding: FragmentHostBinding? = null
    private val binding get() = _binding!!

    private val sharedViewModel: MainViewModel by activityViewModels()

    private var pollJob: Job? = null
    private var lastMismatchToastAt = 0L
    private var testPendingAfterPermission = false

    private companion object {
        const val REQ_ALARM_PERMS = 4101
    }

    /** 铃声选择回调（SAF 文件选择器）：保存所选音频 Uri；取消 = 保持原状 */
    private val ringtonePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri: Uri = result.data?.data ?: return@registerForActivityResult
            // 持久化读权限（部分 provider 不支持则静默忽略，届时播放失败自动降级内置音）
            val resolver = requireContext().contentResolver
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Prefs.setRingtoneUri(requireContext(), uri)
            refreshRingtoneName()
            Toast.makeText(requireContext(), "报警铃声已设置", Toast.LENGTH_SHORT).show()
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHostBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindModelBuilder()
        bindServiceSwitch()
        bindFindControl()
        setupActions()
        // 初始状态
        binding.tvBattery.text = "电量：加载中…"
        binding.tvLastFind.text = formatLastFind(requireContext())
        refreshRingtoneName()
        binding.tvPortText.text = "监听端口：检测中…"
        renderFindControl()
    }

    private fun bindModelBuilder() {
        binding.tvModel.text = "型号：" + (Build.MODEL ?: "未知")
        binding.tvDevice.text = "设备：" + (Build.DEVICE ?: "未知")
    }

    // ------------------------------------------------------------------
    // 5 秒轮询：电量 / 上次查找 / 端口真实监听状态
    // ------------------------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                // Root 电池读取仅每 12 轮（约 60s）执行一次，避免周期性触发 su
                // 导致系统频繁弹出“超级用户授权”提示；其余轮次走免 Root 系统 API。
                refreshHostState(useRoot = (tick % 12 == 0))
                tick++
                delay(5000)
            }
        }
    }

    private suspend fun refreshHostState(useRoot: Boolean) {
        val b = _binding ?: return
        val ctx = requireContext()

        val level = withContext(Dispatchers.IO) { rootBatteryLevel(ctx, useRoot) }
        b.tvBattery.text = "电量：${if (level >= 0) "$level%" else "未知"}"
        b.tvLastFind.text = formatLastFind(ctx)

        // 端口真实态与开关记录对账：以实测为准，失配时纠正开关与存储
        val open = withContext(Dispatchers.IO) { isPortOpen(HostService.DEFAULT_PORT) }
        val prefRunning = Prefs.sp(ctx).getBoolean(HostService.KEY_WEB_RUNNING, false)
        if (open != prefRunning) {
            Prefs.sp(ctx).edit().putBoolean(HostService.KEY_WEB_RUNNING, open).apply()
            if (b.switchWeb.isChecked != open) {
                b.switchWeb.isChecked = open // 触发监听器（内部会按 wasRunning 决定是否 Toast）
                if (!open && System.currentTimeMillis() - lastMismatchToastAt > 5000) {
                    lastMismatchToastAt = System.currentTimeMillis()
                    Toast.makeText(
                        ctx,
                        "检测到 Web 服务未在监听，已自动关闭开关；请重新开启（若仍失败请检查“特殊应用权限”）",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        renderPortText(open)
        renderFindControl()
    }

    // ------------------------------------------------------------------
    // Web 服务开关（带真实启动自检）
    // ------------------------------------------------------------------

    private fun bindServiceSwitch() {
        val ctx = requireContext()
        binding.switchWeb.isChecked = Prefs.sp(ctx).getBoolean(HostService.KEY_WEB_RUNNING, false)
        binding.switchWeb.setOnCheckedChangeListener { _, isChecked ->
            val app = requireContext()
            val wasRunning = Prefs.sp(app).getBoolean(HostService.KEY_WEB_RUNNING, false)
            if (isChecked) {
                Prefs.sp(app).edit().putBoolean(HostService.KEY_WEB_RUNNING, true).apply()
                HostService.start(app)
                Toast.makeText(app, "正在启动 Web 服务…", Toast.LENGTH_SHORT).show()
                // 异步自检：800ms 后端口仍未监听 → 回滚开关并提示真实原因
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(800)
                    val open = withContext(Dispatchers.IO) { isPortOpen(HostService.DEFAULT_PORT) }
                    if (!open) {
                        Prefs.sp(app).edit().putBoolean(HostService.KEY_WEB_RUNNING, false).apply()
                        if (_binding?.switchWeb?.isChecked == true) {
                            binding.switchWeb.isChecked = false // 触发关闭分支（wasRunning=false → 静默）
                        }
                        Toast.makeText(
                            app,
                            "启动失败：端口未进入监听状态。若为前台服务限制，请到「设置 → 应用 → Find You Device → 特殊应用权限」允许后重试",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(app, "Web 服务已启动", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                HostService.stop(app)
                Prefs.sp(app).edit().putBoolean(HostService.KEY_WEB_RUNNING, false).apply()
                if (wasRunning) Toast.makeText(app, "Web 服务已停止", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 环回 127.0.0.1 探测端口是否真实监听（反映 0.0.0.0 绑定结果） */
    private fun isPortOpen(port: Int): Boolean = runCatching {
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", port), 600)
            true
        } finally {
            runCatching { s.close() }
        }
    }.getOrDefault(false)

    private fun renderPortText(running: Boolean) {
        val b = _binding ?: return
        val lan = lanIpv4() ?: "本机IP"
        val port = HostService.DEFAULT_PORT
        b.tvPortText.text = if (running) {
            "监听 :$port · http://$lan:$port（点击复制）"
        } else {
            "监听端口：未启动"
        }
        b.tvPortText.setOnClickListener {
            if (running) {
                val url = "http://$lan:$port"
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("fyd_url", url))
                Toast.makeText(requireContext(), "已复制 $url", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // ------------------------------------------------------------------
    // 查找控制：状态展示 / 锁定开关 / 停止查找
    // ------------------------------------------------------------------

    private fun bindFindControl() {
        // 锁定开关已移至“触发方”（客户端面板 / 网页控制台），主机端只负责状态展示与随时停止
        binding.btnStopFind.setOnClickListener {
            AlarmController.stop()
            renderFindControl()
            Toast.makeText(requireContext(), "已停止查找", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 同步查找控制区 UI（供 5 秒轮询与关键动作后调用）：
     * 状态文本、停止按钮可用性；查找进行中时禁用“测试报警效果”防止互相打断。
     */
    private fun renderFindControl() {
        val b = _binding ?: return
        val running = AlarmController.isRunning
        b.tvFindState.text = when {
            AlarmController.isLocked -> "查找状态：锁定查找中（持续响铃/闪光）"
            running -> {
                val ms = AlarmController.remainingMs()
                val sec = if (ms >= 0) (ms + 999) / 1000 else 0
                "查找状态：进行中（约 ${sec} 秒后自动停止）"
            }
            else -> "查找状态：空闲"
        }
        b.btnStopFind.isEnabled = running
        b.btnTestAlarm.isEnabled = !running
    }

    /** 免定位枚举站点本地 IPv4，用于向用户展示局域网访问地址 */
    private fun lanIpv4(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { ni ->
            if (!ni.isUp || ni.isLoopback) return@forEach
            Collections.list(ni.inetAddresses).forEach { a ->
                if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                    return a.hostAddress
                }
            }
        }
        null
    }.getOrNull()

    // ------------------------------------------------------------------
    // 报警效果：铃声选择 + 测试报警
    // ------------------------------------------------------------------

    private fun setupActions() {
        binding.btnChooseRingtone.setOnClickListener { openRingtonePicker() }
        binding.btnTestAlarm.setOnClickListener { onTestAlarmClick() }
        binding.btnSavePassword.setOnClickListener { savePassword() }
        binding.btnSaveFlash.setOnClickListener { saveFlashPath() }
    }

    /**
     * 系统文件选择器（SAF，Android 自带）：过滤常见音频后缀
     * （mp3 / m4a / m4b / aac / wav / ogg / flac / amr 等）。
     * 部分 ROM 文件管理器忽略 MIME 白名单时仍可在其菜单切“显示所有文件”。
     */
    private fun openRingtonePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/aac",
                    "audio/wav", "audio/x-wav", "audio/ogg", "application/ogg",
                    "audio/flac", "audio/x-flac", "audio/amr", "audio/3gpp"
                )
            )
            putExtra(Intent.EXTRA_TITLE, "选择报警铃声（音频文件）")
        }
        ringtonePicker.launch(intent)
    }

    /** 刷新界面上的铃声名（文件选择器 Uri 的 DISPLAY_NAME 读取放 IO）；未自选 = 内置报警音 */
    private fun refreshRingtoneName() {
        val ctx = requireContext()
        val uri = Prefs.getRingtoneUri(ctx) ?: run {
            binding.tvRingtoneName.text = "内置报警音"
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) c.getString(idx) else null
                        } else null
                    }
                }.getOrNull()
            }
            binding.tvRingtoneName.text = name?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment ?: "自定义铃声"
        }
    }

    private fun onTestAlarmClick() {
        val ctx = requireContext()
        // SAF 选中的音频由 Uri 授权直接读取，不再需要媒体库读权限；仅闪光灯需 CAMERA
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.CAMERA)
        }
        if (missing.isNotEmpty()) {
            testPendingAfterPermission = true
            requestPermissions(missing.toTypedArray(), REQ_ALARM_PERMS)
            return
        }
        runTestAlarm(true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_ALARM_PERMS) return
        val pending = testPendingAfterPermission
        testPendingAfterPermission = false
        if (!pending) return
        val cameraGranted = permissions.indices.any {
            permissions[it] == Manifest.permission.CAMERA &&
                grantResults[it] == PackageManager.PERMISSION_GRANTED
        }
        if (!cameraGranted) {
            Toast.makeText(
                requireContext(),
                "相机权限未授予：闪光灯将尝试 Root 路径，不可用则自动跳过",
                Toast.LENGTH_LONG
            ).show()
        }
        runTestAlarm(cameraGranted)
    }

    /**
     * 本地完整测试：响铃+振动+闪光约 12 秒。
     * 走 AlarmController 统一托管（非锁定、12s 自动停止）：期间“立即停止查找”按钮
     * 可用可随时提前结束；测试按钮/停止按钮可用性由 renderFindControl() 统一同步。
     */
    private fun runTestAlarm(cameraGranted: Boolean) {
        val ctx = requireContext()
        if (AlarmController.isRunning) {
            Toast.makeText(ctx, "已有查找正在进行，请先停止再测试", Toast.LENGTH_SHORT).show()
            return
        }
        AlarmController.start(ctx, false, 12_000L)
        renderFindControl()
        Toast.makeText(
            ctx,
            "正在测试报警效果（12 秒自动停止，可随时点“立即停止查找”提前结束）…",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ------------------------------------------------------------------
    // 设备信息 / 密码 / 节点
    // ------------------------------------------------------------------

    private fun rootBatteryLevel(ctx: Context, useRoot: Boolean): Int {
        if (useRoot) {
            val rootOut = RootShell.execOrIgnore("dumpsys battery")
            val rootLevel = rootOut?.lineSequence()
                ?.firstOrNull { it.trim().startsWith("level", true) }
                ?.filter { it.isDigit() }
                ?.takeIf { it.isNotBlank() }
                ?.toIntOrNull()
            if (rootLevel != null) return rootLevel
        }
        return runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
        }.getOrDefault(-1)
    }

    /** 上次被查找时间展示（卡片左侧标题已含“上次被查找”，此处仅返回时间本体） */
    private fun formatLastFind(ctx: Context): String {
        val stamp = Prefs.getLastFind(ctx)
        return if (stamp > 0) {
            try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(stamp))
            } catch (_: Exception) { "从未" }
        } else {
            "从未"
        }
    }

    private fun savePassword() {
        val ctx = requireContext()
        val raw = binding.etNewPassword.text?.toString()?.trim() ?: ""
        Prefs.setPasswordHash(ctx, if (raw.isEmpty()) "" else Prefs.sha256(raw))
        binding.etNewPassword.setText("")
        Toast.makeText(ctx, if (raw.isEmpty()) "已清除密码" else "密码已保存", Toast.LENGTH_SHORT).show()
    }

    private fun saveFlashPath() {
        val ctx = requireContext()
        val p = binding.etFlashPath.text?.toString()?.trim().orEmpty()
        Prefs.setFlashPath(ctx, p)
        Toast.makeText(ctx, "闪光灯节点已保存", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
        pollJob = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollJob?.cancel()
        pollJob = null
        _binding = null
    }
}