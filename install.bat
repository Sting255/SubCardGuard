@echo off
chcp 65001 >nul
REM 一键安装 + 授权 + 启动 副卡卫士
REM 前提：手机开 USB 调试并连电脑；首次连接需在手机上允许 USB 调试授权

REM 优先用 PATH 里的 adb；找不到则用本脚本同级的 tools\platform-tools\adb.exe
set ADB=
where adb >nul 2>&1 && set ADB=adb
if not defined ADB if exist "%~dp0tools\platform-tools\adb.exe" set ADB=%~dp0tools\platform-tools\adb.exe
if not defined ADB (
    echo 错误：没找到 adb。
    echo   请安装 Android SDK Platform-Tools 并把 adb 加入 PATH，
    echo   或把 platform-tools 放到本脚本同级的 tools\ 目录下。
    pause
    exit /b 1
)
set APK=%~dp0app\build\outputs\apk\debug\app-debug.apk
set PKG=com.subcard.guard

echo [1/4] 检查设备连接...
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo 错误：未检测到手机。请插 USB 线、打开 USB 调试、并在手机上允许调试授权后重试。
    "%ADB%" devices
    pause
    exit /b 1
)

echo [2/4] 安装 APK...
"%ADB%" install -r "%APK%" || (echo 安装失败 & pause & exit /b 1)

echo [3/4] 授权（一次性）...
"%ADB%" shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS
"%ADB%" shell pm grant %PKG% android.permission.READ_PHONE_STATE
"%ADB%" shell pm grant %PKG% android.permission.POST_NOTIFICATIONS

echo [4/4] 启动 App...
"%ADB%" shell monkey -p %PKG% -c android.intent.category.LAUNCHER 1 >nul

echo.
echo 完成。App 内点「启动看门狗」即可。
echo 自查命令（可选）：
echo   "%ADB%" shell settings get global mobile_data1      期望 0
echo   "%ADB%" shell settings get global multi_sim_data_call  期望 1
echo   "%ADB%" shell settings get global smart_dual_sim       期望 0
pause
