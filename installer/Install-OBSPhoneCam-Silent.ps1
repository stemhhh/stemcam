#Requires -RunAsAdministrator
$ErrorActionPreference = "Stop"
$log = Join-Path $env:TEMP "opcm-install.log"
function Log($m) {
    $line = "$(Get-Date -Format o)  $m"
    Add-Content -Path $log -Value $line
    Write-Host $line
}

try {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $RepoRoot = Resolve-Path (Join-Path $ScriptDir "..")
    $PackageDir = Join-Path $RepoRoot "dist\obs-phone-cam-windows"
    $pluginSrc = Join-Path $PackageDir "obs-plugins\64bit"
    $dataSrc = Join-Path $PackageDir "data\obs-plugins\obs-phone-cam"

    Log "PackageDir=$PackageDir"
    if (-not (Test-Path (Join-Path $pluginSrc "obs-phone-cam.dll"))) {
        throw "Missing staged dll in $pluginSrc"
    }

    $ObsRoot = "${env:ProgramFiles}\obs-studio"
    if (-not (Test-Path (Join-Path $ObsRoot "obs-plugins\64bit"))) {
        throw "OBS not found at $ObsRoot"
    }
    Log "ObsRoot=$ObsRoot"

    Get-Process -Name "obs64","obs32","obs" -ErrorAction SilentlyContinue | ForEach-Object {
        Log "Stopping $($_.ProcessName) pid=$($_.Id)"
        $_ | Stop-Process -Force
    }
    Start-Sleep -Seconds 2

    $pluginDst = Join-Path $ObsRoot "obs-plugins\64bit"
    $dataDst = Join-Path $ObsRoot "data\obs-plugins\obs-phone-cam"
    New-Item -ItemType Directory -Force -Path $pluginDst | Out-Null
    New-Item -ItemType Directory -Force -Path $dataDst | Out-Null
    Copy-Item (Join-Path $pluginSrc "*") $pluginDst -Force
    Copy-Item (Join-Path $dataSrc "*") $dataDst -Recurse -Force

    $dll = Join-Path $pluginDst "obs-phone-cam.dll"
    if (-not (Test-Path $dll)) { throw "Copy failed, dll missing" }
    Log "SUCCESS dll=$dll size=$((Get-Item $dll).Length)"

    $inf = Join-Path $ScriptDir "winusb\opcm_accessory.inf"
    if (Test-Path $inf) {
        try {
            pnputil /add-driver "$inf" /install | Out-String | ForEach-Object { Log $_ }
        } catch {
            Log "WinUSB warn: $($_.Exception.Message)"
        }
    }
    Log "DONE"
    exit 0
} catch {
    Log "FAILED: $($_.Exception.Message)"
    exit 1
}
