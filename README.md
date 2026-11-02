# OBS Phone Cam (USB)

High-quality Android phone camera → OBS Studio over USB (AOA + OPCM protocol).

**Version:** see [`VERSION`](VERSION) · **Changelog:** [`CHANGELOG.md`](CHANGELOG.md)

## Download / install — two installers only

| Platform | File | What it does |
|----------|------|----------------|
| **Windows** | `dist/OBSPhoneCam-Windows-Setup-1.0.0.exe` | One click: closes OBS, installs plugin + FFmpeg + locale + WinUSB |
| **Android** | `dist/OBSPhoneCam-Android.apk` | One install on the phone (Camera/Mic permissions) |

```bat
REM Rebuild Windows Setup.exe (needs staged dist\obs-phone-cam-windows):
"%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" installer\OBSPhoneCam.iss
```

Guide: [`docs/install-alpha.md`](docs/install-alpha.md) · Landing: [`landing/index.html`](landing/index.html)

## Repo layout

| Path | Role |
|------|------|
| `android-app/` | Kotlin: Camera2 + MediaCodec + mic + USB accessory |
| `obs-plugin/` | Windows OBS source (FFmpeg decode required) |
| `installer/` | Inno Setup + WinUSB INF |
| `shared/` | OPCM protocol |
| `docs/` | Toolchain, QA, privacy, signing |
| `landing/` | Simple download/FAQ page |
| `dist/` | Pack output (gitignored) |

## Develop

See [`docs/toolchain.md`](docs/toolchain.md) and [`docs/qa-smoke.md`](docs/qa-smoke.md).

```bat
cd android-app
gradlew.bat assembleDebug

cd ..\obs-plugin
powershell -File scripts\build-windows.ps1
```

## Features (1.0.0)

- Presets: Native Max / High / Balanced / Low Latency
- Manual ISO, exposure, WB, focus, zoom, torch
- Optional phone microphone → OBS audio
- Remote controls from OBS (preset, bitrate, torch, keyframe)
- ES + EN UI strings
- Session log share from the phone app

## License notes

Plugin redistributes FFmpeg shared libs under LGPL terms — see [`docs/THIRD_PARTY_FFMPEG.txt`](docs/THIRD_PARTY_FFMPEG.txt).  
Privacy: [`docs/privacy.md`](docs/privacy.md).

## Roadmap (post-1.0)

Virtual webcam, Wi‑Fi/SRT, multi-phone, Play Store, iPhone.
