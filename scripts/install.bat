@echo off
rem Installs OoruTV on the TV: finds it on your Wi-Fi, installs OoruTV.apk and saves a screenshot in .\shots.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Apk "%~dp0..\OoruTV.apk"
pause
