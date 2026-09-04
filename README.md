# lateralus-v2

Greenfield rewrite of [Lateralus](https://github.com/kschltz/lateralus) (v1 archive). A Clojure LLM agent with an interceptor-chain runtime, Integrant lifecycles, session memory contract, and a clean-slate CLI.

**Coord:** `net.clojars.kschltz/lateralus-v2`  
**Main ns:** `kschltz.lateralus`  
**License:** [Eclipse Public License 2.0](LICENSE)  
**Suite:** 940 tests / 3,142 assertions, green; `clj-kondo` 0 errors

## Quick start

**Docker (recommended for a full workbench):** Java/Ollama packaged; interactive profile gate + CHAT | Portal UI.

```bash
./scripts/start-workbench                  # macOS / Linux / WSL / Git Bash
# .\scripts\start-workbench.ps1            # Windows PowerShell
```

Open **http://localhost:7860** (Portal iframe on **:7870**). Details: [`docker/README.md`](docker/README.md).

**Local Clojure** (Java 22+; JVM flags are baked into `:run` / `:workbench`):

```bash
cd lateralus-v2

# Interactive workbench — no --config opens the AWS-style profile gate
clojure -M:workbench:run -i

# One-shot / help
clojure -M:run -h
clojure -M:run --config resources/lateralus/demo-stub.edn "Explain recursion"

# Tests
clojure -M:test                            # full suite (excludes ^:e2e)
clojure -M:e2e                             # live-LLM memory e2e (auto-skips w/o Ollama)
LATERALUS_E2E_FAKE=true clojure -M:e2e     # deterministic fake-server e2e
```

The default `resources/lateralus/config.edn` expects a reachable Ollama at
`http://localhost:11434/v1`. Without Ollama, run offline with the bundled stub:

```bash
clojure -M:run --config resources/lateralus/demo-stub.edn "your prompt"
```

Profiles are saved under `~/.config/lateralus/` (override with `LATERALUS_CONFIG_HOME`).
Secrets are never written to profile files — use `OLLAMA_API_KEY` for Ollama Cloud.

## CLI

```
Usage: lateralus [flags] [prompt]

Flags:
  -h, --help               show help and exit
  --version                show version and exit
  -i, --interactive        read prompts from stdin, line-by-line
  --no-interactive         force one-shot mode (default)
  -s, --session ID         session id (default: random-uuid)
  --config PATH            Integrant EDN config (skips the profile gate)
  --model NAME             LLM model name (overrides config / profile)
  --base-url URL           LLM base URL (overrides config / profile)
  --api-key KEY            LLM API key (overrides config; else OLLAMA_API_KEY)
```

When `--config` is omitted on a TTY, lateralus always opens the **profile gate**
(pick / create / edit a saved profile; Enter keeps current values). Editing also
shows a **tool-group checklist** (`j`/`k` move, space/`t` toggle, Enter accept).
Model prompts accept `?` (list catalog) and `/term` (filter), backed by the
endpoint's `/v1/models` (and Ollama Cloud when keyed).

Examples:

```bash
# Workbench + profile gate
clojure -M:workbench:run -i

# Workbench + Ollama Cloud (requires OLLAMA_API_KEY and
# LATERALUS_SECRETS_PASSPHRASE)
clojure -M:workbench:run -i \
  --config resources/lateralus/ollama-cloud-workbench.edn

# Named session with an explicit EDN config
clojure -M:run -s my-session --config resources/lateralus/demo-ollama.edn "Hello"

# OpenAI-compatible HTTP LLM via flags
clojure -M:run \
  --model gpt-4 \
  --base-url https://api.openai.com/v1 \
  --api-key "$OPENAI_API_KEY" \
  "Hello"

# Persistent memory example
clojure -M:run \
  --config resources/lateralus/proximum-example.edn \
  -s my-session \
  "Remember this"
```

## Architecture

Lateralus v2 is built on three ideas:

1. **Interceptor chain engine** — every stage of an exchange (compose, LLM call, parse, dispatch, persist, deliver) is a pure function of an immutable context map.
2. **Integrant lifecycles** — the LLM client, embedder, memory backend, plugins, and optional stores (secrets, skills) are Integrant-managed components, Malli-validated at init (`ig/assert-key`).
3. **Thin outer runtime** — `kschltz.agent.runtime` creates a per-exchange context with traceability IDs, runs the chain, and merges `:agent/state-delta` into an in-memory atom.

For details, see [`docs/architecture.md`](docs/architecture.md).

## Built-in tool suite

The default registries give the model, among others:

| Group | Tools |
|-------|-------|
| Filesystem | `file_read` (windowed, SHA-256 witness), `file_list`, `file_info`, `file_glob`, `file_search`, `file_create`, `file_write`, `file_update`, `file_patch` |
| Clojure structured edit | `clojure_query`, `clojure_add_require`, `clojure_remove_def`, `clojure_rename_symbol`, `clojure_insert_form`, `clojure_edit_def`, `clojure_format_file`, `clojure_lint` |
| Runtime eval | `clojure_eval`, `clojure_add_lib`, `clojure_loaded_libs` |
| Web | `web_search`, `web_fetch`, `web_extract` |
| Session config | `set_llm_config`, `set_system_message`, `set_loop_policy`, `set_tool_enabled`, `set_memory_policy`, `list_llm_models`, `reload_runtime` |
| Introspection | `self_status`, `runtime_describe` |
| Dynamic tools | `tool_define`, `tool_test`, `tool_list_runtime`, `tool_forget`, `tool_promote` |
| Workflows | `workflow_register_action`, `workflow_seed`, `workflow_run`, `workflow_status`, `workflow_clear` |
| Workbench/Portal | `portal_submit`, `portal_clear`, `portal_selected`, `portal_focus` |
| MCP management | `mcp_list_servers`, `mcp_upsert_server`, `mcp_refresh_server`, `mcp_remove_server` |
| Secrets (opt-in) | `secret_list_handles`, `secret_check` |
| Skills (opt-in) | `load_skill`, `read_skill_file` |

## Session transitions

The agent changes its own session knobs via staged **transitions** (never by
mutating the runtime atom from a tool): `set_llm_config`, `set_system_message`,
`set_loop_policy`, `set_tool_enabled`, `set_memory_policy`, and
`reload_runtime` (reload allowlisted agent namespaces after source edits and
rebuild the Integrant-assembled plugin chain; core engine changes report
`restart-required`). Wired via `:lateralus/config-tools`. The same knobs are
exposed to the human as the workbench **Settings** panel
(`GET/POST /api/settings`). See [`docs/transitions.md`](docs/transitions.md)
and [`docs/runtime-tools.md`](docs/runtime-tools.md).

## Workbench (CHAT | Portal)

An http-kit web UI served by `kschltz.agent.workbench`, with Portal as the
rich visual pane. Session persistence, settings, and secrets are all managed
from the UI; each is backed by an HTTP surface with its own ops map:

- **Sessions** — `/api/sessions` (list / create / activate / rename / delete)
- **Settings** — `/api/settings`: LLM config, system message, loop policy,
  memory policy, per-tool enable/disable, model listing (`/api/settings/models`)
- **Secrets** — `/api/secrets`: add/replace/delete secrets from **Settings →
  Secrets** in the UI; the API returns labels only, values never come back.
  The store is the same `SecretStore` the secrets plugin wraps tools with,
  so a value saved in the UI is immediately usable as `{{secret:label}}`
  (opt-in wiring shown in `resources/lateralus/demo-workbench.edn`).

### Workbench 2-way Portal loop

Portal artifacts are interactive. An HTML artifact submitted with
`portal_submit` may embed a tiny JS helper that POSTs interaction events
(button clicks, sliders, form input) to `/api/portal-event` (same-origin
iframe); the event becomes `⟨portal-event⟩` input to the agent's next
exchange, and the agent responds with an updated artifact that shows the new
state. `portal_selected` covers the complementary "human points at data"
read-back — it pulls the value currently selected in the Portal pane into the
conversation, serialized as clamped EDN. Trust model: events are
human-initiated data only (the model authors the JS, the human clicks) — no
`portal.api/register!` or UI-runtime eval is exposed. See
[`docs/workbench-2way.md`](docs/workbench-2way.md).

## Secrets plugin (use-without-seeing)

Opt-in plugin (`:lateralus/secret-store` + `:lateralus/secret-plugin`) backed
by a sealed, encrypted-at-rest store (AES-256-GCM, PBKDF2, `LATSEC1` format,
passphrase via env var). The model references secrets with
`{{secret:label}}` handles: values are substituted into tool arguments at
dispatch and every tool result is swept through redaction — values circulate
in-process but never enter a prompt or tool result. `secret_list_handles`
shows the model which labels exist; there is no read-back tool. Every
response path is redacted; the workbench UI and HTTP API never serve a value.
See [`docs/secrets.md`](docs/secrets.md).

## Skill packs

Pure-data, on-disk skills (`.edn`) with progressive disclosure, so skill
bodies never sit in the system prompt: a Tier-1 **catalog fragment**
(name + description only) is appended to the context by the skills plugin;
`load_skill` fetches a body into the conversation (trimmable); Tier-3
resources load on demand via `read_skill_file` with undeclared-path gating
before canonical containment. Malli-closed schemas, fail-closed dir loading
(one bad file fails init loudly), byte-stable sorted catalog. Opt-in via
`:lateralus/skills-store` + `:lateralus/skills-plugin`. See
[`docs/skills.md`](docs/skills.md).

## Web tool

The agent ships `web_search`, `web_fetch`, and `web_extract`. The default
provider is `:none` — **no API key, no paid service, and no network I/O** out
of the box (`web_extract` still works on raw HTML in air-gapped mode). Live
access is opt-in via `:provider :mojeek` (parses Mojeek's public HTML) or
`:provider :ddg` (DuckDuckGo HTML with a browser TLS/HTTP2 fingerprint).
Both live providers are JVM-only and excluded from the native-image
classpath. All three ops are guarded against SSRF (private/loopback/metadata
IPs, `file://`, protocol-relative URLs), prompt-injection markers, recursive
self-activation, and snippet exfiltration patterns. See [`docs/web.md`](docs/web.md).

## Runtime-eval tool

The agent can evaluate Clojure and pull dependencies at runtime:

- `clojure_eval` — evaluate in a **persistent** runtime namespace; returns the
  value, captured stdout, and any exception; bounded timeout per call.
- `clojure_add_lib` — Maven/Git dependency loading via Clojure 1.12
  `clojure.repl.deps/add-libs`.
- `clojure_loaded_libs` — list libs loaded in-process.

Behind the `ClojureRuntime` protocol, Malli-instrumented, wired via
`:lateralus/runtime-tools` (JVM configs; excluded from native-image).
`{ :enabled? false }` disables it; `:network? false` keeps eval but blocks
dependency loading. See [`docs/runtime-eval.md`](docs/runtime-eval.md).

## Runtime tool factory + workflows

`tool_define` compiles a real protocol `Tool` mid-session (callable on the
next turn). `tool_test` runs it through the current guarded registry and
records an exact-output pass against the current spec; redefinition
invalidates the pass. `tool_list_runtime` inventories lifecycle state,
`tool_forget` drops one, and `tool_promote` persists it. With secrets active,
runtime code executes in SCI without host context/JVM I/O, receives opaque
secret handles, and can compose only operator-allowlisted protocol tools;
promotion stores a sandboxed workspace spec rather than host-loadable source.
Non-secret operator profiles retain generated-source targets. Session switches
synchronize the ephemeral overlay, while promoted catalogs retain a recovery
spec. This is the bridge between scratch code and persistent capabilities — see
[`docs/runtime-tools.md`](docs/runtime-tools.md).

`:lateralus/workflow-tools` is an in-process artifact engine: actions declare
`:needs` / `:produces` artifact sets, `workflow_run` schedules and executes
the DAG, `workflow_register_action` / `workflow_seed` / `workflow_status` /
`workflow_clear` manage specs. Specs persist on `:agent/runtime-tools` in
`:agent/state-delta`.

## File harness

Bounded, line-numbered reads and safe create/write/update operations:

- `file_read` returns a window, continuation metadata, and a SHA-256 witness.
- `file_list` is deterministic and bounded; all read/discovery tools enforce
  canonical workspace containment and blocked paths by default.
- `file_glob` provides sorted, bounded `**/*.ext`-style discovery without
  following directory symlinks or traversing blocked trees.
- `file_update` applies ambiguity-safe text edits atomically and detects races.
- `file_patch` applies one or more hash-anchored 1-based line-range patches;
  stale, overlapping, out-of-range, binary, or invalid-Clojure patches make
  zero writes.
- `file_write` accepts `expected-sha256` to reject stale full-file replacements.
- `file_create` is create-only.

All mutations use canonical workspace containment (including symlink
resolution), blocked-path checks, per-path locks, size/omission guards,
atomic moves, and write verification; replacement writes create timestamped
backups. The `clojure_*` structured-edit tools give the same guarantees via
rewrite-clj; `clojure_lint` provides bounded, read-only clj-kondo findings
after edits.

## Runtime introspection

`runtime_describe` exposes the active runtime as redacted structured data —
session summary + loop policy, registered tool contracts, the ordered
interceptor chain — reading the immutable per-exchange context. API keys and
live implementation objects are never serialized. `self_status` is the
lighter self-check sibling (used by the self-update playbook after reloads).

## MCP client

Attaches **stdio** MCP servers (`command`/`args`/`env`) and **remote
Streamable HTTP** endpoints (`:url` + optional Bearer/headers). Default is
air-gapped (`:servers {}`). Tools are discovered at Integrant init, remapped
to portable prefixed ids (`filesystem_read_file`), and closed on halt; mid-
session upsert/refresh/remove is available via the `mcp_*` tools and the
`:lateralus/mcp-session-tools` component. Remote URLs are SSRF-checked
(https-only; loopback blocked unless opted in). See [`docs/mcp.md`](docs/mcp.md).

```clojure
:lateralus/mcp-tools
{:servers
 {"filesystem"
  {:command "npx"
   :args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp/sandbox"]}
  "acme"
  {:url "https://mcp.example.com/mcp"
   :bearer-token-env "ACME_MCP_TOKEN"}}}
```

## Memory backend

The runtime default (`resources/lateralus/config.edn`) is a **Proximum** HNSW
memory backend with a **LangChain4j** in-process ONNX embedder
(`all-MiniLM-L6-v2`, 384 dims) — session memory (recent + semantic recall)
works out of the box. To disable memory, restore noop components:

```clojure
{:lateralus/embedder       {:method :noop}
 :lateralus/memory-backend {:impl :noop}}
```

To make memory durable across JVM restarts, add a file-backed `:store`:

```clojure
{:lateralus/memory-backend
 {:impl :proximum
  :embedder #ig/ref :lateralus/embedder
  :store {:backend :file
          :path "sessions/proximum"
          :id #uuid "465df026-fcd3-4cb3-be44-29a929776250"}}}
```

See [`docs/memory-v2.md`](docs/memory-v2.md) for the full reference.
Requirements: Java 22+; the `jdk.incubator.vector` JVM flags are baked into
the `:run`, `:workbench`, `:test`, and uberjar launcher. LangChain4j in-process
embedding uses ONNX and is **not compatible with GraalVM native-image** — the
native config uses the pure-Clojure **KG + BM25** backend and a noop embedder.

## Docker

See [`docker/README.md`](docker/README.md). Summary:

| Item | Value |
|------|--------|
| One-liner | `./scripts/start-workbench` |
| Workbench | http://localhost:7860 |
| Portal iframe | http://localhost:7870 |
| Config volume | `lateralus-config` → `/data/config` |
| Local LLM | compose `ollama` service |
| Cloud | `OLLAMA_API_KEY=…` and pick **ollama-cloud** in the profile gate (do not force `LATERALUS_BASE_URL` to the local Ollama service) |

The uberjar build includes the `:workbench` alias (portal + http-kit).

## Build

```bash
clojure -T:build test   # default suite, no e2e
clojure -T:build uber   # workbench deps included
./target/lateralus-v2 -h
echo "ping" | ./target/lateralus-v2
docker compose build lateralus
```

This produces:
- `target/net.clojars.kschltz/lateralus-v2-0.1.0-SNAPSHOT.jar`
- `target/lateralus-v2` — executable launcher script
- Docker image `lateralus-v2-lateralus` via compose

### End-to-end tests

A separate `^:e2e` namespace exercises a real HTTP LlmClient, the LangChain4j
embedder, and the Proximum backend against a local Ollama instance (default
`http://localhost:11434/v1`, model `glm5.1:cloud`):

```bash
clojure -M:e2e
LATERALUS_E2E_MODEL=llama3.1:latest LATERALUS_E2E_BASE_URL=http://localhost:11434/v1 clojure -M:e2e
```

For deterministic assertions without a real LLM, use the bundled fake server:

```bash
LATERALUS_E2E_FAKE=true clojure -M:e2e
```

Live-LLM and list-models e2e tests auto-skip when Ollama is unreachable.
The interceptor/runtime/file harness has a deterministic offline scenario
(it initializes the production Integrant graph, then drives runtime
introspection, policy/tool transitions, deferred reload, `file_read`, and a
hash-anchored `file_patch` through the real ReAct interceptor chain):

```bash
clojure -M:e2e:workbench -n kschltz.agent.runtime-harness-e2e-test
```

MCP live e2e:

```bash
LATERALUS_E2E_MCP=live clojure -M:e2e -n kschltz.agent.tools.mcp.mcp-e2e-test
```

The default `clojure -M:test` and `clojure -T:build test` exclude these slow
integration tests.

### GraalVM native-image

The `:native` alias builds a self-contained executable that excludes the
JVM-only Proximum HNSW backend, the LangChain4j embedder, and the live web
providers. Instead it uses the KG + BM25 memory backend and the bundled stub
LLM (or an HTTP LLM when `--model`/`--base-url` are supplied).

Requirements: [GraalVM JDK](https://www.graalvm.org/downloads/) and
`GRAALVM_HOME` exported. Build:

```bash
export GRAALVM_HOME=/path/to/graalvm
clojure -T:native native
./target/lateralus-v2-native --config resources/lateralus/native.edn "hello"
```

What the build does:
- Creates `target/lateralus-v2-native.jar` from a filtered classpath omitting
  `proximum_backend.clj`, `langchain4j_embedding.clj`, `mojeek.clj`, and
  `ddg.clj`.
- Compiles Clojure with `-Dclojure.compiler.direct-linking=true` and
  `*warn-on-reflection*` enabled.
- Invokes `native-image` with `--features=clj_easy.graal_build_time.InitClojureClasses`.

Notes:
- Native-image users must pass `--config resources/lateralus/native.edn`.
- The native config uses a **noop embedder**; recall is keyword-based
  (BM25 + small KG). For dense embeddings in native-image, configure an HTTP
  embedder (`:method :http`).
- The binary has not been exercised on CI; manual verification on a host with
  GraalVM is required.

## Project structure

| Path | Description |
|------|-------------|
| [`goals/`](goals/) | Goal folders (facts / plan / interview artifacts): `lateralus-v2-rewrite` (active), `mcp-client-tools`, `dynamic-mcp-tool-setup`, `clojure-structured-edit-tools`, `lateralus-file-editing`, `interceptor-runtime-file-harness` |
| [`docs/architecture.md`](docs/architecture.md) | Architecture overview, component graph, plugin slot vocabulary |
| [`docs/transitions.md`](docs/transitions.md) | Staged state transitions + session-config tools |
| [`docs/runtime-tools.md`](docs/runtime-tools.md) | Runtime tool factory (`tool_define`/`tool_promote`) + workflow artifact engine |
| [`docs/runtime-eval.md`](docs/runtime-eval.md) | `clojure_eval` / `clojure_add_lib` / `clojure_loaded_libs` |
| [`docs/web.md`](docs/web.md) | Web tools: `:none` default, `:mojeek`/`:ddg` opt-in, guards, native-image story |
| [`docs/mcp.md`](docs/mcp.md) | MCP client tools: stdio + Streamable HTTP, naming, guards, e2e |
| [`docs/workbench-2way.md`](docs/workbench-2way.md) | Portal 2-way loop: `portal_selected` read-back, `/api/portal-event` callback, trust model |
| [`docs/secrets.md`](docs/secrets.md) | Secrets plugin: `{{secret:label}}` handles, sealed AES-GCM store, workbench UI/API |
| [`docs/skills.md`](docs/skills.md) | Skill packs: Malli-enforced `.edn` skills, tiered progressive disclosure |
| [`docs/memory-v2.md`](docs/memory-v2.md) | Memory subsystem design and backend configuration reference |
| [`docs/memory-backend-research.md`](docs/memory-backend-research.md) | Decision log for memory backend selection |
| [`docs/duckdb-core-engine.md`](docs/duckdb-core-engine.md) | Options: DuckDB as persistence substrate (not a chain replacement) |
| [`docs/file-index.md`](docs/file-index.md) | Opt-in workspace file index + edit log (`StoreEngine`) |
| [`docs/memory-embedding-free-alternatives.md`](docs/memory-embedding-free-alternatives.md) | Embedding-free memory strategies and the `:kg-bm25` native default |
| [`docs/network-boundaries.md`](docs/network-boundaries.md) | Protocol isolation + Malli instrumentation matrix |
| [`docs/stream.md`](docs/stream.md) | Streaming bus design |
| [`docs/transitions.md`](docs/transitions.md) | Transition algebra reference |
| [`docs/dynamic-mcp-tool-setup.md`](docs/dynamic-mcp-tool-setup.md) | Exploration: mid-session MCP tool setup via transitions |
| [`src/kschltz/lateralus.clj`](src/kschltz/lateralus.clj) | `-main` entry point; delegates to CLI |
| [`src/kschltz/agent/cli.clj`](src/kschltz/agent/cli.clj) | Argument parsing, Integrant init/halt, runtime invocation |
| [`src/kschltz/agent/cli/spinner.clj`](src/kschltz/agent/cli/spinner.clj) | CLI spinner / progress indicator |
| [`src/kschltz/agent/cli/profile/`](src/kschltz/agent/cli/profile/) | Interactive AWS-style profile gate + tool-group checklist |
| [`src/kschltz/agent/runtime.clj`](src/kschltz/agent/runtime.clj) | Outer loop: ctx creation + chain call + state merge |
| [`src/kschltz/agent/runtime_reload.clj`](src/kschltz/agent/runtime_reload.clj) | Allowlisted namespace reload + chain rebuild |
| [`src/kschltz/agent/system.clj`](src/kschltz/agent/system.clj) | Integrant component definitions, default config, Malli `ig/assert-key` validation |
| [`src/kschltz/agent/chain.clj`](src/kschltz/agent/chain.clj) | Interceptor engine |
| [`src/kschltz/agent/plugin.clj`](src/kschltz/agent/plugin.clj) | Plugin assembly and slot-order contract |
| [`src/kschltz/agent/plugins/base.clj`](src/kschltz/agent/plugins/base.clj) | Default base plugin with the standard exchange chain |
| [`src/kschltz/agent/plugins/memory.clj`](src/kschltz/agent/plugins/memory.clj) | Memory plugin: recall (`:enrich`) and persist (`:persist`) |
| [`src/kschltz/agent/plugins/tools.clj`](src/kschltz/agent/plugins/tools.clj) | Tool plugin: seeds `:agent/tool-registry` |
| [`src/kschltz/agent/plugins/secrets.clj`](src/kschltz/agent/plugins/secrets.clj) | Secrets plugin: `:guard` tool wrap + redaction sweep |
| [`src/kschltz/agent/plugins/skills.clj`](src/kschltz/agent/plugins/skills.clj) | Skills plugin: catalog in compose slot + tool registration |
| [`src/kschltz/agent/plugins/summarizer.clj`](src/kschltz/agent/plugins/summarizer.clj) | Summarizer plugin (context compaction) |
| [`src/kschltz/agent/plugins/workbench.clj`](src/kschltz/agent/plugins/workbench.clj) | CHAT \| Portal workbench plugin |
| [`src/kschltz/agent/workbench/`](src/kschltz/agent/workbench/) | Workbench implementation: `hub`, `http`, `portal`, `jvm`, settings/sessions/secrets HTTP, tools, guidance |
| [`src/kschltz/agent/secrets.clj`](src/kschltz/agent/secrets.clj) | `SecretStore` protocol, sealed-file store, substitution + redaction |
| [`src/kschltz/agent/skills.clj`](src/kschltz/agent/skills.clj) | Skills: Malli schema, fail-closed loader, catalog + tools |
| [`src/kschltz/agent/tool.clj`](src/kschltz/agent/tool.clj) | `Tool` protocol and registry helpers |
| [`src/kschltz/agent/transitions.clj`](src/kschltz/agent/transitions.clj) | Allowlisted state-transition algebra |
| [`src/kschltz/agent/store/`](src/kschltz/agent/store/) | Opt-in `StoreEngine` + FileIndex (memory or DuckDB) |
| [`src/kschltz/agent/tools/`](src/kschltz/agent/tools/) | Tool namespaces: `filesystem.clj`, `config/`, `runtime/`, `web/`, `workflow/`, plus MCP tool glue |
| [`src/kschltz/agent/interceptors.clj`](src/kschltz/agent/interceptors.clj) | Core interceptor stages |
| [`src/kschltz/agent/interceptors/schema.clj`](src/kschltz/agent/interceptors/schema.clj) | Interceptor and context Malli schemas |
| [`src/kschltz/agent/llm/`](src/kschltz/agent/llm/) | `LlmClient` protocol, stub + HTTP impl, OpenAI-shaped schemas |
| [`src/kschltz/agent/memory/`](src/kschltz/agent/memory/) | `MemoryBackend`/`Embedder` protocols + Proximum, KG-BM25, LangChain4j, HTTP, noop impls |
| [`resources/lateralus/config.edn`](resources/lateralus/config.edn) | JVM runtime default config (Proximum + LangChain4j + file-tools) |
| [`resources/lateralus/native.edn`](resources/lateralus/native.edn) | Native-image config (KG-BM25 + noop embedder + file-tools) |
| [`resources/lateralus/demo-workbench.edn`](resources/lateralus/demo-workbench.edn) | Ollama + CHAT \| Portal workbench profile (with commented secrets opt-in) |
| [`resources/lateralus/demo-file-index-workbench.edn`](resources/lateralus/demo-file-index-workbench.edn) | Offline CHAT \| Portal + DuckDB file index (pair with `file-index-demo-llm`) |
| [`resources/lateralus/`](resources/lateralus/) | Other runnable profiles (Ollama local/cloud, MCP, native, stub) |
| [`AGENT_INSTRUCTIONS.md`](AGENT_INSTRUCTIONS.md) | Short contributor guide |

## Status

Implemented steps 1–10: bootstrap, chain engine, plugin system, Integrant
system with Malli pre-init validation, HTTP-backed `LlmClient`, memory plugin
with Proximum HNSW + LangChain4j ONNX defaults (KG + BM25 for native), the
tool-calling loop, clean-slate CLI with interactive profile gate, GraalVM
native-image build, docs and quality-gate tests.

Recently completed:
- **Workbench CHAT \| Portal** — session persistence, settings UI (LLM/system/
  loop/memory/tools), secrets-management UI, live tool panel; `settings_http`,
  `session_http`, `secrets_http` surfaces
- **Portal 2-way loop** — `portal_selected` read-back and `/api/portal-event`
  artifact call-back; interactive-artifact guidance
- **Secrets plugin** — sealed `LATSEC1` store, deny-by-default per-tool/label
  capabilities, untrusted-runtime plaintext isolation, output redaction sweep,
  `secret_list_handles` / `secret_check`, opt-in Integrant wiring
- **Skill packs** — `.edn` skills with Malli-closed schema, tiered progressive
  disclosure (`load_skill` / `read_skill_file`), fail-closed loading
- **Runtime tool factory + workflow engine** — `tool_define`/`tool_promote`,
  `:needs`/`:produces` artifact DAG scheduling
- **MCP client + management tools** — stdio + Streamable HTTP, mid-session
  upsert/refresh/remove, SSRF guards
- Dynamic tool setup (`dynamic-mcp-tool-setup` goal), Clojure structured-edit
  tools, interceptor/runtime/file harness e2e

Deferred:
- Async worker thread for the runtime
- Tier-3 *scripts in skills* (skill-declared scripts executed via existing
  shell/eval tools so stdout enters context without script text)
- Multi-agent communication plugin

## License

Eclipse Public License 2.0 (same as v1)