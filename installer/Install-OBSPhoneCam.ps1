#Requires -RunAsAdministrator
<#
.SYNOPSIS
  Clean install of OBS Phone Cam into an existing OBS Studio installation.
.DESCRIPTION
  1) Closes OBS if running
  2) Copies plugin + FFmpeg DLLs into obs-plugins\64bit
  3) Copies locale/data into data\obs-plugins\obs-phone-cam
  4) Optionally registers the WinUSB INF for AOA accessory
#>
[CmdletBinding()]
param(
    [string]$ObsRoot = "",
    [string]$PackageDir = "",
    [switch]$SkipWinUsb,
    [switch]$NoCloseObs
)

$ErrorActionPreference = "Stop"

function Write-Step($msg) { Write-Host "[OBS Phone Cam] $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  OK  $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  !!  $msg" -ForegroundColor Yellow }

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..")

if (-not $PackageDir) {
    $PackageDir = Join-Path $RepoRoot "dist\obs-phone-cam-windows"
}
$PackageDir = (Resolve-Path $PackageDir).Path

$pluginSrc = Join-Path $PackageDir "obs-plugins\64bit"
$dataSrc = Join-Path $PackageDir "data\obs-plugins\obs-phone-cam"

if (-not (Test-Path (Join-Path $pluginSrc "obs-phone-cam.dll"))) {
    throw "Package missing obs-phone-cam.dll at: $pluginSrc`nBuild/stage first (dist\obs-phone-cam-windows)."
}
if (-not (Test-Path $dataSrc)) {
    throw "Package missing data folder at: $dataSrc"
}

function Find-ObsRoot {
    $candidates = @(
        "${env:ProgramFiles}\obs-studio",
        "${env:ProgramFiles(x86)}\obs-studio",
        "$env:LOCALAPPDATA\Programs\obs-studio"
    )
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c "obs-plugins\64bit")) { return $c }
    }
    # Registry uninstall keys
    $keys = @(
        "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )
    foreach ($pattern in $keys) {
        Get-ItemProperty $pattern -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -match "OBS Studio" -and $_.InstallLocation } |
            ForEach-Object {
                $p = $_.InstallLocation.TrimEnd('\')
                if (Test-Path (Join-Path $p "obs-plugins\64bit")) { return $p }
            }
    }
    return $null
}

if (-not $ObsRoot) { $ObsRoot = Find-ObsRoot }
if (-not $ObsRoot -or -not (Test-Path (Join-Path $ObsRoot "obs-plugins\64bit"))) {
    throw "OBS Studio not found. Pass -ObsRoot 'C:\Path\to\obs-studio'"
}

Write-Step "OBS root: $ObsRoot"
Write-Step "Package:  $PackageDir"

if (-not $NoCloseObs) {
    $procs = Get-Process -Name "obs64","obs32","obs" -ErrorAction SilentlyContinue
    if ($procs) {
        Write-Step "Closing OBS…"
        $procs | Stop-Process -Force
        Start-Sleep -Seconds 2
        Write-Ok "OBS closed"
    } else {
        Write-Ok "OBS was not running"
    }
}

$pluginDst = Join-Path $ObsRoot "obs-plugins\64bit"
$dataDst = Join-Path $ObsRoot "data\obs-plugins\obs-phone-cam"

Write-Step "Copying plugin + FFmpeg DLLs…"
New-Item -ItemType Directory -Force -Path $pluginDst | Out-Null
Copy-Item -Path (Join-Path $pluginSrc "*") -Destination $pluginDst -Force
Write-Ok "Files in $pluginDst"

Write-Step "Copying plugin data/locale…"
New-Item -ItemType Directory -Force -Path $dataDst | Out-Null
Copy-Item -Path (Join-Path $dataSrc "*") -Destination $dataDst -Recurse -Force
Write-Ok "Files in $dataDst"

# Verify
$dll = Join-Path $pluginDst "obs-phone-cam.dll"
if (-not (Test-Path $dll)) { throw "Install failed: dll not present at $dll" }

if (-not $SkipWinUsb) {
    $infScript = Join-Path $ScriptDir "winusb\install-winusb.ps1"
    if (Test-Path $infScript) {
        Write-Step "Registering WinUSB accessory driver (best effort)…"
        try {
            & powershell -NoProfile -ExecutionPolicy Bypass -File $infScript
            Write-Ok "WinUSB helper finished"
        } catch {
            Write-Warn "WinUSB helper failed (can install later): $($_.Exception.Message)"
        }
    } else {
        Write-Warn "WinUSB helper not found — skip"
    }
}

Write-Host ""
Write-Host "Installation complete." -ForegroundColor Green
Write-Host "Open OBS Studio -> Add Source -> Phone Cam (USB)"
Write-Host "Install the Android APK on your phone, open it, plug USB, tap Start."
pause
