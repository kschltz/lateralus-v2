# Web search tool

Lateralus v2 includes a `web_search` tool for the agent loop. It uses a `WebSearchProvider` protocol so network calls are isolated and the tool only depends on the interface.

## Providers

| Provider | Config | Notes |
|----------|--------|-------|
| `:ddg-lite` | none required | DuckDuckGo Lite, no API key, pure-Clojure HTML parsing |
| `:searxng` | `:base-url` | Self-hosted SearXNG instance; JSON format must be enabled in `settings.yml` |

Default provider is `:ddg-lite`.

## Tool arguments

The LLM invokes `web_search` with:

```json
{
  "query": "search terms",
  "fetch?": false,
  "result-count": 10
}
```

- `query` — required, max 400 characters by default.
- `fetch?` — optional, fetch and strip selected result pages to plain text.
- `result-count` — optional, default 10, hard cap 20.

## Guarded output

The tool returns a JSON string with `:provider`, `:query`, and `:results`. Each result contains `:title`, `:url`, and `:snippet`; `:body` is present when `fetch?` is true.

## Security model

All guard defaults are on. Each guard can be disabled individually by setting its key to `false` in the `:lateralus/web-search-tools` config.

- **Query guards** — length cap, control-character stripping, injection-marker block-list.
- **URL guards** — allow-list / block-list support, private IP blocking, loopback blocking, metadata endpoint blocking (e.g. `169.254.169.254`), non-HTTP(S) scheme blocking, protocol-relative URL blocking.
- **Result guards** — HTML stripping, `javascript:` / `data:` URL removal, recursive self-activation detection, exfiltration-pattern detection.
- **Resource guards** — configurable timeout and max response/page size.
- **Policy model** — optional LLM-based snippet classifier; disabled by default.

## Configuration example

```clojure
{:lateralus/web-search-tools
 {:provider :ddg-lite
  :policy-model? false
  :max-query-length 400
  :block-private-ips? true
  :block-metadata-endpoints? true
  :block-injection-markers? true
  :block-self-activation? true
  :block-exfiltration-patterns? true}

 :lateralus/tool-registry
 [#ig/ref :lateralus/file-tools
  #ig/ref :lateralus/self-awareness-tools
  #ig/ref :lateralus/web-search-tools]

 :lateralus/tools-plugin
 {:registry #ig/ref :lateralus/tool-registry}}
```

## Implementation files

- `src/kschltz/agent/tools/web_search.clj` — tool record and registry
- `src/kschltz/agent/tools/web_search/protocol.clj` — provider protocol
- `src/kschltz/agent/tools/web_search/schemas.clj` — Malli schemas
- `src/kschltz/agent/tools/web_search/guards.clj` — defensive guard implementations
- `src/kschltz/agent/tools/web_search/ddg_lite.clj` — DuckDuckGo Lite provider
- `src/kschltz/agent/tools/web_search/searxng.clj` — SearXNG provider
- `src/kschltz/agent/tools/web_search/policy.clj` — optional policy-model layer
