# NexoCam QA checklist

## Install

- [ ] `scripts/release-nexocam.ps1` produces `dist/NexoCam-Windows-Setup-1.0.0.exe` (or staged folder if Inno missing)
- [ ] Installer refuses to run when OBS is not installed
- [ ] After install, OBS → Add Source shows **NexoCam**
- [ ] Uninstall removes plugin files

## Cloud connect

- [ ] Source shows room + password
- [ ] Connect link opens `/app/?room=…`
- [ ] Wrong password → rejected, no frames
- [ ] Correct password → camera and/or mic appear in OBS
- [ ] Camera only works
- [ ] Mic only works
- [ ] Second client with same password replaces the first
- [ ] Closing OBS / removing source disconnects the browser

## LAN fallback

- [ ] Enable LAN, open local URL, password required
- [ ] Phone on same Wi‑Fi can use HTTPS LAN link when Cloudflare is off

## Site

- [ ] Landing shows only **Conectar** and **Descargar plugin**
- [ ] Descargar opens GitHub Releases
- [ ] Conectar opens `/app/`
