package com.subcard.guard

import android.content.Context

/**
 * 一键自检：OTA / 换卡 / 授权失效后逐项核对防线。
 * ok=true ✅；ok=false ❌（附修复命令）；ok=null ⚠️ 无法自动判断，需人工确认。
 */
object SelfCheck {

    data class Item(
        val ok: Boolean?,
        val title: String,
        val detail: String,
        val fixCmd: String? = null,
    )

    fun apnQueryCmd(subId: Int): String =
        "adb shell content query --uri content://telephony/carriers " +
            "--projection name,carrier_enabled,sub_id --where \"sub_id=$subId\""

    fun apnFixCmd(rowId: Int): String =
        "adb shell content update --uri content://telephony/carriers " +
            "--where \"_id=$rowId\" --bind carrier_enabled:i:0"

    fun run(ctx: Context): List<Item> {
        val items = mutableListOf<Item>()
        val state = SettingsRepo.readState(ctx)

        // 1. WRITE_SECURE_SETTINGS
        val wss = SettingsRepo.hasWriteSecure(ctx)
        items += Item(
            wss,
            "WRITE_SECURE_SETTINGS 授权",
            if (wss) "已授权，可以自动修复设置" else "未授权：看门狗只能提醒，无法自动修复",
            if (!wss) SettingsRepo.GRANT_CMD else null,
        )

        // 2. 副卡数据开关
        val mdKey = SettingsRepo.mobileDataKey(state.subPhoneId)
        val (md, mdReadable) = SettingsRepo.readInt(ctx.contentResolver, mdKey)
        items += Item(
            if (!mdReadable) null else md == 0,
            "副卡数据开关（$mdKey）",
            when {
                !mdReadable -> "系统不允许读取，无法验证"
                md == 0 -> "已关（封锁态）"
                md == null -> "未设置（视为未封锁）"
                else -> "开着！副卡随时可以走流量"
            },
            if (mdReadable && md != 0) "adb shell settings put global $mdKey 0" else null,
        )

        // 3. 默认上网卡（multi_sim_data_call 受签名权限保护，用 SubscriptionManager 等效值判断）
        val def = state.defaultDataSubId
        val defOk = state.mainSubId > 0 && def == state.mainSubId
        items += Item(
            if (state.mainSubId <= 0) null else defOk,
            "默认上网卡",
            when {
                state.mainSubId <= 0 -> "无法识别主卡（缺电话权限或未插卡）"
                def <= 0 -> "系统未报告默认上网卡"
                def == state.mainSubId -> "主卡（subId=${state.mainSubId}）"
                def == state.subSubId -> "在副卡上！副卡数据开关为关、不会产生流量，但主卡暂时用不了移动数据。" +
                    "系统限制 App 无法自动夺回：请到 系统设置 → 移动网络 → 上网卡 切回主卡（重启手机也会自动恢复）"
                else -> "subId=$def（非主卡）"
            },
            null,
        )

        // 4. 智能双卡切换
        val (smart, smartReadable) = SettingsRepo.readInt(ctx.contentResolver, SettingsRepo.KEY_SMART_DUAL_SIM)
        items += Item(
            if (!smartReadable) null else (smart == null || smart == 0),
            "智能双卡切换",
            when {
                !smartReadable -> "系统不允许读取，无法验证"
                smart == null -> "本机未启用该设置（无需处理）"
                smart == 0 -> "已关"
                else -> "开着！系统可能自动把流量切到副卡"
            },
            if (smartReadable && smart != null && smart != 0)
                "adb shell settings put global ${SettingsRepo.KEY_SMART_DUAL_SIM} 0"
            else null,
        )

        // 5. 换卡 / 订阅重建检测（APN 防线的信号灯）
        val remembered = Prefs.rememberedSubId(ctx)
        val current = state.subSubId
        when {
            current <= 0 -> items += Item(
                null,
                "副卡订阅一致性",
                "副卡不在位或无电话权限，无法判断（APN 防线无法从 App 内查看）",
            )
            remembered == -1 -> {
                Prefs.setRememberedSubId(ctx, current)
                items += Item(
                    true,
                    "副卡订阅一致性",
                    "已记录当前副卡 subId=$current，下次自检对比。换卡/重插卡会重建订阅并重置 APN，届时这里会报警",
                )
            }
            remembered == current -> items += Item(
                true,
                "副卡订阅一致性",
                "与上次记录一致（subId=$current），APN 防线大概率仍在",
            )
            else -> items += Item(
                null,
                "副卡订阅一致性",
                "subId 变了：$remembered → $current。订阅重建会重置 APN（carrier_enabled 恢复为 1），" +
                    "请用下方 APN 自查命令确认，必要时重新封禁",
                apnQueryCmd(current),
            )
        }

        // 6. 副卡数据连接（真实泄露信号，不依赖 IMSI）
        items += Item(
            !GuardService.subNetConnected,
            "副卡数据连接",
            if (GuardService.subNetConnected) "❗检测到副卡存在可上网的蜂窝连接，零流量已被突破！" else "无（零流量承诺当前成立）",
        )

        // 7. 看门狗
        items += Item(
            GuardService.running,
            "看门狗服务",
            if (GuardService.running) "运行中" else "未运行——请点「启动看门狗」",
        )

        return items
    }
}
