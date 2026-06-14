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
- **LangChain4j in-process ONNX embedder** (`kschltz.agent.memory.langchain4j-embedding`): bundled all-MiniLM-L6-v2 model, 384 dimensions, no runtime network calls.
- Runtime default config (`resources/lateralus/config.edn`) now uses **Proximum + LangChain4j** for real session memory out of the box.
- JVM uberjar + launcher script build (`clojure -T:build uber`, `./target/lateralus-v2`).
- Quality-gate tests (`kschltz.agent.quality-test`) verifying namespace parity, LOC limits, and forbidden patterns.
- JVM flags for Proximum (`--add-modules=jdk.incubator.vector`, `--enable-native-access=ALL-UNNAMED`) wired into `deps.edn` `:test`, `build.clj`, and the launcher script.

### Changed
- `src/kschltz/lateralus.clj` now delegates to `kschltz.agent.cli/-main` instead of printing a stub message.
- `:lateralus/memory-backend` now receives the resolved `:embedder` so real backends can embed message content at store time.
- `resources/lateralus/config.edn` now defaults to Proximum in-memory memory + LangChain4j ONNX embedding (was noop backend + noop embedder).

### Deferred
- Step 9: GraalVM native-image build. `clojure -T:build native` currently documents the blocker and defers to the JVM path. Proximum makes native-image feasible (pure JVM, no JNI), but the default LangChain4j ONNX embedder uses native libraries, so native-image requires switching to an HTTP embedder first.

### Known limitations
- The runtime is single-threaded/synchronous. A queue + worker thread is a follow-up for when a real consumer needs non-blocking sends.
- The CLI `--interactive` flag is a placeholder; the MVP only supports one-shot mode.
- Environment-variable support for `LATERALUS_V2_*` is a follow-up.
- The in-memory `system/default-config` still uses the noop backend + noop embedder for fast, isolated tests. The runtime default in `resources/lateralus/config.edn` is Proximum + LangChain4j.
- LangChain4j in-process ONNX embedding is JVM-only and not compatible with GraalVM native-image.

## 0.1.0-SNAPSHOT

Initial MVP snapshot. See commit history for per-step provenance.
