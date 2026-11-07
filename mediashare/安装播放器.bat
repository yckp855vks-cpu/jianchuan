@echo off
chcp 65001 >nul
title 安装「媒体库播放器」到投影仪
cd /d "%~dp0"

rem 投影仪 IP 变了就改这里，或者把 IP 拖到本文件上运行
set "IP=%~1"
if "%IP%"=="" set "IP=192.168.1.100"   rem 改成你投影仪的 IP

set "ADB=C:\下载\platform-tools-latest-windows\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

set "APK=..\deploy\媒体库播放器-v1.0.apk"
if not exist "%APK" (
    echo [错误] 找不到安装包：%APK%
    echo        确认 deploy 目录里有 媒体库播放器-v1.0.apk
    pause
    exit /b 1
)

echo.
echo  连接投影仪 %IP%:5555 ...
"%ADB%" connect %IP%:5555
echo.
"%ADB%" devices
echo.

echo  正在安装，投影仪上可能会弹「允许安装」的提示...
"%ADB%" install -r "%APK%"

if errorlevel 1 (
    echo.
    echo [失败] 安装没成功。常见原因：
    echo   1. 投影仪没开机 / 没连同一个 Wi-Fi
    echo   2. IP 变了，把新的 IP 改到本文件第 8 行
    echo   3. 投影仪上点了「取消」
    echo.
    pause
    exit /b 1
)

echo.
echo  安装完成，正在启动...
"%ADB%" shell monkey -p com.gathin.tvplayer -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo.
echo  好了。投影仪上应该已经打开「媒体库播放器」。
echo  以后从桌面应用列表里点「媒体库播放器」就能进。
echo.
pause
