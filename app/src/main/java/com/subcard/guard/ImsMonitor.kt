package com.subcard.guard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File

/**
 * 副卡 IMS 承载监控。
 *
 * VoLTE 的媒体流（视频彩铃、视频通话）走 IMS 承载，不经过数据开关，也不经过 APN，
 * 普通流量统计同样看不到它——2026-10-14 的 932KB、10-29 的 4.6MB 都是这样漏掉的。
 *
 * 每次轮询都重新枚举当前 IMS 网络再读接口计数，不缓存接口名：
 * 承载重建时接口编号会变（rmnet_data3 → rmnet_data1），绑定单次句柄会漏掉整段流量。
 */
class ImsMonitor(
    private val ctx: Context,
    private val subSubId: () -> Int,
    private val onLeak: (iface: String, bytes: Long) -> Unit,
) {
    companion object {
        private const val TAG = "SubGuard"

        /** IMS 心跳是百字节级，媒体流才会到这个量级 */
        const val ALERT_THRESHOLD = 20 * 1024L
    }

    /** 接口名 -> 上次读数；接口重建时计数归零，这里同步丢弃旧值 */
    private val lastBytes = HashMap<String, Long>()
    private var suFailed = false

    @Volatile
    var currentIface: String? = null
        private set

    /** 读一轮并返回副卡 IMS 承载的总增量；没有承载或读不到返回 null */
    fun poll(): Long? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val want = subSubId()
        if (want <= 0) return null

        var total = 0L
        var seen = false
        val alive = HashSet<String>()

        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) continue
            // 不直接引用 TelephonyNetworkSpecifier（当前 SDK 组合下解析不到），
            // 从 specifier 的 toString 里取 mSubId，形如 <TelephonyNetworkSpecifier [mSubId = 2]>
            val subId = Regex("mSubId\\s*=\\s*(\\d+)")
                .find(caps.networkSpecifier?.toString() ?: "")
                ?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (subId != want) continue

            val name = cm.getLinkProperties(n)?.interfaceName ?: continue
            seen = true
            currentIface = name
            alive += name

            val bytes = readIfaceBytes(name) ?: continue
            val prev = lastBytes[name] ?: -1L
            lastBytes[name] = bytes
            if (prev < 0) {
                android.util.Log.i(TAG, "ims baseline iface=$name bytes=$bytes")
                continue
            }
            val d = bytes - prev
            if (d > 0) {
                total += d
                android.util.Log.d(TAG, "ims delta iface=$name bytes=$d")
            }
        }

        // 已消失的接口不保留旧读数，避免重建后把计数归零当成负增长
        lastBytes.keys.retainAll(alive)

        if (!seen) {
            currentIface = null
            return null
        }
        if (total <= 0) return null
        if (total >= ALERT_THRESHOLD) onLeak(currentIface ?: "ims", total)
        return total
    }

    fun reset() {
        lastBytes.clear()
        currentIface = null
    }

    private fun readIfaceBytes(name: String): Long? =
        readDirect(name) ?: readViaSu(name)

    /** 普通 App 读 /proc/net/dev 会被 SELinux 拒绝，实际基本都走 su */
    private fun readDirect(name: String): Long? = try {
        File("/proc/net/dev").useLines { lines ->
            lines.firstOrNull { it.trim().startsWith("$name:") }?.let { parse(it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun readViaSu(name: String): Long? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/net/dev"))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        val v = out.lineSequence().firstOrNull { it.trim().startsWith("$name:") }?.let { parse(it) }
        if (v != null && suFailed) {
            suFailed = false
            android.util.Log.i(TAG, "su read recovered")
        }
        v
    } catch (e: Exception) {
        if (!suFailed) {
            suFailed = true
            android.util.Log.w(TAG, "su read failed, IMS byte watch unavailable: ${e.message}")
        }
        null
    }

    private fun parse(line: String): Long? {
        val parts = line.substringAfter(":").trim().split(Regex("\\s+"))
        if (parts.size < 9) return null
        val rx = parts[0].toLongOrNull() ?: return null
        val tx = parts[8].toLongOrNull() ?: return null
        return rx + tx
    }
}
