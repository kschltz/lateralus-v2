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

{:op :set-system-message
 :message "…"}

{:op :set-loop-opts
 :max-loop-depth 8
 :max-tool-calls-per-exchange 40
 :max-tool-calls-per-turn 10
 :tool-content-caps {"file_read" 65536}}

{:op :set-tool-enabled
 :tool-name "file_write"
 :enabled false}

{:op :set-memory-policy
 :top-y 8
 :last-n 12
 :recall-enabled true
 :persist-enabled false}

{:op :reload-runtime
 :namespaces ["kschltz.agent.interceptors"
              "kschltz.agent.plugins.base"]}

{:op :reload-runtime
 :from-edits true}   ; uses :agent/edited-namespaces from file_* results

{:op :register-runtime-tool
 :spec {:name "add_two" :description "…"
        :input-schema "[:map [:a :int] [:b :int]]"
        :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"}}

{:op :forget-runtime-tool
 :tool-name "add_two"}

{:op :promote-runtime-tool
 :tool-name "add_two"
 :as-plugin true
 :target :workspace}   ; or :project
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

### `set_system_message`

Replaces `:agent/system-message` through the transition queue. The apply
interceptor updates the working state immediately, and `:agent/state-delta`
makes it durable for later exchanges. The tool cannot mutate the session atom.

### `set_loop_policy`

Merges an allowlisted loop-policy patch into `:agent/loop-opts`. Positive
limits are accepted for loop depth, per-exchange calls, per-turn calls, and
per-tool content caps. Apply patches the current immutable ctx for same-
exchange follow-ups and stages the policy in `:agent/state-delta`; the outer
runtime overlays that session policy on boot defaults for every later
exchange.

### `set_tool_enabled`

Adds or removes a tool name from the durable session overlay. The tools plugin
recomputes `static ∪ MCP ∪ factory − disabled` during transition apply and patches the
in-flight request, so changes affect the next same-exchange LLM call and later
exchanges. Integrant tools, MCP session tools, and `tool_define` overlays can be
selected. `set_tool_enabled`, `runtime_describe`, and factory control tools
are protected recovery tools.

### `tool_define` / `tool_test` / `tool_forget` / `tool_promote`

Define a real in-process `Tool` from a persistable spec, test it through the
guarded registry, drop it, or write it to disk as a reusable plugin.
`tool_test` records a passing exact-output assertion against the current
spec fingerprint; redefinition invalidates it and `tool_promote` refuses
untested specs. Runtime-tool maps replace (rather than deep-merge) in the
outer runtime so forget/promote removals are durable. See
[`docs/runtime-tools.md`](runtime-tools.md).

### `set_memory_policy`

Updates the memory interceptors' session overlay: recall result counts and
independent recall/persistence switches. The memory plugin reads this policy
from immutable `:agent/state` on every exchange. Backend and embedder instances
remain Integrant-managed and are not replaced by this pure-data transition.

### `reload_runtime`

Stages a one-shot reload request. After the current chain finishes and its
state delta is merged, the outer runtime reloads the named project namespaces,
asks each Integrant-assembled built-in plugin for a fresh interceptor vector,
and atomically swaps the chain used by the next exchange. Tool code never
mutates the chain or runtime atom. Pass `:from-edits true` to reload
`:agent/edited-namespaces` recorded from successful `file_update` /
`file_patch` / `file_create` results in this session.

Core engine/protocol namespaces (`runtime`, `chain`, `system`, `tool`, and
`transitions`) report `restart-required`: replacing their generated
classes/protocol identities inside a running JVM is unsafe. The request is
consumed either way, and `runtime_describe` reports the reload status.

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

In-process tools (not MCP) use `tool_define` / `tool_promote` —
[`docs/runtime-tools.md`](runtime-tools.md).
