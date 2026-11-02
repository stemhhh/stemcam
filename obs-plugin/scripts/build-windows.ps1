# Build OBS Phone Cam plugin for Windows x64 and stage dist package.
param(
    [string]$ObsSourceDir = $env:OBS_SOURCE_DIR,
    [string]$ObsBuildDir = $env:OBS_BUILD_DIR,
    [string]$FfmpegRoot = $env:FFMPEG_ROOT,
    [string]$Config = "Release"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$RepoRoot = Resolve-Path (Join-Path $Root "..")
$BuildDir = Join-Path $Root "build"
$StageDir = Join-Path $RepoRoot "dist\obs-phone-cam-windows"

if (-not $ObsSourceDir) { throw "Set OBS_SOURCE_DIR or pass -ObsSourceDir" }
if (-not $ObsBuildDir) { throw "Set OBS_BUILD_DIR or pass -ObsBuildDir" }
if (-not $FfmpegRoot) { throw "Set FFMPEG_ROOT or pass -FfmpegRoot (shared FFmpeg with bin/*.dll and include/)" }

Write-Host "OBS_SOURCE_DIR=$ObsSourceDir"
Write-Host "OBS_BUILD_DIR=$ObsBuildDir"
Write-Host "FFMPEG_ROOT=$FfmpegRoot"

New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

$ffmpegInclude = Join-Path $FfmpegRoot "include"
$ffmpegLib = Join-Path $FfmpegRoot "lib"
$ffmpegBin = Join-Path $FfmpegRoot "bin"

cmake -S "$Root" -B "$BuildDir" `
    -DOBS_SOURCE_DIR="$ObsSourceDir" `
    -DOBS_BUILD_DIR="$ObsBuildDir" `
    -DFFMPEG_ROOT="$FfmpegRoot" `
    -DFFMPEG_INCLUDE_DIRS="$ffmpegInclude" `
    -DFFMPEG_LIBRARIES="avcodec;avutil;swscale" `
    -DCMAKE_PREFIX_PATH="$FfmpegRoot"

if ($LASTEXITCODE -ne 0) { throw "cmake configure failed" }

cmake --build "$BuildDir" --config $Config
if ($LASTEXITCODE -ne 0) { throw "cmake build failed" }

# Locate built DLL
$dll = Get-ChildItem -Path $BuildDir -Recurse -Filter "obs-phone-cam.dll" |
    Where-Object { $_.FullName -match $Config -or $_.DirectoryName -match "Release|Debug|$Config" } |
    Select-Object -First 1
if (-not $dll) {
    $dll = Get-ChildItem -Path $BuildDir -Recurse -Filter "obs-phone-cam.dll" | Select-Object -First 1
}
if (-not $dll) { throw "obs-phone-cam.dll not found under $BuildDir" }

Remove-Item -Recurse -Force $StageDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $StageDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $StageDir "obs-plugins\64bit") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $StageDir "data\obs-plugins\obs-phone-cam") | Out-Null

Copy-Item $dll.FullName (Join-Path $StageDir "obs-plugins\64bit\obs-phone-cam.dll")
Copy-Item -Recurse (Join-Path $Root "data\*") (Join-Path $StageDir "data\obs-plugins\obs-phone-cam\")

# Stage FFmpeg runtime DLLs commonly required
$needed = @("avcodec*.dll", "avutil*.dll", "swscale*.dll", "swresample*.dll")
foreach ($pat in $needed) {
    Get-ChildItem -Path $ffmpegBin -Filter $pat -ErrorAction SilentlyContinue |
        ForEach-Object { Copy-Item $_.FullName (Join-Path $StageDir "obs-plugins\64bit\") }
}

Copy-Item (Join-Path $RepoRoot "docs\install-alpha.md") (Join-Path $StageDir "README.txt") -ErrorAction SilentlyContinue

Write-Host "Staged: $StageDir"
Write-Host "Copy obs-plugins\64bit\* into your OBS install obs-plugins\64bit\"
Write-Host "Copy data\obs-plugins\obs-phone-cam into OBS data\obs-plugins\"
