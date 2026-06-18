# Plan: Agentic web search tool with 2026 attack defenses

## Solution approach

Add a new `web_search` tool that the LLM can invoke with a query and an optional `fetch?` flag. The tool delegates network calls to a `WebSearchProvider` protocol. The default implementation uses DuckDuckGo Lite (no API key). A SearXNG implementation is added as an opt-in, self-hosted provider. All search inputs and outputs pass through Malli schemas. Result content is stripped to plain text and validated against URL allow/block-lists. An opt-in policy model can classify snippets. Every defensive guard is individually toggle-able from config.

The work touches the tool registry (`kschltz.agent.plugins.tools`), the system component map (`kschltz.agent.system`), resource configs, and tests. It follows the existing rule that every external/network dependency is protocol-bound and Malli-instrumented.

## Ordered steps

### Step 1 — Define the provider protocol and defensive schemas
**Files:** `src/kschltz/agent/tools/web_search.clj` (new), `src/kschltz/agent/tools/web_search/schemas.clj` (new)

- Define `WebSearchProvider` protocol with `(-search [provider query opts])` and `(-fetch-page [provider url opts])`.
- Define Malli schemas for provider opts, search request, search result, fetch request, and fetch result.
- Define a `SafeUrl` schema and a `SanitizedQuery` schema that encode allow-list/block-list rules and query limits.
- Provide a default guard map for: private IP ranges, loopback, metadata endpoints (`169.254.169.254`), `file://` URLs, protocol-relative URLs, overly long queries, and common injection markers.

**Verification:**
- `clj -M:test -m cognitect.test-runner -n kschltz.agent.tools.web-search-schemas-test` passes after schema tests are added.

### Step 2 — Implement DuckDuckGo Lite provider
**Files:** `src/kschltz/agent/tools/web_search/ddg_lite.clj` (new)

- Implement `WebSearchProvider` using `hato.client` HTTP requests to `https://lite.duckduckgo.com/lite`.
- Parse HTML result snippets with a small, dependency-light parser. The project already has no JSoup; avoid adding it. Use a minimal regex/seq-based extractor limited to known DuckDuckGo Lite result structure, or add `org.jsoup/jsoup` to `deps.edn` if the extraction proves too brittle. Decision to be recorded before adding the dependency.
- Implement `fetch-page` using `hato.client`, enforcing size cap and timeout, then strip HTML to plain text.
- Return only title, URL, and snippet/plain-text body. Never return raw HTML to the LLM.

**Verification:**
- Mock the HTTP client via a protocol test stub and assert the provider produces the expected result shape.
- Add an integration test guarded by a `^:network` metadata tag so it only runs when explicitly enabled.

### Step 3 — Implement SearXNG provider
**Files:** `src/kschltz/agent/tools/web_search/searxng.clj` (new)

- Implement `WebSearchProvider` using SearXNG’s `format=json` endpoint.
- Support configurable `base-url`, `categories`, `language`, and `safesearch`.
- Reuse the same `fetch-page` HTML-stripping code from Step 2.

**Verification:**
- Mock SearXNG JSON responses in unit tests.
- Add an optional `^:network` integration test.

### Step 4 — Build the `WebSearchTool` and register it
**Files:** `src/kschltz/agent/tools/web_search.clj`, `src/kschltz/agent/plugins/tools.clj` (read-only usage), `src/kschltz/agent/system.clj`

- Create a `WebSearchTool` record that satisfies `kschltz.agent.tool/Tool`.
- Input schema: `{:query :string, :fetch? :boolean, :result-count :int}` with defaults.
- Output schema: a JSON string containing a vector of results, each with `:title`, `:url`, `:snippet`, and optional `:body` when `fetch?` is true.
- Add `kschltz.agent.tools.web-search/web-search-registry` helper that returns `{ "web_search" tool }`.
- Add a new Integrant component `:lateralus/web-search-tools` in `kschltz.agent.system` and wire it into `:lateralus/tool-registry`.
- Add a Malli config schema for `:lateralus/web-search-tools`.

**Verification:**
- `clj -M:test -m cognitect.test-runner -n kschltz.agent.tools.web-search-test` passes.
- Running `clj -M:run --config resources/lateralus/llama-local.edn -i` (or equivalent) shows `web_search` in the available tool list if enabled.

### Step 5 — Add defensive guard implementation
**Files:** `src/kschltz/agent/tools/web_search/guards.clj` (new)

- Query sanitizer: length cap, strip control chars, reject known injection markers (`ignore previous`, `system instruction`, `---`, `\x00`, etc.), configurable block-list.
- URL validator: parse with `java.net.URI`, reject private IP ranges, loopback, link-local, metadata endpoints, non-HTTP(S) schemes, and protocol-relative URLs. Allow-list overrides block-list when configured.
- HTML sanitizer: strip tags, remove `javascript:` and `data:` URLs from attributes, truncate to max bytes.
- Exfiltration check: reject snippets that contain HTTP(S) URLs to private hosts or that repeat encoded patterns.
- Recursive self-activation check: reject snippets whose text contains a literal tool-call schema (e.g., `{"name": "web_search"}`).

**Verification:**
- `clj -M:test -m cognitect.test-runner -n kschltz.agent.tools.web-search-guards-test` passes for every attack class.

### Step 6 — Add opt-in policy model layer
**Files:** `src/kschltz/agent/tools/web_search/policy.clj` (new)

- Define a `PolicyModel` protocol or reuse `LlmClient` from `kschltz.agent.llm.client` to run a small classification prompt against the configured LLM.
- When enabled, classify each snippet as `:safe` or `:unsafe`. Unsafe snippets are replaced with a redacted marker and a reason.
- Default disabled; controlled by `:policy-model? true` in `:tools/web-search` config.

**Verification:**
- Unit test with a stub `LlmClient` returning safe/unsafe classifications.
- Test that disabling the policy model bypasses the LLM call entirely.

### Step 7 — Update all resource configs
**Files:** `resources/lateralus/*.edn`

- Add `:lateralus/web-search-tools` and `:tools/web-search` sections to every existing config file under `resources/lateralus/`.
- Show provider selection (`:ddg-lite` or `:searxng`), guard toggles, and opt-out flags.

**Verification:**
- `clj -M:run --config resources/lateralus/<each>.edn --help` (or a config validation script) passes.
- A new test `kschltz.agent.system-config-test` validates that every `.edn` file under `resources/lateralus/` parses and satisfies the Integrant assert-key schemas.

### Step 8 — Gate and finalize
**Files:** `goals/web-search-tool/plan.md`, `goals/web-search-tool/goal.md`

- Run `plannotator annotate goals/web-search-tool/plan.md --gate`.
- If denied, revise from feedback.
- Write `goal.md` with the articulated goal, facts reference, plan reference, and done condition.

**Verification:**
- `plannotator annotate` returns approved.
- `goals/web-search-tool/goal.md` exists and is human-readable.

### Step 9 — Update docs and drop stale/invalid ones ✅
**Files:** `README.md`, `docs/web-search.md`, `docs/architecture.md`, `AGENT_INSTRUCTIONS.md`, `CHANGELOG.md`

- Added a `web_search` section to `README.md` documenting the default provider, on-demand fetch, and defenses.
- Added `docs/web-search.md` with full design, provider config, guard reference, and policy model details.
- Updated `docs/architecture.md` component graph and extension points to include `:lateralus/web-search-tools`.
- Updated `AGENT_INSTRUCTIONS.md` active goal pointer and doc freshness policy reference.
- Added changelog entry under `[Unreleased]`.
- No stale search pages were found; the interceptor-loop design note was already marked superseded.

**Verification:**
- `clj -M:test` passes (268 tests, 717 assertions).
- `README.md` section is discoverable from the table of contents.

## Risks / open questions

- DuckDuckGo Lite HTML is not a stable API. If extraction breaks, the fallback is to switch the default provider to SearXNG or add JSoup for more robust parsing. I’ll record a decision before adding JSoup.
- The policy model uses the configured LLM, which adds latency and cost. It is opt-in and defaults off.
- Native image compatibility: any new dependency must also be added to the `:native` alias in `deps.edn` if it is required at runtime. DuckDuckGo Lite HTML parsing should stay pure Clojure/Java stdlib to keep the native image small.
- The agentic self-activation check is heuristic. It reduces risk but cannot fully prevent a determined adversarial LLM output. Pairing with the policy model and strict URL allow-listing is the recommended defense in depth.
