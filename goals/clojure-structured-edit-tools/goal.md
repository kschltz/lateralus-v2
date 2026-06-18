# Goal: Clojure structured editing tools

Add a new group of file-modification tools to lateralus-v2 that use `rewrite-clj` to perform structured edits on Clojure and EDN source files. The tools will be exposed to the LLM through the existing `Tool` protocol and must work in the GraalVM native-image build.

Shared understanding: `facts.md`
Execution plan: `plan.md`

## Done condition

- `rewrite-clj` is declared as a dependency in `deps.edn` and in the `:native` alias.
- `src/kschltz/agent/tools/clojure.clj` exists and implements `clojure/query`, `clojure/add-require`, `clojure/remove-def`, `clojure/rename-symbol`, `clojure/insert-form`, `clojure/edit-def`, and `clojure/format-file` as `Tool` instances with Malli schemas.
- Every mutating tool performs a round-trip parse check before writing and writes a `.bak` sidecar on success.
- The new tools are wired into the Integrant system and available in the default runtime config.
- `kschltz.agent.tools.clojure` is added to the native-image compiled namespaces in `build.clj`.
- The native-image build (`clojure -T:native native`) completes successfully.
- Unit tests in `test/kschltz/agent/tools/clojure_test.clj` cover all seven tools plus error paths for malformed source, missing symbols, and failed round-trip validation.

Launch this goal with `/goal goals/clojure-structured-edit-tools/goal.md`.
