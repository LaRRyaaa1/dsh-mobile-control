@echo off
setlocal EnableExtensions
chcp 65001 >nul
title DSH Mobile Bridge (Tailscale)

rem Starts the bridge on this PC's Tailscale IPv4 address, so the phone can
rem connect from another network without router port forwarding.
rem The phone and PC must both be logged into the same Tailscale tailnet.

set "TS="
where tailscale >nul 2>nul && set "TS=tailscale"
if not defined TS if exist "%ProgramFiles%\Tailscale\tailscale.exe" set "TS=%ProgramFiles%\Tailscale\tailscale.exe"
if not defined TS if exist "%ProgramFiles(x86)%\Tailscale\tailscale.exe" set "TS=%ProgramFiles(x86)%\Tailscale\tailscale.exe"

if not defined TS (
  echo.
  echo [ERROR] Tailscale not found on this PC.
  echo         Install it from https://tailscale.com/download/windows
  echo         and sign in with the same account as the phone.
  echo.
  pause
  exit /b 1
)

set "IPFILE=%TEMP%\dsh-tailscale-ip.txt"
call "%TS%" ip -4 > "%IPFILE%" 2>nul
set "IP="
for /f "usebackq tokens=1" %%i in ("%IPFILE%") do if not defined IP set "IP=%%i"
del "%IPFILE%" >nul 2>nul

if not defined IP (
  echo.
  echo [ERROR] Tailscale is installed but not connected.
  echo         Open Tailscale and sign in, then run this file again.
  echo.
  pause
  exit /b 1
)

echo.
echo Tailscale IPv4: %IP%
echo Starting bridge on %IP%:8787 ...
echo Keep this window open while using the phone.
echo.

if /i "%~1"=="--check" exit /b 0

call "%~dp0start-mobile.bat" --bind %IP%
