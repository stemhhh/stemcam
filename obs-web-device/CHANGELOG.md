# NexoCam changelog

## 1.0.1

- Fix partial/top-only video (WebSocket binary fragment reassembly)
- Auto quality adapts to network load; skip frames when the socket backs up
- Keep-alive ping/pong for iPhone/Brave stability
- Connect link from OBS includes room + password

## 1.0.0

- OBS source **NexoCam** with room code + password
- Cloudflare Worker bridge (plugin server role, browser client)
- Connect web app: any camera, any mic, or either alone
- LAN HTTP/HTTPS fallback kept for offline/local use
- Windows installer (`NexoCam-Windows-Setup-1.0.0.exe`)
- Landing: Conectar | Descargar plugin
