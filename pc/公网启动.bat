@echo off
chcp 65001 >nul 2>nul
title 简传 Lite - 公网模式
cd /d "%~dp0"

set "CFD=%USERPROFILE%\Documents\cloudflared-windows-amd64.exe"

echo.
echo   ============================================================
echo    简传 Lite · 公网模式启动中...
echo   ============================================================
echo.

rem ---- 0. 清掉上次残留 ----
taskkill /IM cloudflared-windows-amd64.exe /F >nul 2>nul

rem ---- 1. 找 Python（和 启动.bat 同一套逻辑）----
set "PY="
where python >nul 2>nul
if not errorlevel 1 set "PY=python"
if not defined PY (
  where py >nul 2>nul
  if not errorlevel 1 set "PY=py"
)
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python313\python.exe"

if not defined PY (
  echo   [X] 没有找到 Python，请先安装并勾选 Add Python to PATH
  pause
  exit /b 1
)

if not exist "%CFD%" (
  echo   [X] 没找到 cloudflared：%CFD%
  echo       如果穿透程序在别的位置，请用记事本修改本脚本第 5 行的路径。
  pause
  exit /b 1
)

rem ---- 2. 后台启动简传 Lite（数据/密码与 启动.bat 完全共用）----
echo   [1/2] 启动简传 Lite 服务（8787）...
start "jc-server" /min "%PY%" -u server.py --no-browser
timeout /t 3 /nobreak >nul

"%PY%" -c "import socket,sys; s=socket.socket(); s.settimeout(3); sys.exit(0 if s.connect_ex(('127.0.0.1',8787))==0 else 1)"
if errorlevel 1 (
  echo   [X] 本地服务没起来，请先单独双击 启动.bat 看看报什么错。
  pause
  exit /b 1
)

rem ---- 3. 把访问密码从配置里读出来显示 ----
set "PASS="
for /f "usebackq delims=" %%P in (`"%PY%" -c "import json,io;print(json.load(io.open('data/config.json',encoding='utf-8')).get('password',''))"`) do set "PASS=%%P"

echo   [2/2] 启动公网隧道...
echo.

rem ---- 4. tunnel_watch.py 会盯着 cloudflared 的输出，一出现网址就大字显示、
rem         存进「公网地址.txt」并自动复制到剪贴板 ----
"%PY%" -u tunnel_watch.py

echo.
echo   隧道已断开，按任意键关闭。
pause >nul
