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
- **Proximum memory backend** (`kschltz.agent.memory.proximum-backend`): pure-JVM HNSW vector store + message metadata, hybrid recall, and session isolation.
- Synchronous agent runtime (`kschltz.agent.runtime`) with traceability IDs (`session-id`, `user-msg-id`, `assistant-msg-id`) and `:agent/state-delta` merge semantics.
- Clean-slate CLI (`kschltz.agent.cli`) with flags for help/version/interactive/session/config/model/base-url/api-key, plus test seams for `:system-fn` and `:runner-fn`.
- JVM uberjar + launcher script build (`clojure -T:build uber`, `./target/lateralus-v2`).
- Quality-gate tests (`kschltz.agent.quality-test`) verifying namespace parity, LOC limits, and forbidden patterns.
- JVM flags for Proximum (`--add-modules=jdk.incubator.vector`, `--enable-native-access=ALL-UNNAMED`) wired into `deps.edn` `:test`, `build.clj`, and the launcher script.

### Changed
- `src/kschltz/lateralus.clj` now delegates to `kschltz.agent.cli/-main` instead of printing a stub message.
- `:lateralus/memory-backend` now receives the resolved `:embedder` so real backends can embed message content at store time.

### Deferred
- Step 9: GraalVM native-image build. `clojure -T:build native` currently documents the blocker and defers to the JVM path. Proximum makes native-image feasible (pure JVM, no JNI), but reachability metadata for the FFM API is still required.

### Known limitations
- The runtime is single-threaded/synchronous. A queue + worker thread is a follow-up for when a real consumer needs non-blocking sends.
- The CLI `--interactive` flag is a placeholder; the MVP only supports one-shot mode.
- Environment-variable support for `LATERALUS_V2_*` is a follow-up.
- The default noop memory backend always returns `[]` on recall. Enabling the Proximum backend requires a real `Embedder` (HTTP/ONNX) and Java 22+.

## 0.1.0-SNAPSHOT

Initial MVP snapshot. See commit history for per-step provenance.
