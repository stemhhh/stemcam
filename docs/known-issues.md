# Known issues (1.0.0)

- First accessory connection on a PC may require the WinUSB INF / installer (or Zadig once).
- Some OEM USB modes (charge-only cables) never enumerate data — use a data cable.
- NV21 rotation on unusual sensor orientations may need per-device tuning.
- Without FFmpeg DLLs beside the plugin, OBS shows no video.
- Thermal throttling on phones can drop FPS in Native Max during long sessions — use High/Balanced if needed.
- SmartScreen may warn on unsigned Beta installers until a code-signing cert is applied.
