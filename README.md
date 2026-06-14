# lateralus-v2

Greenfield rewrite of [Lateralus](../lateralus) (v1 archive). A Clojure LLM agent with an interceptor-chain runtime, Integrant lifecycles, session memory contract, and a clean-slate CLI.

**Coord:** `net.clojars.kschltz/lateralus-v2`  
**Main ns:** `kschltz.lateralus`

## Quick start

```bash
cd lateralus-v2

# Run the test suite
clojure -M:test -m cognitect.test-runner

# One-shot from stdin
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
# Named session
clojure -M:run -s my-session "Hello"

# Use a real HTTP-backed LLM
clojure -M:run \
  --model gpt-4 \
  --base-url https://api.openai.com/v1 \
  --api-key "$OPENAI_API_KEY" \
  "Hello"
```

## Architecture

Lateralus v2 is built on three ideas:

1. **Interceptor chain engine** — every stage of an exchange (compose, LLM call, parse, dispatch, persist, deliver) is a pure function of an immutable context map.
2. **Integrant lifecycles** — the LLM client, embedder, memory backend, and plugins are Integrant-managed components.
3. **Thin outer runtime** — `kschltz.agent.runtime` creates a per-exchange context with traceability IDs, runs the chain, and merges `:agent/state-delta` into an in-memory atom.

For details, see [`docs/architecture.md`](docs/architecture.md).

## Build

### JVM distributable (required MVP gate)

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
- Step 7: agent outer loop + traceability (synchronous MVP design)
- Step 8: clean-slate CLI
- Step 10 (partial): docs, JVM distributable, quality-gate tests

Deferred:
- Step 6: full memory plugin wiring (noop backend exists; recall/persist interceptors are a follow-up)
- Step 9: GraalVM native-image build

## License

Eclipse Public License 2.0 (same as v1)
