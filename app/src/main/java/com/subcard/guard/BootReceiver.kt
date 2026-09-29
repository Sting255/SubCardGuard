package com.subcard.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启看门狗；MIUI 未放开自启动时会静默失败，由用户在 App 内手动开启兜底 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            try {
                GuardService.start(context)
            } catch (e: Exception) {
                // MIUI 拦自启动时在这里失败，不崩溃
            }
        }
    }
}
