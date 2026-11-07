@chcp 65001 >nul
@echo off
title MediaShare 控制台
cd /d "%~dp0"
setlocal

rem 这台机器自带的 python 可能没编 tkinter，所以挨个试，挑第一个能 import tkinter 的
set "PY="

rem 1) PATH 里的 python
where python >nul 2>&1
if not errorlevel 1 (
    python -c "import tkinter" >nul 2>&1
    if not errorlevel 1 set "PY=python"
)

rem 2) 常见安装目录（官方安装包默认带 tkinter）
for /d %%V in ("%LOCALAPPDATA%\Programs\Python\Python3*") do call :check "%%V\python.exe"
for /d %%V in ("C:\Python3*") do call :check "%%V\python.exe"
for /d %%V in ("C:\Program Files\Python3*") do call :check "%%V\python.exe"

if not defined PY (
    echo.
    echo [错误] 没找到带 tkinter 的 Python 3。
    echo        图形控制台需要 tkinter，python.org 的官方安装包默认就带。
    echo        装好后重新双击本文件；不想装的话，命令行方式仍可用「启动共享.bat」。
    echo.
    pause
    exit /b 1
)

rem 有 pythonw.exe 就用它，启动后不留黑框
set "PYW="
if /i not "%PY%"=="python" (
    for %%I in ("%PY%") do if exist "%%~dpIpythonw.exe" set "PYW=%%~dpIpythonw.exe"
) else (
    for /f "delims=" %%W in ('where pythonw 2^>nul') do if not defined PYW set "PYW=%%W"
)

if defined PYW (
    start "" "%PYW%" "控制台.py"
) else (
    echo [提示] 没找到 pythonw，用带控制台的 python 启动，关掉窗口服务就停了。
    "%PY%" -u "控制台.py"
    if errorlevel 1 pause
)
exit /b 0

:check
if defined PY exit /b
if not exist "%~1" exit /b
"%~1" -c "import tkinter" >nul 2>&1
if not errorlevel 1 set "PY=%~1"
exit /b
