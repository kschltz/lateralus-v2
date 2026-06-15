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

# One-shot from stdin (uses Proximum in-memory memory + LangChain4j ONNX embedder by default)
echo "What is the capital of France?" | clojure -M:run

# One-shot from a positional argument
clojure -M:run "Explain recursion"

# Help
clojure -M:run -h
```

## CLI

```
Usage: lateralus [flags] [prompt]

Flags:
  -h, --help               show help and exit
  --version                show version and exit
  -i, --interactive        read prompts from stdin, line-by-line (placeholder)
  --no-interactive         force one-shot mode (default)
  -s, --session ID       session id (default: random-uuid)
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
  `src/kschltz/agent/memory/proximum_backend.clj` and
  `src/kschltz/agent/memory/langchain4j_embedding.clj`.
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
| [`src/kschltz/lateralus.clj`](src/kschltz/lateralus.clj) | `-main` entry point; delegates to CLI |
| [`src/kschltz/agent/cli.clj`](src/kschltz/agent/cli.clj) | Argument parsing, Integrant init/halt, runtime invocation |
| [`src/kschltz/agent/runtime.clj`](src/kschltz/agent/runtime.clj) | Outer loop: ctx creation + chain call + state merge |
| [`src/kschltz/agent/system.clj`](src/kschltz/agent/system.clj) | Integrant component definitions, default config, Malli `ig/assert-key` validation |
| [`src/kschltz/agent/chain.clj`](src/kschltz/agent/chain.clj) | Interceptor engine |
| [`src/kschltz/agent/plugin.clj`](src/kschltz/agent/plugin.clj) | Plugin assembly and slot-order contract |
| [`src/kschltz/agent/plugins/base.clj`](src/kschltz/agent/plugins/base.clj) | Default base plugin with the standard exchange chain |
| [`src/kschltz/agent/plugins/memory.clj`](src/kschltz/agent/plugins/memory.clj) | Memory plugin: recall (`:enrich`) and persist (`:persist`) |
| [`src/kschltz/agent/interceptors.clj`](src/kschltz/agent/interceptors.clj) | Core interceptor stages |
| [`src/kschltz/agent/interceptors/schema.clj`](src/kschltz/agent/interceptors/schema.clj) | Interceptor and context Malli schemas |
| [`src/kschltz/agent/llm/client.clj`](src/kschltz/agent/llm/client.clj) | `LlmClient` protocol + stub + HTTP wrapper |
| [`src/kschltz/agent/llm/schemas.clj`](src/kschltz/agent/llm/schemas.clj) | OpenAI-shaped request/response Malli schemas |
| [`src/kschltz/agent/memory/protocol.clj`](src/kschltz/agent/memory/protocol.clj) | `MemoryBackend` and `Embedder` protocols |
| [`src/kschltz/agent/memory/kg_bm25_backend.clj`](src/kschltz/agent/memory/kg_bm25_backend.clj) | Embedding-free KG + BM25 backend (native-image default) |
| [`src/kschltz/agent/memory/http_embedding.clj`](src/kschltz/agent/memory/http_embedding.clj) | OpenAI-compatible HTTP embedder (native-image friendly) |
| [`resources/lateralus/config.edn`](resources/lateralus/config.edn) | JVM runtime default config (Proximum + LangChain4j) |
| [`resources/lateralus/native.edn`](resources/lateralus/native.edn) | Native-image runtime config (KG-BM25 + noop HTTP embedder) |
| [`AGENT_INSTRUCTIONS.md`](AGENT_INSTRUCTIONS.md) | Short contributor guide |

## Status

Implemented:
- Steps 1–5: bootstrap, chain engine, plugin system, Integrant system, real HTTP-backed `LlmClient` + Malli schemas
- Step 6: memory plugin interceptors + noop `MemoryBackend`; **Proximum HNSW backend** + **LangChain4j in-process ONNX embedder** as the JVM runtime default; **KG + BM25 backend** as the native-image default
- Step 7: agent outer loop + traceability (synchronous MVP design)
- Step 8: clean-slate CLI
- Step 9: **GraalVM native-image build** with the KG + BM25 backend and a filtered classpath that excludes JVM-only Proximum / LangChain4j sources
- Step 10: docs, JVM distributable, quality-gate tests

Current work (see `kb status`):
- [006] Replace shallow state merge with deep or explicit state update
- [007] Pre-wire dependencies into context instead of bind-llm-client
- [008] Refactor KG-BM25 backend into focused namespaces

Recently completed:
- [009] Add Malli pre-init validation to Integrant components (`ig/assert-key` for `:lateralus/llm-client`, `:lateralus/embedder`, and `:lateralus/memory-backend`)

Deferred:
- Async worker thread for the runtime
- `--interactive` REPL mode
- Environment-variable support for `LATERALUS_V2_*`
- Multi-agent communication plugin (`docs/file-backed-comms-plan-consensus.md`)

## License

Eclipse Public License 2.0 (same as v1)
