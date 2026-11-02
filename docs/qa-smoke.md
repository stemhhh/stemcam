# Smoke QA checklist — USB Alpha

Run after every Alpha build. Target: ≥2 Android phones, 1080p30+, 10+ minutes.

## Prep

- [ ] App debug/release installed; camera permission granted
- [ ] Plugin + FFmpeg DLLs in OBS `obs-plugins/64bit` (or via installer)
- [ ] WinUSB bound to accessory `18D1:2D00` / `2D01` (installer or Zadig once)
- [ ] OBS source **Phone Cam (USB)** added; preset Native Max

## Connect path

1. [ ] Open app on phone
2. [ ] Plug USB into PC
3. [ ] Status app: Connected (or Streaming if auto-start)
4. [ ] Status OBS properties: Connected / Streaming
5. [ ] Video appears, upright orientation
6. [ ] Metrics show resolution, FPS, bitrate, drops≈0

## Stability

- [ ] Stream 10 minutes without OBS freeze
- [ ] Unplug cable mid-stream → OBS stays up, status Reconnecting/Disconnected
- [ ] Replug → handshake + video resumes (or Start works)
- [ ] Request keyframe from OBS → image recovers if stuck
- [ ] Flip camera / change preset while streaming
- [ ] Toggle torch
- [ ] Lock ISO/exposure; image stays sharp

## Failures to log

| Symptom | Note (phone model / Android / cable) |
|---------|--------------------------------------|
| No accessory | |
| WinUSB open fail | |
| Hello but no video | |
| Green/corrupt frames | |
| High drops | |

Record results in [`devices.md`](devices.md).
