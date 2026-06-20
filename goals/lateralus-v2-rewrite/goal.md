# Goal: Lateralus v2 Complete Rewrite

## Articulated Goal

Rebuild Lateralus from scratch in a **new git repository** (`../lateralus-v2/`), applying iteration-1 lessons: interceptor-chain runtime with an immutable, Malli-schemed context map; Integrant-managed component lifecycles; protocol + Malli boundaries on all network I/O; plugin-only extension; sequential tool execution by default; full test coverage with integration tests per feature.

MVP ships **core agent loop** (empty tool registry, stub-tested dispatch), **session memory** (the `MemoryBackend` protocol with a noop impl; a real persistent store is a follow-up, not an MVP requirement), **clean-slate CLI**, and a **JVM distributable**. GraalVM native-image is a stretch target (Step 9), not a hard blocker.

The existing `lateralus` repo remains the v1 archive. This goal package lives at `lateralus/goals/lateralus-v2-rewrite/` and is copied into the v2 repo at bootstrap.

## Shared Understanding

See [`facts.md`](./facts.md) for the 17 accepted facts. Key decisions:

- **New repo** — `../lateralus-v2/`, coord `net.clojars.kschltz/lateralus-v2`, ns `kschltz.lateralus` / `kschltz.agent.*`
- **Architecture** — interceptor chain engine + Integrant; all extensible parts are integrant-managed interceptors fed into the agent at startup
- **Traceability** — session IDs and user message IDs on every exchange; optional Malli instrumentation after each interceptor stage
- **Fresh sessions** — no v1 migration; the v2 memory contract is the `MemoryBackend` protocol, with a noop impl for MVP. Schema sketch in `docs/memory-v2.md` (Step 1) describes a future real store; no Datalevin dependency in MVP.
- **Clean-slate CLI** — no v1 flag compat
- **No MVP tools** — empty default registry; dispatch tested via dev stub only
- **Out of MVP** — remember, file editing, repl-eval, portal, nREPL, v1 uberjar, **Datalevin**
- **Out of scope entirely** — paid search, repl sandbox, multi-agent, pi/Cursor SDK, custom TUI, cloud hosting

## Execution Plan

See [`plan.md`](./plan.md) for the 10 ordered steps:

1. Bootstrap new repository (+ memory schema sketch)
2. Chain engine + ctx schema (port from v1)
3. Plugin system + default chain (decouple from `loop.clj`)
4. Integrant system definition
5. LlmClient protocol + HTTP boundary
6. Session memory (protocol + noop impl; real store is a follow-up)
7. Agent outer loop + traceability
8. Clean-slate CLI
9. GraalVM native-image build (stretch)
10. Documentation + quality gate (+ JVM distributable)

Highest risk: GraalVM native-image reflection config (Step 9) — now that the MVP runtime excludes Datalevin JNI, ONNX, and persistent storage, the risk is primarily a Clojure reflect-config exercise. JVM launcher/uberjar is the required MVP distributable if native-image blocks.

Portable v1 seed: `chain.clj`, `plugin.clj`, `interceptors/schema.clj`, `interceptors.clj`, `context.clj`, `llm/client.clj`, `exchange.clj`. Do **not** port `core.clj` or `loop.clj`.

## Done Condition

- All facts in `facts.md` with `automatedVerification: true` pass their verification commands
- `clojure -M:test -m cognitect.test-runner` — 0 failures
- JVM distributable builds and runs (`clojure -T:build uber`)
- GraalVM native-image attempted; success **or** documented blocker + JVM fallback
- README documents new CLI, architecture (interceptor + Integrant), and build paths
- Every src namespace has tests; no feature without integration tests
- No `add-*-tool!` functions; no direct `http/completion` outside LLM HTTP layer; no `loop.clj` dependency in interceptors

Done! Launch a goal with `/goal goals/lateralus-v2-rewrite/goal.md`
