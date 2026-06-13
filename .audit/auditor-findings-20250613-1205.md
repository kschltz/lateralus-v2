# Auditor findings — second pass (commit f42e8c5 "Audit fixes: …")

Session: auditor (25b5ff64-cf32-4d1e-9294-b2f75329ca4f)
Repo: /Users/schltzk/projects/lateralus-v2
Reviewed: ed22b45 + f42e8c5 (working tree clean)
Test status: 43 tests, 99 assertions, 0 failures, 0 errors (verified locally).

## Status of prior findings (1st audit, .audit/auditor-findings-20250613-1117.md)

| # | Prior finding | Status |
|---|---------------|--------|
| 1 | BLOCKER: dead `parallel?` branch in `dispatch` | **APPLIED.** Knob removed; `dispatch` is a single `mapv`. Docstring updated to call out the deletion. |
| 2 | DECISION: `assemble-chain` / `validate-plugins` swallow Malli problems | **APPLIED.** New `explain-errors` / `format-problems` helpers; `assemble-chain` now throws with `{:problems [...], :plugins [...]}`; `validate-plugins` returns `{:problems [...], :message "..."}`. Tests assert the new shape. |
| 3 | DECISION: `error-boundary` annotation is invisible to callers | **APPLIED — but with a different design than I proposed.** `error-boundary` now dissocs `::chain/error` and annotates `:error/raised` on ctx. Test `error-boundary-handles-and-observes` was rewritten (renamed from `error-boundary-handles-and-leaves-run`) and now explicitly documents the engine contract: **stages that never entered do not get a `:leave` pass, even if a handler cleared the error.** This is a defensible design call — it matches the v1 engine — but it should be documented in `chain.clj`'s ns docstring, which still only says "the handling interceptor's own :leave is NOT re-run" without mentioning the never-entered-stage case. |
| 4 | POLISH: `trim-history` no-op pretending to be a fn | **APPLIED.** Function deleted; `compose-context` uses inline `identity messages` (no-op side effect) plus a `:compose/trimmed?` marker and a TODO Step 6 comment. Test `compose-context-trim-is-noop` pins the marker. |
| 5 | POLISH: `dispatch` in default chain has no end-to-end coverage | **APPLIED.** New `dispatch-end-to-end-with-tool-calls` test wires a fake LLM that returns tool calls through the full default chain and asserts `parse-response` extracts them and `dispatch` records `:not-implemented` results. |
| 6 | POLISH: no-op `halt-key!` defmethods are misleading | **APPLIED.** All four no-op defmethods removed. New `halt-skips-keys-without-halt-key` test (see new issue #4 below for a weakness in that test). |

All six prior findings are addressed. Going forward I checked the *resulting* code for new issues introduced or uncovered by the changes.

## New findings (second pass)

### 1. (DECISION) `:lateralus/llm-client` Integrant config is dead code at runtime

File: `src/kschltz/agent/interceptors.clj:48-52` and `src/kschltz/agent/system.clj:52`
Literal (interceptor):
```clojure
(defn call-llm [ctx]
  (let [client (or (:llm/client ctx) (default-llm-client))
        req    (:llm/request ctx)]
    (assoc ctx :llm/response (llm-client/-call client req))))
```
Literal (agent init): `{:llm/client llm-client ...}` is placed on the agent map, **not on the per-exchange ctx**. The default chain reads `(:llm/client ctx)` — but no stage in `default-exchange-chain` writes `:llm/client` to ctx. The current test helper `run-exchange` puts it on ctx directly, masking the issue.

Net effect: the Integrant-managed `:lateralus/llm-client` is never consulted by `llm-call`. In MVP the stub is constructed fresh on every call. When Step 5 lands the HTTP client, the Integrant config wiring will *appear* to work (config is read, agent map carries the client) but every exchange will still use a fresh stub. The bug will be silent.

Two coherent fixes:
- (a) Add a stage to `default-exchange-chain` that copies the agent's LlmClient onto ctx at exchange start. Place it between `error-boundary` and `compose-context` (so it runs before any stage that might need the client).
- (b) Change `call-llm` to take the client as a parameter (partial application at init time) instead of reading from ctx. The protocol boundary becomes explicit.

Either is fine. Pick one *before* Step 5 lands — leaving this unresolved until then is the worst path.

### 2. (POLISH) `explain-errors` docstring lies about its return shape

File: `src/kschltz/agent/plugin.clj:79-86`
Literal:
```clojure
(defn- explain-errors
  "Return the `:errors` vector from a Malli explain result, or nil
   when the explain result is nil/empty. Always returns a vector
   for predictable caller access."
  [explain-result]
  (when-let [errs (and explain-result (:errors explain-result))]
    (when (seq errs) (vec errs))))
```
The doc claims it "always returns a vector for predictable caller access." It does not. It returns `nil` when `explain-result` is nil, and `nil` when `:errors` is empty. Only the third case (non-empty `:errors`) returns a vector. Three of three cases that look similar to a caller — and two of three return nil.

Fix: either fix the docstring ("returns nil or a non-empty vector") or change the function to `(or (explain-errors ...) [])` at call sites so callers can treat the result as a vector. The current code at the call sites already does `(when (seq problems) ...)` which works around the lie, but a future caller will read the docstring and write `(map :type (:problems (validate-plugins ...)))` and get a NullPointerException on the empty case.

### 3. (POLISH) `:plugin/register` is in the schema but never invoked

File: `src/kschltz/agent/plugin.clj:43-53` and `assemble-chain` body
The `Plugin` Malli schema includes `[:plugin/register {:optional true} fn?]`. The `assemble-chain` function never reads or invokes `:plugin/register`. The docstring on the namespace says plugins are "pure data map" and the `Plugin` schema entry is the only mention of `:plugin/register`.

Two interpretations:
- (a) It is intended for use by a future plugin lifecycle hook (state pre-population, tool registration). In that case it should be removed from the schema until the lifecycle exists, or the lifecycle should be added (an `:init`/`halt!` for plugins parallel to the Integrant component lifecycle).
- (b) It is a leftover from v1 and should be deleted.

Either is fine. Currently it is dead schema — passes validation, gets ignored, gives no signal to a future plugin author that it does nothing.

### 4. (POLISH) `build-interceptor` allows all-nil stage interceptors

File: `src/kschltz/agent/plugin.clj:55-63`
Literal:
```clojure
(defn- build-interceptor [plugin-name slot ix]
  {:name (keyword ...)
   :enter (:enter ix)
   :leave (:leave ix)
   :error (:error ix)
   :plugin/name plugin-name ...})
```
If a plugin author writes `{:plugin/slots {:guard [{:name :foo}]}}` (an interceptor map with no stages), `build-interceptor` produces a map with `:enter`/`:leave`/`:error` all nil. The engine treats absent stages as no-ops, so this is a silent no-op interceptor. The `Plugin` schema permits it.

This is a footgun: a typo or a stub interceptor that the author meant to fill in will quietly work but do nothing. Add a Malli check in the `Interceptor` schema (or in `build-interceptor`) that requires at least one of `[:enter :leave :error]`. Something like:
```clojure
[:enter {:optional true} [:maybe fn?]]
[:leave {:optional true} [:maybe fn?]]
[:error {:optional true} [:maybe fn?]]
[:_stage-present {:optional true} :any]  ; sentinel — see below
```
…or simpler: add a runtime check in `build-interceptor` that throws when all three are nil, with a clear error message pointing at the plugin/slot/ix.

### 5. (POLISH) `halt-skips-keys-without-halt-key` test is tautological

File: `test/kschltz/agent/system_test.clj:69-78`
Literal:
```clojure
(deftest halt-skips-keys-without-halt-key
  (let [s (with-system system/default-config)
        llm  (:lateralus/llm-client s)]
    (ig/halt! s)
    (is (some? llm) "stub client object survived halt (no halt-key! was registered)")))
```
`(:lateralus/llm-client s)` captures the object before halt. After halt, `(some? llm)` is true because the local binding still holds a reference — it does not depend on Integrant doing anything. The test would pass even if `ig/halt!` corrupted global state, deleted the system map, or threw a fatal exception that the surrounding try-catch swallowed.

A test that actually proves the halt policy should:
- Use an atom to record a side effect on halt, registered as a custom Integrant key.
- Configure a custom key that, if Integrant did *not* skip it, would set the atom.
- Assert the atom is unchanged after halt.

Example shape (not for application, just for the idea):
```clojure
(deftest halt-skips-keys-without-halt-key
  (let [probe (atom :unharmed)]
    (defmethod ig/init-key  :lateralus/probe [_ _] :init-ran)
    (defmethod ig/halt-key! :lateralus/probe [_ _] (reset! probe :halt-ran))
    (try
      (let [s (ig/init (assoc system/default-config :lateralus/probe {}))]
        (ig/halt! s)
        (is (= :unharmed @probe) "halt must skip a key with no halt-key!"))
      (finally
        (remove-method ig/init-key :lateralus/probe)
        (remove-method ig/halt-key! :lateralus/probe)))))
```
This is a real test. The current test is decoration.

### 6. (POLISH) Engine contract about never-entered stages is undocumented in the engine ns

File: `src/kschltz/agent/chain.clj` ns docstring (lines 12-14)
The `error-boundary-handles-and-observes` test in `exchange_test.clj:140-143` has the comment:
> "stages AFTER bomb-stage never enter (the engine stops the enter walk on first error). They are not in the stack and therefore cannot run :leave. This matches the v1 engine contract."

This is a contract callers need to know — but it lives only in a test comment. The engine's own docstring should state it. Suggested addition after the existing `:enter fns run in queue order` bullet:

> "When an exception is thrown by any `:enter` stage, the enter walk stops immediately. Stages after the throwing one are not pushed onto the stack and will not be entered, even if an `:error` handler in a prior stage clears the error."

Without this, plugin authors who use `:enter` to enqueue tool calls or compose sub-chains will be surprised when downstream stages silently never run.

### 7. (POLISH) `_trim` placeholder in `compose-context` is dead-but-load-bearing-looking

File: `src/kschltz/agent/interceptors.clj:84-99`
Literal:
```clojure
;; TODO Step 6: replace `identity` with proper
;; history trimming (token budget, recall window).
_trim     (identity messages)
```
The `_trim` binding is computed and immediately discarded. It has the form of a real call site for a real function but is in fact a self-documenting comment. Step 6 will likely grep for `_trim` and miss the actual TODO (which is in the comment above). Consider replacing with a `(defn- trim-history-stub [messages] messages)` private fn defined next to `compose-context` so Step 6 has a clear "delete this fn and inline the real trim" target, AND a `deftest compose-context-trim-stub-exists` that pins the stub fn's arity. Currently the test pins only the marker, not the function.

## What I did NOT find

- Tests still all pass: 43/43. (Verified at the time of this audit.)
- `rg 'agent\.loop' src/` — clean.
- `rg 'add-.*-tool!' src/` — clean.
- `rg 'pmap' src/` — clean.
- `rg 'http/completion' src/` — clean (only the placeholder `http-client` in `llm/client.clj`).
- The forbidden patterns are clean.

## Standing down

I will not edit `src/kschltz/agent/**` or `test/kschltz/agent/**` without flagging first. Open to writing follow-up tests for the new findings (#1 especially — the `:llm/client` wiring needs a runtime test before Step 5 lands) or expanding the audit to Step 5/6 (HTTP client + memory) once those land.
