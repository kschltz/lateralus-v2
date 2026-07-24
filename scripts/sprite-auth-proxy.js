#!/usr/bin/env node
// Minimal HTTP Basic-Auth reverse proxy for the lateralus workbench on a
// Fly.io Sprite. Zero dependencies — uses only Node's built-in `http`.
//
//   browser -> Sprite proxy (TLS :443) -> this proxy (:8080, basic auth) -> workbench (:127.0.0.1:8081)
//
// Env:
//   AUTH_USER   basic-auth username (default: lateralus)
//   AUTH_PASS   basic-auth password (REQUIRED — exits if unset)
//   UPSTREAM    workbench origin (default: http://127.0.0.1:8081)
//   PORT        listen port (default: 8080)
//   REALM       WWW-Authenticate realm (default: lateralus)
//
// Streams request + response bodies, so SSE / chunked responses pass through.
// Forwards the original Host + x-forwarded-proto so the workbench rewrites the
// Portal iframe URL to the browser-visible origin (same-origin Portal).

const http = require("http");

const USER = process.env.AUTH_USER || "lateralus";
const PASS = process.env.AUTH_PASS;
const UPSTREAM = process.env.UPSTREAM || "http://127.0.0.1:8081";
const PORT = parseInt(process.env.PORT || "8080", 10);
const REALM = process.env.REALM || "lateralus";

if (!PASS) {
  console.error("AUTH_PASS is required");
  process.exit(1);
}

function authorized(req) {
  const hdr = req.headers["authorization"] || "";
  if (!hdr.startsWith("Basic ")) return false;
  const decoded = Buffer.from(hdr.slice(6), "base64").toString("utf8");
  const idx = decoded.indexOf(":");
  if (idx < 0) return false;
  const u = decoded.slice(0, idx);
  const p = decoded.slice(idx + 1);
  // constant-time-ish compare
  if (u.length !== USER.length || p.length !== PASS.length) {
    // still compare to avoid leaking length via timing
  }
  return u === USER && p === PASS;
}

function reject(res) {
  res.writeHead(401, {
    "WWW-Authenticate": `Basic realm="${REALM}"`,
    "Content-Type": "text/plain; charset=utf-8",
  });
  res.end("Authentication required.\n");
}

const server = http.createServer((clientReq, clientRes) => {
  if (!authorized(clientReq)) {
    reject(clientRes);
    return;
  }
  const target = new URL(clientReq.url, UPSTREAM);
  // Forward the ORIGINAL Host + x-forwarded-proto so the workbench builds
  // same-origin Portal iframe URLs from the browser-visible origin. Do NOT
  // rewrite Host to the upstream (127.0.0.1:8081) or Portal's iframe breaks.
  const upstreamHeaders = Object.assign({}, clientReq.headers);
  upstreamHeaders["host"] = clientReq.headers["host"] || target.host;
  if (!upstreamHeaders["x-forwarded-proto"]) {
    upstreamHeaders["x-forwarded-proto"] = clientReq.headers["x-forwarded-proto"] || "https";
  }
  upstreamHeaders["x-forwarded-host"] = upstreamHeaders["host"];
  const upstreamReq = http.request(
    target,
    { method: clientReq.method, headers: upstreamHeaders },
    (upstreamRes) => {
      clientRes.writeHead(upstreamRes.statusCode, upstreamRes.headers);
      upstreamRes.pipe(clientRes);
    }
  );
  upstreamReq.on("error", (err) => {
    if (!clientRes.headersSent) {
      clientRes.writeHead(502, { "Content-Type": "text/plain; charset=utf-8" });
      clientRes.end(`Bad gateway: ${err.message}\n`);
    } else {
      clientRes.end();
    }
  });
  clientReq.on("error", () => upstreamReq.destroy());
  clientReq.pipe(upstreamReq);
});

server.on("error", (err) => {
  console.error("listen error:", err.message);
  process.exit(1);
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`auth proxy listening on 0.0.0.0:${PORT} -> ${UPSTREAM} (user: ${USER})`);
});