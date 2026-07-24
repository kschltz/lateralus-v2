# AGENTS

For contributor/architecture guidance see `AGENT_INSTRUCTIONS.md`, `README.md`,
and `docs/`. Task/kanban workflow lives in `CLAUDE.md`.

## Cursor Cloud specific instructions

This is a Clojure CLI (`deps.edn`) project — an LLM agent (`lateralus-v2`). It is
terminal-based; there is no web/GUI service to run.

Toolchain baked into the VM snapshot (do NOT reinstall in the update script):
- Temurin JDK **22** is the default `java` (set via `update-alternatives`). The
  project requires Java 22+ for `jdk.incubator.vector` (Proximum HNSW +
  LangChain4j ONNX embedder). Java 21 is present but must not be the default.
- `clojure` CLI and `clj-kondo` are on `PATH` (`/usr/local/bin`).

The `WARNING: Using incubator modules: jdk.incubator.vector` line on every JVM
launch is expected and harmless — the vector-API flags are baked into the
`:run`/`:test`/`:workbench`/`:e2e` aliases (no manual `-J` flags needed).

Standard commands (see `README.md` / `AGENT_INSTRUCTIONS.md` for the full list):
- Lint: `clj-kondo --lint src test`
- Tests: `clojure -M:test` (fast suite, excludes `^:e2e`).
- Offline e2e: `LATERALUS_E2E_FAKE=true clojure -M:e2e` (real LangChain4j ONNX
  embedder + Proximum backend against a bundled fake HTTP LLM server).
- Run offline (no network / no Ollama needed):
  `clojure -M:run --config resources/lateralus/demo-stub.edn "your prompt"`.

Non-obvious caveats:
- The default runtime config (`resources/lateralus/config.edn`) and the plain
  `clojure -M:run` path expect a reachable **Ollama** LLM endpoint at
  `http://localhost:11434/v1`. No Ollama runs in the cloud VM by default, so use
  `--config resources/lateralus/demo-stub.edn` (stub LLM + KG-BM25 memory) for
  offline runs.
- `^:e2e` "live-llm" and "list-models" tests intentionally **auto-skip** when
  Ollama is unreachable — a skip is expected here, not a failure.
- `clojure -M:test` currently has **3 pre-existing failures** in
  `test/kschltz/agent/workbench/portal_test.clj`
  (`with-default-viewer-picks-rich-surfaces`, about Portal viewer metadata
  defaults). They are unrelated to environment setup (pure-data assertions) and
  reproduce on a clean `main`.
- Running without `--config` on a TTY opens an interactive profile gate that
  blocks on input; always pass `--config ...` (or pipe stdin for one-shot) in
  non-interactive/automated contexts.
