# Toolchain — OBS Phone Cam

## Android app

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog / newer |
| JDK | 17 |
| Android SDK | compileSdk 35, minSdk 26 |
| Gradle | 8.9 (wrapper) |

```bat
cd android-app
gradlew.bat assembleDebug
```

APK: `android-app/app/build/outputs/apk/debug/app-debug.apk`

Release (needs keystore — see `docs/signing.md`):

```bat
gradlew.bat assembleRelease
```

## OBS plugin (Windows x64)

| Tool | Version |
|------|---------|
| Visual Studio | 2022 (C++ desktop) |
| CMake | ≥ 3.16 |
| OBS Studio | 30.x or 31.x source + build |
| FFmpeg | shared Windows x64 (avcodec/avutil/swscale) |

Environment variables (or pass as CMake `-D`):

```bat
set OBS_SOURCE_DIR=C:\src\obs-studio
set OBS_BUILD_DIR=C:\src\obs-studio\build
set FFMPEG_ROOT=C:\deps\ffmpeg
```

Build:

```bat
cd obs-plugin
powershell -File scripts\build-windows.ps1
```

Output lands in `dist/obs-phone-cam-windows/` (dll + ffmpeg + locale).

## Runtime test machine

- Windows 10/11 x64
- OBS Studio 30 or 31 installed
- Android phone with USB debugging optional (not required for AOA)
- USB cable that supports data (not charge-only)
