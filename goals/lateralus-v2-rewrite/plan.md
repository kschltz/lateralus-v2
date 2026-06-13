# Plan — Lateralus v2 Complete Rewrite

## Solution approach

Bootstrap a **new git repository** at `../lateralus-v2/` with a greenfield codebase. Locked identifiers:

| Item | Value |
|------|-------|
| Repo path | `../lateralus-v2/` |
| Clojars coord | `net.clojars.kschltz/lateralus-v2` |
| Main ns | `kschltz.lateralus` |
| Agent ns prefix | `kschltz.agent.*` |

Port only the v1 artifacts that already match the interceptor architecture (`chain.clj`, `plugin.clj`, `interceptors/schema.clj`, `interceptors.clj`, `context.clj`, `llm/client.clj`, `exchange.clj` patterns) as reference implementations — do not copy `core.clj`, `loop.clj`, or legacy ad-hoc wiring. Decouple ported interceptors from `loop/` delegation during Step 3.

Use **Integrant** as the sole lifecycle/DI mechanism: every extensible component (LLM client, memory store, embedding provider, plugin bundles, agent ref) is an Integrant key whose `init`/`halt!` produces interceptors or protocol implementations fed into the agent at startup. The agent runtime is **engine + ctx + default chain** (~200 LOC assembly max).

MVP delivers: interceptor-based agent loop (empty tool registry + stub-tested dispatch), session memory (new format), clean-slate CLI, JVM distributable. GraalVM native-image is Step 9 stretch — not a hard blocker if Datalevin JNI fails.

Reference docs carried forward from v1 archive:
- `docs/interceptor-loop-implementation-plan.md` (architecture thesis)
- `docs/memory-system-mvi.md` (conceptual reference only — v2 schema will differ)

---

## Ordered steps

### Step 1 — Bootstrap new repository

**Touches:** new repo root, `deps.edn`, `build.clj`, `README.md`, `.gitignore`, `docs/memory-v2.md`, `goals/` (copied from v1)

**Work:**
- `git init` at `../lateralus-v2/` (adjacent to v1 archive)
- Clojure 1.12.5, deps: `metosin/malli`, `hato/hato`, `cheshire/cheshire`, `integrant/integrant`, `io.github.cognitect-labs/test-runner`. **No Datalevin dependency** in MVP — session storage is a no-op stub against the `MemoryBackend` protocol; a real persistent store (Datalevin, SQLite, etc.) is a follow-up.
- Namespace prefix: **`kschltz.lateralus` / `kschltz.agent.*`** (locked)
- Clojars coord: **`net.clojars.kschltz/lateralus-v2`** (locked)
- Copy from v1 archive into v2:
  - `docs/interceptor-loop-implementation-plan.md`
  - `goals/lateralus-v2-rewrite/` (planning provenance — this package)
- Add `docs/memory-v2.md` schema sketch (deliverable in this step, not Step 6):
  - Entity attrs: `:v2/session-id`, `:v2/msg-id`, `:v2/role`, `:v2/text`, `:v2/timestamp`, `:v2/indexed`
  - Hybrid recall: top-Y semantic + last-N recent (same semantics as v1 MVI, new attr names)
  - Vector index separate from the Datalog store (when a real backend lands); HTTP embedder default for GraalVM profile
- Add `AGENT_INSTRUCTIONS.md` (MVI, ~50 lines) pointing at architecture doc

**Verification:**
```bash
cd ../lateralus-v2 && clojure -M:test -m cognitect.test-runner  # empty suite passes
test -f docs/memory-v2.md && test -f goals/lateralus-v2-rewrite/goal.md
```

---

### Step 2 — Chain engine + ctx schema (port & harden)

**Touches:** `src/kschltz/agent/chain.clj`, `src/kschltz/agent/interceptors/schema.clj`, `test/kschltz/agent/chain_test.clj`, `test/kschltz/agent/interceptors/schema_test.clj`

**Work:**
- Port v1 `chain.clj` (~145 LOC) verbatim with tests
- Extend `Ctx` schema with traceability keys: `:exchange/session-id`, `:exchange/user-msg-id`, `:exchange/assistant-msg-id` (UUIDs generated at exchange start)
- Wire `:chain/instrument?` + `:chain/validate` using `make-validator` + optional full Malli decode in test profile
- Property test: `execute` is pure given pure interceptors

**Verification:**
```bash
clojure -M:test -m cognitect.test-runner -n kschltz.agent.chain-test
clojure -M:test -m cognitect.test-runner -n kschltz.agent.interceptors.schema-test
wc -l src/kschltz/agent/chain.clj  # must be < 200
```

**Risk:** None — v1 chain is already tested and stable.

---

### Step 3 — Plugin system + default chain assembly

**Touches:** `src/kschltz/agent/plugin.clj`, `src/kschltz/agent/exchange.clj`, `src/kschltz/agent/interceptors.clj`, `src/kschltz/agent/context.clj`, `src/kschltz/agent/interceptors/*.clj`, tests

**Work:**
- Port v1 `plugin.clj` + `assemble-chain`
- Port v1 `interceptors.clj` + `context.clj` as reference, then **rewrite** to remove all `kschltz.agent.loop/` delegation — inline compose, dispatch, store-exchange logic into interceptors (do not copy `loop.clj`)
- Implement core interceptors (no business logic in assembly ns):
  - `error-boundary`, `compose-context`, `llm-call`, `parse-response`, `dispatch`, `deliver-responses`, `update-history`, `notify`
- `dispatch` uses sequential tool execution (mapv, not pmap); parallel behind `:agent/parallel-tools?` default false
- **MVP default: empty tool registry.** Add `:dev/stub-echo-tool` plugin used only in tests to exercise dispatch without shipping user-facing tools
- `default-exchange-chain` in `exchange.clj` — thin assembly only

**Verification:**
```bash
clojure -M:test -m cognitect.test-runner -n kschltz.agent.plugin-test
clojure -M:test -m cognitect.test-runner -n kschltz.agent.interceptors-test
rg 'kschltz\.agent\.loop' src/  # no matches — decoupled from loop.clj
rg 'pmap' src/  # no matches in tool dispatch
```

**Risk:** Require cycles — keep assembly in `exchange.clj` as v1 does.

---

### Step 4 — Integrant system definition

**Touches:** `src/kschltz/agent/system.clj`, `resources/lateralus/config.edn`, `test/kschltz/agent/system_test.clj`

**Work:**
- Define Integrant config:
  ```clojure
  {:lateralus/llm-client       {:base-url ..., :api-key ..., :model ...}
   :lateralus/embedder         {:method :http}
   :lateralus/memory-backend   {:impl :noop}    ; MVP: noop; follow-up may add a real store
   :lateralus/plugins          [:memory]  ; MVP: memory plugin only; no user-facing tools
   :lateralus/agent            {:llm-client (ig/ref :lateralus/llm-client)
                                :memory-backend (ig/ref :lateralus/memory-backend)
                                :plugins (ig/ref :lateralus/plugins)}}
  ```
- `init` for `:lateralus/agent` builds agent ref + assembled chain from plugin interceptors
- `halt!` closes backend resources (no-op for the MVP noop backend)
- Plugins are Integrant keys that resolve to `{:plugin/name ... :plugin/slots ...}` maps

**Verification:**
```bash
clojure -M:test -m cognitect.test-runner -n kschltz.agent.system-test
# integration: (ig/init config) → agent accepts message → (ig/halt! system)
```

**Risk:** Integrant + dynamic plugin loading — keep MVP config static in EDN.

---

### Step 5 — LlmClient protocol + HTTP boundary

**Touches:** `src/kschltz/agent/llm/client.clj`, `src/kschltz/agent/llm/http.clj`, `src/kschltz/agent/http/schemas.clj`, tests

**Work:**
- Port `LlmClient` protocol + `DefaultLlmClient` + `instrumented-call`
- HTTP completion behind `llm/http.clj` with Malli on request opts and response shape
- **No** `http/completion` calls outside `llm/http.clj` and `DefaultLlmClient`
- Fake `LlmClient` record for tests

**Verification:**
```bash
clojure -M:test -m cognitect.test-runner -n kschltz.agent.llm.client-test
rg 'http/completion' src/  # only in llm/http.clj
```

**Risk:** Cloud API timeouts — default connect timeout ≥ 10s (lesson from v1).

---

### Step 6 — Session memory protocol + MVP noop backend

**Touches:** `src/kschltz/agent/memory/protocol.clj`, `src/kschltz/agent/memory/embedding.clj`, `src/kschltz/agent/memory/noop_backend.clj`, `src/kschltz/agent/plugins/memory.clj`, `docs/memory-v2.md`, tests + integration test

**Work:**
- **The `MemoryBackend` protocol is the contract.** MVP ships a noop backend that satisfies it (returns [] on recall, no-op on store). A real persistent store (Datalevin, SQLite, LMDB, flat files, etc.) is a follow-up that slots in as another implementation of the same protocol — no consumer changes required.
- Embedder via `Embedder` protocol; HTTP embedder impl ships in MVP, noop embedder for the default config
- Memory plugin interceptors:
  - `:enrich` — recall injection pre-compose (skipped by noop backend, no-op assoc on ctx)
  - `:persist` — store exchange on leave (skipped by noop backend)
- Track `:exchange/session-id` and `:exchange/user-msg-id` in ctx for audit trail
- **No** v1 session migration code (and no Datalevin migration either — the v2 format is independent of any specific store)

**Verification:**
```bash
clojure -M:test -m cognitect.test-runner -n kschltz.agent.memory-test
clojure -M:test -m cognitect.test-runner -n kschltz.agent.memory-integration-test
# unit test: protocol is well-formed; noop backend satisfies it
# (no end-to-end recall test against a real store until one ships)
```

**Risk:** None for MVP — noop backend removes the GraalVM/JNI interaction. Real backends are follow-ups with their own risk profiles.

---

### Step 7 — Agent outer loop + traceability

**Touches:** `src/kschltz/agent/runtime.clj`, `src/kschltz/agent/exchange.clj`, tests

**Work:**
- Thin outer loop (~100 LOC): drain message queue, create ctx with session-id + UUID msg-ids, call `chain/execute`, merge `:agent/state-delta` into agent ref
- No business logic — only queue + ctx creation + state merge
- Integration test: full exchange with fake LLM returning text response

**Verification:**
```bash
clojure -M:test -m cognitect.test-runner -n kschltz.agent.runtime-test
clojure -M:test -m cognitect.test-runner -n kschltz.agent.exchange-integration-test
wc -l src/kschltz/agent/runtime.clj  # < 150
```

---

### Step 8 — Clean-slate CLI

**Touches:** `src/kschltz/lateralus.clj`, `src/kschltz/agent/cli.clj`, `test/kschltz/agent/cli_test.clj`, `README.md`

**Work:**
- Redesign flags (suggestion):
  - `--model`, `--base-url`, `--api-key` (or env: `LATERALUS_V2_*`)
  - `--session` / `-s`
  - `--interactive` / `-i` (default when no prompt arg)
  - one-shot: positional prompt or stdin
  - `--config` path to Integrant EDN (default `resources/lateralus/config.edn`)
- `-main` → parse args → `ig/init` → run agent → `ig/halt!`
- Help text documents all flags

**Verification:**
```bash
clojure -M:run -h  # prints help
echo "ping" | clojure -M:run --no-interactive  # one-shot with fake LLM in test profile
clojure -M:test -m cognitect.test-runner -n kschltz.agent.cli-test
```

**Risk:** Flag naming — document in README; no v1 compat promised.

---

### Step 9 — GraalVM native-image build (stretch)

**Touches:** `build.clj`, `resources/META-INF/native-image/**`, `native-config.edn`, `README.md`, CI script

**Work:**
- Add `:native` alias with GraalVM build-time deps (`clj-easy/graal-build-time`)
- Native profile Integrant config: HTTP embedder only, no ONNX, minimal plugins
- Run tracing agent pass against integration test to collect reflect-config
- **No Datalevin in MVP**, so no Datalevin JNI to wire; if a real memory backend lands later, its native-image story is its own concern
- `clojure -T:build native` produces `./target/lateralus-v2` binary
- **If native-image fails after reasonable effort:** document blocker, ship JVM launcher/uberjar as MVP distributable, file tracked issue — goal still completable

**Verification:**
```bash
clojure -T:build native  # success = stretch complete
# OR, on blocker:
clojure -T:build uber && ./target/lateralus-v2 -h  # JVM fallback documented in README
./target/lateralus-v2 -s test "hello"  # against local/mock LLM (native or JVM)
```

**Risk:** **Moderate.** The MVP runtime is now small (no Datalevin JNI, no ONNX, no real embeddings); native-image is primarily a Clojure reflect-config exercise. JVM path is the required MVP distributable; native-image is stretch, not a hard gate.

---

### Step 10 — Documentation + quality gate

**Touches:** `README.md`, `docs/architecture.md`, `CHANGELOG.md`, `build.clj`

**Work:**
- README: quick start, CLI flags, architecture overview (interceptor + Integrant), JVM build + GraalVM stretch instructions
- Architecture doc: reference interceptor plan + Integrant component graph
- JVM distributable: `clojure -T:build uber` + launcher script (required MVP gate)
- Full test suite + LOC audit:
  ```bash
  clojure -M:test -m cognitect.test-runner  # 0 failures
  # every src ns has test ns
  # no src file > 500 LOC without tests
  ```
- Document GraalVM outcome (success or blocker + issue link)

**Verification:** all automated facts from `facts.meta.json` with `automatedVerification: true`.

---

## Verification matrix (facts → commands)

| Fact ID | Check |
|---------|-------|
| fact-interceptor-arch | `chain_test` + `interceptors_test` + `system_test` (Integrant init/halt!) green |
| fact-immutable-ctx | tests assert interceptors return new ctx maps; no `swap!` in interceptor ns |
| fact-auditable | ctx carries session-id + msg-ids; instrumented chain test |
| fact-small-engine | `wc -l` on chain + runtime < 350 combined |
| fact-mvp-core-loop | exchange integration test with fake LLM; stub tool dispatch test; default registry empty |
| fact-mvp-memory | memory integration test; recall across turns; schema matches `docs/memory-v2.md` |
| fact-mvp-cli | cli_test + manual `-h` / one-shot |
| fact-graalvm | `clojure -T:build native` exits 0 **or** JVM uber/launcher + README documents native blocker |
| fact-llm-boundary | `rg http/completion src/` scoped to llm/http |
| fact-malli-boundaries | instrumented protocol tests for LLM + embed |
| fact-sequential-tools | stub tool dispatch test; no pmap in dispatch |
| fact-plugin-registration | no `add-*-tool!` in src |
| fact-test-coverage | test-runner green; namespace parity script |
| fact-done-bar | README + JVM distributable + native attempt documented |

---

## Risks and open questions

1. **GraalVM native-image** — moderate risk now that the MVP runtime is small (no Datalevin JNI, no ONNX, no real embeddings). JVM distributable is the required MVP gate; native-image is stretch with documented fallback.
2. **loop.clj decoupling** — v1 interceptors delegate to `loop/`; Step 3 must rewrite, not copy. Verify with `rg 'agent\.loop' src/`.
3. **Embedding in native** — HTTP-only is safest; document required embedding API endpoint for native users. (No embedding provider ships in MVP; the noop embedder is the default.)
4. **v1 code port scope** — port tests alongside code; do not port `core.clj` or `loop.clj` wholesale; rewrite as interceptors.
5. **Integrant config format** — EDN recommended for auditability (locked in Step 4).
6. **Memory storage backend** — `MemoryBackend` protocol is the contract; the noop backend is the MVP impl. A real persistent store (Datalevin, SQLite, etc.) is a follow-up that satisfies the same protocol. **No Datalevin in MVP.**
