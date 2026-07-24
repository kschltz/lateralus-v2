# Web tool (`tools.web`)

The `kschltz.agent.tools.web` namespace provides a small set of web-aware
operations for the lateralus agent loop:

| Tool | Input | Output |
|---|---|---|
| `web_search` | `{:query string, :result-count? int}` | JSON envelope with `:provider`, `:query`, `:results` |
| `web_fetch`  | `{:url string, :max-bytes? int}`      | JSON envelope with `:url`, `:title`, `:body`, `:bytes`, `:status` |
| `web_extract` | `{:html string, :selector? string}`  | JSON envelope with `:text`, `:title`, `:selectors-hit`, `:provider` |

All three return JSON strings, matching the `Tool` protocol contract. The
default provider is `:none`, which performs **no network I/O** and returns a
structured disabled envelope for `web_search` and `web_fetch` while still
offering a useful `web_extract` transform over raw HTML. Live web access is
opt-in via `:provider :mojeek`.

## Design constraints

- **No API keys.** No paid service or external SaaS is required for the
default `:none` behavior.
- **No external services by default.** Air-gapped and GraalVM native-image
builds work out of the box.
- **Protocol + Malli boundaries.** Every external/network call goes through
`WebProvider`; inputs/outputs are Malli-schemed.
- **Attack guards.** URL, query, and snippet guards run before and after the
provider call.

## Providers

| Provider | Config | Notes |
|---|---|---|
| `:none` (default) | none required | Zero I/O. `web_extract` still works via a zero-dep regex stripper. |
| `:mojeek` | none required | JVM-only. Uses `hickory` to parse Mojeek's public HTML result pages. **Opt-in** because HTML scraping can break if markup changes. |
| `:ddg` | none required | JVM-only. **Recommended live provider.** Keyless DuckDuckGo search via `html.duckduckgo.com/html`, reached with a browser JA3/JA4 + HTTP/2 fingerprint (`impersonator-okhttp`) so DDG returns real HTML instead of a CAPTCHA page. Default preset is `android` (only preset that completes the TLS handshake with this bctls version); override via `:impersonate` in config. **Opt-in**. Provider owns its own base URL — shared guard defaults must not set `:base-url`. |
| `:searxng` | not shipped | Planned self-hosted follow-up. |

`:mojeek` and `:ddg` are **excluded from the native-image classpath**: `:mojeek`
depends on `hickory` (transitively `jsoup`), and `:ddg` depends on
`impersonator-okhttp` (BouncyCastle bctls + forked OkHttp). Both are kept in the
top-level `:deps` of `deps.edn` only (never `:native :replace-deps`); both
namespaces load behind a guarded `try/require` in `web.clj` / `system.clj` so
native-image builds stay clean and selecting either under native raises a typed
`ex-info` instead of a `ClassNotFoundException`. `resources/lateralus/native.edn`
pins `:provider :none`.

## Configuration

Add `:lateralus/web-tools` to your Integrant config:

```clojure
{:lateralus/web-tools
 {:provider :none            ;; or :mojeek
  :max-query-length 400
  :max-result-count 20
  :max-page-bytes   2097152
  :max-snippet-bytes 16384
  :timeout-ms       15000
  :block-private-ips?        true
  :block-loopback?           true
  :block-metadata-endpoints? true
  :block-file-scheme?        true
  :block-protocol-relative?  true
  :block-injection-markers?  true
  :block-self-activation?     true
  :block-exfiltration-patterns? true
  :url-allow-list []
  :url-block-list  []}

 :lateralus/tool-registry [#ig/ref :lateralus/file-tools
                           #ig/ref :lateralus/web-tools]
 ...}
```

Every guard toggle defaults to `true` and can be disabled individually. See
`src/kschltz/agent/tools/web/guards.clj` for the implementation.

## Defense checklist

- **URL guards** — scheme allow-list (`http`/`https`), private IP block,
loopback block, metadata endpoint block (`169.254.169.254`,
`metadata.google.internal`, ...), port allow-list (`80`, `443`), userinfo and
fragment rejection, protocol-relative URL rejection, allow-list/block-list.
- **Query guards** — length cap, control-char stripping, injection-marker
block-list.
- **Result/snippet guards** — HTML stripping, `javascript:`/`data:text/html`
URL removal, exfiltration-pattern detection, recursive self-activation
detection (tool-call JSON markers in snippets).
- **Phase 3 SSRF / UA / redirect guards** (`ssrf.clj`) — resolve host and pin
IP before connect (block private/loopback/link-local and CGNAT `100.64.0.0/10`),
random per-request User-Agent rotation, safe-redirect-target validation with
up-to-5-hop re-validation (each hop re-resolved + re-pinned), per-process
duplicate-query circuit breaker, snippet-truncation hint.

## Native-image / air-gap story

- `hickory` is **not** in `:native :replace-deps`, so the native binary cannot
accidentally load `:mojeek`.
- `native.edn` ships `:provider :none`.
- Selecting `:mojeek` under native-image raises a typed `ex-info` at init time,
not a `ClassNotFoundException`.

## Testing

Default suite (no network):

```bash
clojure -M:test
```

Live-web e2e (requires outbound internet and sets `LATERALUS_E2E_WEB`):

```bash
LATERALUS_E2E_WEB=true clojure -M:e2e
```

The `:mojeek` unit tests use a canned HTML fixture and a stub `:http-fn` seam
so the provider path is exercised without real HTTP.

## Why `:none` is the default

The previous `web_search` tool used DuckDuckGo Lite as its default provider.
In 2026, DDG started serving CAPTCHAs on programmatic access, which made the
scraping path unreliable. Rather than ship another fragile live-by-default
backend, the revival ships the complete tool surface with `:none` as the
default and `:mojeek` as an explicit opt-in. Operators who need live search can
enable it with a one-line config change; if it breaks, the same one-line change
flips back to `:none`.

## Implementation files

- `src/kschltz/agent/tools/web/protocol.clj` — `WebProvider` protocol
- `src/kschltz/agent/tools/web/schemas.clj` — Malli schemas
- `src/kschltz/agent/tools/web/guards.clj` — defensive guard pipeline
- `src/kschltz/agent/tools/web/ssrf.clj` — Phase 3 SSRF / UA / redirect guards
- `src/kschltz/agent/tools/web/none.clj` — `:none` provider
- `src/kschltz/agent/tools/web/mojeek.clj` — `:mojeek` provider
- `src/kschltz/agent/tools/web/ddg.clj` — `:ddg` provider (impersonator TLS fingerprint)
- `src/kschltz/agent/tools/web/web.clj` — `WebSearchTool`, `WebFetchTool`, `WebExtractTool`, registry factory
- `test/kschltz/agent/tools/web/*_test.clj` — unit tests
- `test/kschltz/agent/tools/web/web_e2e_test.clj` — `^:e2e` live web tests
