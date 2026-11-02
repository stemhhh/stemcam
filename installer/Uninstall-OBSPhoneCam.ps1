#Requires -RunAsAdministrator
[CmdletBinding()]
param([string]$ObsRoot = "")

$ErrorActionPreference = "Stop"

function Find-ObsRoot {
    foreach ($c in @(
        "${env:ProgramFiles}\obs-studio",
        "${env:ProgramFiles(x86)}\obs-studio",
        "$env:LOCALAPPDATA\Programs\obs-studio"
    )) {
        if (Test-Path (Join-Path $c "obs-plugins\64bit")) { return $c }
    }
    return $null
}

if (-not $ObsRoot) { $ObsRoot = Find-ObsRoot }
if (-not $ObsRoot) { throw "OBS Studio not found" }

Get-Process -Name "obs64","obs32","obs" -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 1

$pluginDst = Join-Path $ObsRoot "obs-plugins\64bit"
# Only remove our plugin DLL — keep FFmpeg if other tools use it; remove known staged names if desired.
Remove-Item (Join-Path $pluginDst "obs-phone-cam.dll") -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $ObsRoot "data\obs-plugins\obs-phone-cam") -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "OBS Phone Cam removed from $ObsRoot" -ForegroundColor Green
pause
