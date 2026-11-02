/**
 * NexoCam relay: OBS plugin (server) and browser (client) meet in a Durable Object room.
 * Auth: first JSON message { type:"auth", role:"plugin"|"client", room, password }.
 * Binary media from client is forwarded to the plugin only after both are authenticated.
 */

const ROOM_RE = /^[A-Z0-9]{4,12}$/;
const MAX_PASSWORD = 64;
const MAX_AUTH_ATTEMPTS_WINDOW_MS = 60_000;
const MAX_AUTH_FAILS = 12;

function normalizeRoom(raw) {
  return String(raw || "")
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, "")
    .slice(0, 12);
}

function corsHeaders(env, request) {
  const allow = (env.ALLOW_ORIGIN || "*").trim();
  const origin = request.headers.get("Origin") || "*";
  const value = allow === "*" ? "*" : allow.split(",").map((s) => s.trim()).includes(origin) ? origin : allow.split(",")[0].trim();
  return {
    "Access-Control-Allow-Origin": value,
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
  };
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders(env, request) });
    }

    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return Response.json({ ok: true, service: "nexocam-api" }, { headers: corsHeaders(env, request) });
    }

    if (url.pathname !== "/ws") {
      return new Response("NexoCam API — use /ws\n", {
        status: 404,
        headers: { ...corsHeaders(env, request), "Content-Type": "text/plain; charset=utf-8" },
      });
    }

    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected WebSocket", { status: 426, headers: corsHeaders(env, request) });
    }

    const room = normalizeRoom(url.searchParams.get("room"));
    if (!ROOM_RE.test(room)) {
      return new Response("Invalid room", { status: 400, headers: corsHeaders(env, request) });
    }

    const id = env.ROOM.idFromName(room);
    const stub = env.ROOM.get(id);
    return stub.fetch(request);
  },
};

export class Room {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.plugin = null;
    this.client = null;
    this.password = null;
    this.authFails = [];
  }

  pruneFails(now) {
    this.authFails = this.authFails.filter((t) => now - t < MAX_AUTH_ATTEMPTS_WINDOW_MS);
  }

  tooManyFails() {
    const now = Date.now();
    this.pruneFails(now);
    return this.authFails.length >= MAX_AUTH_FAILS;
  }

  noteFail() {
    this.authFails.push(Date.now());
  }

  sendJson(ws, obj) {
    try {
      ws.send(JSON.stringify(obj));
    } catch (_) {
      /* closed */
    }
  }

  clearSlot(role) {
    if (role === "plugin") {
      this.plugin = null;
      this.password = null;
      if (this.client) {
        this.sendJson(this.client.ws, { type: "error", error: "plugin_gone" });
        try {
          this.client.ws.close(4000, "plugin_gone");
        } catch (_) {}
        this.client = null;
      }
    } else if (role === "client") {
      this.client = null;
      if (this.plugin) {
        this.sendJson(this.plugin.ws, { type: "peer", linked: false });
      }
    }
  }

  async fetch(request) {
    const pair = new WebSocketPair();
    const [clientSock, serverSock] = Object.values(pair);
    serverSock.accept();

    let role = null;
    let authed = false;
    let peerName = "";

    const closeSelf = (code, reason) => {
      try {
        serverSock.close(code, reason);
      } catch (_) {}
    };

    serverSock.addEventListener("message", (event) => {
      if (typeof event.data !== "string") {
        if (!authed || role !== "client" || !this.plugin) return;
        /* Drop oversized frames instead of killing the Durable Object. */
        const size = typeof event.data.byteLength === "number" ? event.data.byteLength : event.data.length || 0;
        if (size > 3 * 1024 * 1024) return;
        try {
          this.plugin.ws.send(event.data);
        } catch (_) {
          this.sendJson(serverSock, { type: "error", error: "plugin_send_failed" });
        }
        return;
      }

      let msg;
      try {
        msg = JSON.parse(event.data);
      } catch (_) {
        return;
      }

      if (msg.type === "ping") {
        this.sendJson(serverSock, { type: "pong" });
        return;
      }

      if (!authed) {
        if (msg.type !== "auth") {
          this.sendJson(serverSock, { type: "error", error: "auth_required" });
          closeSelf(4401, "auth_required");
          return;
        }
        if (this.tooManyFails()) {
          this.sendJson(serverSock, { type: "error", error: "rate_limited" });
          closeSelf(4429, "rate_limited");
          return;
        }

        const wantRole = msg.role === "plugin" ? "plugin" : msg.role === "client" ? "client" : null;
        const room = normalizeRoom(msg.room);
        const password = String(msg.password || "").slice(0, MAX_PASSWORD);
        if (!wantRole || !ROOM_RE.test(room) || !password) {
          this.noteFail();
          this.sendJson(serverSock, { type: "error", error: "invalid_auth" });
          closeSelf(4401, "invalid_auth");
          return;
        }

        if (wantRole === "plugin") {
          if (this.plugin) {
            try {
              this.plugin.ws.close(4001, "replaced");
            } catch (_) {}
            this.plugin = null;
          }
          this.password = password;
          this.plugin = { ws: serverSock };
          role = "plugin";
          authed = true;
          this.sendJson(serverSock, { type: "welcome", role: "plugin", room });
          if (this.client) {
            this.sendJson(serverSock, { type: "peer", linked: true, name: this.client.name || "" });
            this.sendJson(this.client.ws, { type: "peer", linked: true });
          }
          return;
        }

        /* client */
        if (!this.plugin || this.password == null) {
          this.noteFail();
          this.sendJson(serverSock, { type: "error", error: "unknown_room" });
          closeSelf(4404, "unknown_room");
          return;
        }
        if (password !== this.password) {
          this.noteFail();
          this.sendJson(serverSock, { type: "error", error: "bad_password" });
          closeSelf(4403, "bad_password");
          return;
        }
        if (this.client) {
          try {
            this.client.ws.close(4001, "replaced");
          } catch (_) {}
          this.client = null;
        }
        this.client = { ws: serverSock, name: "" };
        role = "client";
        authed = true;
        this.sendJson(serverSock, { type: "welcome", role: "client", room });
        this.sendJson(this.plugin.ws, { type: "peer", linked: true });
        return;
      }

      if (msg.type === "hello" && role === "client") {
        peerName = String(msg.name || "").slice(0, 80);
        if (this.client) this.client.name = peerName;
        if (this.plugin) {
          this.sendJson(this.plugin.ws, { type: "peer", linked: true, name: peerName });
        }
        return;
      }

      /* control JSON from plugin to client */
      if (role === "plugin" && this.client) {
        try {
          this.client.ws.send(event.data);
        } catch (_) {}
      }
    });

    serverSock.addEventListener("close", () => {
      if (!authed || !role) return;
      if (role === "plugin" && this.plugin && this.plugin.ws === serverSock) {
        this.clearSlot("plugin");
      } else if (role === "client" && this.client && this.client.ws === serverSock) {
        this.clearSlot("client");
      }
    });

    serverSock.addEventListener("error", () => {
      if (authed && role) this.clearSlot(role);
    });

    return new Response(null, { status: 101, webSocket: clientSock });
  }
}
