@echo off
setlocal
chcp 65001 >nul
title DSH Mobile Bridge
set "DSH_MOBILE_HOME=%~dp0.state"
if not exist "%DSH_MOBILE_HOME%" mkdir "%DSH_MOBILE_HOME%"
cd /d "%~dp0bridge"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo [ERROR] Node.js not found. Install Node.js 20 or newer:
  echo         https://nodejs.org/
  echo.
  pause
  exit /b 1
)

if not exist "node_modules\qrcode-terminal" (
  echo Installing optional QR dependency, one time only...
  call npm install --omit=dev --no-audit --no-fund
  if errorlevel 1 (
    echo.
    echo [WARN] npm install failed. Continuing without QR code; paste the link instead.
  )
)

echo.
echo Starting DSH Mobile Bridge...
echo Keep this window open. Closing it disconnects the phone.
echo.

node server.mjs %*

echo.
echo Bridge stopped.
pause