# Stage Alpha distribution folders under dist/
$ErrorActionPreference = "Stop"
$Repo = Resolve-Path (Join-Path $PSScriptRoot "..")
$Dist = Join-Path $Repo "dist"
New-Item -ItemType Directory -Force -Path $Dist | Out-Null

# Android APK if present
$apk = Join-Path $Repo "android-app\app\build\outputs\apk\debug\app-debug.apk"
$apkRelease = Join-Path $Repo "android-app\app\build\outputs\apk\release\app-release.apk"
$apkOut = Join-Path $Dist "OBSPhoneCam-android.apk"
if (Test-Path $apkRelease) {
    Copy-Item $apkRelease $apkOut -Force
    Write-Host "APK: $apkOut (release)"
} elseif (Test-Path $apk) {
    Copy-Item $apk $apkOut -Force
    Write-Host "APK: $apkOut (debug)"
} else {
    Write-Host "APK not built yet. Run: cd android-app; .\gradlew.bat assembleDebug"
}

# Plugin stage (from build script output)
$pluginStage = Join-Path $Dist "obs-phone-cam-windows"
$zip = Join-Path $Dist "obs-phone-cam-windows.zip"
if (Test-Path $pluginStage) {
    if (Test-Path $zip) { Remove-Item $zip -Force }
    Compress-Archive -Path (Join-Path $pluginStage "*") -DestinationPath $zip
    Write-Host "ZIP: $zip"
} else {
    Write-Host "Plugin stage missing. Run obs-plugin\scripts\build-windows.ps1 first."
}

Copy-Item (Join-Path $Repo "docs\install-alpha.md") (Join-Path $Dist "INSTALL.txt") -Force
Copy-Item (Join-Path $Repo "docs\qa-smoke.md") (Join-Path $Dist "QA-SMOKE.txt") -Force
Write-Host "Alpha pack folder: $Dist"
