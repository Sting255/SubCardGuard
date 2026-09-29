package com.subcard.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * 副卡看门狗前台服务：
 * - ContentObserver 监听 mobile_data0/1、multi_sim_data_call、smart_dual_sim
 * - 变更后防抖、先读值比对，只有偏离期望态才写回（自己的写入不会造成循环）
 * - 每 15 分钟查一次副卡流量，>0 即告警
 */
class GuardService : Service() {

    companion object {
        const val CHANNEL_STATUS = "guard_status"
        const val CHANNEL_ALERT = "guard_alert"
        const val ID_FGS = 1
        const val ID_FIX = 2
        const val ID_TRAFFIC = 3
        const val ACTION_STOP = "com.subcard.guard.action.STOP"
        const val CHECK_DELAY_MS = 800L
        const val TRAFFIC_INTERVAL_MS = 15 * 60 * 1000L
        const val FULL_CHECK_INTERVAL_MS = 5 * 60 * 1000L

        private const val TAG = "SubGuard"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastCheckTime: Long = 0

        @Volatile
        var lastFixText: String = ""

        /** 副卡当前是否存在可上网的蜂窝连接（真实泄露信号，不依赖 IMSI） */
        @Volatile
        var subNetConnected: Boolean = false
            private set

        @Volatile
        var lastSubNetAt: Long = 0
            private set

        @Volatile
        private var lastManualAlertAt: Long = 0

        @Volatile
        private var lastLeakAlertAt: Long = 0

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, GuardService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GuardService::class.java).setAction(ACTION_STOP))
        }
    }

    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var observers: List<ContentObserver> = emptyList()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val subNetworks = mutableSetOf<Network>()

    /** key -> (写入时刻, 写入值)，防止自己写入触发的回调造成来回写 */
    private val recentWrites = HashMap<String, Pair<Long, Int>>()

    private fun markWrite(key: String, value: Int) {
        synchronized(recentWrites) { recentWrites[key] = Pair(SystemClock.elapsedRealtime(), value) }
    }

    private fun justWrote(key: String, value: Int): Boolean {
        synchronized(recentWrites) {
            val (t, v) = recentWrites[key] ?: return false
            return SystemClock.elapsedRealtime() - t < 15_000 && v == value
        }
    }

    private val watchedKeys = listOf(
        "mobile_data0",
        "mobile_data1",
        SettingsRepo.KEY_MULTI_SIM_DATA_CALL,
        SettingsRepo.KEY_SMART_DUAL_SIM,
    )

    private val checkRunnable = Runnable { checkAndFix() }

    private val trafficRunnable = object : Runnable {
        override fun run() {
            checkTraffic()
            handler?.postDelayed(this, TRAFFIC_INTERVAL_MS)
        }
    }

    /** 周期全量核对：部分键（如 multi_sim_data_call）的变更监听在本 ROM 不触发，靠它兜底 */
    private val fullCheckRunnable = object : Runnable {
        override fun run() {
            checkAndFix()
            handler?.postDelayed(this, FULL_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannels()
        startAsForeground()
        worker = HandlerThread("GuardWorker").apply { start() }
        handler = Handler(worker!!.looper)
        registerObservers()
        registerNetWatch()
        handler!!.postDelayed(checkRunnable, 500)
        handler!!.postDelayed(trafficRunnable, 10_000)
        handler!!.postDelayed(fullCheckRunnable, FULL_CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        observers.forEach { contentResolver.unregisterContentObserver(it) }
        observers = emptyList()
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // 服务销毁时回调可能已失效
            }
        }
        netCallback = null
        synchronized(subNetworks) { subNetworks.clear() }
        subNetConnected = false
        handler?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        handler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerObservers() {
        val h = handler ?: return
        observers = watchedKeys.map { key ->
            val uri: Uri = Settings.Global.getUriFor(key)
            object : ContentObserver(h) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    handler?.removeCallbacks(checkRunnable)
                    handler?.postDelayed(checkRunnable, CHECK_DELAY_MS)
                }
            }.also { contentResolver.registerContentObserver(uri, false, it) }
        }
    }

    /**
     * 副卡数据连接监听：IMSI 在本 ROM 被系统隐私层拦截（字节级流量查询不可用），
     * 改为监听蜂窝网络归属——副卡只要建立任何可上网的连接就立刻告警，
     * 比 15 分钟流量轮询更快也更准。
     */
    private fun registerNetWatch() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                handler?.post { handleCaps(network, caps) }
            }

            override fun onLost(network: Network) {
                handler?.post {
                    synchronized(subNetworks) {
                        subNetworks.remove(network)
                        if (subNetworks.isEmpty()) subNetConnected = false
                    }
                }
            }
        }
        netCallback = cb
        try {
            cm.registerNetworkCallback(req, cb)
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    handleCaps(n, caps)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "net watch register failed: ${e.message}")
        }
    }

    private fun handleCaps(network: Network, caps: NetworkCapabilities) {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        val state = SettingsRepo.readState(this)
        if (state.subSubId <= 0) return
        // 归属判断：蜂窝互联网承载只会在默认数据卡上建立（副卡数据开关同时被看死为关），
        // 所以默认数据卡在副卡上 ⇒ 这个连接就是副卡的
        val isSub = state.defaultDataSubId == state.subSubId
        android.util.Log.i(TAG, "cellular internet net=$network defaultSub=${state.defaultDataSubId} isSub=$isSub")
        if (!isSub) return
        var added = false
        synchronized(subNetworks) {
            added = subNetworks.add(network)
            if (added) {
                subNetConnected = true
                lastSubNetAt = System.currentTimeMillis()
            }
        }
        if (added) {
            val now = System.currentTimeMillis()
            if (now - lastLeakAlertAt > 30 * 60 * 1000L) {
                lastLeakAlertAt = now
                notifyAlert(
                    "⚠️⚠️ 副卡建立了数据连接！",
                    "零流量防线已被突破，副卡正在（或刚刚）产生可上网连接！请立即打开 App 执行一键自检，" +
                        "并检查 设置→移动网络 的上网卡选择和副卡数据开关。",
                )
            }
        }
    }

    /** 防抖后的核对：读当前值 → 与期望态比对 → 只写偏离的项 */
    private fun checkAndFix() {
        val state = SettingsRepo.readState(this)
        val fixed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val manualRequired = mutableListOf<String>()

        val mdKey = SettingsRepo.mobileDataKey(state.subPhoneId)
        if (state.subMobileDataReadable && state.subMobileData != 0) {
            if (SettingsRepo.putGlobal(this, mdKey, 0)) {
                markWrite(mdKey, 0)
                fixed += "副卡数据开关"
            } else failed += "副卡数据开关"
        }

        if (state.smartReadable && state.smartDualSim != null && state.smartDualSim != 0) {
            if (SettingsRepo.putGlobal(this, SettingsRepo.KEY_SMART_DUAL_SIM, 0)) {
                markWrite(SettingsRepo.KEY_SMART_DUAL_SIM, 0)
                fixed += "智能双卡切换"
            } else failed += "智能双卡切换"
        }

        // 默认上网卡：本 ROM 实测（2026-09-29）无论 App 还是 adb 写 multi_sim_data_call，
        // 系统控制器都不会实时切回（只有系统设置 UI 的切换被真正执行）。
        // 能做的：①固化原值（重启后系统按设置值恢复，漂移不跨重启）；②副卡数据开关保持关，
        // 即使默认卡在副卡上也不会产生流量；③高优通知引导手动切回。
        if (state.mainSubId > 0 && state.defaultDataSubId > 0 &&
            state.defaultDataSubId != state.mainSubId
        ) {
            if (!justWrote(SettingsRepo.KEY_MULTI_SIM_DATA_CALL, state.mainSubId)) {
                SettingsRepo.putGlobal(this, SettingsRepo.KEY_MULTI_SIM_DATA_CALL, state.mainSubId)
            }
            manualRequired += "默认上网卡"
        }

        lastCheckTime = System.currentTimeMillis()
        if (fixed.isNotEmpty()) {
            lastFixText = "已自动修复：" + fixed.joinToString("、")
            android.util.Log.i(TAG, "fixed=$fixed failed=$failed manual=$manualRequired")
            notifyAlert(
                "看门狗已修复",
                lastFixText + "。副卡相关设置被改动过，已写回封锁态。",
            )
        }
        if (manualRequired.isNotEmpty()) {
            val now = System.currentTimeMillis()
            if (now - lastManualAlertAt > 30 * 60 * 1000L) {
                lastManualAlertAt = now
                android.util.Log.w(TAG, "manual required: $manualRequired")
                notifyAlert(
                    "⚠️ 上网卡被切到副卡，请手动切回",
                    "系统限制，App 无法自动夺回（已固化设置值，重启后也会自动恢复）。" +
                        "副卡数据开关仍为关、不会产生流量。" +
                        "请到 系统设置 → 移动网络 → 上网卡 切回主卡。",
                )
            }
        }
        if (failed.isNotEmpty()) {
            android.util.Log.w(TAG, "fix failed: $failed")
            notifyAlert(
                "看门狗修复失败",
                "无法写回：" + failed.joinToString("、") +
                    "。可能是 WRITE_SECURE_SETTINGS 授权失效，请打开 App 重新授权。",
            )
        }
        updateForeground()
    }

    private fun checkTraffic() {
        val state = SettingsRepo.readState(this)
        val usage = TrafficMonitor.query(this, state.subSubId)
        if (!usage.available || usage.totalBytes <= 0) return

        val cal = java.util.Calendar.getInstance()
        val dayKey = cal.get(java.util.Calendar.YEAR) * 10000L +
            (cal.get(java.util.Calendar.MONTH) + 1) * 100L +
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        val lastDate = Prefs.lastTrafficDate(this)
        val lastTotal = Prefs.lastTrafficTotal(this)

        // 出现流量即报；已报过则只在继续增长或跨天时再报，避免刷屏
        if (usage.totalBytes > lastTotal || dayKey != lastDate) {
            Prefs.setLastTrafficDate(this, dayKey)
            Prefs.setLastTrafficTotal(this, usage.totalBytes)
            notifyAlert(
                "⚠️ 副卡出现流量",
                "副卡今日 " + TrafficMonitor.fmt(usage.todayBytes) +
                    "，累计 " + TrafficMonitor.fmt(usage.totalBytes) +
                    "。部分防线可能已失效（如换卡重置了 APN），请打开 App 执行一键自检。",
            )
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "看门狗运行状态", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "修复与流量警报", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun startAsForeground() {
        val n = buildStatusNotification("运行中 · 防线核对已启动")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_FGS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_FGS, n)
        }
    }

    private fun updateForeground() {
        val text = when {
            GuardService.lastFixText.isNotEmpty() -> "运行中 · 最近：${GuardService.lastFixText}"
            else -> "运行中 · 防线完好"
        }
        getSystemService(NotificationManager::class.java)?.notify(ID_FGS, buildStatusNotification(text))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildStatusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle("副卡卫士看门狗")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun notifyAlert(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java)?.notify(ID_FIX, n)
    }
}
