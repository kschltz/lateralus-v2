# lateralus-v2

Greenfield rewrite of [Lateralus](../lateralus) (v1 archive). A Clojure LLM agent with an interceptor-chain runtime, Integrant lifecycles, session memory contract, and a clean-slate CLI.

**Coord:** `net.clojars.kschltz/lateralus-v2`  
**Main ns:** `kschltz.lateralus`

## Quick start

```bash
cd lateralus-v2

# Run the default test suite (excludes slow ^:e2e tests)
clojure -M:test

# Run the end-to-end memory tests (requires a local Ollama instance or
# LATERALUS_E2E_FAKE=true for the deterministic fake-server mode)
clojure -M:e2e
LATERALUS_E2E_FAKE=true clojure -M:e2e

# One-shot from stdin (default LLM is the stub, so override --model/--base-url for a real response;
# default memory is Proximum in-memory + LangChain4j ONNX embedder)
echo "What is the capital of France?" | clojure -M:run

# Note: `clojure -M:run` needs the same JVM flags the uberjar launcher provides:
#   -J--add-modules=jdk.incubator.vector -J--enable-native-access=ALL-UNNAMED

# One-shot from a positional argument
clojure -M:run "Explain recursion"

# Help
clojure -M:run -h

# Note: the default runtime config loads Proximum + LangChain4j, which need
#   --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED
# The uberjar launcher already includes these flags; pass them manually with
#   clojure -J--add-modules=jdk.incubator.vector -J--enable-native-access=ALL-UNNAMED -M:run ...
```

## CLI

```
Usage: lateralus [flags] [prompt]

Flags:
  -h, --help               show help and exit
  --version                show version and exit
  -i, --interactive        read prompts from stdin, line-by-line
  --no-interactive         force one-shot mode (default)
  -s, --session ID         session id (default: random-uuid)
  --config PATH            Integrant EDN config (default: built-in)
  --model NAME             LLM model name (overrides config)
  --base-url URL           LLM base URL (overrides config)
  --api-key KEY            LLM API key (overrides config; env support is a follow-up)
```

Examples:

```bash
# Named session (memory is now enabled by default)
clojure -M:run -s my-session "Hello"

# Use a real HTTP-backed LLM
clojure -M:run \
  --model gpt-4 \
  --base-url https://api.openai.com/v1 \
  --api-key "$OPENAI_API_KEY" \
  "Hello"

# Persistent memory: file-backed Proximum + LangChain4j embedder
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

## Web tool

The agent now ships `web/search`, `web/fetch`, and `web/extract` tools. The
default provider is `:none`, so **no API key, no paid service, and no network
I/O** are required out of the box. `web/extract` still works on raw HTML in
air-gapped mode. Live web access is opt-in via `:provider :mojeek`.

The `:mojeek` provider parses Mojeek's public HTML result pages with
`hickory`. It is JVM-only and excluded from the GraalVM native-image classpath;
`resources/lateralus/native.edn` pins `:provider :none`.

All three ops are guarded against SSRF (private/loopback/metadata IPs,
`file://`, protocol-relative URLs), prompt-injection markers, recursive
self-activation, and snippet exfiltration patterns. See [`docs/web.md`](docs/web.md).

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
- JVM flags `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` (included in the uberjar launcher; pass them manually when running via `clojure -M:run`).

**Note:** LangChain4j in-process embedding uses ONNX and native tokenizer libraries, so it is **not compatible with GraalVM native-image**. For native-image, switch to an HTTP embedder.


## Build

```bash
clojure -T:build test   # default suite, no e2e
clojure -T:build uber
./target/lateralus-v2 -h
echo "ping" | ./target/lateralus-v2
```

This produces:
- `target/net.clojars.kschltz/lateralus-v2-0.1.0-SNAPSHOT.jar`
- `target/lateralus-v2` — executable launcher script

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
  `src/kschltz/agent/memory/langchain4j_embedding.clj`, and
  `src/kschltz/agent/tools/web/mojeek.clj`.
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
| [`docs/web.md`](docs/web.md) | Web tool design: `:none` default, `:mojeek` opt-in, guards, native-image story |
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
| [`src/kschltz/agent/tools/filesystem.clj`](src/kschltz/agent/tools/filesystem.clj) | Read-only filesystem `Tool` implementations |
| [`src/kschltz/agent/tools/web/protocol.clj`](src/kschltz/agent/tools/web/protocol.clj) | `WebProvider` protocol |
| [`src/kschltz/agent/tools/web/schemas.clj`](src/kschltz/agent/tools/web/schemas.clj) | Web tool Malli schemas |
| [`src/kschltz/agent/tools/web/guards.clj`](src/kschltz/agent/tools/web/guards.clj) | URL/query/snippet guard pipeline |
| [`src/kschltz/agent/tools/web/none.clj`](src/kschltz/agent/tools/web/none.clj) | `:none` provider (air-gapped default) |
| [`src/kschltz/agent/tools/web/mojeek.clj`](src/kschltz/agent/tools/web/mojeek.clj) | `:mojeek` live provider (JVM-only, opt-in) |
| [`src/kschltz/agent/tools/web/web.clj`](src/kschltz/agent/tools/web/web.clj) | `web/search`, `web/fetch`, `web/extract` Tool implementations |
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
- [web] Revive web tool: `:none` default, `:mojeek` opt-in live provider, full guard pipeline, Integrant wiring, docs

Deferred:
- Async worker thread for the runtime
- Environment-variable support for `LATERALUS_V2_*`
- Multi-agent communication plugin (`docs/file-backed-comms-plan-consensus.md`)

## License

Eclipse Public License 2.0 (same as v1)
