# Orchestrate release artifacts into dist/
$ErrorActionPreference = "Stop"
$Repo = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Repo

Write-Host "== Android APK =="
Push-Location android-app
if (Test-Path ".\gradlew.bat") {
    .\gradlew.bat assembleRelease
    if ($LASTEXITCODE -ne 0) {
        Write-Host "assembleRelease failed — trying assembleDebug"
        .\gradlew.bat assembleDebug
    }
} else {
    Write-Host "gradlew.bat missing"
}
Pop-Location

Write-Host "== OBS plugin =="
if ($env:OBS_SOURCE_DIR -and $env:OBS_BUILD_DIR -and $env:FFMPEG_ROOT) {
    powershell -File "obs-plugin\scripts\build-windows.ps1"
} else {
    Write-Host "Skip plugin build (set OBS_SOURCE_DIR, OBS_BUILD_DIR, FFMPEG_ROOT)"
}

Write-Host "== Pack =="
powershell -File "scripts\pack-alpha.ps1"

$iscc = Get-Command iscc -ErrorAction SilentlyContinue
if ($iscc -and (Test-Path "dist\obs-phone-cam-windows")) {
    Write-Host "== Inno Setup =="
    & iscc "installer\OBSPhoneCam.iss"
} else {
    Write-Host "Skip installer (install Inno Setup and stage plugin first)"
}

Write-Host "Release staging complete under dist\"
Write-Host "Sign binaries per docs\signing.md before publishing GitHub Release v$(Get-Content VERSION)"
