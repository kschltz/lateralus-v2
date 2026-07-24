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

Real LLM via Ollama Cloud (when `OLLAMA_API_KEY` is set as a secret):
- One-shot run: `echo "..." | clojure -M:run --config resources/lateralus/demo-ollama.edn --model gpt-oss:20b --base-url https://ollama.com/v1`.
  The API key is read automatically from `OLLAMA_API_KEY`. Pick any cloud model
  from `curl -s -H "Authorization: Bearer $OLLAMA_API_KEY" https://ollama.com/v1/models`.
- Live e2e against the cloud: `OLLAMA_BASE_URL=https://ollama.com/v1 OLLAMA_MODEL=gpt-oss:20b clojure -M:e2e -n kschltz.agent.live-llm-tool-test`.

Non-obvious caveats:
- **`--config` is required to skip the interactive profile gate.** Passing only
  `--model`/`--base-url` (without `--config`) still opens the gate, which then
  consumes piped stdin and silently falls back to the stub LLM. Always pass
  `--config <edn>` for non-interactive/cloud runs.
- The default runtime config (`resources/lateralus/config.edn`) and the plain
  `clojure -M:run` path expect a reachable **Ollama** LLM endpoint at
  `http://localhost:11434/v1`. No local Ollama runs in the cloud VM, so either
  use `--base-url https://ollama.com/v1` (cloud, needs `OLLAMA_API_KEY`) or
  `--config resources/lateralus/demo-stub.edn` (offline stub + KG-BM25 memory).
- The `^:e2e` `e2e-memory-test` probes a *local* Ollama via `/api/tags` and uses
  `LATERALUS_E2E_API_KEY` (not `OLLAMA_API_KEY`); it will skip against Ollama
  Cloud. The `live-llm-tool-test` is the one that works with cloud + `OLLAMA_API_KEY`.
- `^:e2e` "live-llm" and "list-models" tests intentionally **auto-skip** when
  the endpoint is unreachable — a skip is expected, not a failure.
- `clojure -M:test` currently has **3 pre-existing failures** in
  `test/kschltz/agent/workbench/portal_test.clj`
  (`with-default-viewer-picks-rich-surfaces`, about Portal viewer metadata
  defaults). They are unrelated to environment setup (pure-data assertions) and
  reproduce on a clean `main`.
- Running without `--config` on a TTY opens an interactive profile gate that
  blocks on input; always pass `--config ...` (or pipe stdin for one-shot) in
  non-interactive/automated contexts.
