# How Unsloth Studio does web search (code-level)

A synthesis of the Unsloth Studio web-search implementation, drawn from five source-extraction passes over the `unslothai/unsloth` repository (main branch, June 2026). Sources quoted: `studio/backend/core/inference/tools.py`, `studio/backend/core/inference/_html_to_md.py`, `studio/backend/core/inference/external_provider.py`, `studio/backend/core/inference/tool_call_parser.py`, `studio/backend/core/inference/tool_loop_controller.py`, `studio/backend/routes/inference.py`, `studio/backend/core/inference/providers.py`, plus the Studio frontend (`tool-ui-web-search.tsx`, chat-adapter, provider-support predicates). Where a subagent did not capture an exact line number, I say so rather than invent one.

Unsloth Studio's web search is **two architecturally distinct code paths behind one tool name**:

1. **The local `web_search` tool** — Unsloth's own Python sandbox: DuckDuckGo snippet search plus a hand-rolled URL fetcher with hard SSRF hardening. Runs when the active model is a locally-served GGUF/Safetensors model.
2. **Server-side `web_search`** — delegated to external providers (OpenAI, Anthropic, OpenRouter, Kimi, Gemini) and re-expressed into one unified `_toolEvent` SSE shape so the Studio frontend can treat all of them identically.

Mistral is conspicuously absent from the server-side list — that absence is itself a code-level finding, discussed in §4.

---

## 1. Architecture overview

```
                 ┌─────────────────────────────────────────────────────┐
                 │   Studio frontend (chat.tsx, tool-ui-web-search)   │
                 │   enable_tools + enabled_tools: ["web_search"]     │
                 │   parseSearchResults / isSafeHttpUrl               │
                 └─────────────────────────────────────────────────────┘
                                       │  POST /v1/chat/completions (SSE)
                                       ▼
        ┌──────────────────────────────────────────────────────────────┐
        │  studio/backend/routes/inference.py                          │
        │  • date injection + behavioral nudge                         │
        │  • XML-strip regex on every assistant message + per token    │
        │  • tool_status SSE ("Searching: …" / "Reading: …")           │
        └──────────────────────────────────────────────────────────────┘
                                       │
              ┌────────────────────────┴───────────────────────────┐
              ▼                                                     ▼
   LOCAL model path                                   EXTERNAL provider path
   (GGUF / Safetensors)                              (ExternalProviderClient)
   tool_loop_controller.py                           external_provider.py
   execute_tool("web_search", args)                  stream_chat_completion()
              │                                                     │
              ▼                    ┌───────────────┬───────────────┬───────────────┐
   tools.py:                       ▼               ▼               ▼               ▼
   _web_search()                 OpenAI        Anthropic      OpenRouter       Kimi
   ├─ query mode: DDGS().text()   /responses    /messages      /chat/compl     /chat/compl ×2
   └─ url mode:   _fetch_page_text ────── all emit unified _toolEvent {tool_start, tool_end}
       ├─ _validate_and_resolve_host
       ├─ _PinnedHTTPSConnection (SNI vs pinned IP)
       ├─ _NoRedirect + 5-hop manual loop
       └─ html_to_markdown → 16k char cap
```

The contract on both sides of the fork is the same: a `web_search` tool call returns a single string, and the backend emits `tool_start` / `tool_end` SSE events carrying that string. The frontend has one parser (`parseSearchResults`) that consumes the `Title: …\nURL: …\nSnippet: …` block shape no matter which path produced it.

---

## 2. The local tool (`tools.py`)

### 2.1 OpenAI function-calling schema

`WEB_SEARCH_TOOL` (in `studio/backend/core/inference/tools.py`, around lines 430–450) declares the tool to the model:

```python
WEB_SEARCH_TOOL = {
    "type": "function",
    "function": {
        "name": "web_search",
        "description": (
            "Search the web and fetch page content. Returns snippets for all results. "
            "Use the url parameter to fetch full page text from a specific URL."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "The search query"},
                "url":   {"type": "string",
                          "description": "A URL to fetch full page content from (instead of searching). "
                                          "Use this to read a page found in search results."},
            },
            "required": [],
        },
    },
}
```

Both params are optional (`"required": []`). The dispatch in `execute_tool()` (~line 545) routes on the tool name:

```python
if name == "web_search":
    return _web_search(arguments.get("query", ""), url=arguments.get("url"), timeout=effective_timeout)
```

### 2.2 `_web_search()` — query mode vs url mode

`_web_search()` (~lines 670–710) is a single dispatch with two modes gated by whether `url` is non-empty:

```python
def _web_search(query: str, max_results: int = 5, timeout: int = _EXEC_TIMEOUT, url: str | None = None) -> str:
    """Search the web using DuckDuckGo and return formatted results."""
    if url and url.strip():
        fetch_timeout = 60 if timeout is None else min(timeout, 60)
        return _fetch_page_text(url.strip(), timeout=fetch_timeout)

    if not query or not query.strip():
        return "No query provided."
    try:
        from ddgs import DDGS
        results = DDGS(timeout=timeout).text(query, max_results=max_results)
        if not results:
            return "No results found."
        parts = []
        for r in results:
            parts.append(
                f"Title: {r.get('title', '')}\n"
                f"URL: {r.get('href', '')}\n"
                f"Snippet: {r.get('body', '')}"
            )
        text = "\n\n---\n\n".join(parts)
        text += (
            "\n\n---\n\nIMPORTANT: These are only short snippets. "
            "To get the full page content, call web_search with "
            'the url parameter (e.g. {"url": " "}).'
        )
        return text
    except Exception as e:
        return f"Search failed: {e}"
```

The "search" backend is the **`ddgs` library** (deedy5/duckduckgo_search, renamed `ddgs`), an *unofficial* client that scrapes DuckDuckGo's HTML/Lite endpoints — **not** a paid JSON API. The deprecated `links.duckduckgo.com/d.js` JSON endpoint was removed in ddgs v9.x (commit `3ee8e08`, Dec 2024); the current `text()` method with `backend="auto"` tries `html.duckduckgo.com/html`, `lite.duckduckgo.com/lite`, and `ecosia` in randomized order, using `primp` (TLS-fingerprinting HTTP client) and rotating UAs via `fake-useragent`.

Two notable hard-coded knobs the model cannot change through the tool schema:

- `max_results = 5` — fixed at the function default, not surfaced in the JSON schema.
- `_EXEC_TIMEOUT` — passed straight into `DDGS(timeout=timeout)` (default 300 s / 5 min).

The output shape is the canonical `Title: / URL: / Snippet:` triple per result, joined by `"\n\n---\n\n"`, with an **appended truncation hint** that nudges the model toward the `url` mode:

```
IMPORTANT: These are only short snippets. To get the full page content, call web_search with the url parameter (e.g. {"url": " "}).
```

That hint is placed *inside the same return string* the model consumes, right after the snippets — a cheap, effective way to bias the agent toward doing a second, deeper fetch rather than reasoning over shallow snippets.

### 2.3 URL-fetch mode: `_fetch_page_text()`

When `url` is non-empty, `_fetch_page_text(url, max_chars=_MAX_PAGE_CHARS, timeout=30)` (~lines 600–660) takes over. Its pipeline:

1. **Scheme + host check.**
   ```python
   if parsed.scheme not in ("http", "https"):
       return f"Blocked: only http/https URLs are allowed (got {parsed.scheme!r})."
   if not parsed.hostname:
       return "Blocked: URL is missing a hostname."
   ```
2. **SSRF validation + IP pinning** (§2.4).
3. **Fetch with a homegrown redirect loop (up to 5 hops)** — the URL is rewritten each hop so the TCP connection targets the *validated IP*, not the hostname:
   ```python
   for _hop in range(5):
       cp = urlparse(current_url)
       ip_str = f"[{pinned_ip}]" if ":" in pinned_ip else pinned_ip
       ip_netloc = f"{ip_str}:{cp.port}" if cp.port else ip_str
       pinned_url = urlunparse(cp._replace(netloc=ip_netloc))
       req = urllib.request.Request(pinned_url, headers={"User-Agent": ua, "Host": current_host})
   ```
4. **Raw byte cap:**
   ```python
   _MAX_FETCH_BYTES = 512 * 1024   # line ~610
   raw_bytes = resp.read(max_bytes)
   ```
5. **Charset decode** (from `Content-Type`, fallback utf-8):
   ```python
   charset = resp.headers.get_content_charset() or "utf-8"
   raw_html = raw_bytes.decode(charset, errors="replace")
   ```
6. **HTML → Markdown** via a *local* module (not the popular `html2text` package):
   ```python
   from ._html_to_md import html_to_markdown
   text = html_to_markdown(raw_html)
   ```
7. **Post-conversion cap** — 16 000 chars of Markdown, with a truncation marker carrying the original char count:
   ```python
   _MAX_PAGE_CHARS = 16000   # line ~605
   if len(text) > max_chars:
       text = text[:max_chars] + f"\n\n... (truncated, {len(text)} chars total)"
   ```

The companion `_html_to_md.py` is a ~250-line `html.parser.HTMLParser` subclass (`_MarkdownRenderer`). It maps headings → `#`/`##`/…, links → `[text](url)`, bold/italic → `**`/`*`, lists → `1.`/`*`, tables → pipe-delimited with separator row, blockquotes → `> `, `<pre>` → fenced ` ``` `, inline code → backticks. It strips `script`, `style`, `head`, `noscript`, `svg`, `math`, `nav`, `footer`, decodes entities via `html.unescape()`, and exposes a `flush_pending()` that gracefully recovers from the truncated HTML produced by the 512 KB byte cap. Unsloth deliberately dropped the external `html2text` dependency in favor of this stdlib-only renderer.

### 2.4 SSRF hardening — the most engineered part

Four cooperating pieces:

**`_validate_and_resolve_host(hostname, port)`** (~lines 630–660) resolves the hostname *before* any connection is opened (closing the time-of-check / time-of-use window) and returns the resolved IP so the caller can pin it:

```python
def _validate_and_resolve_host(hostname: str, port: int) -> tuple[bool, str, str]:
    import ipaddress, socket
    try:
        infos = socket.getaddrinfo(hostname, port, type=socket.SOCK_STREAM)
    except OSError as e:
        return False, f"Failed to resolve host: {e}", ""
    if not infos:
        return False, f"Failed to resolve host: no addresses for {hostname!r}", ""
    for *_, sockaddr in infos:
        ip = ipaddress.ip_address(sockaddr[0])
        if (not ip.is_global or ip.is_private or ip.is_loopback or ip.is_link_local
            or ip.is_multicast or ip.is_reserved or ip.is_unspecified):
            return False, f"Blocked: refusing to fetch non-public address {ip}.", ""
    first_ip = infos[0][4][0]
    return True, "", first_ip
```

`not ip.is_global` is the catch-all that also blocks CGNAT (`100.64.0.0/10`), benchmark, and documentation ranges; the explicit `is_private`/`is_loopback`/… checks are belt-and-suspenders for older Python stdlib corner cases.

**IP pinning (`_PinnedHTTPSConnection`)** separates the TCP destination from the TLS identity:

```python
class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    """TCP connects to the pinned IP, TLS uses sni_hostname."""
    def __init__(self, host: str, *, sni_hostname: str, **kwargs):
        super().__init__(host, **kwargs)
        self._sni_hostname = sni_hostname
    def connect(self):
        http.client.HTTPConnection.connect(self)   # TCP to pinned IP
        self.sock = self._context.wrap_socket(self.sock, server_hostname=self._sni_hostname)
```

So DNS rebinding between resolution and connection is useless — the socket opens to the IP that *was* validated, while SNI and cert verification still reference the original hostname so legitimate HTTPS keeps working. The `Host` header is also set explicitly: `headers={"User-Agent": ua, "Host": current_host}`.

**`_NoRedirect`** disables urllib's automatic redirect handling:

```python
class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None
```

Every redirect (301/302/307/308) is then handled manually inside the 5-hop loop, and **each new URL is re-scheme-checked, re-host-checked, re-resolved, and re-pinned**. A redirect to a private IP returns:

```python
"Blocked: redirect target is not a valid http/https URL."
```

**`_SNIHTTPSHandler`** glues the pinned connection into urllib's opener so the whole stack uses the same TLS context:

```python
class _SNIHTTPSHandler(urllib.request.HTTPSHandler):
    def __init__(self, hostname: str):
        super().__init__(context=_tls_ctx)
        self._sni_hostname = hostname
    def https_open(self, req):
        return self.do_open(self._sni_connection, req)
    def _sni_connection(self, host, **kwargs):
        kwargs["context"] = _tls_ctx
        return _PinnedHTTPSConnection(host, sni_hostname=self._sni_hostname, **kwargs)
```

The opener is rebuilt per request: `urllib.request.build_opener(_NoRedirect, _SNIHTTPSHandler(current_host))`.

### 2.5 Error vocabulary

The tool contract is `str -> str`, so every failure mode is a *prefixed string* the agentic loop can pattern-match on:

| Prefix | Meaning | Example |
|---|---|---|
| `"Blocked: "` | Fetch-time SSRF / scheme / host / redirect rejection | `"Blocked: only http/https URLs are allowed (got 'ftp')."` |
| `"Search failed: "` | Any exception from `DDGS().text()` | `"Search failed: Connection timeout"` |
| `"Failed to fetch URL: "` | HTTP error status, generic fetch exception, or too-many-redirects | `"Failed to fetch URL: HTTP 403 Forbidden"` |
| `"Failed to resolve host: "` | DNS resolution failure | — |
| `"No query provided."` | Both `query` and `url` empty | — |
| `"No results found."` | DDG returned `[]` | — |
| `"(page returned no readable text)"` | HTML→Markdown produced empty text | — |

Those prefixes matter beyond UX: the **error-recovery nudge** (§3.3) keys off `TOOL_ERROR_PREFIXES` containing exactly these.

---

## 3. The agentic loop

When a locally-served model is running, web search happens *inside* Unsloth's own tool loop, not the model's. Five mechanisms are worth quoting.

### 3.1 Date injection + behavioral nudge

`_build_tool_action_nudge(tools, model_name)` in `studio/backend/routes/inference.py` produces a date-prefixed string from a base tip plus per-tool tips. The web tip branches on model size: a compact one-sentence version for `<9B` models and an expanded three-sentence version (including "rephrase or fetch URL directly") for `≥9B` models. When the schema has no relevant tool, it returns an empty string. The exact web-tip text was not captured verbatim by the subagent; this is a gap — the *structure* (date prefix + size-branched body) is confirmed, the literal sentences are not.

### 3.2 XML strip regexes

A single regex `_TOOL_XML_RE` in `routes/inference.py` matches both `<tool_call>...` and `<function=NAME>…</function>` forms (with hyphens tolerated for MCP tool names), plus orphan opens/closes and tail-anchored close fragments. It is applied **pre-loop on every history assistant message** *and* **per-token in the SSE stream** via a cleaned-cumulative diff. This is the garbage-collector that lets small GGUF models emit sloppy tool-call XML without the loop mis-parsing it.

### 3.3 Error-recovery nudge

`TOOL_ERROR_NUDGE` (imported from `core/inference/tool_call_parser.py`) is appended to the `role="tool"` message by `ToolCallCompletion.model_message()` in `tool_loop_controller.py` when the result starts with one of the `TOOL_ERROR_PREFIXES` (`Error`, `Search failed`, `Blocked`, …). `strip_result_for_model` strips frontend-only sentinels first. Concretely, a `web_search` that returned `"Search failed: Connection timeout"` gets `TOOL_ERROR_NUDGE` concatenated onto the tool message the model sees next, so the model is steered toward retrying or rephrasing rather than treating the failure as final.

### 3.4 Max iterations + duplicate-call detection

- `max_tool_calls_per_message` on `ChatCompletionRequest`, **default 25**, wired through both GGUF and Safetensors inference paths. That is the hard ceiling on how many tool turns a single user message can drive.
- Layered on top: `_MAX_REPROMPTS = 1` for "plan-without-action" nudges (the model describing what it will do instead of emitting a tool call).
- **Duplicate detection** uses `canonical_tool_call_key = f"{tool_name}:{json.dumps(args, sort_keys=True)}"` tracked in a `_successful_keys` set. Once a key has produced two duplicate no-ops, `force_final_answer` flips and the loop exits into final synthesis. This is the circuit breaker for models that get stuck re-issuing the same query.

### 3.5 Searching vs Reading SSE differentiation

`status_for_tool()` in `tool_loop_controller.py` is the single source of truth for the per-tool status line:

- `web_search` **with** a `url` arg → `"Reading: {hostname}"` (with `www.` stripped).
- `web_search` **without** `url` → `"Searching: {query}"`.

The route emits this as a dedicated SSE event:

```json
{"type": "tool_status", "content": "Searching: rust async runtime internals"}
```

So the frontend can show a meaningful, live status string ("Searching: …" vs "Reading: blog.example.com") that distinguishes the two tool modes even though they share one tool name.

> Bonus: `web_fetch` (Anthropic's server-tool name) is aliased onto the local `web_search` tool with the `url` parameter — same backend, different arg shape. This is how `web_fetch_20250910` / `web_fetch_20260209` get unified into the local GGUF loop.

---

## 4. Per-provider server-side search

When the active model is served by an external provider, Unsloth does **not** run its own Python fetcher. It translates each provider's native web-search mechanism into one unified SSE shape and lets the provider's API do the crawling.

### 4.0 The unified `_toolEvent` shape

Two helpers centralize every provider's emission:

```python
# tags synthetic server-side cards so the frontend can tell them from user-declared tools
_SERVER_SIDE_BUILTIN_TOOL_NAMES = frozenset({"web_search", "web_fetch", "code_execution", "image_generation"})
def _stamp_server_tool_marker(payload):
    if payload.get("type") != "tool_start": return
    if payload.get("tool_name") not in _SERVER_SIDE_BUILTIN_TOOL_NAMES: return
    args = payload.get("arguments") or {}
    args["_server_tool"] = True
    payload["arguments"] = args
```

```python
def _emit_tool_event(payload):
    _stamp_server_tool_marker(payload)
    chunk = {
        "id": completion_id,
        "object": "chat.completion.chunk",
        "choices": [{"index": 0, "delta": {}, "finish_reason": None}],
        "_toolEvent": payload,            # rides OUTSIDE choices, alongside the OpenAI chunk
    }
    return f"data: {_json.dumps(chunk)}"
```

`_toolEvent` payloads used by web search:

- `{"type": "tool_start", "tool_name": "web_search", "tool_call_id": <id>, "arguments": {"query": …}}`
- `{"type": "tool_end", "tool_call_id": <id>, "result": "Title: …\nURL: …\nSnippet: …\n---\n…"}`

The result string is shaped exactly like the local tool's output so the frontend's `parseSourcesFromResult` works uniformly.

### 4.1 Top-level dispatch (`stream_chat_completion`, ~line 818)

Routing decides which path each provider takes. The "hosted builtins allowed" gate is identical everywhere — a pinned user function tool (`tool_choice={type:function, function:{name}}`) or `tool_choice="none"` **suppresses** server-side web search (privacy + billing):

```python
if self.provider_type == "gemini":     ... _stream_gemini ...
if self.provider_type in {"anthropic", ...}:  _stream_anthropic ...   # all non-OAI-compat
if self.provider_type == "openai":     ... _stream_openai_responses ...   # /v1/responses only
if (self.provider_type == "kimi" and not tool_choice_disabled
    and not _kimi_tool_choice_forced_function
    and enabled_tools and "web_search" in enabled_tools):
    _stream_kimi_web_search(messages, model, max_tokens); return
# openrouter / mistral / vllm / custom fall through to OpenAI /chat/completions
```

### 4.2 OpenAI — `_stream_openai_responses` (~line 4502)

OpenAI moved flagship models off `/chat/completions` (gpt-5.x returns "This is not a chat model" 404), so **all** OpenAI traffic goes to `/v1/responses`. Web search is the hosted built-in `{"type": "web_search"}`:

```python
_responses_hosted_builtins_allowed = (
    not _responses_tool_choice_none and not _responses_tool_choice_forced_function
)
if (_responses_hosted_builtins_allowed and enabled_tools and "web_search" in enabled_tools):
    tools_array.append({"type": "web_search"})
url = f"{self.base_url}/responses"   # NOT /chat/completions
body["tools"] = tools_array
```

OpenAI emits `response.output_item.added` / `response.output_item.done` events for items of type `web_search_call`. Per-call start/end is emitted at `output_item.done`:

```python
# response.output_item.done, item.type == "web_search_call"
item_id = item.get("id", "") or f"ws_{len(web_search_calls)}"
action = item.get("action")
query = action.get("query", "") if isinstance(action, dict) else ""
web_search_calls[item_id] = {"query": query}
yield _emit_tool_event({"type": "tool_start", "tool_name": "web_search",
                        "tool_call_id": item_id,
                        "arguments": ({"query": query} if query else {})})
yield _emit_tool_event({"type": "tool_end", "tool_call_id": item_id,
                        "result": f"Searching: {query}" if query else ""})
```

**Citations are not per-call.** They ride on text deltas as inline `annotations` and as standalone `response.output_text.annotation.added` events, aggregated into one shared list:

```python
web_search_calls: dict[str, dict[str, Any]] = {}
all_url_citations: list[dict[str, Any]] = []
```

`_record_url_citation` dedupes by URL and collects every `source_id` alias onto a single entry (the same URL can surface under multiple `source_id`s across API revisions):

```python
def _record_url_citation(payload):
    if payload.get("type") != "url_citation": return
    url = payload.get("url", "")
    source_id = payload.get("source_id") or payload.get("id") or payload.get("locator") or ""
    for c in all_url_citations:
        if c["url"] != url: continue
        if source_id:
            aliases = c.setdefault("source_ids", [])
            if source_id not in aliases: aliases.append(source_id)
        return
    all_url_citations.append({"url": url, "title": payload.get("title") or url,
                              "snippet": payload.get("snippet") or payload.get("quote") or "",
                              "source_ids": [source_id] if source_id else []})
```

**Inline citation markers** use private-use Unicode bytes (`\ue200cite\ue202SOURCE_ID\ue201`) embedded *inside* the assistant text. `_replace_openai_citation_markers` rewrites them to `[[N]](url)` markdown; because the marker can straddle SSE chunks and reference a `source_id` whose `annotation.added` hasn't arrived yet, `_rewrite_citation_markers_partial` returns `(text, has_unresolved)` — unresolved segments are buffered in `pending_citation_segments` and retried on the next annotation event, then force-flushed at `[DONE]` / `response.completed` with leftover markers stripped.

**The aggregation hack:** at `response.completed`, the **last** `web_search_call`'s `tool_end` is re-emitted with the full aggregated citation list, overwriting the per-call "Searching: …" placeholder:

```python
# response.completed — web_search state. Citations are emitted on text deltas
# (not per call), so the aggregate list is shared and applied to the LAST
# web_search tool_end (parseSourcesFromResult flatmaps every call, one non-empty is enough).
if web_search_calls and all_url_citations:
    last_id = list(web_search_calls.keys())[-1]
    blocks = []
    for cit in all_url_citations:
        line = f"Title: {cit['title']}\nURL: {cit['url']}"
        if cit.get("snippet"): line += f"\nSnippet: {cit['snippet']}"
        blocks.append(line)
    yield _emit_tool_event({"type": "tool_end", "tool_call_id": last_id,
                            "result": "\n---\n".join(blocks)})
```

The same backfill is mirrored at `response.incomplete` (~line 5810) so truncated streams still surface their citations.

### 4.3 Anthropic — `_stream_anthropic` (~line 1627)

Anthropic is the cleanest provider: citations come **per-call** inside the result block, so no aggregation hack is needed. Date-pinned tool type per model family:

```python
def _anthropic_web_search_version(model):
    return ("web_search_20260209" if model.startswith(_ANTHROPIC_NEW_WEB_PREFIXES)
            else "web_search_20250305")

# tool attachment
if _anthropic_hosted_builtins_allowed and enabled_tools and "web_search" in enabled_tools:
    anthropic_tools = list(body.get("tools") or [])
    anthropic_tools.append({"type": _anthropic_web_search_version(model),
                            "name": "web_search", "max_uses": 5})
    body["tools"] = anthropic_tools
url = f"{self.base_url}/messages"
```

Anthropic streams a `server_tool_use` content block (the call, with the query arriving as `input_json_delta`) followed by a separate `web_search_tool_result` block. State is held in two slots:

```python
current_server_tool_use: Optional[dict] = None   # open call -> query buffer
current_result_block:    Optional[dict] = None  # open result -> results list
web_search_calls: dict[str, dict] = {}           # id -> {query, results}
```

`content_block_start` opens each:

```python
if block_type == "server_tool_use" and block_name == "web_search":
    tool_use_id = content_block.get("id", "") or f"ws_{len(web_search_calls)}"
    current_server_tool_use = {"id": tool_use_id, "buffer": ""}
    web_search_calls[tool_use_id] = {"query": "", "results": []}
elif block_type == "web_search_tool_result":
    tool_use_id = content_block.get("tool_use_id", "")
    content = content_block.get("content") or []
    current_result_block = {"tool_use_id": tool_use_id,
                             "results": list(content) if isinstance(content, list) else []}
```

`input_json_delta` accumulates the query JSON:

```python
elif delta_type == "input_json_delta":
    partial = delta.get("partial_json", "")
    if current_server_tool_use is not None:
        current_server_tool_use["buffer"] += partial
```

`content_block_stop` is where each side finalizes — closing `server_tool_use` parses the buffered JSON and emits `tool_start`; closing `web_search_tool_result` emits `tool_end` with formatted results:

```python
# end of server_tool_use -> tool_start
if current_server_tool_use is not None:
    buffer = current_server_tool_use["buffer"]
    query = ""
    if buffer:
        try:
            parsed = _json.loads(buffer)
            q = parsed.get("query", "") if isinstance(parsed, dict) else ""
            if isinstance(q, str): query = q
        except Exception: query = ""
    tool_use_id = current_server_tool_use["id"]
    if tool_use_id in web_search_calls:
        web_search_calls[tool_use_id]["query"] = query
    yield _emit_tool_event({"type": "tool_start", "tool_name": "web_search",
                            "tool_call_id": tool_use_id,
                            "arguments": ({"query": query} if query else {})})
    current_server_tool_use = None

# end of web_search_tool_result -> tool_end
elif current_result_block is not None:
    tool_use_id = current_result_block["tool_use_id"]
    results = current_result_block["results"]
    if tool_use_id in web_search_calls:
        web_search_calls[tool_use_id]["results"] = results
    result_text = _format_web_search_results(results)
    yield _emit_tool_event({"type": "tool_end", "tool_call_id": tool_use_id,
                            "result": (result_text or "(search complete)")})
    current_result_block = None
```

`_format_web_search_results` keeps only `web_search_result` entries:

```python
def _format_web_search_results(results):
    blocks = []
    for r in results:
        if not isinstance(r, dict): continue
        if r.get("type") != "web_search_result": continue
        url = r.get("url", ""); title = r.get("title") or url
        if not url: continue
        blocks.append(f"Title: {title}\nURL: {url}")
    return "\n---\n".join(blocks)
```

(Anthropic's separate `web_fetch` and `code_execution` server-side tools use the same `server_tool_use → *_tool_result` block pattern, each tracked in its own slot — `current_web_fetch_use`, `current_code_exec_use` — so concurrent pills don't collide. Document citations via `citations_delta` are a *different* mechanism for RAG/context, not web search.)

### 4.4 OpenRouter — inline in `stream_chat_completion` (~line 999)

OpenRouter's web search is the `plugins` field, **not** the deprecated `:online` model-id suffix (`:online` fails on meta-router model ids; the plugin works on every id):

```python
# OpenRouter web plugin works on every model id including meta-routers (unlike `:online`).
# Forced-function tool_choice suppresses it, matching Gemini/Anthropic.
# https://openrouter.ai/docs/guides/features/plugins/web-search
if (not tool_choice_disabled and not _or_tool_choice_forced_function
    and enabled_tools and "web_search" in enabled_tools):
    plugins = list(body.get("plugins") or [])
    if not any(isinstance(p, dict) and p.get("id") == "web" for p in plugins):
        plugins.append({"id": "web"})
    body["plugins"] = plugins
```

OpenRouter emits **no** `web_search_call` lifecycle events — citations only arrive as `url_citation` annotations on chunks. So Unsloth synthesizes a single fixed tool card:

```python
web_search_active = (self.provider_type == "openrouter" and not tool_choice_disabled
                     and not _or_tool_choice_forced_function
                     and bool(enabled_tools) and "web_search" in (enabled_tools or []))
web_search_tool_id = "openrouter_web_search"   # fixed single id
web_search_citations: list[dict[str, str]] = []
web_search_tool_started = False
web_search_tool_ended = False

# eager tool_start (no query known yet)
if web_search_active:
    yield _emit_synthetic_tool_event({"type": "tool_start", "tool_name": "web_search",
                                      "tool_call_id": web_search_tool_id, "arguments": {}})
    web_search_tool_started = True
```

`_record_or_url_citation` accepts both nested (`url_citation` sub-dict) and flat shapes — OpenRouter's upstream varies:

```python
def _record_or_url_citation(payload):
    if payload.get("type") != "url_citation": return
    cit = payload.get("url_citation")
    if not isinstance(cit, dict): cit = payload          # flat fallback
    url = cit.get("url", "") if isinstance(cit, dict) else ""
    if not url or not isinstance(url, str): return
    if any(c["url"] == url for c in web_search_citations): return   # dedup by URL
    web_search_citations.append({"url": url, "title": cit.get("title") or url,
                                  "snippet": (cit.get("content") or cit.get("snippet") or "")})
```

Each chunk is scanned:

```python
if web_search_active:
    choices = parsed.get("choices") or []
    for choice in choices:
        for envelope in (choice.get("delta"), choice.get("message")):
            for ann in envelope.get("annotations") or []:
                _record_or_url_citation(ann)
```

`tool_end` is emitted at `[DONE]` (and again as a safety net if the stream closes without `[DONE]`):

```python
def _build_web_search_tool_end():
    blocks = []
    for cit in web_search_citations:
        line = f"Title: {cit['title']}\nURL: {cit['url']}"
        if cit.get("snippet"): line += f"\nSnippet: {cit['snippet']}"
        blocks.append(line)
    return _emit_synthetic_tool_event({"type": "tool_end",
            "tool_call_id": web_search_tool_id,
            "result": ("\n---\n".join(blocks) if blocks else "(search complete)")})
```

Unlike OpenAI's "aggregate onto the last *real* call id", OpenRouter uses one **fixed** synthetic id `"openrouter_web_search"` because there are no real per-call ids at all.

### 4.5 Kimi — `_stream_kimi_web_search` (~line 1284) — two-call round-trip

Kimi's web search is a `builtin_function` named `$web_search` that requires a **manual two-call protocol** — the server actually runs the search on the *second* call. Docs: https://platform.kimi.ai/docs/guide/use-web-search.

```python
url = f"{self.base_url}/chat/completions"
body = {
    "model": model, "messages": messages, "stream": True,
    # $web_search forbids thinking; sending the toggle makes the server 400.
    "thinking": {"type": "disabled"},
    "tools": [{"type": "builtin_function", "function": {"name": "$web_search"}}],
}
```

**Call 1** collects the `$web_search` tool_call internally (it is *not* forwarded to the client):

```python
tool_calls_acc: dict[int, dict] = {}
...
for tc in delta.get("tool_calls") or []:
    idx = tc.get("index", 0)
    slot = tool_calls_acc.setdefault(idx, {"id": tc.get("id") or f"call_{idx}",
                                          "type": "function",
                                          "function": {"name": "", "arguments": ""}})
    if tc.get("id"): slot["id"] = tc["id"]
    fn = tc.get("function") or {}
    if fn.get("name"): slot["function"]["name"] = fn["name"]
    if fn.get("arguments"): slot["function"]["arguments"] += fn["arguments"]
```

If the model didn't search, fall back to a plain stream (mirrors every other provider's "web_search on but unneeded" UX):

```python
search_calls = [tc for tc in tool_calls_acc.values() if tc["function"]["name"] == "$web_search"]
if not search_calls:
    logger.info("Kimi $web_search: model did not invoke search; falling back to plain stream")
    fallback_body = dict(body); fallback_body.pop("tools", None)
    ... plain stream passthrough ...
    return
```

Kimi's `arguments` are an opaque **receipt** (`{"search_result":…, "usage":{"total_tokens":N}}`), not a query — the search already ran server-side. So `tool_start` carries the receipt as arguments and `tool_end` fires immediately (otherwise the card would spin through the whole second-call answer):

```python
first_args = _json.loads(search_calls[0]["function"]["arguments"] or "{}") or {}
first_args_search_tokens = None
usage_block = first_args.get("usage") if isinstance(first_args, dict) else None
if isinstance(usage_block, dict):
    tok = usage_block.get("total_tokens")
    if isinstance(tok, int): first_args_search_tokens = tok

yield _synthetic_chunk({"type": "tool_start", "tool_name": "web_search",
                        "tool_call_id": "kimi_web_search",         # fixed synthetic id
                        "arguments": first_args if isinstance(first_args, dict) else {}})
yield _build_kimi_tool_end(_synthetic_chunk, "kimi_web_search", [])
```

**Call 2** echoes the tool_calls back as a `role="tool"` message with the **same arguments verbatim** (per Kimi docs: "the caller just needs to submit tool_call.function.arguments to Kimi as they are"):

```python
assistant_msg = {"role": "assistant", "content": "",
                 "tool_calls": list(tool_calls_acc.values())}
tool_msgs = [{"role": "tool", "tool_call_id": tc["id"], "name": tc["function"]["name"],
              "content": tc["function"]["arguments"]} for tc in tool_calls_acc.values()]
followup_body = dict(body)
followup_body["messages"] = list(messages) + [assistant_msg] + tool_msgs
followup_body["stream_options"] = {"include_usage": True}
# Keep the tool on the second call so the model can search again mid-turn.
```

The second stream is passed through verbatim to the client (it's the user-facing answer). Annotations are scanned for diagnostics only — Kimi doesn't emit `url_citation` today, but the code records `annotation_shapes` so a future type name would surface in logs. `_build_kimi_tool_end` reuses the standard `Title: / URL: / Snippet:` format so the frontend treats Kimi identically, falling back to `"(search complete)"` when there are no citations.

### 4.6 Gemini

The Gemini branch calls `_stream_gemini` and uses Gemini's native REST web-search tool. The frontend predicate (§5.1) documents that Gemini support is **conditional** on base URL + image-model version. The subagent that covered external providers did not capture the exact Gemini request body or citation-translation code; this is an honest gap — the Gemini *tool attachment* shape was not extracted at the same depth as the other four providers. The unified `_toolEvent` emission is reused (the dispatch comment at line 877 confirms `_stream_gemini`), but the precise native request shape and citation aggregation are not quoted here.

### 4.7 Why Mistral was dropped

**Code-level evidence**: there is **no** web-search wiring for Mistral anywhere in `external_provider.py`. The entire Mistral branch in `stream_chat_completion` is one line:

```python
elif self.provider_type == "mistral":
    _apply_mistral_reasoning_controls(body, model, enable_thinking, reasoning_effort)
```

`_apply_mistral_reasoning_controls` (~line 434) only ever sets/clears `prompt_mode` (Magistral) or `reasoning_effort` (mistral-small/vibe). It never touches `tools`, never attaches a web-search tool, never synthesizes tool events:

```python
_MISTRAL_THINKING_SPECS = (
    _MistralThinkingSpec(models=("magistral-medium-latest",), style="prompt_mode"),
    _MistralThinkingSpec(models=("mistral-small-latest", "mistral-vibe-cli-latest"),
                         style="reasoning_effort", efforts=("none", "high")),
)
```

`providers.py` confirms Mistral is registered purely as an OpenAI-compatible `/v1/chat/completions` provider with `supports_tool_calling: True` but **no** web-search capability flag and **no** notes about server-side search:

```python
"mistral": {
    "display_name": "Mistral AI",
    "base_url": "https://api.mistral.ai/v1",
    "default_models": ["codestral-latest", "devstral-latest", ..., "mistral-vibe-cli-latest"],
    "supports_streaming": True, "supports_vision": True, "supports_tool_calling": True,
    "auth_header": "Authorization", "auth_prefix": "Bearer ",
    "model_id_allowlist": re.compile(r"^(codestral-latest|...|mistral-vibe-cli-latest)$"),
}
```

**Why it can't be wired the OpenAI/Anthropic way.** Mistral's server-side web search is exposed as a **Connector** that only works on the `/v1/agents` endpoint (the Agents API), **not** on `/v1/chat/completions` (the chat endpoint Unsloth proxies for every other OpenAI-compatible provider). The hosted-tool shapes OpenAI (`{"type": "web_search"}`) and Anthropic (`{"type": "web_search_20250305", …}`) attach cleanly to their respective endpoints' `tools` arrays; Mistral has no equivalent tool type on `/chat/completions` — its web search lives behind an agent+connector abstraction on a different endpoint. Since Unsloth's entire `ExternalProviderClient` is built around streaming `/chat/completions` (and `/responses` for OpenAI), wiring Mistral would mean implementing a separate Agents-API client with its own request/response translation. Out of scope, so web_search is simply not offered for `provider_type == "mistral"`. The `enabled_tools` entry `"web_search"` is silently ignored on the Mistral branch (no `if "web_search" in enabled_tools` clause exists for it), unlike OpenRouter/Kimi/Anthropic/Gemini/OpenAI which all have explicit gates.

### 4.8 Provider matrix

| Provider | Endpoint | Request web_search shape | Citation delivery | tool_event synthesis |
|---|---|---|---|---|
| **OpenAI** | `/v1/responses` | `tools=[{type:"web_search"}]` | inline `\ue200cite…\ue201` markers + `url_citation` annotations on text deltas (global, not per-call) | real per-call `tool_start`/`tool_end` from `web_search_call` items; **last call's `tool_end` overwritten** with aggregated citations at `response.completed`/`incomplete` |
| **Anthropic** | `/v1/messages` | `tools=[{type:"web_search_20250305", name:"web_search", max_uses:5}]` (or `web_search_20260209` for new prefixes) | per-call `web_search_tool_result` block content | `server_tool_use` close → `tool_start`; `web_search_tool_result` close → `tool_end` (per-call, no aggregation) |
| **OpenRouter** | `/chat/completions` | `plugins=[{id:"web"}]` (not `:online`) | `url_citation` annotations on chunks | single fixed `tool_start` immediately; `tool_end` (with collected citations) before `[DONE]` |
| **Kimi** | `/chat/completions` ×2 | `tools=[{type:"builtin_function", function:{name:"$web_search"}}]`, `thinking:{type:"disabled"}` | none today (search baked into context server-side); args are an opaque receipt | `tool_start` from receipt args + immediate `tool_end`; second call streams the answer verbatim |
| **Gemini** | native REST | native tool attachment (conditional on base URL + image model) | native | unified `_toolEvent` reused via `_stream_gemini` — exact request shape not captured at the same depth |
| **Mistral** | `/chat/completions` | **none** — not wired | n/a | n/a — web search is a Connector on `/v1/agents`, unavailable on the chat endpoint Unsloth proxies, so the capability is dropped |

---

## 5. Frontend

### 5.1 `providerSupportsBuiltinWebSearch()`

Returns `true` unconditionally for `openai`, `anthropic`, `openrouter`, `kimi`, and conditionally for `gemini` (depends on base URL + image-model version). The doc-comment enumerates per-provider request shapes: OpenAI `tools:[{type:"web_search"}]`, Anthropic `web_search_20250305`, OpenRouter `plugins:[{id:"web"}]`, Kimi `builtin_function/$web_search` with mandatory `thinking:{type:"disabled"}`. (The exact branching condition for Gemini was not captured verbatim by the frontend subagent — structure confirmed, predicate body not quoted.)

### 5.2 `supportsTools` vs `supportsBuiltinWebSearch` split

Search lights up on either **local** `supportsTools` **OR** provider builtin. Code only lights up on local `supportsTools` **OR** Anthropic/OpenAI cloud with specific model prefixes. This is why OpenRouter and Kimi models show **Search but not Code**. Anthropic/OpenAI Search defaults to **on**.

### 5.3 chat-adapter wiring

`toolsEnabled && providerSupportsBuiltinWebSearch` → `webSearchEnabledForThisTurn` → the request body carries:

```json
{
  "enable_tools": true,
  "enabled_tools": ["web_search"]
}
```

That is the shorthand the backend reads to decide whether to attach server-side web search (external providers) or expose the local `WEB_SEARCH_TOOL` (GGUF/Safetensors).

### 5.4 `tool-ui-web-search.tsx`

`parseSearchResults` uses regexes over the `Title:` / `URL:` / `Snippet:` triples joined by `/---` block separators — i.e. the same shape every provider emits in §4. `isSafeHttpUrl` rejects `javascript:`, `data:`, `vbscript:` schemes, CR/LF injection (header-splitting), and any unparseable URL. The card auto-collapses when the LLM starts generating text, so the search pill doesn't permanently occupy space once the answer is streaming.

### 5.5 Kimi Think/Search mutual exclusion

Three click handlers — Search pill, Thinking toggle, effort dropdown — all check `isKimiExternal` and force the companion off with `{ persist: false }`. The backend doc-comment confirms Kimi's API requires `thinking:{type:"disabled"}` alongside `$web_search`, so letting a user enable both would 400 the request. Mutual exclusion is enforced client-side, not just documented.

---

## 6. Privacy and data flow

### 6.1 Who sees what, when web search is on

| Party | What they see | Notes |
|---|---|---|
| **DuckDuckGo** (local-tool search provider) | The **search query** + your **IP** + your **User-Agent** + approximate **geolocation** (from IP) + language/region hint | DDG's privacy policy: queries may be saved "completely disconnected" from identifiers; no IP-to-query persistent logging per their policy. Source: maintainer comment on issue #6323 quoting duckduckgo.com/privacy. |
| **Fetched target websites** (when `url` used) | Your IP + User-Agent + a "bot" request | Target sites see an automated crawler hit. They do not know *why* you are there. |
| **Unsloth (the company)** | **Nothing** when running locally | The local model has no network access — only the `web_search` Python subprocess opens external connections. Only cloud-hosted Studio instances (Colab, Lambda, …) would expose chat history to that provider. |

### 6.2 The local-model-stays-local property

The locally-served GGUF/Safetensors model itself never makes a network call. The tool loop is the *only* code path that opens sockets — and it does so from a Python subprocess, not the inference process. This is the property that lets Studio run air-gapped-by-default with web search still available as an opt-in tool.

### 6.3 Server-side-tool policy (localhost vs `0.0.0.0`)

**Current policy after PR #6403 (merged June 18, 2026):**

| Launch command | Tool policy | Tools |
|---|---|---|
| `unsloth studio` (no flag) | None → per-request, UI toggle honored | **on** |
| `unsloth studio --secure` | None | **on** |
| `unsloth studio -H 0.0.0.0` | None | **on** |
| `unsloth studio --disable-tools` | `False` (forced) | **off** |
| `unsloth studio --enable-tools` | `True` (forced) | **on** |
| `unsloth studio run` (no flag) | `True` (default for `run`) | **on** |
| `unsloth studio run --disable-tools` | `False` | **off** |

Key history:

- **Before PR #6403** (c. June 14–17, 2026): `--secure` and `-H 0.0.0.0` forced tools **off** to prevent remote API-key holders from running Python/terminal.
- **After PR #6403**: Tools default **on** for every bind. `--secure` is now about the authenticated Cloudflare tunnel, not a blanket "disable everything" mode.
- **Default host**: changed from `0.0.0.0` to `127.0.0.1` (PR #5267, May 4, 2026). Before that, `0.0.0.0` exposed all interfaces. The installer (`install.sh` / `install.ps1`) now prompts before auto-starting the server.

The resolver is a one-liner:

```python
# _tool_policy.py
def resolve_tool_policy(host, flag, yes, silent, prompt) -> bool:
    return True if flag is None else flag
```

No override means ON; `--disable-tools`/`--enable-tools` force the bit.

### 6.4 User-Agents

Unsloth does **not** pass a User-Agent to `ddgs` for the *search* call itself — the library handles its own UA rotation (`fake-useragent` + `primp` TLS fingerprinting). For the *page fetch* (`url` parameter) path, Unsloth has its **own** rotating pool in `tools.py`:

```python
_USER_AGENTS = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:133.0) Gecko/20100101 Firefox/133.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Safari/605.1.15",
)
```

Each fetch picks a random entry. These are browser-like Chrome 131 / Firefox 133 / Safari 18.2 UAs — exactly the kind of header a polite scraper would send.

### 6.5 DuckDuckGo anti-bot reality (June 2026)

The `ddgs` library tries `html.duckduckgo.com/html` and `lite.duckduckgo.com/lite` in randomized order. Both endpoints now serve CAPTCHA challenges (`anomaly.js` + visual puzzles) to some IPs without browser-like TLS fingerprinting. `ddgs` mitigates this via header randomization + TLS fingerprinting (`primp`), but it is not 100% reliable; IP-based blocking has a sliding window (~1 hour unblock). So whether Studio's local search works reliably depends on a third-party library's ability to bypass DDG's bot detection — not on Unsloth itself. (Cross-ref: [[duckduckgo-html-endpoint-2026-captcha]].)

---

## 7. Design lessons for other implementers

A Clojure agent runtime (e.g. Lateralus, whose `WebSearchProvider` protocol is documented in [[lateralus-v2-web-search-provider-pattern]]) can borrow five concrete patterns from this codebase:

1. **One unified tool-event shape across heterogeneous provider SSE.** Every provider emits `{"type":"tool_start"|"tool_end", "tool_call_id":…, "arguments":…, "result":…}` *alongside* the OpenAI chunk (`_toolEvent` rides outside `choices`). That lets the frontend have one parser (`parseSearchResults`) for DuckDuckGo snippets, Anthropic `web_search_tool_result` blocks, OpenAI aggregated citations, OpenRouter annotations, and Kimi receipts. The trick is making the result *string* the contract — not the provider's native event shape. In Lateralus terms, the `:agent/all-tool-results` channel should normalize every backend's output into the same `{:tool-name :query :result-string}` map regardless of upstream wire format.

2. **URL-fetch-as-second-mode of the same tool.** Surfacing `query` and `url` as two mutually-exclusive optional params on one `web_search` tool — and appending a *literal hint* to the search-result string telling the model to re-call with `url` — gives you a two-step agent behavior (search → read) without a separate `web_fetch` tool, separate schema, separate frontend card, or separate loop logic. The hint lives *inside the data the model reads*, so it biases behavior without prompt engineering. Lateralus's current single-mode `web_search` could add `:fetch-url` the same way, reusing the same `WebSearchProvider` protocol via a `-fetch-page` method (which already exists in [[lateralus-web-provider-protocol]]).

3. **Graceful per-provider fallbacks.** Three different providers, three different citation-delivery models, three different synthesis strategies — but each *degrades gracefully*: OpenAI overwrites the last call's `tool_end` with aggregated citations; Anthropic emits per-call (no aggregation needed); OpenRouter synthesizes one fixed card from annotations; Kimi short-circuits the card from a receipt because the search already ran server-side. The pattern is: assume citations might arrive at a different time than the call, and have a per-provider reconciliation step at stream end. A Clojure runtime can mirror this with a `:finalize` multimethod dispatched on provider type, run once at `[DONE]`.

4. **SSRF hardening as a stack, not a check.** Unsloth's protection is four cooperating pieces, and skipping any one re-opens a hole: pre-resolve + validate (`_validate_and_resolve_host`), pin the IP for the TCP connection (`_PinnedHTTPSConnection`), keep SNI/cert verification on the original hostname, and disable auto-redirects so every hop is re-validated and re-pinned. Lateralus's existing fetch guard (`guards/validate-url`) only does the *first* step; the latent bug noted in [[lateralus-web-search-fetch-guard-dead-code]] (where `(first url-check)` never matches `:error` because `validate-url` returns a map) means even that first step doesn't fire in fetch-page today. Borrowing the full stack — including the `_NoRedirect` + re-pin-per-hop discipline — would close both the rebinding and the redirect-into-localhost classes at once.

5. **The `_MAX_FETCH_BYTES` / `_MAX_PAGE_CHARS` two-cap pattern.** Cap raw bytes *before* decode (512 KB) and cap converted text *after* decode (16 000 chars), with a truncation marker that tells the model how much was cut (`"... (truncated, {len(text)} chars total)"`). The raw cap bounds memory and bandwidth; the text cap bounds the model's context window. Doing only one leaves you either vulnerable to a 50 MB HTML blob or starving the model of useful pages. This pairs naturally with a stdlib-only HTML→Markdown converter (~250 LoC, no external `html2text` dependency) that gracefully `flush_pending()` on truncated input.

A sixth, cross-cutting lesson: the **error-prefix contract**. Every failure returns a prefixed string (`Blocked:`, `Search failed:`, `Failed to fetch URL:`, `No results found.`, `(page returned no readable text)`) and the loop pattern-matches on those prefixes to decide whether to append `TOOL_ERROR_NUDGE`. Treating errors as tagged strings rather than exceptions lets the model participate in recovery — it sees the failure reason *and* a hint about what to do — which is more useful to an agent than a stack trace. Lateralus's `:error` map convention is the Clojure equivalent and could be surfaced to the model the same way.

---

### Honest gaps

- The **exact web-tip body** of `_build_tool_action_nudge` (size-branched) was not captured verbatim; only its structure is confirmed.
- The **Gemini native request body and citation aggregation code** were not extracted at the same depth as the other four providers; only the dispatch (`_stream_gemini`) and frontend conditional-support predicate were confirmed.
- The **frontend `providerSupportsBuiltinWebSearch` predicate body** (Gemini branch) was not quoted verbatim; structure confirmed.
- Line numbers are approximate (subagents reported ranges like ~430–450, ~670–710); I preserved the approximations rather than invent exact lines.