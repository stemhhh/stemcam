# Quality presets

Goal: match native camera app sharpness and detail over USB by keeping bitrate high and using sensor-native capture sizes.

## Presets

| Preset        | Id | Resolution strategy              | Target FPS | Bitrate (HEVC) | Bitrate (H.264) | GOP (frames) | Latency bias |
|---------------|----|----------------------------------|------------|----------------|-----------------|--------------|--------------|
| Native Max    | 0  | Highest stable capture size      | max stable | 40000–60000 kbps | 50000–80000 | 60           | quality      |
| High          | 1  | Prefer 4K30 or 1080p60           | 30–60      | 20000–35000      | 25000–45000 | 60           | quality      |
| Balanced      | 2  | 1080p                            | 30         | 12000–18000      | 15000–22000 | 30           | balanced     |
| Low Latency   | 3  | 720p or 1080p                    | 30–60      | 6000–10000       | 8000–12000  | 15           | latency      |

## Encoder rules

- Prefer **HEVC** when the device reports a hardware encoder; else **H.264**.
- Use CBR or near-CBR with high bitrate for USB (bandwidth is plentiful).
- Force IDR on preset change, resolution change, and `REQUEST_KEYFRAME`.
- Disable unnecessary post-processing that softens detail when the API allows.
- Single encode only — never re-encode on the PC beyond decode → texture.

## Manual controls (Native Max / High)

Unlocked when hardware supports them:

- Exposure time (ns) and ISO
- White balance mode / Kelvin
- Focus mode / distance
- Zoom ratio
- Torch
- Optical/electronic stabilization toggle

Auto modes remain available; switching any manual control leaves AE/AWB/AF auto for that axis.

## Reconnect

- On USB detach: stop encode, keep UI alive, show “Reconnecting…”.
- On reattach: HELLO handshake, restore last CONTROL snapshot, `START_STREAM`.
- OBS source stays loaded; shows last frame or black until first KEYFRAME after reconnect.
