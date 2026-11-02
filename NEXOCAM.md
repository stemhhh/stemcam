# NexoCam

**Any camera. OBS. One link.**

NexoCam is the product that turns a phone, tablet, or another PC into an OBS camera and/or microphone through the browser. The OBS plugin is the server (room + password). The web app is the client. Cloudflare Pages hosts the site; a Worker relays the session.

## One decision on the homepage

1. **Conectar** → open the web app  
2. **Descargar plugin** → GitHub Releases

## Ship checklist

1. Deploy Worker: `cd nexocam-worker && npm i && npx wrangler deploy`
2. Put the `wss://…/ws` URL into `nexocam-site/config.js` and the OBS default (or leave editable in OBS)
3. Deploy Pages: `cd nexocam-site && npx wrangler pages deploy . --project-name=nexocam`
4. Build installer: `powershell -File scripts\release-nexocam.ps1`
5. Create GitHub repo `nexocam/nexocam`, push, upload `dist/NexoCam-Windows-Setup-1.0.0.exe` as Release `v1.0.0`
6. Point `downloadUrl` / `githubUrl` in `config.js` at that repo/release

## Docs

- Privacy: [`docs/privacy-nexocam.md`](docs/privacy-nexocam.md)
- QA: [`docs/qa-nexocam.md`](docs/qa-nexocam.md)
- Plugin: [`obs-web-device/README.md`](obs-web-device/README.md)
