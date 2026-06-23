# Changelog

All notable changes to `lateralus-v2`.

## [Unreleased]

### Added
- **Runtime-eval tool suite** (`kschltz.agent.tools.runtime.*`): `clojure/eval` (evaluate Clojure in a persistent runtime namespace, with stdout/value/exception capture and a per-call timeout), `clojure/add-lib` (load Maven/Git dependencies at runtime via Clojure 1.12 `clojure.repl.deps/add-libs`), and `clojure/loaded-libs`. Isolated behind the `ClojureRuntime` protocol with a Malli-instrumented network boundary; wired via the `:lateralus/runtime-tools` Integrant key (JVM config + `default-config`; excluded from native-image). Guarded by `:enabled?` / `:network?` toggles. See `docs/runtime-eval.md`.
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
- **End-to-end memory tests** (`kschltz.agent.e2e-memory-test`): separate `^:e2e` suite that exercises real HTTP LLM + LangChain4j + Proximum memory, defaulting to local Ollama `glm5.1:cloud`. Run with `clojure -M:e2e`; default suite excludes it.
- JVM uberjar + launcher script build (`clojure -T:build uber`, `./target/lateralus-v2`).
- Quality-gate tests (`kschltz.agent.quality-test`) verifying namespace parity, LOC limits, and forbidden patterns.
- JVM flags for Proximum (`--add-modules=jdk.incubator.vector`, `--enable-native-access=ALL-UNNAMED`) wired into `deps.edn` `:test`, `build.clj`, and the launcher script.

### Changed
- `src/kschltz/lateralus.clj` now delegates to `kschltz.agent.cli/-main` instead of printing a stub message.
- `:lateralus/memory-backend` now receives the resolved `:embedder` so real backends can embed message content at store time.
- `resources/lateralus/config.edn` now defaults to Proximum in-memory memory + LangChain4j ONNX embedding (was noop backend + noop embedder).
- `kschltz.agent.cli/build-system` is now public so integration tests can reuse it.
- `kschltz.agent.llm.http/post-chat` builds the completions URL robustly for both `http://host/v1` and `http://host` base URL conventions.

### Deferred
- Step 9: GraalVM native-image build. `clojure -T:build native` currently documents the blocker and defers to the JVM path. We attempted a build with GraalVM 25 on macOS arm64 and confirmed two blockers:
  1. The default LangChain4j ONNX embedder uses JNI/native libraries, which are incompatible with native-image; switching to an HTTP embedder is required.
  2. Transitive Timbre (via `org.replikativ/konserve`) puts mutable logger state into the image heap, rejected under `--strict-image-heap`. Resolving this requires replacing Timbre in the dependency tree, custom class-initialization metadata, or using a non-Proximum backend for native-image mode.
- HTTP embedder for native-image / cloud embedding deployments.
- Async worker thread for the runtime.
- `--interactive` REPL mode.
- Environment-variable support for `LATERALUS_V2_*`.

### Known limitations
- The runtime is single-threaded/synchronous. A queue + worker thread is a follow-up for when a real consumer needs non-blocking sends.
- The CLI `--interactive` flag is a placeholder; the MVP only supports one-shot mode.
- Environment-variable support for `LATERALUS_V2_*` is a follow-up.
- The in-memory `system/default-config` still uses the noop backend + noop embedder for fast, isolated tests. The runtime default in `resources/lateralus/config.edn` is Proximum + LangChain4j.
- LangChain4j in-process ONNX embedding is JVM-only and not compatible with GraalVM native-image.

## 0.1.0-SNAPSHOT

Initial MVP snapshot. See commit history for per-step provenance.
