# Auditor findings — Steps 2-4 chain/plugin/integrant

Session: auditor (25b5ff64-cf32-4d1e-9294-b2f75329ca4f)
Repo: /Users/schltzk/projects/lateralus-v2
Commit reviewed: ed22b45 "Steps 2-4: chain engine, plugin system, default exchange chain, Integrant system"
Test status: 38 tests, 79 assertions, 0 failures, 0 errors (verified locally with `clojure -M:test -m cognitect.test-runner`)

## Severity scale

- (BLOCKER) — incorrect behavior or broken contract; will hurt later steps
- (DECISION) — design choice that should be reconsidered before next step lands
- (POLISH)   — code-quality / clarity issue, non-blocking

## Findings

### 1. (BLOCKER) Dead `parallel?` branch in `dispatch` — fact-sequential-tools is unverified

File: `src/kschltz/agent/interceptors.clj` lines ~127-137
Literal:
```clojure
results  (if parallel?
           ;; Parallel opt-in only. MVP must not use
           ;; pmap by default (fact-sequential-tools).
           (mapv (fn [c] {:call c :result :not-implemented}) calls)
           (mapv (fn [c] {:call c :result :not-implemented}) calls))
```
Both branches are byte-identical `mapv` calls. The `parallel?` flag does nothing — the docstring/comment claim "parallel opt-in only" is false; the user-visible behavior is **always sequential, forever, regardless of state**. This makes the `:agent/parallel-tools?` knob a silent lie.

Why this is a blocker, not polish:
- The plan (Step 3) and the verification matrix (fact-sequential-tools) require sequential by default AND the test asserts the sequential path. The knob is the only place where the parallel-vs-sequential contract is encoded, and it does not encode anything.
- When Step 5/6 lands real tools and someone flips `:agent/parallel-tools?` to true, nothing will change. The bug will be invisible until a real tool's semantics differ between the two modes (e.g. side effects, ordering).

Fix options:
- (a) If parallel is desired later, replace the `parallel?` branch with `pmap` and add a `fact-sequential-tools` test that asserts `:agent/parallel-tools?` toggles the implementation. The current code does the opposite — it claims a contract it doesn't implement.
- (b) If parallel is never planned, drop the `parallel?` binding and the comment; remove `:agent/parallel-tools?` from any docs/AGENT_INSTRUCTIONS mentions. Do not ship a knob that does nothing.

Either is fine; do not ship the current state.

### 2. (DECISION) `assemble-chain` and `validate-plugins` give the wrong error context

File: `src/kschltz/agent/plugin.clj` lines ~67-79
Literal:
```clojure
(when explain
  (throw (ex-info "Invalid plugin map"
                  {:explain (str (first plugins))})))
...
(when-let [explain (m/explain [:sequential Plugin] (vec plugins))]
  (str (first plugins)))
```
`m/explain` returns a vector of *problem maps*, one per failing entry. Throwing `(str (first plugins))` discards Malli's actual explanation (the path, the schema, the value mismatch) and just stringifies the first plugin — the user sees their own data back, not what was wrong with it.

Test `assemble-chain-fails-fast-on-bad-shape` only checks the exception class and message regex; it does not assert the explanation is useful. So the regression window is wide.

Fix: in both spots, use `m/explain` to format the actual problems. Example:
```clojure
(when-let [problems (m/explain [:sequential Plugin] (vec plugins))]
  (throw (ex-info "Invalid plugin map"
                  {:problems problems
                   :plugins  (vec plugins)})))
```
Update `validate-plugins` to return the same shape. Add a test that asserts the throw carries `:problems` with a non-empty vector.

### 3. (DECISION) `error-boundary` swallows + re-throws; that double-handling is fragile

File: `src/kschltz/agent/interceptors.clj` lines ~78-83
Literal:
```clojure
:error (fn [ctx ex]
         (assoc ctx :error/raised {:exception ex
                                   :stage     (:chain/stage (ex-data ex))}))}
```
The chain engine already calls `:error` handlers in stack-reverse and rethrows unhandled errors after the walk. This `:error` handler **returns a ctx with `:error/raised` set but does not clear `::error`**, so the engine will:
1. See `::error` still set on ctx after this handler runs.
2. Continue the error walk over the remaining stack.
3. After the walk, rethrow the original (unhandled) error.
4. Then this stage's `:leave` (none defined) does not run — and `:error/raised` lives on the *thrown* ctx only if a caller captures it, which `chain/execute` does not (it strips engine keys and rethrows).

Net effect: `:error/raised` is set on a ctx that is **immediately thrown away**. The annotation is invisible to callers.

Two coherent designs:
- (a) This stage should *handle* the error: `(assoc (dissoc ctx ::chain/error) :error/raised ...)` so the chain returns cleanly with `:error/raised` on the final ctx. Then the engine treats it as handled and continues into `:leave`.
- (b) This stage should not be in the default chain at all — the engine's own rethrow-with-context is the boundary, and tests can assert on the thrown `ex-info`'s `:chain/stage` / `:interceptor/name` directly.

The current state is the worst of both: it pretends to annotate but the annotation never escapes. Pick one and update the test (currently no test exercises error propagation through this stage).

### 4. (POLISH) `trim-history` is a no-op disguised as a function

File: `src/kschltz/agent/interceptors.clj` lines ~62-65
Literal:
```clojure
(defn- trim-history
  "Truncate ctx-internal history to the most recent N turns (Step 6
   will replace this with proper memory recall)."
  [ctx]
  ctx)
```
And the only call site is `(-> ctx ... (trim-history))` in `compose-context`. This is a stub masquerading as a working function. It will be easy to forget when Step 6 lands.

Fix: either inline `(identity ctx)` and add a `;; TODO Step 6` comment, or move the stub declaration to a `(defn- trim-history [_ctx] ...)` near the dispatch stage with a clear "stub until Step 6" marker, AND add a `deftest compose-context-...` that asserts the no-op behavior so removal in Step 6 is a deliberate test change, not a silent one.

### 5. (POLISH) `:agent/parallel-tools?` is referenced but never defaulted / documented

Related to (1) but distinct: even if the bug in (1) is fixed, the state key `:agent/parallel-tools?` is read in `interceptors.clj` but never set anywhere in MVP, never listed in `AGENT_INSTRUCTIONS.md`, and never appears in `kschltz.agent.exchange` ctx-prep. If you decide to keep the knob, add it to the MVP state defaults and document it. If you decide to drop it, do a `rg ':agent/parallel-tools?'` first to catch references.

### 6. (POLISH) `dispatch` is in the default chain but does nothing meaningful in MVP

The docstring says "MVP: no-op (stub LLM never produces tool calls). The :leave stage is a no-op; the entire stage is here to exercise the dispatch *path* in tests via a fake LLM that returns tool calls." But:
- The default stub LLM in `llm/client.clj` does not return `:tool/calls`.
- The only test that exercises the dispatch path is `dispatch-uses-sequential-mapv-by-default`, which calls `(:enter ix/dispatch)` directly with hand-built ctx — not through `chain/execute`.

So the "exercise the dispatch path" claim is also aspirational. Either add an end-to-end test that pushes tool calls through the default chain via a fake LLM, or move `dispatch` out of `default-exchange-chain` until it has work to do. Currently the test suite never proves dispatch composes with the rest of the chain.

### 7. (POLISH) `noop-backend` is a `reify`, so `satisfies?` after `ig/halt-key!` may surprise

File: `src/kschltz/agent/system.clj` line ~70
Literal:
```clojure
(defmethod ig/halt-key! :lateralus/memory-backend [_ backend]
  (when (satisfies? memory-protocol/MemoryBackend backend)
    (memory-protocol/-close backend))
  :halted)
```
This guards with `satisfies?`, which is fine for a `reify`. But `ig/halt-key!` is called after `ig/init`, and Integrant normally already routes to the right defmethod per key — so the `satisfies?` check is redundant for the only backend in MVP. Harmless, but adds noise. Either drop it (trust the defmethod dispatch) or keep it as defensive code with a comment explaining why.

### 8. (POLISH) `:lateralus/plugins` `:lateralus/llm-client` `:lateralus/embedder` halt! methods are noise

File: `src/kschltz/agent/system.clj` lines ~77-86
```clojure
(defmethod ig/halt-key! :lateralus/plugins [_ _] :halted)
(defmethod ig/halt-key! :lateralus/llm-client [_ _] :halted)
(defmethod ig/halt-key! :lateralus/embedder [_ _] :halted)
```
Integrant halts keys only when `halt-key!` is defined. Adding a no-op `halt-key!` for every key just so it halts at all is a footgun — it tells future readers "this resource needs cleanup" when it doesn't. Drop the three no-op `halt-key!` defmethods; let Integrant skip them on halt. The current `halt-closes-memory-backend` test will still pass (the system only halts the components that have real halt methods, which is correct behavior).

## What I did NOT find

- `rg 'agent\.loop' src/` — clean (the docstring mention is a comment, not a delegation; verified by `deftest interceptors-do-not-depend-on-loop-clj` in `exchange_test.clj`).
- `rg 'add-.*-tool!' src/` — clean.
- `rg 'pmap' src/` — clean.
- `clojure -M:test` — 38 tests, 79 assertions, 0 failures, 0 errors. (Verified at the time of this audit.)

The plumbing is in good shape; the issues above are mostly about the contract between the chain engine and the interceptors, and about not shipping a knob that lies.

## Standing down

I will not edit `src/kschltz/agent/*.clj` or `test/kschltz/agent/*.clj` without flagging first. Open to writing follow-up tests or to expanding the audit to Step 5/6 once LlmClient + memory land.
