# Goal: Interceptor-native runtime and file harness

## Objective

Preserve Lateralus's “everything is an interceptor” architecture while making
the running agent safely inspectable and editable, and provide a modern
coding-agent filesystem harness.

## Delivered contract

- Runtime inspection is redacted data read from immutable exchange context.
- Runtime edits are closed-schema transitions harvested/applied in `:tools`;
  only the outer runtime merges session state or consumes deferred reloads.
- Integrant-assembled plugins and tool registries carry rebuild metadata so
  edited built-in namespaces can take effect on the next exchange.
- Core JVM protocol/class namespaces explicitly require process restart.
- File reads are bounded and hash-witnessed; discovery is sorted, bounded, and
  sandboxed; mutations are locked, stale-safe, backed up, atomic, and verified.
- Generic patches use exact snapshot hashes and line ranges. Clojure edits add
  rewrite-clj round-trip validation and bounded clj-kondo diagnostics.
- Every outbound network implementation is protocol-isolated and
  Malli-instrumented at its implementation boundary.

## Authoritative verification

```bash
clj-kondo --lint src test
clojure -M:test
clojure -M:e2e:workbench -n kschltz.agent.runtime-harness-e2e-test
```

The full fast suite has three documented pre-existing Portal viewer metadata
failures. The focused harness suites and deterministic offline E2E must pass
with zero failures.

## Runtime restart boundary

`kschltz.agent.runtime`, `chain`, `system`, `tool`, and `transitions` define
generated classes/protocol identities and are not replaced inside a running
JVM. `reload_runtime` returns `restart-required` for those namespaces rather
than claiming an unsafe in-process replacement succeeded.
