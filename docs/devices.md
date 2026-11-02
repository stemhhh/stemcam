# Device matrix — Copilot Camera Pro

## AI / color packs (Phase 9)

| Family | Denoise | Detail | Default look | Prefer HEVC10 | Notes |
|--------|---------|--------|--------------|---------------|-------|
| Pixel | 0.35 | 0.22 | Neutral | yes | Natural color, moderate denoise |
| Samsung | 0.40 | 0.30 | Vivid | yes | Punchier sharpen/saturation |
| Xiaomi | 0.45 | 0.28 | Vivid | no | Stronger denoise for noisy sensors |
| OnePlus/OPPO | 0.38 | 0.26 | Neutral | yes | Balanced compute pack |
| Huawei/Honor | 0.42 | 0.27 | Cinematic | yes | Cinematic default look |
| Generic | 0.30 | 0.25 | Native | no | Safe defaults |

Detection: `Build.MANUFACTURER` + `Build.MODEL` via `DevicePacks.resolve()`.

## Field test log

| Model | Android | USB | HEVC enc | Ext HDR/Night | RAW | Result | Notes | Tester | Date |
|-------|---------|-----|----------|---------------|-----|--------|-------|--------|------|
| _TBD_ | | 2/3 | yes/no | | | pass/fail | | | |

## Legend

- **pass**: 1080p30+ stable ≥10 min, reconnect OK
- **fail**: note blocker in Notes
- Capabilities matrix also available in-app via **Caps** button (`CapabilityProbe`)
