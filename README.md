# lateralus-v2

Greenfield rewrite of [Lateralus](https://github.com/kschltz/lateralus) (v1 archive). A Clojure LLM agent with an interceptor-chain runtime, Integrant lifecycles, session memory contract, and a clean-slate CLI.

**Coord:** `net.clojars.kschltz/lateralus-v2`  
**Main ns:** `kschltz.lateralus`  
**License:** [Eclipse Public License 2.0](LICENSE)

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
clojure -M:run "Explain recursion"

# Tests
clojure -M:test
clojure -M:e2e
LATERALUS_E2E_FAKE=true clojure -M:e2e
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
Model prompts
accept `?` (list catalog) and `/term` (filter), backed by the endpoint’s `/v1/models`
(and Ollama Cloud when keyed).

Examples:

```bash
# Workbench + profile gate
clojure -M:workbench:run -i

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
2. **Integrant lifecycles** — the LLM client, embedder, memory backend, and plugins are Integrant-managed components.
3. **Thin outer runtime** — `kschltz.agent.runtime` creates a per-exchange context with traceability IDs, runs the chain, and merges `:agent/state-delta` into an in-memory atom.

For details, see [`docs/architecture.md`](docs/architecture.md).

## Session config tools

The agent can change its own LLM session knobs mid-run via staged
**transitions** (not by mutating the runtime atom from a tool):

- `set_llm_config` — update `:model`, `:base-url`, and/or `:api-key` for the
  rest of the session; applies before the next LLM call (including ReAct
  follow-ups in the same exchange).
- `set_system_message` — replace the durable system instruction through the
  transition/state-delta path.
- `set_loop_policy` — update allowlisted loop depth, tool-call, and
  per-tool-content limits for the current and later exchanges.
- `list_llm_models` — list models at the current (or overridden) endpoint
  behind the `ModelCatalog` protocol.

Wired via `:lateralus/config-tools` (`:catalog :http` or `:stub`). See
[`docs/transitions.md`](docs/transitions.md).

## Web tool

The agent now ships `web_search`, `web_fetch`, and `web_extract` tools. The
default provider is `:none`, so **no API key, no paid service, and no network
I/O** are required out of the box. `web_extract` still works on raw HTML in
air-gapped mode. Live web access is opt-in via `:provider :mojeek` or `:provider :ddg`.

The `:mojeek` provider parses Mojeek's public HTML result pages with
`hickory`. The `:ddg` provider queries DuckDuckGo's `html.duckduckgo.com/html`
endpoint with a browser TLS/HTTP2 fingerprint (`impersonator-okhttp`) so DDG
returns real HTML instead of a CAPTCHA page. Both are JVM-only and excluded
from the GraalVM native-image classpath; `resources/lateralus/native.edn` pins
`:provider :none`.

All three ops are guarded against SSRF (private/loopback/metadata IPs,
`file://`, protocol-relative URLs), prompt-injection markers, recursive
self-activation, and snippet exfiltration patterns. See [`docs/web.md`](docs/web.md).

## Runtime-eval tool

The agent ships a Clojure runtime-eval suite for prototyping: it can write
Clojure code and actually run it, then pull in missing dependencies at
runtime without a JVM restart.

- `clojure_eval` — evaluate Clojure source in a **persistent** runtime
  namespace (`def`s and `require`s persist across calls). Returns the
  last form's value, captured stdout, and any exception. Each call is
  bounded by a configurable timeout so runaway loops are cancelled.
- `clojure_add_lib` — load a Maven (or Git) dependency onto the live
  classpath via Clojure 1.12's `clojure.repl.deps/add-libs`. After it
 returns, `require` the new namespaces from `clojure_eval`.
- `clojure_loaded_libs` — list the libs currently loaded in the JVM.

The suite sits behind the `ClojureRuntime` protocol with a
Malli-instrumented network boundary, and is wired via the
`:lateralus/runtime-tools` Integrant key (enabled by default in the JVM
config; excluded from native-image). It runs arbitrary Clojure in-process
— set `:enabled? false` to disable it, or `:network? false` to keep eval
but block runtime dependency loading. See [`docs/runtime-eval.md`](docs/runtime-eval.md).

```clojure
:lateralus/runtime-tools {:enabled? true :network? true}
```

## File harness

The default tool registry includes bounded, line-numbered file reads and safe
create/write/update operations:

- `file_read` returns a window, continuation metadata, and a SHA-256 witness.
- `file_update` applies ambiguity-safe text edits atomically and detects races.
- `file_write` accepts `expected-sha256` to reject stale full-file replacements.
- `file_create` is create-only; it never silently overwrites an existing file.

All mutations use canonical workspace containment (including symlink
resolution), blocked-path checks, per-path locks, size/omission guards, atomic
moves, and write verification. Replacement writes also create timestamped
backups. These tools enter through the same `Tool` dispatch interceptor as
every other agent capability.

## Runtime introspection

`runtime_describe` exposes the active runtime as redacted structured data. It
can return the session summary and loop policy, registered tool contracts, the
ordered interceptor chain, or all three. API keys and live implementation
objects are never serialized. The tool reads the immutable per-exchange
context; it does not bypass the transition/state-delta model.

## MCP client tools

Lateralus can attach **stdio** MCP servers (Claude Desktop / Cursor
`command` / `args` / `env`) and **remote Streamable HTTP** MCP endpoints
(`:url` + optional Bearer/headers). Default config is air-gapped
(`:servers {}`). Opt in via `:lateralus/mcp-tools`; tools are discovered at
Integrant init, remapped to portable prefixed ids (`filesystem_read_file`),
and closed on halt. Remote URLs are SSRF-checked (https-only; loopback
blocked unless opted in). See [`docs/mcp.md`](docs/mcp.md).

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

The runtime Integrant config (`resources/lateralus/config.edn`) now wires a **Proximum** HNSW memory backend and a **LangChain4j** in-process ONNX embedder (`all-MiniLM-L6-v2`, 384 dimensions) by default. Session memory (recent + semantic recall) works out of the box in one-shot mode.

To disable memory and restore the noop behavior:

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

See [`docs/memory-v2.md`](docs/memory-v2.md) for the full configuration reference.

Requirements:
- Java 22+
- JVM flags `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` (baked into the `:run`, `:workbench`, `:test`, and uberjar launcher — no manual `-J` flags needed)

**Note:** LangChain4j in-process embedding uses ONNX and native tokenizer libraries, so it is **not compatible with GraalVM native-image**. For native-image, switch to an HTTP embedder.

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

### End-to-end memory tests

A separate `^:e2e` namespace exercises a real HTTP LlmClient, the
LangChain4j embedder, and the Proximum backend against a local Ollama
instance. Defaults:

- base URL: `http://localhost:11434/v1`
- model: `glm5.1:cloud`

Run it with:

```bash
clojure -M:e2e
```

Override with env vars:

```bash
LATERALUS_E2E_MODEL=llama3.1:latest LATERALUS_E2E_BASE_URL=http://localhost:11434/v1 clojure -M:e2e
```

For deterministic assertions without a real LLM, use the bundled fake server:

```bash
LATERALUS_E2E_FAKE=true clojure -M:e2e
```

The default `clojure -M:test` and `clojure -T:build test` exclude these
slow integration tests.

### GraalVM native-image

The `:native` alias builds a self-contained executable that excludes the
JVM-only Proximum HNSW backend and the LangChain4j in-process ONNX embedder.
Instead it uses the pure-Clojure **KG + BM25 memory backend** and the bundled
**stub LLM** (or an HTTP LLM when `--model`/`--base-url` are supplied).

Requirements:
- [GraalVM JDK](https://www.graalvm.org/downloads/) installed locally
- `GRAALVM_HOME` exported

Build:

```bash
export GRAALVM_HOME=/path/to/graalvm
clojure -T:native native
```

The binary is written to `target/lateralus-v2-native`. Run it with the native
config:

```bash
./target/lateralus-v2-native --config resources/lateralus/native.edn "hello"
echo "one-shot via stdin" | ./target/lateralus-v2-native --config resources/lateralus/native.edn
```

What the build does:
- Creates `target/lateralus-v2-native.jar` from a filtered classpath that omits
  `src/kschltz/agent/memory/proximum_backend.clj`,
  `src/kschltz/agent/memory/langchain4j_embedding.clj`,
  `src/kschltz/agent/tools/web/mojeek.clj`, and
  `src/kschltz/agent/tools/web/ddg.clj`.
- Compiles Clojure with `-Dclojure.compiler.direct-linking=true` and
  `*warn-on-reflection*` enabled.
- Invokes `native-image` with `--features=clj_easy.graal_build_time.InitClojureClasses`
  so Clojure classes are initialized at build time correctly.

Notes and limitations:
- The default `resources/lateralus/config.edn` still selects Proximum +
  LangChain4j for the normal JVM run. Native-image users must pass
  `--config resources/lateralus/native.edn`.
- The native config uses a **noop embedder**; memory recall is keyword-based
  (BM25 + small KG). If you need dense embeddings in native-image, configure
  an HTTP embedder (`:method :http`) in a custom config.
- The binary has not been exercised on CI in this repository yet; manual
  verification on a host with GraalVM is required.

## Project structure

| Path | Description |
|------|-------------|
| [`goals/lateralus-v2-rewrite/`](goals/lateralus-v2-rewrite/) | Active goal — facts, plan, interview artifacts |
| [`docs/architecture.md`](docs/architecture.md) | Architecture overview, component graph, and plugin slot vocabulary |
| [`docs/interceptor-loop-design-note.md`](docs/interceptor-loop-design-note.md) | Historical interceptor-chain thesis (superseded, kept for context) |
| [`docs/memory-v2.md`](docs/memory-v2.md) | Memory subsystem design and backend configuration reference |
| [`docs/memory-backend-research.md`](docs/memory-backend-research.md) | Decision log for memory backend selection |
| [`docs/memory-embedding-free-alternatives.md`](docs/memory-embedding-free-alternatives.md) | Embedding-free memory strategies and current `:kg-bm25` default |
| [`docs/web.md`](docs/web.md) | Web tool design: `:none` default, `:mojeek`/`:ddg` opt-in, guards, native-image story |
| [`docs/transitions.md`](docs/transitions.md) | Staged state transitions + `set_llm_config` / `list_llm_models` |
| [`docs/dynamic-mcp-tool-setup.md`](docs/dynamic-mcp-tool-setup.md) | Exploration: mid-session MCP tool setup via transitions |
| [`docs/runtime-eval.md`](docs/runtime-eval.md) | Runtime-eval tool suite: `clojure_eval`, `clojure_add_lib`, `clojure_loaded_libs` |
| [`docs/mcp.md`](docs/mcp.md) | MCP client tools: stdio servers, naming, guards, offline/live e2e |
| [`src/kschltz/lateralus.clj`](src/kschltz/lateralus.clj) | `-main` entry point; delegates to CLI |
| [`src/kschltz/agent/cli.clj`](src/kschltz/agent/cli.clj) | Argument parsing, Integrant init/halt, runtime invocation |
| [`src/kschltz/agent/cli/spinner.clj`](src/kschltz/agent/cli/spinner.clj) | CLI spinner / progress indicator |
| [`src/kschltz/agent/runtime.clj`](src/kschltz/agent/runtime.clj) | Outer loop: ctx creation + chain call + state merge |
| [`src/kschltz/agent/system.clj`](src/kschltz/agent/system.clj) | Integrant component definitions, default config, Malli `ig/assert-key` validation |
| [`src/kschltz/agent/chain.clj`](src/kschltz/agent/chain.clj) | Interceptor engine |
| [`src/kschltz/agent/plugin.clj`](src/kschltz/agent/plugin.clj) | Plugin assembly and slot-order contract |
| [`src/kschltz/agent/plugins/base.clj`](src/kschltz/agent/plugins/base.clj) | Default base plugin with the standard exchange chain |
| [`src/kschltz/agent/plugins/memory.clj`](src/kschltz/agent/plugins/memory.clj) | Memory plugin: recall (`:enrich`) and persist (`:persist`) |
| [`src/kschltz/agent/plugins/tools.clj`](src/kschltz/agent/plugins/tools.clj) | Tool plugin: seeds `:agent/tool-registry` |
| [`src/kschltz/agent/loop.clj`](src/kschltz/agent/loop.clj) | Tool-calling loop interceptors |
| [`src/kschltz/agent/tool.clj`](src/kschltz/agent/tool.clj) | `Tool` protocol and registry helpers |
| [`src/kschltz/agent/transitions.clj`](src/kschltz/agent/transitions.clj) | Allowlisted state-transition algebra |
| [`src/kschltz/agent/transitions/interceptors.clj`](src/kschltz/agent/transitions/interceptors.clj) | Harvest/apply transition interceptors |
| [`src/kschltz/agent/tools/config.clj`](src/kschltz/agent/tools/config.clj) | `set_llm_config` + `list_llm_models` tools |
| [`src/kschltz/agent/tools/config/catalog.clj`](src/kschltz/agent/tools/config/catalog.clj) | `ModelCatalog` protocol (HTTP / stub) |
| [`src/kschltz/agent/tools/filesystem.clj`](src/kschltz/agent/tools/filesystem.clj) | Read-only filesystem `Tool` implementations |
| [`src/kschltz/agent/tools/runtime/protocol.clj`](src/kschltz/agent/tools/runtime/protocol.clj) | `ClojureRuntime` protocol (runtime-eval boundary) |
| [`src/kschltz/agent/tools/runtime/schemas.clj`](src/kschltz/agent/tools/runtime/schemas.clj) | Runtime-eval Malli schemas + config |
| [`src/kschltz/agent/tools/runtime/jvm.clj`](src/kschltz/agent/tools/runtime/jvm.clj) | In-process `ClojureRuntime` impl (eval + `add-libs`), Malli-instrumented |
| [`src/kschltz/agent/tools/runtime/tools.clj`](src/kschltz/agent/tools/runtime/tools.clj) | `clojure_eval`, `clojure_add_lib`, `clojure_loaded_libs` Tool implementations |
| [`src/kschltz/agent/tools/web/protocol.clj`](src/kschltz/agent/tools/web/protocol.clj) | `WebProvider` protocol |
| [`src/kschltz/agent/tools/web/schemas.clj`](src/kschltz/agent/tools/web/schemas.clj) | Web tool Malli schemas |
| [`src/kschltz/agent/tools/web/guards.clj`](src/kschltz/agent/tools/web/guards.clj) | URL/query/snippet guard pipeline |
| [`src/kschltz/agent/tools/web/none.clj`](src/kschltz/agent/tools/web/none.clj) | `:none` provider (air-gapped default) |
| [`src/kschltz/agent/tools/web/mojeek.clj`](src/kschltz/agent/tools/web/mojeek.clj) | `:mojeek` live provider (JVM-only, opt-in) |
| [`src/kschltz/agent/tools/web/ddg.clj`](src/kschltz/agent/tools/web/ddg.clj) | `:ddg` live provider (JVM-only, opt-in; impersonator TLS fingerprint) |
| [`src/kschltz/agent/tools/web/web.clj`](src/kschltz/agent/tools/web/web.clj) | `web_search`, `web_fetch`, `web_extract` Tool implementations |
| [`src/kschltz/agent/interceptors.clj`](src/kschltz/agent/interceptors.clj) | Core interceptor stages |
| [`src/kschltz/agent/interceptors/schema.clj`](src/kschltz/agent/interceptors/schema.clj) | Interceptor and context Malli schemas |
| [`src/kschltz/agent/llm/client.clj`](src/kschltz/agent/llm/client.clj) | `LlmClient` protocol + stub + HTTP wrapper |
| [`src/kschltz/agent/llm/schemas.clj`](src/kschltz/agent/llm/schemas.clj) | OpenAI-shaped request/response Malli schemas |
| [`src/kschltz/agent/memory/protocol.clj`](src/kschltz/agent/memory/protocol.clj) | `MemoryBackend` protocol |
| [`src/kschltz/agent/memory/embedding.clj`](src/kschltz/agent/memory/embedding.clj) | `Embedder` protocol + noop implementation |
| [`src/kschltz/agent/memory/http_embedding.clj`](src/kschltz/agent/memory/http_embedding.clj) | OpenAI-compatible HTTP `Embedder` |
| [`src/kschltz/agent/memory/langchain4j_embedding.clj`](src/kschltz/agent/memory/langchain4j_embedding.clj) | LangChain4j in-process ONNX `Embedder` |
| [`src/kschltz/agent/memory/proximum_backend.clj`](src/kschltz/agent/memory/proximum_backend.clj) | Proximum HNSW `MemoryBackend` |
| [`src/kschltz/agent/memory/kg_bm25.clj`](src/kschltz/agent/memory/kg_bm25.clj) | KG + BM25 `MemoryBackend` facade |
| [`src/kschltz/agent/memory/bm25.clj`](src/kschltz/agent/memory/bm25.clj) | BM25 scoring |
| [`src/kschltz/agent/memory/knowledge_graph.clj`](src/kschltz/agent/memory/knowledge_graph.clj) | Entity knowledge graph |
| [`src/kschltz/agent/memory/store/file.clj`](src/kschltz/agent/memory/store/file.clj) | File-backed store for KG-BM25 |
| [`src/kschltz/agent/memory/noop_backend.clj`](src/kschltz/agent/memory/noop_backend.clj) | noop `MemoryBackend` |
| [`resources/lateralus/config.edn`](resources/lateralus/config.edn) | JVM runtime default config (Proximum + LangChain4j + file-tools) |
| [`resources/lateralus/native.edn`](resources/lateralus/native.edn) | Native-image runtime config (KG-BM25 + noop embedder + file-tools) |
| [`AGENT_INSTRUCTIONS.md`](AGENT_INSTRUCTIONS.md) | Short contributor guide |

## Status

Implemented:
- Steps 1–5: bootstrap, chain engine, plugin system, Integrant system, real HTTP-backed `LlmClient` + Malli schemas
- Step 6: memory plugin interceptors + noop `MemoryBackend`; **Proximum HNSW backend** + **LangChain4j in-process ONNX embedder** as the JVM runtime default; **KG + BM25 backend** as the native-image default
- Step 7: agent outer loop + traceability (synchronous MVP design)
- Step 8: clean-slate CLI
- Step 9: **GraalVM native-image build** with the KG + BM25 backend and a filtered classpath that excludes JVM-only Proximum / LangChain4j sources
- Step 10: docs, JVM distributable, quality-gate tests

Recently completed:
- [006] Replace shallow state merge with deep `merge-state`
- [007] Pre-wire dependencies into context instead of `bind-llm-client`
- [008] Refactor KG-BM25 backend into focused namespaces
- [009] Add Malli pre-init validation to Integrant components (`ig/assert-key` for `:lateralus/llm-client`, `:lateralus/embedder`, and `:lateralus/memory-backend`)
- [011] Promote tool-calling loop into the base plugin + filesystem tools example
- [web] Revive web tool: `:none` default, `:mojeek`/`:ddg` opt-in live providers, full guard pipeline (SSRF/UA/redirect), Integrant wiring, docs
- [runtime-eval] Clojure runtime-eval tool suite: `clojure_eval` (persistent runtime ns + timeout), `clojure_add_lib` (Clojure 1.12 runtime dependency loading), `clojure_loaded_libs`, behind the `ClojureRuntime` protocol with a Malli-instrumented network boundary

Deferred:
- Async worker thread for the runtime
- Environment-variable support for `LATERALUS_V2_*`
- Multi-agent communication plugin (`docs/file-backed-comms-plan-consensus.md`)

## License

Eclipse Public License 2.0 (same as v1)
