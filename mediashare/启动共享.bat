@echo off
chcp 65001 >nul
title MediaShare - 局域网影音共享
cd /d "%~dp0"

rem 系统 PATH 里不一定有 python，这里按顺序找一遍
set "PY="
where python >nul 2>&1 && set "PY=python"
if not defined PY ( where py >nul 2>&1 && set "PY=py -3" )
if not defined PY for %%D in (
    "%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
    "%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
    "%LOCALAPPDATA%\Programs\Python\Python311\python.exe"
    "C:\Python313\python.exe"
    "C:\Python312\python.exe"
    "C:\Python311\python.exe"
) do if not defined PY if exist %%D set "PY=%%D"
rem WorkBuddy 自带 python 的版本号会变，再按目录扫一遍
    if not defined PY if exist "%%V\python.exe" set "PY=%%V\python.exe"
)

if not defined PY (
    echo [错误] 没找到 Python 3。
    echo        装一个 Python 3，或者把本机 python.exe 的路径填到本文件第 20 行附近。
    echo.
    pause
    exit /b 1
)

echo 正在启动 MediaShare...
echo.

if "%~1"=="" (
    %PY% -u server.py
) else (
    echo 临时共享目录：%~1
    echo.
    %PY% -u server.py "%~1"
)

echo.
echo 服务已停止。按任意键关闭窗口。
pause >nul
