# Install NexoCam into the OBS Studio folder (admin). Prefer the Inno Setup .exe when available.
$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Stage = Join-Path $Root "dist\nexocam-windows"
$Obs = Join-Path $env:ProgramFiles "obs-studio"

if (-not (Test-Path (Join-Path $Stage "obs-plugins\64bit\nexocam.dll"))) {
    Write-Host "Building staged package…"
    powershell -NoProfile -File (Join-Path $Root "obs-web-device\scripts\build-windows.ps1")
}

if (-not (Test-Path (Join-Path $Obs "obs-plugins\64bit"))) {
    throw "OBS Studio was not found in $Obs"
}

Write-Host "Closing OBS if it is open…"
foreach ($name in @("obs64", "obs32", "obs")) {
    Get-Process -Name $name -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 1

Copy-Item -Recurse -Force (Join-Path $Stage "*") $Obs
Write-Host "NexoCam installed into $Obs"
Write-Host "Open OBS → Add Source → NexoCam"
