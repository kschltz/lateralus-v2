# Plan: Clojure structured editing tools

## Solution approach

Add a new tool namespace `kschltz.agent.tools.clojure` that provides structured editing of Clojure/EDN files on top of `rewrite-clj`. The tools follow the existing `kschltz.agent.tool/Tool` protocol: each declares a name, description, Malli input/output schemas, and returns JSON strings. All mutating operations are guarded by a round-trip parse check so a malformed edit never leaves a file unparseable.

The implementation is designed for native-image from the start. `rewrite-clj` itself is actively tested under GraalVM and only depends on `org.clojure/tools.reader`, which is already native-friendly. We add the dependency to both the default deps and the `:native` alias, add the new namespace to the native compiled list in `build.clj`, and run the native build as the final verification step.

## Ordered steps

### Step 1 — Add rewrite-clj dependency
**Files:** `deps.edn`
**What:** Add `rewrite-clj/rewrite-clj {:mvn/version "1.2.54"}` to `:deps` and to the `:native` alias's `:replace-deps`. Verify it is not excluded by the native classpath.
**Verification:** `clojure -T:build test` still passes.

### Step 2 — Create the Clojure tool namespace
**Files:** `src/kschltz/agent/tools/clojure.clj`
**What:** Implement helper functions for reading, round-trip validating, and writing a file via `rewrite-clj.zip`. Implement seven `Tool` deftypes:
- `clojure/query` — read-only AST inspection: list defs, requires, or find symbol occurrences.
- `clojure/add-require` — add a require/import entry to the `:require`/`:import` section of `ns`.
- `clojure/remove-def` — remove a top-level `def`, `defn`, `defmacro`, etc. by name.
- `clojure/rename-symbol` — rename a symbol locally or across the whole file.
- `clojure/insert-form` — insert a top-level form before/after a named form or at the end.
- `clojure/edit-def` — replace the body of a `defn`/`def` while preserving metadata and docstring.
- `clojure/format-file` — reformat the file with `rewrite-clj.zip/print` or `zprint` if we decide to bundle it.

Each tool returns JSON. Mutating tools return `{:path ..., :changed true, :backup-path ...}` on success or an error string on failure.
**Verification:** Unit tests in Step 6 pass.

### Step 3 — Wire the new tools into Integrant
**Files:** `src/kschltz/agent/system.clj`, `resources/lateralus/config.edn`, `resources/lateralus/demo-*.edn`
**What:** Add a `:lateralus/clojure-tools` Integrant component that builds the registry via `tools.clojure/clojure-registry`. Include it in `:lateralus/tool-registry`. Update the runtime config EDNs so the new tools are available.
**Verification:** `(ig/init (system/default-config))` starts without errors; the merged registry contains the seven new tool names.

### Step 4 — Update native-image build metadata
**Files:** `build.clj`, `scripts/build-native.sh` if needed
**What:** Add `kschltz.agent.tools.clojure` to `:ns-compile` in `native-uber-opts`. Confirm `rewrite-clj` is already covered by the existing `--initialize-at-build-time=com.fasterxml.jackson` and `graal-build-time` flags.
**Verification:** `clojure -T:native native` completes (this is the long final gate).

### Step 5 — Gate the plan via Plannotator
**Files:** `goals/clojure-structured-edit-tools/plan.md`
**What:** Run `plannotator annotate goals/clojure-structured-edit-tools/plan.md --gate` and revise from feedback until approved.
**Verification:** Plan is approved.

### Step 6 — Write unit tests
**Files:** `test/kschltz/agent/tools/clojure_test.clj`
**What:** Use temp files in a `deftest` fixture. Test each tool for happy path and failure cases: missing file, unknown symbol, malformed new form, and round-trip failure. Assert that the file is unchanged when validation fails.
**Verification:** `clojure -T:build test` passes.

### Step 7 — Write the goal markdown
**Files:** `goals/clojure-structured-edit-tools/goal.md`
**What:** Articulate the goal, reference `facts.md` and `plan.md`, and define the done condition.

## Risks and open questions

1. **Native-image reflection:** `rewrite-clj` and `tools.reader` may need a small `reflect-config.json` entry. The project already uses `graal-build-time`, so most Clojure classes are initialized at build time. If the build fails, we'll add the specific reflection metadata rather than falling back to global `--initialize-at-build-time`.
2. **Format tool implementation:** `format-file` could use `rewrite-clj.zip/print` only, or bundle `zprint`. The interview selected `rewrite-clj`; we will start with `rewrite-clj` formatting and only add `zprint` if the result is unsatisfactory.
3. **Edit granularity:** `edit-def` replaces the entire body. If later goals want finer-grained edits (e.g., replace just one arity), we can extend the tool without breaking existing callers.
4. **Backup strategy:** The current filesystem tools have no backup. Mutating tools will write to a `.bak` sidecar file before overwriting, matching the user's safety preference from the interview.
