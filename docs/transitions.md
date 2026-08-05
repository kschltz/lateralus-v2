# Runtime state transitions

Lateralus lets tools propose **allowlisted session-state transitions** without
mutating the runtime atom. Interceptors stage ops on `:agent/transitions`; a
commit stage in the `:tools` slot applies them to working `:agent/state`,
`:agent/state-delta`, and the in-flight `:llm/request`.

## Why

Tools return strings only (`Tool/-invoke`). The outer runtime is the single
writer of the session atom via `:agent/state-delta`. Transitions are the bridge:
tools emit a tagged JSON envelope; harvest/apply interceptors turn that into a
durable state change — including **same-exchange** model/endpoint switches
before the next ReAct LLM call.

## Namespaces

| Namespace | Role |
|-----------|------|
| `kschltz.agent.transitions` | Transition algebra, Malli schemas, redact helpers |
| `kschltz.agent.transitions.interceptors` | `harvest-transitions` + `apply-transitions` |
| `kschltz.agent.tools.config` | `set_llm_config`, `list_llm_models` |
| `kschltz.agent.tools.config.catalog` | `ModelCatalog` protocol (HTTP / stub) |

## Ops

```clojure
{:op :set-llm
 :model "…"      ; optional
 :base-url "…"   ; optional
 :api-key "…"}   ; optional — at least one key required

{:op :mcp-upsert-server
 :server-id "…"
 :config {…}}    ; redacted server stanza

{:op :mcp-remove-server
 :server-id "…"}

{:op :mcp-refresh-server
 :server-id "…"}
```

Unknown keys are rejected by the closed Malli schema. Integrant client
class (`:stub` vs `:http`), memory, and embedder are **not** swappable
this way. MCP servers **are** swappable by default
(`:lateralus/mcp-tools {:dynamic {:enabled? true}}`); set
`:dynamic {:enabled? false}` to lock — see
[`docs/dynamic-mcp-tool-setup.md`](dynamic-mcp-tool-setup.md).

## Tool surface

### `set_llm_config`

Proposes a `:set-llm` transition. Result envelope (model-visible after harvest):

```json
{
  "ok": true,
  "tool": "set_llm_config",
  "pending": "same-exchange",
  "before": {"model": "a"},
  "after": {"model": "b"},
  "transition": {"op": "set-llm", "model": "b"}
}
```

`:api-key` is never echoed to the model (`:api-key-set true` marker instead).
The real key still lands in `:agent/state-delta` for the runtime merge;
logging already redacts `:api-key` from ctx views.

### `list_llm_models`

Lists model ids via `ModelCatalog` (never calls HTTP from the tool). Optional
`base-url` / `api-key` override the session for that call only.

Integrant:

```clojure
:lateralus/config-tools {:catalog :http}  ; or :stub for offline
```

## Pipeline (`:tools` slot)

```
dispatch-tools
  → harvest-transitions   ; enqueue ops, redact secrets in tool results
  → apply-transitions     ; merge state, reconcile MCP I/O, patch :llm/request
  → compose-tool-results  ; append assistant+tool msgs (post-reconcile)
  → tool-loop / finalize
```

The ReAct follow-up chain in `kschltz.agent.loop` mirrors the same order so a
mid-loop `set_llm_config` / `mcp_*` call affects the next LLM call of the same
exchange. MCP connect/close/refresh runs in apply (not in tool `-invoke`),
matching the monadic propose-then-commit pattern of LLM config updates.

## Demo: Ollama Cloud mid-session switch

Real interactive session against `https://ollama.com/v1` (needs
`OLLAMA_API_KEY`):

```bash
./scripts/demo-ollama-cloud-config-switch.sh
# or PTY-driven (visible typing, good for screen recordings):
python3 scripts/demo-ollama-cloud-config-switch-pty.py
```

Config: `resources/lateralus/demo-ollama-cloud-config.edn` — starts on
`deepseek-v4-flash`, then the agent calls `set_llm_config` to move to
`gpt-oss:20b` and confirms with `self_status`.

## Demo: Ollama Cloud + dynamic MCP upsert

```bash
python3 scripts/demo-ollama-cloud-mcp-dynamic-pty.py
```

Starts a local fake Streamable HTTP MCP server, runs against Ollama
Cloud with empty `:servers` and `:dynamic {:enabled? true}`, then the
agent lists → ADD (`mcp_upsert_server`) → calls `demo_echo` →
EDIT/replace → REMOVE → lists (count 0).
Config: `resources/lateralus/demo-ollama-cloud-mcp-dynamic.edn`.

## Adding a new transition op

1. Extend `Transition` / `SetLlmOp`-style schemas in `transitions.clj`.
2. Handle the op in `apply-transition`.
3. Emit it from a tool result under `:transition`.
4. Keep allowlists tight — prefer new ops over open maps.

## Future: dynamic tool setup (MCP)

Implemented. See [`docs/dynamic-mcp-tool-setup.md`](dynamic-mcp-tool-setup.md)
and the `mcp_*` control tools under `:lateralus/mcp-session-tools`.
