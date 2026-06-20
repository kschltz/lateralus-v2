**Historical / archived — does not describe the current Integrant + plugin architecture. See `docs/architecture.md` for current design.**

# Lateralus Architecture Remediation Plan

Evidence-backed fixes from the 2026 architecture audit. Each item cites the flaw ID and acceptance checks.

**Coordination:** leader (coordinator) + follower1 (helper) via intercom `[TASK]` / `[DONE]`.

## Crew split

| Agent | Session | Owns | Never touches (unless reassigned) |
|-------|---------|------|-----------------------------------|
| **Leader** | leader | Decompose, assign, verify, merge — no direct code edits | all implementation files |
| **Follower1** | follower1 | `http.clj`, `memory/schemas.clj`, `web.clj`, portal tests, memory API rename | `core.clj` agent loop, `llm.clj` routing |
| **Follower2** | follower2 | `deps.edn`, `core.clj` tool concurrency (fix-021), isolated tasks | http/llm unless reassigned |

**Parallel rule:** Wave 2+ tasks with disjoint scopes run concurrently. Leader merges after both `[DONE]` + local verify.

**Branch rule:** One branch per task: `feature/{fix-id}-{short-name}`. No mixed-scope commits.

## Principles

- One task in flight per agent unless file scopes are disjoint
- Every change must pass `clojure -M:test -m cognitect.test-runner`
- External/network boundaries: protocol + Malli in/out (project rule)
- No `core.clj` split until Phase 3 — unblock tests and seams first

---

## Phase 0 — Unblock quality gates (P0)

| ID | Owner | Title | Scope | Depends |
|----|-------|-------|-------|---------|
| fix-001 | follower1 | Test suite compiles | `test/kschltz/agent/cli_test.clj`, `src/kschltz/agent/cli.clj` | — |
| fix-002 | leader | Verify full test run green | run tests after fix-001 | fix-001 |

**fix-001 evidence:** `cli/default-response-timeout-ms` is `^:private` but test references it directly → compile error.

**Acceptance:**
- `clojure -M:test -m cognitect.test-runner` exits 0
- No new public vars unless test needs them (prefer `#'cli/resolve-response-timeout-ms` or public constant)

---

## Phase 1 — LLM & HTTP boundaries (P0)

| ID | Owner | Title | Scope | Depends |
|----|-------|-------|-------|---------|
| fix-010 | leader | Route production LLM through `llm/call` | `src/kschltz/agent/core.clj`, `src/kschltz/agent/llm.clj` | fix-002 |
| fix-011 | follower1 | Malli schemas for `http/completion` I/O | `src/kschltz/agent/http.clj`, `src/kschltz/agent/memory/schemas.clj` (or new `http/schemas.clj`) | fix-002 |
| fix-012 | leader | Wire `llm/call` to validated http layer | `core.clj` after fix-011 | fix-010, fix-011 |

**Evidence:** `core.clj` calls `http/completion` directly; `llm/call` only used in tests.

**Acceptance:**
- `grep -r 'http/completion' src/kschltz/agent/core.clj` → empty (only via llm)
- `http/completion` validates request opts and response shape with Malli (throw `ex-info` on invalid)
- Existing `http_test.clj` + `llm_test.clj` pass; add test for invalid completion response if missing

---

## Phase 2 — External tool isolation (P1)

| ID | Owner | Title | Scope | Depends |
|----|-------|-------|-------|---------|
| fix-020 | follower1 | HTTP client record for web search | `src/kschltz/agent/tools/web.clj` | fix-002 |
| fix-021 | leader | Serialize parallel tool execution | `src/kschltz/agent/core.clj` (`execute-tool-calls`) | fix-012 |
| fix-022 | follower1 | Remove dead `proximum` dep | `deps.edn` | fix-002 |

**Evidence:** `http-get` is inline; `pmap` in tool runner; proximum unused in `src/`.

**Acceptance fix-021:**
- Replace `pmap` with sequential `mapv` OR document + gate parallel behind flag (default sequential)
- `core_test.clj` / e2e tests pass

**Acceptance fix-022:**
- `proximum` removed from `deps.edn`
- `clojure -M:test` still passes

---

## Phase 3 — Modularity (P2, incremental)

| ID | Owner | Title | Scope | Depends |
|----|-------|-------|-------|---------|
| fix-030 | leader | Extract `agent.context` (truncation, history, memory msgs) | new ns + `core.clj` | Phase 2 |
| fix-031 | leader | Extract `agent.loop` (queue, process-messages, llm-turn) | new ns + `core.clj` | fix-030 |
| fix-032 | follower1 | Portal tool smoke tests | `test/kschltz/agent/tools/portal_test.clj`, read-only on `portal.clj` | fix-002 |

**Evidence:** `core.clj` 1216 LOC; `portal.clj` 523 LOC untested.

**Acceptance fix-030/031:** `core.clj` < 800 LOC; no behavior change; all tests green.

**Acceptance fix-032:** Tests cover `normalize-visualize-args`, `try-parse-data`, tool registration (no Portal UI).

---

## Phase 4 — Memory backend selection (P2, optional)

| ID | Owner | Title | Scope | Depends |
|----|-------|-------|-------|---------|
| fix-040 | follower1 | Swap Datalevin for non-Datalevin store (SQLite/LMDB) | `src/kschltz/agent/memory/` + deps | fix-021 |

**Evidence:** Datalevin adds native LMDB complexity; SQLite or LMDB may be simpler for MVP.

**Acceptance:**
- Same `MemoryBackend` protocol surface
- Existing memory tests pass against new backend
- No Datalevin dependency in `:deps` if removed

---

## Verification Checklist

- [ ] `clojure -M:test -m cognitect.test-runner` green on `main` after each merge
- [ ] `rg 'http/completion' src/kschltz/agent/core.clj` empty
- [ ] `rg 'pmap' src/kschltz/agent/core.clj` empty (or behind explicit flag)
- [ ] `core.clj` < 800 LOC
- [ ] Each fix branch has its own test file update

## Open Risks

1. `core.clj` split may change callback/event ordering — parity tests required before merge.
2. Removing Datalevin may require migration/reset of dev session data; acceptable for MVP.
