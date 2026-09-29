package com.subcard.guard

import android.content.Context
import android.content.SharedPreferences

/** 本 App 自己的持久化配置 */
object Prefs {
    private const val FILE = "guard_prefs"
    const val KEY_SUB_PHONE_ID = "sub_phone_id"          // 哪个卡槽是副卡，默认 1（卡槽2）
    const val KEY_REMEMBERED_SUB_ID = "remembered_sub_id" // 上次自检时记录的副卡 subId，用于换卡检测
    const val KEY_LAST_TRAFFIC_TOTAL = "last_traffic_total"
    const val KEY_LAST_TRAFFIC_DATE = "last_traffic_date"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun subPhoneId(ctx: Context): Int = sp(ctx).getInt(KEY_SUB_PHONE_ID, 1)

    fun setSubPhoneId(ctx: Context, v: Int) {
        sp(ctx).edit().putInt(KEY_SUB_PHONE_ID, v).apply()
    }

    fun rememberedSubId(ctx: Context): Int = sp(ctx).getInt(KEY_REMEMBERED_SUB_ID, -1)

    fun setRememberedSubId(ctx: Context, v: Int) {
        sp(ctx).edit().putInt(KEY_REMEMBERED_SUB_ID, v).apply()
    }

    fun lastTrafficTotal(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_TRAFFIC_TOTAL, -1L)

    fun setLastTrafficTotal(ctx: Context, v: Long) {
        sp(ctx).edit().putLong(KEY_LAST_TRAFFIC_TOTAL, v).apply()
    }

    fun lastTrafficDate(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_TRAFFIC_DATE, 0L)

    fun setLastTrafficDate(ctx: Context, v: Long) {
        sp(ctx).edit().putLong(KEY_LAST_TRAFFIC_DATE, v).apply()
    }
}
