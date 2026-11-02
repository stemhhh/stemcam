# Build NexoCam OBS plugin for Windows x64 and stage dist package.
param(
    [string]$ObsSourceDir = $env:OBS_SOURCE_DIR,
    [string]$ObsBuildDir = $env:OBS_BUILD_DIR,
    [string]$Config = "Release",
    [switch]$Install
)

$ErrorActionPreference = "Stop"

function Find-Cmake {
    $cmd = Get-Command cmake -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (Test-Path $vswhere) {
        $vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if ($vs) {
            $candidate = Join-Path $vs "Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
            if (Test-Path $candidate) { return $candidate }
        }
    }
    throw "cmake was not found. Install CMake or the Visual Studio C++ tools."
}

$Cmake = Find-Cmake
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$RepoRoot = Resolve-Path (Join-Path $Root "..")
$BuildDir = Join-Path $Root "build"
$StageDir = Join-Path $RepoRoot "dist\nexocam-windows"

if (-not $ObsSourceDir) { $ObsSourceDir = Join-Path $RepoRoot "deps\obs-studio" }
if (-not $ObsBuildDir) { $ObsBuildDir = Join-Path $RepoRoot "deps\obs-build-stub" }

Write-Host "OBS_SOURCE_DIR=$ObsSourceDir"
Write-Host "OBS_BUILD_DIR=$ObsBuildDir"

New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
& $Cmake -S "$Root" -B "$BuildDir" `
    -DOBS_SOURCE_DIR="$ObsSourceDir" `
    -DOBS_BUILD_DIR="$ObsBuildDir"
if ($LASTEXITCODE -ne 0) { throw "cmake configure failed" }

& $Cmake --build "$BuildDir" --config $Config --target obs-web-device
if ($LASTEXITCODE -ne 0) { throw "cmake build failed" }

$dll = Get-ChildItem -Path $BuildDir -Recurse -Filter "nexocam.dll" |
    Where-Object { $_.FullName -match $Config } |
    Select-Object -First 1
if (-not $dll) {
    $dll = Get-ChildItem -Path $BuildDir -Recurse -Filter "nexocam.dll" | Select-Object -First 1
}
if (-not $dll) { throw "nexocam.dll not found under $BuildDir" }

Remove-Item -Recurse -Force $StageDir -ErrorAction SilentlyContinue
$pluginDir = Join-Path $StageDir "obs-plugins\64bit"
$dataDir = Join-Path $StageDir "data\obs-plugins\nexocam"
New-Item -ItemType Directory -Force -Path $pluginDir | Out-Null
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
Copy-Item $dll.FullName (Join-Path $pluginDir "nexocam.dll")
Copy-Item -Recurse (Join-Path $Root "data\*") $dataDir
Copy-Item -Recurse (Join-Path $Root "web") (Join-Path $dataDir "web")

Write-Host "Staged: $StageDir"
Write-Host "Copy obs-plugins and data into the OBS Studio install folder, then restart OBS."

if ($Install) {
    $obs = Join-Path $env:ProgramFiles "obs-studio"
    if (-not (Test-Path $obs)) { throw "OBS was not found in $obs" }
    Copy-Item -Recurse -Force (Join-Path $StageDir "*") $obs
    Write-Host "Installed into $obs"
}
