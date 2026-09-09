package com.mimo.findyoudevice

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mimo.findyoudevice.data.DeviceEntity
import com.mimo.findyoudevice.databinding.ItemDeviceCardBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 客户端设备卡片列表适配器。
 * 使用 ListAdapter + 简单 Diff 高亮在线/离线状态；点击整卡回调 [onItemClick]。
 */
class DeviceAdapter(
    private val onItemClick: (DeviceEntity) -> Unit,
) : ListAdapter<DeviceEntity, DeviceAdapter.VH>(Diff) {

    class VH(val binding: ItemDeviceCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDeviceCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val ctx = b.root.context

        b.tvAlias.text = item.alias
        b.tvIpModel.text = when {
            item.model.isNullOrBlank() -> item.ip
            else -> "${item.ip} · ${item.model}"
        }
        b.tvLastFind.text = "上次被查找：" + formatStamp(item.lastFindTime)

        // 在线绿 / 离线灰
        val online = item.isOnline
        b.dotStatus.background = ContextCompat.getDrawable(
            ctx, if (online) R.color.status_online else R.color.status_offline
        )
        b.tvOnlineText.text = if (online) "在线" else "离线"
        b.tvOnlineText.setTextColor(ContextCompat.getColor(
            ctx, if (online) R.color.status_online else R.color.status_offline
        ))

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    companion object {
        private fun formatStamp(ms: Long): String {
            if (ms <= 0L) return "从未"
            return try {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
            } catch (_: Exception) { "从未" }
        }

        private val Diff = object : DiffUtil.ItemCallback<DeviceEntity>() {
            override fun areItemsTheSame(o: DeviceEntity, n: DeviceEntity) = o.uid == n.uid
            override fun areContentsTheSame(o: DeviceEntity, n: DeviceEntity) =
                o.alias == n.alias && o.ip == n.ip && o.model == n.model &&
                    o.isOnline == n.isOnline && o.lastFindTime == n.lastFindTime
        }
    }
}