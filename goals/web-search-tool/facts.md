# Facts: Agentic web search tool with 2026 attack defenses

1. The default web search tool uses DuckDuckGo Lite (no API key required). SearXNG is supported as an opt-in, self-hosted provider.
2. The tool implementation is split behind a `WebSearchProvider` protocol. Network calls are isolated in protocol implementations, and the tool only depends on the protocol interface.
3. The tool's input and output schemas are declared with Malli and enforced by the existing `invoke-tool` machinery in `kschltz.agent.tool`.
4. Query length is capped, control characters and common injection markers are stripped or rejected, and overly long queries return a model-visible error.
5. All result URLs and optional page-fetch URLs are validated against an allow-list/block-list. Private IP ranges, loopback, metadata endpoints (e.g., `169.254.169.254`), and `file://`/scheme URLs are rejected by default.
6. HTML returned by the provider or fetched pages is stripped to plain text; active content, inline scripts, `javascript:` links, and protocol-relative URLs are removed before the result is handed to the LLM.
7. Searches and optional fetches have configurable timeouts and maximum response sizes; exceeding either returns a controlled error.
8. An opt-in policy-model layer classifies each result snippet as safe/unsafe before returning it. When disabled, rule-based guards still apply.
9. Tests cover direct injection markers, indirect injection markers in queries, SSRF to private/metadata endpoints, recursive self-activation attempts, exfiltration patterns, and oversized queries. Each protection can be individually disabled in config.
10. Every existing Lateralus config file under `resources/lateralus/` includes a documented `:tools/web-search` section showing provider, guard, and opt-out settings.
11. The tool is registered through `kschltz.agent.plugins.tools` and appears in the default tool registry when enabled in config.
12. The tool accepts an optional `fetch?` argument per call so the agent can request stripped page text for specific results without fetching pages by default.
