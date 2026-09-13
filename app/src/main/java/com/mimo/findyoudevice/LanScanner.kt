package com.mimo.findyoudevice

import android.content.Context
import com.mimo.findyoudevice.data.DeviceDao
import com.mimo.findyoudevice.data.DeviceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.net.HttpURLConnection

/**
 * 客户端“局域网扫描 + /info 信息发现”。
 *
 * 原则遵守约束：
 *  - 不使用任何第三方扫描 SDK；
 *  - 不使用 WiFi 扫描/定位权限，本机网段完全经 [NetworkInterface] / ConnectivityManager 推导；
 *  - 每个候选地址用 Socket 连接目标 1145 端口，超时 500ms，能连通才 GET /info 取 model/battery；
 *  - 探测是纯网络 Socket（非 TLS），故不受 cleartext 限制影响。
 *
 * 扫描收敛以 /24 为准（即对所在子网 1..254 探测——一个局域网段内常见情形）。
 */
object LanScanner {

    /** 一次扫描各网段前缀基数集合  由本机活跃网卡推断 */
    data class Subnet(val prefix: String)

    /** 一次 /info 返回的发现结果 */
    data class Discovery(val ip: String, val model: String?, val battery: Int?)

    private const val PORT = 1145
    private const val CONNECT_TIMEOUT_MS = 700
    private const val MAX_CONCURRENCY = 48

    /**
     * 扫描本机所有活跃局域网网段（如 WiFi+热点并存时逐个扫），并把结果 upsert 到 Room。
     * @return 新发现/更新的设备实体列表（已标记在线），供 UI 展示数量。
     */
    suspend fun scan(context: Context): List<DeviceEntity> = withContext(Dispatchers.IO) {
        val bases = resolveSubnetPrefixes()
        if (bases.isEmpty()) return@withContext emptyList()
        // 黑名单：排除本机 IP（避免扫描到自己）
        val localIps = resolveLocalIps()
        val ips = bases.flatMap { base -> (1..254).map { oct -> "$base.$oct" } }.distinct()
            .filter { it !in localIps }

        val discoveries = pingAll(ips)

        // 写库（顺序执行，DAO 非线程安全由 IO 上下文内的单协程串行）
        val dao = MainApplication.getDeviceDao()
        updateDb(context, dao, discoveries)
        // 清理历史中误存的本机记录（黑名单）
        localIps.forEach { ip -> if (dao.countByIp(ip) > 0) dao.deleteByIp(ip) }

        // 把未在本轮出现的既有主机标记离线
        val foundIps = discoveries.mapTo(HashSet()) { it.ip }
        dao.getAllOnce()
            .filter { !foundIps.contains(it.ip) && it.isOnline }
            .forEach { off -> dao.updateOnlineByIp(off.ip, false) }

        discoveries.mapNotNull { d -> dao.findByIp(d.ip) }
    }

    /** 并发受限地对候选 IP 依次做连接探测 + /info 拉取 */
    private suspend fun pingAll(ips: List<String>): List<Discovery> = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENCY)
        ips.map { ip ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    probeHost(ip)
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** 单 IP 探测：连 1145 → 成则 GET /info */
    private fun probeHost(ip: String): Discovery? {
        if (!reachable(ip)) return null
        return fetchInfo(ip)
    }

    /** 800ms 内尝试建立 TCP 连接（超时即判定离线） */
    private fun reachable(ip: String): Boolean = runCatching {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
            true
        } finally {
            runCatching { s.close() }
        }
    }.getOrDefault(false)

    /** 连上 1145 后再 GET /info 取型号/电量（短超时，解析失败不影响发现） */
    private fun fetchInfo(ip: String): Discovery? = runCatching {
        val url = URL("http://$ip:$PORT/info")
        val conn = (url.openConnection() as? HttpURLConnection) ?: return null
        try {
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val model = json.optString("model").takeIf { it.isNotBlank() }
            val battery = if (json.has("battery")) json.optInt("battery").takeIf { it >= 0 } else null
            Discovery(ip, model, battery)
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    /** 依据 /info 返回结果 upsert 到 Room（保留已存在别名，补齐 model） */
    private suspend fun updateDb(
        context: Context,
        dao: DeviceDao,
        discoveries: List<Discovery>,
    ) {
        for (d in discoveries) {
            val exist = dao.findByIp(d.ip)
            if (exist != null) {
                dao.update(
                    exist.copy(
                        model = exist.model ?: d.model,
                        isOnline = true,
                    )
                )
            } else {
                val alias = defaultAlias(d)
                dao.insert(
                    DeviceEntity(
                        alias = alias,
                        ip = d.ip,
                        model = d.model,
                        isOnline = true,
                    )
                )
            }
        }
    }

    private fun defaultAlias(d: Discovery): String =
        (d.model?.takeIf { it.isNotBlank() } ?: "主机") + " (" + d.ip.substringAfterLast('.') + ")"

    /** 免定位：枚举所有活跃网卡上的站点本地 IPv4 前缀（WiFi/热点/有线可并存） */
    /** 收集本机全部站点内 IPv4（扫描黑名单：不扫描/不收录自己） */
    private fun resolveLocalIps(): Set<String> {
        val set = HashSet<String>()
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                Collections.list(ni.inetAddresses).forEach { a ->
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        a.hostAddress?.let { set.add(it) }
                    }
                }
            }
        }
        return set
    }

    private fun resolveSubnetPrefixes(): List<String> {
        val result = LinkedHashSet<String>()
        runCatching {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                // 跳过 VPN/tun（避免误扫到虚拟网卡的空闲网段）
                val lower = ni.name.lowercase()
                if (lower.startsWith("tun") || lower.startsWith("ppp") ||
                    lower.startsWith("radio") || lower.contains("vpn")) continue
                val addrs = Collections.list(ni.inetAddresses)
                for (a in addrs) {
                    if (a is Inet4Address && !a.isLoopbackAddress && isSiteLocal(a.hostAddress)) {
                        result.add(a.hostAddress.substringBeforeLast('.'))
                    }
                }
            }
        }
        return result.toList()
    }

    private fun isSiteLocal(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        return ip.startsWith("192.168.") ||
            ip.startsWith("10.") ||
            ip.startsWith("172.") && ip.substringAfter("172.").substringBefore('.').toIntOrNull()?.let { it in 16..31 } == true
    }

    /** 供手动添加 / 触发“查找设备”前确认可达性（单 IP 复用） */
    suspend fun isReachable(ip: String): Boolean = withContext(Dispatchers.IO) {
        reachable(ip)
    }

    /**
     * 客户端列表周期性“发包探活”：对每个 IP 做一次 TCP 连接 :1145（真实探测，
     * 而非沿用上一次扫描结论），返回 ip → 是否在线。
     * 用于纠正“明明已离线却仍显示在线”的陈旧状态。
     */
    suspend fun probeOnline(ips: List<String>): Map<String, Boolean> = withContext(Dispatchers.IO) {
        if (ips.isEmpty()) return@withContext emptyMap()
        coroutineScope {
            val semaphore = Semaphore(24)
            ips.map { ip ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { ip to reachable(ip) }
                }
            }.awaitAll().toMap()
        }
    }

    /**
     * 远程触发一台主机 /find（POST）。
     * 鉴权头按 Basic admin:password 打包；主机若未设密码(空则视为放行)时 password 传空即可。
     * [lock]=true 表示锁定查找：主机持续响铃/闪光直到被 /stop 停止（不限触发方）。
     * @return true 表示主机返回 200（触发成功）。
     */
    suspend fun triggerFind(ip: String, password: String = "", lock: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val user = "admin"
                val cred = java.util.Base64.getEncoder()
                    .encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
                val url = URL("http://$ip:$PORT/find")
                val conn = (url.openConnection() as? HttpURLConnection) ?: return@runCatching false
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 1500
                    conn.readTimeout = 4000
                    conn.doOutput = true
                    conn.setRequestProperty("Authorization", "Basic $cred")
                    conn.outputStream.use { it.write(if (lock) "lock=1".toByteArray(Charsets.UTF_8) else byteArrayOf()) }
                    conn.responseCode == 200
                } finally {
                    runCatching { conn.disconnect() }
                }
            }.getOrDefault(false)
        }

    /**
     * 远程停止一台主机的当前查找 /stop（POST）。鉴权方式与 /find 一致。
     * 停止动作幂等：主机空闲时调用也返回 200。
     * @return true 表示主机返回 200。
     */
    suspend fun stopFind(ip: String, password: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val user = "admin"
                val cred = java.util.Base64.getEncoder()
                    .encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
                val url = URL("http://$ip:$PORT/stop")
                val conn = (url.openConnection() as? HttpURLConnection) ?: return@runCatching false
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 1500
                    conn.readTimeout = 4000
                    conn.doOutput = true
                    conn.setRequestProperty("Authorization", "Basic $cred")
                    conn.outputStream.use { /* 空 body */ }
                    conn.responseCode == 200
                } finally {
                    runCatching { conn.disconnect() }
                }
            }.getOrDefault(false)
        }
}