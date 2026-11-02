# NexoCam API (Cloudflare Worker)

WebSocket bridge between the OBS plugin (role `plugin`) and the browser app (role `client`).

## Protocol

1. Client opens `wss://…/ws?room=ABCD&role=…`
2. First text message must be:

```json
{ "type": "auth", "role": "plugin"|"client", "room": "ABCD", "password": "…" }
```

3. On success: `{ "type": "welcome", "role": "…", "room": "…" }`
4. Browser then sends binary frames (JPEG `0x01…`, PCM `0x02…`) and optional `{ "type":"hello","name":"…" }`.
5. Binary from client is relayed to the plugin. Wrong password / empty room → error + close.

## Deploy

```bash
cd nexocam-worker
npm install
npx wrangler login
npx wrangler deploy
```

Copy the `*.workers.dev` URL into:

- `nexocam-site/config.js` → `wsUrl` (use `wss://…/ws`)
- OBS source property **Worker URL** (default in the plugin)

## Local

```bash
npx wrangler dev
```
