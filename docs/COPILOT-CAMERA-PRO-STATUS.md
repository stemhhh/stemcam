# Copilot Camera Pro — implementation status

Mapped to plan phases. Code lives under `android-app/`. APK: `dist/OBSPhoneCam-Android.apk` (2.0.0-pro).

| Phase | Status | Key modules |
|-------|--------|-------------|
| 0 UX pro | Done | Grid/Level/Scope overlays, HUD, real slider labels, AE/AF/AWB lock, flash intensity (API 33+), thermal bitrate, reconnect, diag copy, onboarding |
| 1 Hardware | Done | MultiCamera auto lens + hysteresis, OIS/EIS gyro, CameraExtensionsProbe HDR/Night, RAW still path, CapabilityProbe matrix |
| 2 Compute | Done (v1) | HdrFusion + shadow/highlight, Lut3D, DetailEnhance, SmartExposure, ProcessingHub Studio/Live |
| 3 Scopes | Done | Histogram / Waveform / Vectorscope + zebra/peaking |
| 4 Codecs/rec | Done (v1) | HEVC Main/Main10 when available, H.264 fallback, AV1 flagged in caps, ProxyMasterRecorder dual proxy/master |
| 5 Audio pro | Done (v1) | AudioRouter built-in/USB/BT/camcorder, gate, meter, gain → OPCM AUDIO |
| 6 Dual storage | Done (v1) | DualStorageWriter internal + secondary volume mirror + low-space warn |
| 7 Realtime AI | Done (lite) | PersonTracker, BackgroundBlur ROI, GyroEis |
| 8 Creator | Done (v1) | ScenePresets, QR pairing, H+V DualAspectCrop + vertical encode, widgets |
| 9 Device packs | Done (v1) | DevicePacks Pixel/Samsung/Xiaomi/OnePlus/Huawei/Generic + `docs/devices.md` |

## Still not possible without OEM
- Private ISP / Dolby Vision / ProRes / guaranteed 8K120
