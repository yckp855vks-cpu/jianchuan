@echo off
chcp 65001 >nul
title MediaShare 转码 1080P
cd /d "%~dp0"

rem 把电脑上的 4K / 10bit 片源转成 1080p H.264，投影仪才播得动。
rem 输出到 G:\转码1080P，转完记得把「转码1080P」加进共享目录。

set "PY="
where python >nul 2>&1 && set "PY=python"
if not defined PY ( where py >nul 2>&1 && set "PY=py -3" )
    if not defined PY if exist "%%V\python.exe" set "PY=%%V\python.exe"
)

if not defined PY (
    echo [错误] 没找到 Python 3。
    pause
    exit /b 1
)

echo 开始转码，过程中可以关掉这个窗口以外的其它事，但别关机。
echo.
%PY% -u 转码.py %*
echo.
echo 转码结束。
pause
