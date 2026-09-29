package com.subcard.guard

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.telephony.TelephonyManager
import java.util.Calendar
import java.util.Locale

/** 副卡流量查询；ROM 限制时优雅降级 */
object TrafficMonitor {

    data class Usage(
        val available: Boolean,
        val todayBytes: Long,
        val totalBytes: Long,
        val reason: String,
    )

    fun query(ctx: Context, subId: Int): Usage {
        if (subId <= 0) return Usage(false, 0, 0, "副卡不在位或无电话权限")
        return try {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
                ?.createForSubscriptionId(subId)
            val subscriberId = try {
                tm?.subscriberId
            } catch (e: SecurityException) {
                null
            } ?: return Usage(false, 0, 0, "拿不到副卡标识（需要电话权限）")

            val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
                ?: return Usage(false, 0, 0, "系统不支持流量查询")

            val now = System.currentTimeMillis()
            val dayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            @Suppress("DEPRECATION")
            val today = nsm.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE, subscriberId, dayStart, now,
            )
            @Suppress("DEPRECATION")
            val total = nsm.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE, subscriberId, 0L, now,
            )
            Usage(true, today.rxBytes + today.txBytes, total.rxBytes + total.txBytes, "")
        } catch (e: SecurityException) {
            Usage(false, 0, 0, "被 ROM 限制，不可用")
        } catch (e: Exception) {
            Usage(false, 0, 0, "查询失败：${e.message}")
        }
    }

    fun fmt(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.2f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
