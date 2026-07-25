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

Currently one op:

```clojure
{:op :set-llm
 :model "…"      ; optional
 :base-url "…"   ; optional
 :api-key "…"}   ; optional — at least one key required
```

Unknown keys are rejected by the closed Malli schema. Integrant client class
(`:stub` vs `:http`), memory, embedder, and tool registries are **not**
swappable this way.

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
  → harvest-transitions   ; enqueue ops, redact tool results
  → compose-tool-results  ; append assistant+tool msgs
  → apply-transitions     ; merge state + patch :llm/request
  → tool-loop / finalize
```

The ReAct follow-up chain in `kschltz.agent.loop` mirrors the same order so a
mid-loop `set_llm_config` affects the next LLM call of the same exchange.

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

## Adding a new transition op

1. Extend `Transition` / `SetLlmOp`-style schemas in `transitions.clj`.
2. Handle the op in `apply-transition`.
3. Emit it from a tool result under `:transition`.
4. Keep allowlists tight — prefer new ops over open maps.
