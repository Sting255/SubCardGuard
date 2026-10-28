package com.subcard.guard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.File

/**
 * 副卡 IMS 承载监控。
 *
 * VoLTE 的语音/视频媒体流走 IMS 承载，不经过数据开关，也不经过 APN，
 * 普通流量统计同样看不到它——2026-10-14 那次视频彩铃 932KB 就是这样漏掉的。
 * 这里按接口读取 /proc/net/dev 的收发字节，增量超过阈值即回调告警。
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

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var iface: String? = null
    private var lastBytes: Long = -1
    private var suFailed = false

    val currentIface: String? get() = iface

    fun start() {
        if (callback != null) return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = bind(cm, network)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                bind(cm, network)

            override fun onLost(network: Network) {
                iface = null
                lastBytes = -1
            }
        }
        callback = cb
        try {
            cm.registerNetworkCallback(req, cb)
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                ) {
                    bind(cm, n)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "ims watch register failed: ${e.message}")
        }
    }

    fun stop() {
        callback?.let {
            try {
                (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // 服务销毁时回调可能已失效
            }
        }
        callback = null
        iface = null
        lastBytes = -1
    }

    /** 只认副卡的 IMS 承载，主卡的 IMS 不参与统计 */
    private fun bind(cm: ConnectivityManager, network: Network) {
        val caps = cm.getNetworkCapabilities(network) ?: return
        // 不直接引用 TelephonyNetworkSpecifier（当前 SDK 组合下解析不到），
        // 从 specifier 的 toString 里取 mSubId，形如 <TelephonyNetworkSpecifier [mSubId = 2]>
        val subId = Regex("mSubId\\s*=\\s*(\\d+)")
            .find(caps.networkSpecifier?.toString() ?: "")
            ?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (subId != subSubId()) return
        val name = cm.getLinkProperties(network)?.interfaceName ?: return
        if (name != iface) {
            iface = name
            lastBytes = -1
            android.util.Log.i(TAG, "ims bound iface=$name subId=$subId")
        }
    }

    /** 读一次并返回增量；承载不存在或读不到返回 null */
    fun poll(): Long? {
        val name = iface ?: return null
        val now = readIfaceBytes(name) ?: return null
        val prev = lastBytes
        lastBytes = now
        if (prev < 0) {
            android.util.Log.i(TAG, "ims baseline iface=$name bytes=$now")
            return null
        }
        val delta = now - prev
        if (delta <= 0) return null
        android.util.Log.d(TAG, "ims delta iface=$name bytes=$delta")
        if (delta >= ALERT_THRESHOLD) onLeak(name, delta)
        return delta
    }

    private fun readIfaceBytes(name: String): Long? =
        readDirect(name) ?: readViaSu(name)

    /** 普通 App 通常能读 /proc/net/dev；读不到再退到 su */
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
