# 副卡卫士 (SubCardGuard)

双卡安卓手机的「副卡断流看门狗」：让副卡**只保留通话和短信，流量一个字节都不放行**，并提供可视化看板、设置自动修复、泄露实时报警。无 root 可用。

为 小米 17 Ultra（HyperOS / Android 17）开发，理论上适用于 Android 10+ 的双卡机型。

## 背景

系统层已固化三层断流（adb 操作，详见下方"系统层配置"）：

| 层 | 键 | 封锁值 |
|---|---|---|
| 副卡数据开关 | `settings global mobile_data<副卡phoneId>` | 0 |
| 副卡 APN | telephony.db `carrier_enabled` | 0（adb 改，App 改不了） |
| 双卡智能切换 | `settings global smart_dual_sim` | 0 |

App 是这套防线的**第二道保险**：看住设置、自动修复、泄露报警。

## 功能

- **状态看板**：副卡识别、副卡数据开关、默认上网卡、智能切换、副卡数据连接实时状态
- **看门狗前台服务**：ContentObserver 监听 4 个设置键 + 每 5 分钟全量核对，漂移自动写回 + 通知
- **一键封/解**：App 内直接切副卡数据开关
- **泄露实时报警**：副卡一旦建立任何可上网的蜂窝连接，立即高优通知（不依赖 IMSI）
- **一键自检**：OTA / 换卡后逐项核对防线，subId 变化检测（换卡会重置 APN）
- **开机自启**、MIUI 自启动/电池优化跳转引导

## 构建

要求：JDK 17、Android SDK（platform 34 / build-tools 33.0.1）。Gradle 默认使用 `JAVA_HOME` / PATH 里的 JDK；如需指定，取消 `gradle.properties` 里 `org.gradle.java.home` 的注释并改成自己的路径。

```bash
gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 安装与授权

手机开 USB 调试连电脑，双击 `install.bat`（安装 + 授权 + 启动；脚本会自动找 PATH 里的 adb，也可以把 platform-tools 放到项目下的 `tools\` 目录），或手动：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.subcard.guard android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.subcard.guard android.permission.READ_PHONE_STATE
adb shell pm grant com.subcard.guard android.permission.POST_NOTIFICATIONS
```

授权一次永久有效（存于 /data，OTA 保留；卸载重装或恢复出厂才需重做）。

## HyperOS 上的实测限制（2026-09，小米17 Ultra）

1. 普通 App 读 `multi_sim_data_call` 抛 SecurityException（要求签名级 `READ_PRIVILEGED_PHONE_STATE`）→ App 用 `SubscriptionManager.getDefaultDataSubscriptionId()` 等效判断
2. 该键的 ContentObserver 对 App 不触发（shell 写入时）→ 服务每 5 分钟全量核对兜底
3. **App/adb 写 `multi_sim_data_call` 无法命令系统实时切回默认卡**（只有系统设置 UI 的切换被控制器执行）→ 看门狗对该键：固化设置值（重启自愈）+ 保持副卡开关关（零流量不破）+ 通知引导手动切回
4. IMSI 读取被系统隐私层拦截（连 root 的 dumpsys 都脱敏）→ 字节级流量查询不可用，改用连接级泄露监控（更快更准）

## 边界

- 不尝试用 App 改 APN（签名级权限，改不了）
- 不动紧急呼叫（系统级保底）
