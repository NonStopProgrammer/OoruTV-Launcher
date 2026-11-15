@echo off
rem Installs X-OS on the TV: finds it on your Wi-Fi, installs XOS-Launcher.apk and saves a screenshot in .\shots.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Apk "%~dp0..\XOS-Launcher.apk"
pause
