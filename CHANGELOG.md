# Changelog

All notable changes to `lateralus-v2`.

## [Unreleased]

### Added
- Bootstrap: new repo at `net.clojars.kschltz/lateralus-v2`, Clojure 1.12.5, Integrant, Malli, hato.
- Chain engine (`kschltz.agent.chain`): Pedestal-style enter/leave/error interceptor engine with pure context maps.
- Plugin system (`kschltz.agent.plugin`) + default exchange chain (`kschltz.agent.exchange`).
- Integrant system (`kschltz.agent.system`) with components for LLM client, embedder, memory backend, plugins, and agent.
- Real HTTP-backed LlmClient (`kschltz.agent.llm.http`) with Malli request/response schemas (`kschltz.agent.llm.schemas`), timeouts, and structured errors.
- `MemoryBackend` + `Embedder` protocols and MVP noop implementations (`kschltz.agent.memory.*`).
- Synchronous agent runtime (`kschltz.agent.runtime`) with traceability IDs (`session-id`, `user-msg-id`, `assistant-msg-id`) and `:agent/state-delta` merge semantics.
- Clean-slate CLI (`kschltz.agent.cli`) with flags for help/version/interactive/session/config/model/base-url/api-key, plus test seams for `:system-fn` and `:runner-fn`.
- JVM uberjar + launcher script build (`clojure -T:build uber`, `./target/lateralus-v2`).
- Quality-gate tests (`kschltz.agent.quality-test`) verifying namespace parity, LOC limits, and forbidden patterns.

### Changed
- `src/kschltz/lateralus.clj` now delegates to `kschltz.agent.cli/-main` instead of printing a stub message.

### Deferred
- Step 6: memory plugin interceptors (recall + persist) that wire the noop `MemoryBackend` into the exchange chain.
- Step 9: GraalVM native-image build. `clojure -T:build native` currently documents the blocker and defers to the JVM path.

### Known limitations
- The runtime is single-threaded/synchronous. A queue + worker thread is a follow-up for when a real consumer needs non-blocking sends.
- The CLI `--interactive` flag is a placeholder; the MVP only supports one-shot mode.
- Environment-variable support for `LATERALUS_V2_*` is a follow-up.
- No real persistent memory backend; the noop backend always returns `[]` on recall.

## 0.1.0-SNAPSHOT

Initial MVP snapshot. See commit history for per-step provenance.
