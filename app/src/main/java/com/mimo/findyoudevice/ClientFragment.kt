package com.mimo.findyoudevice

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mimo.findyoudevice.data.DeviceEntity
import com.mimo.findyoudevice.databinding.DialogAddDeviceBinding
import com.mimo.findyoudevice.databinding.FragmentClientBinding
import com.mimo.findyoudevice.databinding.SheetDeviceActionsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 客户端模式（免 Root）。
 *
 * - 设备列表：RecyclerView + ListAdapter，数据来自 Room Flow 自动刷新，卡片显示别名/IP/型号、
 *   在线绿点·离线灰点/上次查找时间；
 * - 底部操作栏：『扫描局域网』（耗时用进度 Dialog + Flow 自动入库）与『手动添加』（弹窗，别名/IP 必填）；
 * - 卡片点击 → 操作面板：查找设备 / 编辑别名 / 删除设备。
 *
 * 全程无定位/相机权限：扫描通过 Socket 连目标 :1145 判定在线，绝不引入 WiFi 扫描或第三方网扫库。
 */
class ClientFragment : Fragment() {

    private var _binding: FragmentClientBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: DeviceAdapter
    private val sharedViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentClientBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupList()
        setupBottomActions()
        observeDevices()
        startProbeLoop()
    }

    /**
     * 5 秒周期性“发包探活”：对列表内全部设备做 TCP 连接 :1145 实测，
     * 仅在线状态发生翻转时才写库（避免频繁写入）；离线主机最多 5 秒内被纠正。
     */
    private fun startProbeLoop() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val dao = MainApplication.getDeviceDao()
                    val devices = dao.getAllOnce()
                    if (devices.isNotEmpty()) {
                        val result = LanScanner.probeOnline(devices.map { it.ip })
                        devices.forEach { d ->
                            val nowOnline = result[d.ip] == true
                            if (d.isOnline != nowOnline) dao.updateOnline(d.uid, nowOnline)
                        }
                    }
                    delay(5000)
                }
            }
        }
    }

    private fun setupList() {
        adapter = DeviceAdapter(::showDeviceSheet)
        binding.rvDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDevices.adapter = adapter
    }

    // Room 全量数据流 → 更新列表并联动空状态
    private fun observeDevices() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MainApplication.getDeviceDao().getAllFlow().collectLatest { list ->
                    adapter.submitList(list)
                    binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvDevices.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun setupBottomActions() {
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnManualAdd.setOnClickListener { showAddDialog(null) }
    }

    /** 扫描局域网：整块塞进 IO，进度框收起后 Toast 结果 */
    private fun startScan() {
        val ctx = requireContext()
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle("扫描局域网")
            .setMessage("正在探测本机网段 :1145 端口…")
            .setCancelable(false)
            .create()
        dlg.show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { LanScanner.scan(ctx) }
                if (dlg.isShowing) dlg.dismiss()
                Toast.makeText(
                    ctx,
                    if (count.isEmpty()) "未发现主机，请确认同网段目标已开启 Web 服务"
                    else "发现 ${count.size} 台主机",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                if (dlg.isShowing) dlg.dismiss()
                Toast.makeText(ctx, "扫描异常：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- 手动添加 / 编辑别名 弹窗 ----------------
    private fun showAddDialog(existing: DeviceEntity?) {
        val ctx = requireContext()
        val b = DialogAddDeviceBinding.inflate(layoutInflater)
        val isEdit = existing != null

        // 编辑：隐藏 IP，仅改名；添加：别名+IP 都暴露且必填
        b.tilDialogIp.visibility = if (isEdit) View.GONE else View.VISIBLE
        if (isEdit) {
            b.etDialogAlias.setText(existing!!.alias)
            b.etDialogIp.setText(existing.ip)
        }

        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isEdit) "编辑别名" else "手动添加设备")
            .setView(b.root)
            .setPositiveButton(if (isEdit) "保存" else "添加", null)
            .setNegativeButton("取消", null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val alias = b.etDialogAlias.text?.toString()?.trim().orEmpty()
                val ip = b.etDialogIp.text?.toString()?.trim().orEmpty()
                if (alias.isEmpty()) {
                    b.tilDialogAlias.error = "请填写易记名称"
                    b.etDialogAlias.requestFocus()
                    return@setOnClickListener
                }
                b.tilDialogAlias.error = null
                if (!isEdit && !isValidIp(ip)) {
                    b.tilDialogIp.error = "IP 地址不合法"
                    return@setOnClickListener
                }
                b.tilDialogIp.error = null

                viewLifecycleOwner.lifecycleScope.launch {
                    val dao = MainApplication.getDeviceDao()
                    if (isEdit) dao.update(existing!!.copy(alias = alias))
                    else dao.insert(DeviceEntity(alias = alias, ip = ip, model = null, lastFindTime = 0L, isOnline = false))
                }
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    // 卡片点击 → 底部面板（BottomSheetDialog）
    private fun showDeviceSheet(device: DeviceEntity) {
        val sheet = BottomSheetDialog(requireContext())
        val b = SheetDeviceActionsBinding.inflate(layoutInflater)
        b.sheetTitle.text = device.alias + " · " + device.ip

        // 锁定查找开关（触发方决定）：勾选 → 持续响铃直到被手动停止；按 IP 记忆偏好
        b.swLockFind.isChecked = Prefs.getDeviceLock(requireContext(), device.ip)
        b.swLockFind.setOnCheckedChangeListener { _, checked ->
            Prefs.setDeviceLock(requireContext(), device.ip, checked)
        }
        b.btFind.setOnClickListener { sheet.dismiss(); unlockDevice(device) }
        b.btStopFind.setOnClickListener { sheet.dismiss(); stopRemoteDevice(device) }
        b.btEdit.setOnClickListener { sheet.dismiss(); showAddDialog(device) }
        b.btDelete.setOnClickListener { sheet.dismiss(); confirmDelete(device) }

        sheet.setContentView(b.root)
        sheet.show()
    }

    // ---------------- 查找设备（先探活 → 带记忆密码触发 → 401 才询问密码） ----------------
    private var unlocking = false

    /** 触发一次“查找”。锁定与否取自该设备上次在面板中选择的偏好（触发方决定） */
    private fun unlockDevice(device: DeviceEntity) {
        if (unlocking) return
        unlocking = true
        performUnlock(
            device,
            Prefs.getDevicePassword(requireContext(), device.ip),
            Prefs.getDeviceLock(requireContext(), device.ip),
            false,
        )
    }

    /**
     * 执行一次“探活 + /find”。[fromDialog]=true 表示密码来自用户输入（失败时提示密码错误并可重输）；
     * false 表示使用记忆/空密码（失败时转入密码输入框）。
     */
    private fun performUnlock(device: DeviceEntity, password: String, lock: Boolean, fromDialog: Boolean) {
        val ctx = requireContext()
        val dao = MainApplication.getDeviceDao()
        viewLifecycleOwner.lifecycleScope.launch {
            val up = LanScanner.isReachable(device.ip)
            if (!up) {
                dao.updateOnline(device.uid, false)
                Toast.makeText(ctx, "主机离线：无法连接 ${device.ip}:${HostService.DEFAULT_PORT}", Toast.LENGTH_LONG).show()
                unlocking = false
                return@launch
            }
            dao.updateOnline(device.uid, true)

            val ok = LanScanner.triggerFind(device.ip, password, lock)
            if (ok) {
                dao.updateLastFind(device.uid, System.currentTimeMillis())
                unlocking = false
                Toast.makeText(
                    ctx,
                    if (lock) "已触发锁定查找：主机持续响铃/闪光，直到在任一客户端/网页点“停止查找”"
                    else "已触发查找：主机响铃/闪光约 15 秒后自动停止",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            // 在线但被拒绝：复核一次防止瞬时掉线，再决定是“输密码”还是“已离线”
            val stillUp = LanScanner.isReachable(device.ip)
            if (!stillUp) {
                dao.updateOnline(device.uid, false)
                unlocking = false
                Toast.makeText(ctx, "查找失败：主机已离线", Toast.LENGTH_LONG).show()
            } else if (fromDialog) {
                Toast.makeText(ctx, "密码错误：主机拒绝查找请求，请重新输入", Toast.LENGTH_SHORT).show()
                showPasswordDialog(device, password, forStop = false)
            } else {
                showPasswordDialog(device, password, forStop = false)
            }
        }
    }

    // ---------------- 远程停止（/stop，鉴权同 /find） ----------------

    /** 停止目标主机的当前查找（幂等：主机空闲也返回成功） */
    private fun stopRemoteDevice(device: DeviceEntity) {
        if (unlocking) return
        unlocking = true
        performStop(
            device,
            Prefs.getDevicePassword(requireContext(), device.ip),
            false,
        )
    }

    private fun performStop(device: DeviceEntity, password: String, fromDialog: Boolean) {
        val ctx = requireContext()
        val dao = MainApplication.getDeviceDao()
        viewLifecycleOwner.lifecycleScope.launch {
            val up = LanScanner.isReachable(device.ip)
            if (!up) {
                dao.updateOnline(device.uid, false)
                Toast.makeText(ctx, "主机离线：无法停止 ${device.ip}", Toast.LENGTH_LONG).show()
                unlocking = false
                return@launch
            }
            dao.updateOnline(device.uid, true)

            val ok = LanScanner.stopFind(device.ip, password)
            if (ok) {
                unlocking = false
                Toast.makeText(ctx, "已停止查找", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val stillUp = LanScanner.isReachable(device.ip)
            if (!stillUp) {
                dao.updateOnline(device.uid, false)
                unlocking = false
                Toast.makeText(ctx, "停止失败：主机已离线", Toast.LENGTH_LONG).show()
            } else if (fromDialog) {
                Toast.makeText(ctx, "密码错误：主机拒绝停止请求，请重新输入", Toast.LENGTH_SHORT).show()
                showPasswordDialog(device, password, forStop = true)
            } else {
                showPasswordDialog(device, password, forStop = true)
            }
        }
    }

    /** 密码输入框：输入正确密码后自动记忆（下次免输）；取消不改变记忆 */
    private fun showPasswordDialog(device: DeviceEntity, prefill: String, forStop: Boolean) {
        val ctx = requireContext()
        val pad = (18 * ctx.resources.displayMetrics.density).toInt()
        val et = EditText(ctx).apply {
            hint = "该主机设置的密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefill)
            setSelection(text.length)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val verb = if (forStop) "停止查找" else "查找"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("$verb " + device.alias)
            .setMessage("主机 ${device.ip} 要求密码后才允许$verb（设置一次后将自动记忆，无需重复输入）")
            .setView(et)
            .setPositiveButton(verb) { _, _ ->
                val pw = et.text?.toString()?.trim().orEmpty()
                Prefs.setDevicePassword(ctx, device.ip, pw)
                unlocking = false // 允许重新进入完整流程
                if (forStop) {
                    performStop(device, pw, true)
                } else {
                    val lock = Prefs.getDeviceLock(ctx, device.ip)
                    performUnlock(device, pw, lock, true)
                }
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { unlocking = false }
            .show()
    }

    // ---------------- 删除 ----------------
    private fun confirmDelete(device: DeviceEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除设备")
            .setMessage("确定从列表删除「${device.alias}」？")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    MainApplication.getDeviceDao().delete(device.uid)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { seg ->
            seg.isNotEmpty() && seg.length <= 3 && seg.all { it.isDigit() } && (seg.toIntOrNull() ?: 256) in 0..255
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}