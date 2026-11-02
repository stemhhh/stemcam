@echo off
setlocal
title OBS Phone Cam — Windows Installer
cd /d "%~dp0\.."

set SETUP=%cd%\dist\OBSPhoneCam-Windows-Setup-1.0.0.exe
if not exist "%SETUP%" (
  echo Building Windows installer first...
  "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" "%cd%\installer\OBSPhoneCam.iss"
)
if not exist "%SETUP%" (
  echo ERROR: Setup exe not found: %SETUP%
  pause
  exit /b 1
)

echo Launching single Windows installer...
echo Accept UAC — it will close OBS, install plugin + FFmpeg + driver.
start "" "%SETUP%"
endlocal
