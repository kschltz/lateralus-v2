# lateralus-v2

Greenfield rewrite of [Lateralus](../lateralus) (v1 archive). A Clojure LLM agent with an interceptor-chain runtime, Integrant lifecycles, session memory contract, and a clean-slate CLI.

**Coord:** `net.clojars.kschltz/lateralus-v2`  
**Main ns:** `kschltz.lateralus`

## Quick start

```bash
cd lateralus-v2

# Run the test suite (Proximum + LangChain4j tests need Java 22+ flags)
clojure -M:test -m cognitect.test-runner

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

To make memory durable across JVM restarts, add a file-backed `:store-config`:

```clojure
{:lateralus/memory-backend
 {:impl :proximum
  :embedder #ig/ref :lateralus/embedder
  :store-config {:backend :file
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
clojure -T:build uber
./target/lateralus-v2 -h
echo "ping" | ./target/lateralus-v2
```

This produces:
- `target/net.clojars.kschltz/lateralus-v2-0.1.0-SNAPSHOT.jar`
- `target/lateralus-v2` — executable launcher script

### GraalVM native-image (stretch, Step 9)

Not yet implemented. The current `build.clj` exposes a `native` target that documents the blocker and defers to the JVM path. If you have GraalVM installed, the next step is to add `clj-easy/graal-build-time`, collect `reflect-config.json`, and invoke `native-image` against the uber jar. If it blocks, the JVM uber/launcher remains the supported MVP distributable.

## Project structure

| Path | Description |
|------|-------------|
| [`goals/lateralus-v2-rewrite/`](goals/lateralus-v2-rewrite/) | Active goal — facts, plan, interview artifacts |
| [`docs/architecture.md`](docs/architecture.md) | Architecture overview and component graph |
| [`docs/interceptor-loop-implementation-plan.md`](docs/interceptor-loop-implementation-plan.md) | Original interceptor-chain thesis |
| [`docs/memory-v2.md`](docs/memory-v2.md) | v2 memory schema sketch |
| [`AGENT_INSTRUCTIONS.md`](AGENT_INSTRUCTIONS.md) | Short contributor guide |

## Status

Implemented:
- Steps 1–5: bootstrap, chain engine, plugin system, Integrant system, real HTTP-backed LlmClient + Malli schemas
- Step 6: memory plugin interceptors + noop `MemoryBackend`; **Proximum HNSW backend** + **LangChain4j in-process ONNX embedder** as the runtime default
- Step 7: agent outer loop + traceability (synchronous MVP design)
- Step 8: clean-slate CLI
- Step 10: docs, JVM distributable, quality-gate tests

Deferred:
- Step 9: GraalVM native-image build (blocked by ONNX JNI until an HTTP embedder is used)
- Async worker thread for the runtime
- `--interactive` REPL mode
- Environment-variable support for `LATERALUS_V2_*`
- HTTP embedder for native-image / cloud embedding deployments

## License

Eclipse Public License 2.0 (same as v1)
