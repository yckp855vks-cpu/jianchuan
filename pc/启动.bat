@echo off
chcp 65001 >nul 2>nul
title 简传 Lite - 局域网传输
cd /d "%~dp0"

echo.
echo   ============================================
echo    简传 Lite 启动中，请稍候...
echo   ============================================
echo.

set "PY="

where python >nul 2>nul
if not errorlevel 1 set "PY=python"

if not defined PY (
  where py >nul 2>nul
  if not errorlevel 1 set "PY=py"
)

if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
if not defined PY if exist "C:\Python312\python.exe" set "PY=C:\Python312\python.exe"

if not defined PY (
  echo   [X] 没有找到 Python
  echo.
  echo       请先安装 Python 3，安装时务必勾选 "Add Python to PATH"
  echo       下载地址: https://www.python.org/downloads/
  echo.
  pause
  exit /b 1
)

echo   解释器: %PY%
echo.

"%PY%" -u server.py %*

echo.
echo   服务已停止，按任意键关闭窗口。
pause >nul
