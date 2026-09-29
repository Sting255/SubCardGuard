package com.subcard.guard

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

data class CardInfo(val phoneId: Int, val subId: Int, val carrier: String)

data class GuardState(
    val subPhoneId: Int,
    val subSubId: Int,      // 副卡 subId，-1 = 不在位/无权限
    val mainSubId: Int,
    val subMobileData: Int?,
    val subMobileDataReadable: Boolean,
    val smartDualSim: Int?,
    val smartReadable: Boolean,
    val defaultDataSubId: Int,  // 来自 SubscriptionManager，等效 multi_sim_data_call 的读
) {
    /** 与期望态的偏差，空列表 = 完好。读不到的键不参与判断（无法验证 ≠ 漂移） */
    fun drift(): List<String> {
        val bad = mutableListOf<String>()
        if (subMobileDataReadable && subMobileData != 0) bad += "副卡数据开关"
        if (smartReadable && smartDualSim != null && smartDualSim != 0) bad += "智能双卡切换"
        if (mainSubId > 0 && defaultDataSubId > 0 && defaultDataSubId != mainSubId) bad += "默认上网卡"
        return bad
    }
}

/** 读写 global settings 与订阅信息的统一入口 */
object SettingsRepo {
    const val KEY_MULTI_SIM_DATA_CALL = "multi_sim_data_call"
    const val KEY_SMART_DUAL_SIM = "smart_dual_sim"

    const val GRANT_CMD =
        "adb shell pm grant com.subcard.guard android.permission.WRITE_SECURE_SETTINGS"

    fun mobileDataKey(phoneId: Int) = "mobile_data$phoneId"

    /**
     * 读单个设置。本 ROM 对部分 telephony 键（如 multi_sim_data_call）要求
     * READ_PRIVILEGED_PHONE_STATE（签名级，adb 授不了），读取失败返回 (null, false)。
     */
    fun readInt(cr: ContentResolver, key: String): Pair<Int?, Boolean> = try {
        val raw = Settings.Global.getString(cr, key)
        Pair(if (raw == null) null else raw.toIntOrNull(), true)
    } catch (e: Exception) {
        Pair(null, false)
    }

    fun hasWriteSecure(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasReadPhone(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    fun activeCards(ctx: Context): List<CardInfo> {
        if (!hasReadPhone(ctx)) return emptyList()
        return try {
            val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            val list: List<SubscriptionInfo> = sm.activeSubscriptionInfoList.orEmpty()
            list.map { CardInfo(it.simSlotIndex, it.subscriptionId, it.carrierName?.toString() ?: "") }
                .sortedBy { it.phoneId }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun readState(ctx: Context): GuardState {
        val cr = ctx.contentResolver
        val subPhoneId = Prefs.subPhoneId(ctx)
        val cards = activeCards(ctx)
        val sub = cards.firstOrNull { it.phoneId == subPhoneId }
        val main = cards.firstOrNull { it.phoneId != subPhoneId }
        val (md, mdOk) = readInt(cr, mobileDataKey(subPhoneId))
        val (smart, smartOk) = readInt(cr, KEY_SMART_DUAL_SIM)
        val defSub = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (e: Exception) {
            -1
        }
        return GuardState(
            subPhoneId = subPhoneId,
            subSubId = sub?.subId ?: -1,
            mainSubId = main?.subId ?: -1,
            subMobileData = md,
            subMobileDataReadable = mdOk,
            smartDualSim = smart,
            smartReadable = smartOk,
            defaultDataSubId = defSub,
        )
    }

    /** 写回一个 global 设置；HyperOS 若额外拦截会在这里失败 */
    fun putGlobal(ctx: Context, key: String, value: Int): Boolean = try {
        Settings.Global.putInt(ctx.contentResolver, key, value)
    } catch (e: Exception) {
        false
    }
}
