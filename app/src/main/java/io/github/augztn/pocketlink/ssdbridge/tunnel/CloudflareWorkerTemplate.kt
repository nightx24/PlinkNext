package io.github.augztn.pocketlink.ssdbridge.tunnel

/**
 * Self-contained Cloudflare Worker + Durable Object relay script that pairs with
 * PocketLink's built-in pure-Kotlin Edge Tunnel (`CloudflareTunnelAdapter`).
 *
 * Users can paste this directly into a free Cloudflare Worker (or deploy via `wrangler deploy`)
 * to get a permanent HTTPS endpoint (`https://<name>.<account>.workers.dev`) that forwards
 * requests over a single authenticated WebSocket (`wss://.../__pocketlink_tunnel`) to
 * PocketLink's local HTTP server (`127.0.0.1:8000`) — no external binaries required on Android.
 */
object CloudflareWorkerTemplate {

    const val WORKER_SCRIPT: String = """// PocketLink Cloudflare Edge Tunnel Relay (Worker + Durable Object)
// 1. Create a Worker on Cloudflare Dashboard or Wrangler
// 2. Add a Durable Object binding named POCKETLINK_RELAY -> class PocketLinkRelay
// 3. Add a secret variable named TUNNEL_SECRET (matching the secret in PocketLink Host Setup)

export default {
  async fetch(request, env) {
    const id = env.POCKETLINK_RELAY.idFromName("primary");
    const stub = env.POCKETLINK_RELAY.get(id);
    return stub.fetch(request);
  }
};

export class PocketLinkRelay {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.agent = null;
    this.pending = new Map();
  }

  async fetch(request) {
    const url = new URL(request.url);

    // Outbound authenticated WebSocket from PocketLink Android Host
    if (url.pathname === "/__pocketlink_tunnel") {
      const provided = request.headers.get("X-PocketLink-Tunnel-Key") || "";
      const expected = this.env.TUNNEL_SECRET || "";
      if (!expected || provided !== expected) {
        return new Response("Unauthorized tunnel key", { status: 401 });
      }
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("WebSocket upgrade required", { status: 426 });
      }
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      server.accept();
      if (this.agent) {
        try { this.agent.close(1012, "Replaced by new PocketLink host session"); } catch (_) {}
      }
      this.agent = server;
      server.addEventListener("message", (evt) => this.handleAgentMessage(evt.data));
      server.addEventListener("close", () => {
        if (this.agent === server) this.agent = null;
        for (const [, entry] of this.pending) {
          try { entry.controller.error(new Error("Tunnel disconnected")); } catch (_) {}
        }
        this.pending.clear();
      });
      return new Response(null, { status: 101, webSocket: client });
    }

    if (!this.agent) {
      return new Response(
        JSON.stringify({ detail: "PocketLink host tunnel is offline. Start Cloudflare Tunnel in Host Setup." }),
        { status: 503, headers: { "Content-Type": "application/json" } }
      );
    }

    const reqId = crypto.randomUUID();
    const headers = {};
    for (const [k, v] of request.headers.entries()) {
      headers[k] = v;
    }

    let bodyB64 = "";
    if (request.method !== "GET" && request.method !== "HEAD") {
      const buf = new Uint8Array(await request.arrayBuffer());
      if (buf.byteLength > 0) {
        let bin = "";
        for (let i = 0; i < buf.length; i += 0x8000) {
          bin += String.fromCharCode(...buf.subarray(i, i + 0x8000));
        }
        bodyB64 = btoa(bin);
      }
    }

    return new Promise((resolve) => {
      let streamController = null;
      const readable = new ReadableStream({
        start(c) { streamController = c; },
        cancel() {
          this.pending?.delete(reqId);
        }
      });

      const timer = setTimeout(() => {
        const p = this.pending.get(reqId);
        if (p) {
          this.pending.delete(reqId);
          if (!p.resolved) {
            resolve(new Response(JSON.stringify({ detail: "Tunnel timeout" }), {
              status: 504,
              headers: { "Content-Type": "application/json" }
            }));
          } else {
            try { p.controller.close(); } catch (_) {}
          }
        }
      }, 60000);

      this.pending.set(reqId, {
        resolve,
        resolved: false,
        readable,
        controller: streamController,
        timer
      });

      this.agent.send(JSON.stringify({
        type: "http_request",
        id: reqId,
        method: request.method,
        path: url.pathname + url.search,
        headers,
        body_b64: bodyB64
      }));
    });
  }

  handleAgentMessage(raw) {
    let msg;
    try { msg = JSON.parse(raw); } catch (_) { return; }
    if (msg.type === "ping") {
      try { this.agent?.send(JSON.stringify({ type: "pong" })); } catch (_) {}
      return;
    }
    const entry = this.pending.get(msg.id);
    if (!entry) return;

    if (msg.type === "http_response_start") {
      const resHeaders = new Headers(msg.headers || {});
      entry.resolved = true;
      entry.resolve(new Response(entry.readable, { status: msg.status || 200, headers: resHeaders }));
    } else if (msg.type === "http_response_chunk") {
      const bin = atob(msg.data_b64 || "");
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      try { entry.controller.enqueue(bytes); } catch (_) {}
    } else if (msg.type === "http_response_end") {
      clearTimeout(entry.timer);
      this.pending.delete(msg.id);
      try { entry.controller.close(); } catch (_) {}
    } else if (msg.type === "http_response_error") {
      clearTimeout(entry.timer);
      this.pending.delete(msg.id);
      if (!entry.resolved) {
        entry.resolve(new Response(JSON.stringify({ detail: msg.message || "Upstream error" }), {
          status: msg.status || 502,
          headers: { "Content-Type": "application/json" }
        }));
      } else {
        try { entry.controller.error(new Error(msg.message || "Upstream error")); } catch (_) {}
      }
    }
  }
}
"""

    const val WRANGLER_TOML: String = """name = "pocketlink-relay"
main = "src/index.js"
compatibility_date = "2025-01-01"

[[durable_objects.bindings]]
name = "POCKETLINK_RELAY"
class_name = "PocketLinkRelay"

[[migrations]]
tag = "v1"
new_sqlite_classes = ["PocketLinkRelay"]
"""
}
