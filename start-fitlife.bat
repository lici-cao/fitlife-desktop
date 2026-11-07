@echo off
chcp 65001 >nul
title FitLife Launcher
echo.
echo ==============================================
echo        FitLife  Smart Fitness  Launcher v3
echo ==============================================
echo.
echo Starting... JDK / Maven / Node / MySQL check, DB migrate, backend + frontend.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-fitlife.ps1" %*
if errorlevel 1 (
  echo.
  echo Launch failed. Please read the messages above.
  pause
)
