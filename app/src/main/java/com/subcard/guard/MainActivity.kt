package com.subcard.guard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvWss: TextView
    private lateinit var tvReadPhone: TextView
    private lateinit var tvSubInfo: TextView
    private lateinit var tvSubData: TextView
    private lateinit var tvDefault: TextView
    private lateinit var tvSmart: TextView
    private lateinit var tvTraffic: TextView
    private lateinit var tvSubNet: TextView
    private lateinit var tvService: TextView
    private lateinit var btnService: MaterialButton
    private lateinit var rbSlot1: RadioButton
    private lateinit var rbSlot2: RadioButton
    private lateinit var llSelfCheck: LinearLayout

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshDashboard() }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshDashboard()
        }

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.postDelayed(refreshRunnable, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        requestPerms()
    }

    override fun onStart() {
        super.onStart()
        listOf(
            "mobile_data0", "mobile_data1",
            SettingsRepo.KEY_MULTI_SIM_DATA_CALL, SettingsRepo.KEY_SMART_DUAL_SIM,
        ).forEach {
            contentResolver.registerContentObserver(Settings.Global.getUriFor(it), false, settingsObserver)
        }
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(settingsObserver)
        mainHandler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun bindViews() {
        tvWss = findViewById(R.id.tvWss)
        tvReadPhone = findViewById(R.id.tvReadPhone)
        tvSubInfo = findViewById(R.id.tvSubInfo)
        tvSubData = findViewById(R.id.tvSubData)
        tvDefault = findViewById(R.id.tvDefault)
        tvSmart = findViewById(R.id.tvSmart)
        tvTraffic = findViewById(R.id.tvTraffic)
        tvSubNet = findViewById(R.id.tvSubNet)
        tvService = findViewById(R.id.tvService)
        btnService = findViewById(R.id.btnService)
        rbSlot1 = findViewById(R.id.rbSlot1)
        rbSlot2 = findViewById(R.id.rbSlot2)
        llSelfCheck = findViewById(R.id.llSelfCheck)

        findViewById<MaterialButton>(R.id.btnCopyGrant).setOnClickListener {
            copy(SettingsRepo.GRANT_CMD, "授权命令已复制，电脑上连手机执行")
        }
        findViewById<MaterialButton>(R.id.btnAskPerms).setOnClickListener { requestPerms() }
        findViewById<MaterialButton>(R.id.btnBlock).setOnClickListener { toggleSubData(true) }
        findViewById<MaterialButton>(R.id.btnUnblock).setOnClickListener { toggleSubData(false) }
        btnService.setOnClickListener {
            if (GuardService.running) GuardService.stop(this) else GuardService.start(this)
            mainHandler.postDelayed({ refreshDashboard() }, 600)
        }
        findViewById<MaterialButton>(R.id.btnSelfCheck).setOnClickListener { runSelfCheck() }
        findViewById<MaterialButton>(R.id.btnCopyApnQuery).setOnClickListener {
            val state = SettingsRepo.readState(this)
            copy(SelfCheck.apnQueryCmd(state.subSubId), "已复制。carrier_enabled=0 即 APN 封禁仍在")
        }
        findViewById<MaterialButton>(R.id.btnCopyApnFix).setOnClickListener { showApnFixDialog() }
        findViewById<MaterialButton>(R.id.btnAutoStart).setOnClickListener { gotoAutoStart() }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener { gotoBattery() }
    }

    private fun requestPerms() {
        val want = mutableListOf<String>()
        if (!SettingsRepo.hasReadPhone(this)) want += Manifest.permission.READ_PHONE_STATE
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            want += Manifest.permission.POST_NOTIFICATIONS
        }
        if (want.isNotEmpty()) permLauncher.launch(want.toTypedArray())
    }

    // ---------- 看板 ----------

    private fun refreshDashboard() {
        val state = SettingsRepo.readState(this)

        tvWss.text = if (SettingsRepo.hasWriteSecure(this)) {
            "✅ WRITE_SECURE_SETTINGS 已授权"
        } else {
            "❌ WRITE_SECURE_SETTINGS 未授权：看门狗无法自动修复。连电脑执行下方复制出的命令，一次即可（永久有效）"
        }
        tvReadPhone.text = if (SettingsRepo.hasReadPhone(this)) {
            "✅ 电话权限已同意（用于识别主副卡与查流量）"
        } else {
            "❌ 电话权限未同意：点「申请运行时权限」"
        }

        val subCard = SettingsRepo.activeCards(this).firstOrNull { it.phoneId == state.subPhoneId }
        tvSubInfo.text = buildString {
            append("副卡：卡槽${state.subPhoneId + 1}")
            if (subCard != null) {
                append(" · subId=${subCard.subId}")
                if (subCard.carrier.isNotEmpty()) append(" · ${subCard.carrier}")
            } else {
                append("（未在位，或电话权限未同意）")
            }
        }

        tvSubData.text = when {
            !state.subMobileDataReadable -> "无法读取"
            state.subMobileData == 0 -> "✅ 已关"
            state.subMobileData == null -> "未设置"
            else -> "❌ 开着"
        }

        tvDefault.text = when {
            state.mainSubId <= 0 -> "未知"
            state.defaultDataSubId == state.mainSubId -> "✅ 主卡"
            state.defaultDataSubId == state.subSubId -> "❌ 副卡"
            else -> "subId=${state.defaultDataSubId}"
        }

        tvSmart.text = when {
            !state.smartReadable -> "无法读取"
            state.smartDualSim == null -> "未启用"
            state.smartDualSim == 0 -> "✅ 关"
            else -> "❌ 开"
        }

        tvSubNet.text = if (GuardService.subNetConnected) {
            val t = if (GuardService.lastSubNetAt > 0)
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(GuardService.lastSubNetAt))
            else ""
            "❌ 有连接！（$t）"
        } else {
            "✅ 无"
        }

        tvService.text = if (GuardService.running) {
            val t = if (GuardService.lastCheckTime > 0)
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(GuardService.lastCheckTime))
            else "刚启动"
            "✅ 运行中（核对于 $t）"
        } else {
            "❌ 未运行"
        }
        btnService.text = if (GuardService.running) "停止看门狗" else "启动看门狗"

        // 流量查询放后台线程，回主线程更新
        val subId = state.subSubId
        if (subId <= 0) {
            tvTraffic.text = "不可用"
        } else {
            tvTraffic.text = "查询中…"
            Thread {
                val usage = TrafficMonitor.query(this, subId)
                mainHandler.post {
                    val nowSubId = SettingsRepo.readState(this).subSubId
                    if (subId == nowSubId) {
                        tvTraffic.text = if (usage.available) {
                            "今日 ${TrafficMonitor.fmt(usage.todayBytes)} · 累计 ${TrafficMonitor.fmt(usage.totalBytes)}"
                        } else {
                            "不可用（${usage.reason}）"
                        }
                    }
                }
            }.start()
        }

        // 副卡选择回显（值未变时不会触发监听刷新）
        if (state.subPhoneId == 0) rbSlot1.isChecked = true else rbSlot2.isChecked = true
    }

    // ---------- 一键封/解 ----------

    private fun toggleSubData(block: Boolean) {
        val state = SettingsRepo.readState(this)
        val key = SettingsRepo.mobileDataKey(state.subPhoneId)

        if (!SettingsRepo.hasWriteSecure(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("缺少授权")
                .setMessage(
                    "写 $key 需要 WRITE_SECURE_SETTINGS 权限。手机连电脑（已开 USB 调试），执行：\n\n" +
                        SettingsRepo.GRANT_CMD + "\n\n执行一次即可，之后不用再连电脑。",
                )
                .setPositiveButton("复制命令") { _, _ -> copy(SettingsRepo.GRANT_CMD, "已复制") }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (block) {
            writeKey(key, 0)
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("解除副卡封锁？")
                .setMessage(
                    "将把 $key 设为 1（打开副卡数据开关）。\n\n" +
                        "注意：APN 层仍处于封禁状态（carrier_enabled=0），副卡依旧无法上网，" +
                        "本操作只影响数据开关层。\n\n确定继续？",
                )
                .setPositiveButton("确定") { _, _ -> writeKey(key, 1) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun writeKey(key: String, value: Int) {
        val ok = SettingsRepo.putGlobal(this, key, value)
        Toast.makeText(
            this,
            if (ok) "已写入 $key=$value" else "写入失败：授权可能已失效，请重新执行授权命令",
            Toast.LENGTH_SHORT,
        ).show()
        refreshDashboard()
    }

    // ---------- 一键自检 ----------

    private fun runSelfCheck() {
        val items = SelfCheck.run(this)
        llSelfCheck.removeAllViews()
        items.forEach { item ->
            val mark = when (item.ok) {
                true -> "✅"
                false -> "❌"
                null -> "⚠️"
            }
            val tv = TextView(this).apply {
                text = "$mark ${item.title}\n      ${item.detail}"
                textSize = 13f
                setPadding(0, dp(8), 0, dp(2))
            }
            llSelfCheck.addView(tv)

            item.fixCmd?.let { cmd ->
                val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "复制修复命令"
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(dp(16), dp(4), 0, dp(4)) }
                    setOnClickListener { copy(cmd, "修复命令已复制，电脑上连手机执行") }
                }
                llSelfCheck.addView(btn)
            }
        }
    }

    private fun showApnFixDialog() {
        val state = SettingsRepo.readState(this)
        val subId = state.subSubId
        val msg = buildString {
            append("APN 层 App 无权读写，需要电脑上用 adb 操作：\n\n")
            append("① 先自查（carrier_enabled=0 即封禁仍在）：\n")
            append(SelfCheck.apnQueryCmd(subId))
            append("\n\n② 若需要重新封禁，先从①的结果里找到 _id（当前封禁行是 _id=5010，换卡后会变），再执行：\n")
            append(SelfCheck.apnFixCmd(5010))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("APN 自查 / 修复")
            .setMessage(msg)
            .setPositiveButton("复制自查命令") { _, _ ->
                copy(SelfCheck.apnQueryCmd(subId), "自查命令已复制")
            }
            .setNeutralButton("复制封禁命令") { _, _ ->
                copy(SelfCheck.apnFixCmd(5010), "封禁命令已复制（_id 以自查结果为准）")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------- 副卡选择 ----------

    private fun onSubSlotPicked(phoneId: Int) {
        if (Prefs.subPhoneId(this) == phoneId) return
        Prefs.setSubPhoneId(this, phoneId)
        Toast.makeText(this, "已把卡槽${phoneId + 1}设为副卡（断流对象）", Toast.LENGTH_SHORT).show()
        refreshDashboard()
    }

    // ---------- MIUI 保活 ----------

    private fun gotoAutoStart() {
        val attempts = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (i in attempts) {
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
                // 换下一个入口
            }
        }
        Toast.makeText(this, "找不到设置入口，请到系统设置里手动开启", Toast.LENGTH_SHORT).show()
    }

    private fun gotoBattery() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "打开电池优化设置失败，请手动前往", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 工具 ----------

    private fun copy(text: String, toast: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("cmd", text))
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
