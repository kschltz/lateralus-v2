# Facts — Lateralus v2 Complete Rewrite

- v2 is developed in a new git repository (`../lateralus-v2/`, coord `net.clojars.kschltz/lateralus-v2`, namespaces `kschltz.lateralus` / `kschltz.agent.*`); the existing lateralus repo remains the v1 archive. Goal planning artifacts under `lateralus/goals/lateralus-v2-rewrite/` are copied into the v2 repo at bootstrap; v1 source is not modified by the rewrite.
- The agent runtime is built on an interceptor chain engine (Pedestal-style enter/leave/error semantics) as the single extension mechanism, per docs/interceptor-loop-implementation-plan.md. Integrant manages component lifecycles; extensible parts are integrant-managed interceptors fed into the agent component at startup or runtime.
- All agentic behavior and state change flow through an immutable context map threaded through the interceptor chain; no interceptor mutates shared refs except the outer loop merging `:agent/state-delta` back into the agent ref.
- The context map is Malli-schemed and optionally instrumented after every interceptor stage (dev/test default) so exchanges are auditable and traceable. Session IDs and user message IDs are tracked for interaction traceability.
- The chain engine namespace stays under ~200 LOC; core/agent assembly contains no business logic for memory, LLM, or individual tools.
- MVP ships a working agent loop: queue drain → context compose → LLM call → tool dispatch → response, with bounded tool-call depth and error retries. No user-facing tools ship in MVP; the default tool registry is empty and dispatch is integration-tested via a dev-only stub tool.
- MVP ships session memory with a new v2 storage format (fresh start — no read/migration of v1 Datalevin sessions).
- MVP ships a clean-slate CLI (redesigned flags, no v1 compat guarantee) supporting at least interactive and one-shot modes.
- MVP ships a JVM distributable (launcher script or uberjar). GraalVM native-image is a stretch target (Step 9); if any native-image blocker surfaces, MVP completes with documented JVM fallback and a tracked blocker issue.
- All LLM calls go through an LlmClient protocol; no direct http/completion calls from the agent loop or interceptors.
- Every external/network boundary (LLM HTTP, embeddings, and any future search HTTP) is behind a protocol with Malli-instrumented input/output on implementations.
- Tool calls execute sequentially by default; parallel execution requires an explicit opt-in flag.
- Tools and capabilities register only via plugin/interceptor bundles; there are no ad-hoc add-*-tool! installer functions.
- Every src namespace has corresponding tests; no namespace exceeds 500 LOC without test coverage. No feature ships without integration tests.
- Out of MVP scope: web-search, remember, file editing (clj_edit/file_edit), repl-eval, Portal visualize, nREPL integration, v1 uberjar build, and a real persistent memory backend (Datalevin, SQLite, etc. — the MVP MemoryBackend is a noop impl).
- Out of scope entirely: paid search APIs, repl-eval sandboxing, multi-agent orchestration, pi/Cursor SDK integration, custom TUI, and cloud hosting infrastructure.
- Goal is done when MVP facts pass automated verification, JVM distributable runs, README documents the new CLI and architecture, and GraalVM native-image is attempted with outcome documented (success or explicit blocker + JVM fallback).
