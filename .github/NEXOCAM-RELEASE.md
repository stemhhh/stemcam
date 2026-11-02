# Publishing NexoCam on GitHub

1. Create a public repo (suggested: `nexocam/nexocam` or under your user).
2. Push this project (or a clean product tree with `nexocam-site`, `nexocam-worker`, `obs-web-device`, `installer`, `docs`, `NEXOCAM.md`).
3. Run `powershell -File scripts\release-nexocam.ps1`
4. Create Release **v1.0.0** and upload `dist/NexoCam-Windows-Setup-1.0.0.exe`
5. Update `nexocam-site/config.js`:
   - `githubUrl` → repo URL
   - `downloadUrl` → `…/releases/latest`
   - `wsUrl` → your Worker `wss://…/ws`
   - `siteUrl` → your Pages URL
6. Redeploy Pages + Worker

The landing **Descargar plugin** button must open the GitHub Releases page.
