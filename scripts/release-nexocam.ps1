# Build plugin stage + Inno Setup installer for NexoCam.
param(
    [string]$Version = "1.0.0"
)

$ErrorActionPreference = "Stop"
$Repo = Resolve-Path (Join-Path $PSScriptRoot "..")

Write-Host "== Build plugin =="
powershell -NoProfile -File (Join-Path $Repo "obs-web-device\scripts\build-windows.ps1")
if ($LASTEXITCODE -ne 0) { throw "plugin build failed" }

$isccCandidates = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
)
$iscc = $isccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) {
    Write-Warning "Inno Setup 6 (ISCC.exe) not found. Staged folder is ready at dist\nexocam-windows"
    Write-Warning "Install Inno Setup, then re-run this script to produce NexoCam-Windows-Setup-$Version.exe"
    exit 0
}

Write-Host "== Inno Setup =="
& $iscc (Join-Path $Repo "installer\NexoCam.iss")
if ($LASTEXITCODE -ne 0) { throw "ISCC failed" }

$setup = Join-Path $Repo "dist\NexoCam-Windows-Setup-$Version.exe"
if (-not (Test-Path $setup)) { throw "Missing $setup" }
Write-Host "Release artifact: $setup"
Write-Host "Upload that file to GitHub Releases and point nexocam-site/config.js downloadUrl at it."
