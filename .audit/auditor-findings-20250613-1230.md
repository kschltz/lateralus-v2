# Auditor findings — third pass (working tree, uncommitted)

Session: auditor (25b5ff64-cf32-4d1e-9294-b2f75329ca4f)
Repo: /Users/schltzk/projects/lateralus-v2
Reviewed: ed22b45 + f42e8c5 + working tree (uncommitted; architect is mid-flight on the second-pass findings)
Test status: **50 tests, 105 assertions — 5 ERRORS, 2 FAILS** (verified locally with `clojure -M:test -m cognitect.test-runner`). The architect's work is broken; do not merge as-is.

## Status of second-pass findings (1)

| # | Finding | Status |
|---|---------|--------|
| 1 | (DECISION) `:lateralus/llm-client` Integrant config is dead code at runtime | **APPLIED** with option (a) — new `bind-llm-client` stage placed after `error-boundary`, copies `:agent/llm-client` from ctx onto `:llm/client`. Agent init map renamed key from `:llm/client` → `:agent/llm-client`. Test `full-exchange-with-agent-client` exercises the wiring — but has a critical test-pollution bug (see new finding #1). |
| 2 | (POLISH) `explain-errors` docstring lies | **APPLIED.** Docstring rewritten to "Returns either nil OR a non-empty vector. Callers that need a vector unconditionally should wrap with `(or (explain-errors …) [])`." New `explain-errors-shape` test pins the three cases. |
| 3 | (POLISH) `:plugin/register` in schema but never invoked | **APPLIED.** Removed from `Plugin` Malli schema and from the ns docstring. |
| 4 | (POLISH) `build-interceptor` allows all-nil stage interceptors | **APPLIED.** `build-interceptor` now throws `ex-info "Plugin interceptor has no stage fn (silent no-op)"` with `:plugin/name :plugin/slot :interceptor :hint`. New `assemble-chain-rejects-all-nil-stages` test asserts the throw. |
| 5 | (POLISH) `halt-skips-keys-without-halt-key` test is tautological | **APPLIED — properly this time.** New `halt-skips-keys-without-halt-key-real` registers a fresh probe key `:lateralus/probe-test-3`, sets an atom in `init-key`, never defines `halt-key!`, asserts the atom is unchanged after halt. Real test. |
| 6 | (POLISH) Never-entered stages contract is undocumented in engine ns | **APPLIED.** `chain.clj` ns docstring now has the bullet: "When an exception is thrown by any `:enter` stage, the enter walk stops immediately. **Stages after the throwing one are not pushed onto the stack and will not be entered, even if an `:error` handler in a prior stage clears the error.** A plugin that wants to do post-error work (persistence, cleanup) must be placed BEFORE the stage that may throw, or must be enqueued by the throwing stage's `:enter` before the throw." |
| 7 | (POLISH) `_trim` placeholder in `compose-context` is dead-but-load-bearing-looking | **APPLIED.** New `trim-history-stub` private fn defined next to `compose-context`; `compose-context` calls `(trim-history-stub messages)`. New `trim-history-stub-arity` test pins the arity and the no-op identity behavior. |

All 7 second-pass findings were addressed. The new test additions are good (with one exception — see new finding #2). But **the changes as-written break the test suite**.

## Critical (current test status: 5 errors, 2 fails)

### A. (BLOCKER) Test pollution — `full-exchange-with-agent-client` removes the production defmethod for `:lateralus/llm-client`

File: `test/kschltz/agent/exchange_test.clj:198-225`
Literal:
```clojure
(let [probe-key :lateralus/llm-client]   ; <-- production key, not a probe key
  (defmethod ig/init-key probe-key [_ _] (marker-client))
  (try
    (let [s     (ig/init lateralus-system/default-config)
          ...]
      (is (= "MARKER" (:exchange/response out))
          "the response came from the Integrant-configured client, not a fresh stub")
      (ig/halt! s))
    (finally
      (remove-method ig/init-key probe-key))))))
```
This test overrides the production defmethod for `:lateralus/llm-client` and then **removes** it in `finally`. Once `remove-method` runs, the multimethod `:integrant.core/init-key` has no method for `:lateralus/llm-client`. Every subsequent test in the test-runner JVM that calls `(ig/init ...)` throws `clojure.lang.ExceptionInfo: Error on key :lateralus/llm-client when building system`. This is **why the 5 system_test errors happen**: they all run after `exchange_test`, all call `ig/init`, all blow up on the missing defmethod.

The architect's other new test (`halt-skips-keys-without-halt-key-real`) uses a fresh `:lateralus/probe-test-3` key — that pattern is correct. This test should use the same pattern.

Fix: use a fresh key for the override and rewire the config. Sketch:
```clojure
(let [probe-key :lateralus/probe-llm-client
      config (-> system/default-config
                 (dissoc :lateralus/llm-client)
                 (assoc probe-key {}
                       :lateralus/agent {:plugins (ig/ref :lateralus/plugins)
                                         :llm-client (ig/ref probe-key)  ; still bind to same slot
                                         :embedder (ig/ref :lateralus/embedder)
                                         :memory-backend (ig/ref :lateralus/memory-backend)}))]
  (defmethod ig/init-key probe-key [_ _] (marker-client))
  (try ... (finally (remove-method ig/init-key probe-key))))
```
This way the test never touches the production defmethod. Restoration is clean.

**Verify by running the full suite after the fix — system tests must return to green.**

### B. (BLOCKER) `plugin-schema-does-not-recognize-register` test premise is wrong

File: `test/kschltz/agent/plugin_test.clj:130-139`
Literal:
```clojure
(deftest plugin-schema-does-not-recognize-register
  (testing ":plugin/register is removed; plugins using it fail validation"
    (let [p {:plugin/name :legacy
             :plugin/slots {}
             :plugin/register (fn [_state _tools] {})}
          result (plugin/validate-plugins [p])]
      (is (map? result) ...)
      (is (seq (:problems result)) ...))))
```
The `Plugin` schema is `[:map {:closed false} ...]` (open map). With `{:closed false}`, Malli **silently accepts extra keys**. The legacy plugin `{:plugin/name :legacy :plugin/slots {} :plugin/register (fn [_ _] {})}` validates successfully — `validate-plugins` returns `nil`, not a `{:problems [...]}` map. Hence the test fails with `(not (map? nil))` and `(not (seq nil))`.

Two coherent fixes:
- (a) **Delete the test.** The docstring removal is sufficient signal that `:plugin/register` is gone. A test that pins "this schema accepts legacy keys" is not useful and would be a recurring source of confusion.
- (b) **Make the schema closed**, by setting `{:closed true}` on the `[:map ...]`. But this will break `assembled-interceptors-have-plugin-metadata` and similar tests that assert the assembled interceptor map has `:plugin/name :plugin/slot :plugin/original-name` keys (extra keys beyond the Interceptor schema's allowed set). Closing the schema would need a follow-up that updates the Interceptor schema to include those keys, and then the test would pass. (b) is a larger refactor — recommended only if there is a real motivation beyond "test this deleted key stays deleted".

Pick (a) for now.

## New quality issues (introduced or surfaced by the second-pass changes)

### 1. (DECISION) `bind-llm-client` always sets `:llm/client`, even to nil

File: `src/kschltz/agent/interceptors.clj:94-99`
Literal:
```clojure
(def bind-llm-client
  {:name ::bind-llm-client
   :enter (fn [ctx]
            (let [client (or (:llm/client ctx)
                             (:agent/llm-client ctx))]
              (assoc ctx :llm/client client)))})
```
When both `:llm/client` and `:agent/llm-client` are absent, the `:enter` assoc's `nil` as `:llm/client`. The ctx's `:llm/client` key now exists, but holds nil. `call-llm` does `(or (:llm/client ctx) (default-llm-client))` which handles the nil case — works correctly. But this is a behavioral change: code that distinguishes "absent" from "present, nil" on `:llm/client` will break. There is no such code today, but the stage's contract is "I always write `:llm/client`", which is a stronger claim than "I copy from agent to ctx".

Cleaner: only assoc when there is something to assoc.
```clojure
:enter (fn [ctx]
         (if-let [client (or (:llm/client ctx) (:agent/llm-client ctx))]
           (assoc ctx :llm/client client)
           ctx))
```
This is a small style fix; not blocking, but worth doing before more code starts depending on the "always assoc" behavior.

### 2. (POLISH) The `Plugin` schema's open-ness is now a real risk, not a hypothetical one

Related to issue B above. The `Plugin` schema is `{:closed false}`, and a fresh test (`plugin-schema-does-not-recognize-register`) just demonstrated that open schemas silently accept legacy keys the schema authors thought they had removed. This will recur every time someone deletes a plugin field: a test that asserts the legacy key is rejected will fail, the schema will have to be closed, and the open-schema design will be questioned again.

Either:
- (a) Document the open-schema contract explicitly in the `Plugin` docstring ("extra keys are silently ignored — use `m/explain` directly to assert specific key presence"). Then the open-schema design is a deliberate, understood choice.
- (b) Tighten the schema to `{:closed true}` and update downstream tests. This is the bigger refactor flagged in issue B.

(a) is one line and is the lower-risk path.

### 3. (POLISH) `bind-llm-client` docstring promises "no real HTTP" but the chain can already receive one

File: `src/kschltz/agent/interceptors.clj:78-93` (stage docstring) and `llm/client.clj:26-29` (http-client placeholder)
The `bind-llm-client` docstring says "without this stage, `llm-call` would always fall back to a fresh stub because the agent's client lives on the agent map, not on the per-exchange ctx." This is true in MVP, but the `http-client` placeholder in `llm/client.clj` is already plumbed into `system.clj`'s `:lateralus/llm-client` defmethod (`:http` case). So if someone configures `{:lateralus/llm-client {:impl :http}}` today, `ig/init` will call `(llm-client/http-client opts)` which **throws** "http-client not yet implemented (Step 5)". This propagates up through `ig/init` and the system fails to start.

This is a Step 5 stub, not a regression — it was already in `llm/client.clj`. But `bind-llm-client` adds no defense against it (a runtime that injects the resulting `throw`-only client onto ctx will see `llm-call` fail on the first call). Not a quality issue per se, but worth a one-line note in `bind-llm-client`'s docstring: "MVP: only `:stub` impl is wired; the `:http` impl throws at init time and the chain cannot recover from this until Step 5."

### 4. (POLISH) `:plugin/original-name` from `(:name ix)` is silently nil if ix has no `:name`

File: `src/kschltz/agent/plugin.clj:71-78` (new `build-interceptor`)
Literal:
```clojure
:name (keyword (str (name plugin-name) "." (name slot)))
:enter (:enter ix) ...
:plugin/original-name (:name ix)   ; <-- can be nil
```
The assembled interceptor's `:name` is always derived (`:<plugin>.<slot>`), but `:plugin/original-name` reads the user's input `(:name ix)`. If a plugin author wrote `{:plugin/slots {:guard [{:enter identity}]}}` (no `:name`), the assembled interceptor has `:plugin/original-name nil`. The schema doesn't constrain this, but tests that use `:plugin/original-name` (e.g. `assembled-interceptors-have-plugin-metadata` in `plugin_test.clj`) silently get nil and fail. Currently that test passes because the input interceptor has `:name :recall`. But the absence is un-pinned.

Minor: add `(when-let [orig (:name ix)] [:plugin/original-name orig])` to the map, or a test that exercises "input with no :name" and asserts the assembled interceptor's `:plugin/original-name` is `nil` (or absent, depending on choice). Either pin or document.

### 5. (POLISH) `Plugin` schema change is silent for any plugin in production with `:plugin/register`

This is the "no migration" angle of issue #3 from the second audit. The `Plugin` schema no longer recognizes `:plugin/register`, but **the schema is `{:closed false}`**, so production plugins that use `:plugin/register` will continue to validate. The change only affects new code that wants to use `:plugin/register` and assumes the schema knows about it. This is fine for a greenfield project (lateralus-v2 has no production plugins yet) but is worth a one-line note in CHANGELOG / commit message so that the next reader of the schema doesn't go looking for a code path that wires `:plugin/register` and conclude "it's broken" when in fact it was deliberately removed.

## What I did NOT find

- `rg 'agent\.loop' src/` — clean.
- `rg 'add-.*-tool!' src/` — clean.
- `rg 'pmap' src/` — clean.
- `rg 'http/completion' src/` — clean.

The forbidden patterns are still clean.

## Standing down

I will not edit `src/kschltz/agent/**` or `test/kschltz/agent/**` without flagging first. The two BLOCKER issues (A: test pollution, B: schema-closed test premise) need to be fixed before this work ships — they are the difference between "the test suite is green" and "the test suite is red on every CI run after the first exchange_test". The remaining items (1-5) are quality polish that the architect can take or leave.
