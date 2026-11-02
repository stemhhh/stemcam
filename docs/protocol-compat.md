# Protocol compatibility

| App version | Plugin version | Protocol | Notes |
|-------------|----------------|----------|-------|
| 1.0.0 | 1.0.0 | OPCM v1 (`0x0001`) | VIDEO + AUDIO + CONTROL |

Breaking changes require bumping `OPCM_VERSION` and documenting here.
Handshake must reject mismatched major framing.
