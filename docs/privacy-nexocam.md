# NexoCam — Privacy

NexoCam moves camera and/or microphone media from a browser to the OBS plugin.

## What travels where

- **Cloudflare path (default):** the browser and the OBS plugin both open a WebSocket to your NexoCam Worker. Media is relayed only while the session is open and only after the room password matches.
- **LAN fallback:** media stays on your local network and is served by the plugin.

## What we do not do

- No accounts or profiles
- No permanent recording on the Worker
- No join without the password set in the OBS source

## Your control

- Change room code and password any time in OBS
- Turn off Cloudflare or LAN independently in the source properties
- Host the site and Worker on your own Cloudflare account

See also the short privacy page shipped with the website (`nexocam-site/privacy.html`).
