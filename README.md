# lateralus-v2

Greenfield rewrite of [Lateralus](../lateralus) (v1 archive). Clojure LLM agent with interceptor-chain runtime, Integrant lifecycles, and session memory.

**Coord:** `net.clojars.kschltz/lateralus-v2`  
**Status:** Bootstrap — see [`goals/lateralus-v2-rewrite/goal.md`](goals/lateralus-v2-rewrite/goal.md)

## Quick start

```bash
cd lateralus-v2

# Run tests
clojure -M:test -m cognitect.test-runner

# Stub entry (not yet implemented)
clojure -M:run
```

## Goals & plans

| Path | Description |
|------|-------------|
| [`goals/lateralus-v2-rewrite/`](goals/lateralus-v2-rewrite/) | **Active goal** — v2 rewrite (facts, plan, interview) |
| [`goals/lateralus-file-editing/`](goals/lateralus-file-editing/) | v1 goal (implemented on v1 branch; reference only) |
| [`docs/interceptor-loop-implementation-plan.md`](docs/interceptor-loop-implementation-plan.md) | Architecture thesis |
| [`docs/memory-v2.md`](docs/memory-v2.md) | v2 memory schema sketch |
| [`docs/arch-remediation-plan.md`](docs/arch-remediation-plan.md) | v1 audit remediation (historical) |
| [`docs/clj-edit-implementation-plan.md`](docs/clj-edit-implementation-plan.md) | v1 file editing plan (historical) |
| [`docs/memory-system-mvi.md`](docs/memory-system-mvi.md) | v1 memory spec (reference) |

Launch execution: `/goal goals/lateralus-v2-rewrite/goal.md`

## Architecture (target)

```
Integrant init → plugins → interceptor chain → immutable ctx → outer loop
```

See [`AGENT_INSTRUCTIONS.md`](AGENT_INSTRUCTIONS.md) and [`docs/interceptor-loop-implementation-plan.md`](docs/interceptor-loop-implementation-plan.md).

## Build (when implemented)

```bash
clojure -T:build uber    # JVM jar + ./target/lateralus-v2 launcher
# GraalVM native-image — stretch target, Step 9 in plan
```

## License

Eclipse Public License 2.0 (same as v1)
