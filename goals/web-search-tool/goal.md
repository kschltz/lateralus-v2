# Goal: Agentic web search tool with 2026 attack defenses

Add a free, no-API-key web search tool to lateralus-v2 that the agent can invoke with a query and an optional on-demand page fetch. The tool is protected against the common LLM-driven attacks of 2026 (prompt injection, SSRF, recursive self-activation, data exfiltration) through schema validation, URL allow-listing, query sanitization, HTML stripping, timeouts/size caps, and an opt-in policy-model classifier. Every guard is individually opt-out-able from config.

## Shared understanding

See `facts.md` for the accepted facts.

## Execution plan

See `plan.md` for the ordered implementation steps, verification commands, and risks.

## Done condition

- `web_search` tool is registered in the default tool registry when enabled.
- DuckDuckGo Lite provider works without an API key and returns sanitized snippets.
- SearXNG provider is available as an opt-in, self-hosted alternative.
- Optional `fetch?` flag returns stripped plain-text page bodies.
- All guards are covered by passing tests, and each guard can be disabled in config.
- Every existing `resources/lateralus/*.edn` config shows a `:tools/web-search` example.
- The Plannotator plan gate is approved.
- Documentation is updated and stale/invalid docs are removed or redirected.
