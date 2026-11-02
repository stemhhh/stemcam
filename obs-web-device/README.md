# NexoCam (OBS plugin)

Product name for the former `obs-web-device` plugin. Use any camera or microphone from a browser inside OBS.

## Quick path

1. Build / install: `powershell -File scripts\build-windows.ps1` (or `..\scripts\release-nexocam.ps1`)
2. OBS → Add Source → **NexoCam**
3. Copy **connect link** + **password**
4. Open the NexoCam site → **Conectar** → enter room + password → Start

## Layout

| Path | Role |
|------|------|
| `src/` | OBS plugin + LAN server + Cloudflare WSS client |
| `web/` | LAN-hosted copy of the connect UI |
| `../nexocam-site/` | Cloudflare Pages landing + `/app` |
| `../nexocam-worker/` | Cloudflare Durable Object relay |
| `../installer/NexoCam.iss` | Windows Setup |

## Defaults

Edit after you deploy:

- Plugin properties: Worker URL, Site URL
- `nexocam-site/config.js`: `wsUrl`, `downloadUrl`, `githubUrl`

## Self-test (LAN server only)

```bat
build\Release\obs-web-device-serve.exe --selftest --root web
```
