# Network boundaries

Outbound network access is never performed directly by an interceptor or
model-facing tool. Each capability is isolated behind a protocol, and the
leaf implementation functions that perform I/O have Malli input/output
schemas with always-on namespace instrumentation.

| Capability | Protocol | Instrumented implementation |
|---|---|---|
| Chat completion | `LlmClient`, `StreamableLlmClient` | `kschltz.agent.llm.http` (`post-chat`, `post-chat-stream`) |
| Model discovery | `ModelCatalog` | `tools.config.catalog` → `llm.http` |
| Dense embedding | `Embedder` | `memory.http-embedding`, `langchain4j-embedding` |
| Web search/fetch | `WebProvider` | `tools.web.mojeek`, `tools.web.ddg` |
| MCP HTTP/stdio | `McpTransport`, `McpClient`, `McpSession` | `tools.mcp.http`, `transport`, `client`, `session` |
| Runtime dependency resolution | `ClojureRuntime` | `tools.runtime.jvm` |
| Evolution candidate LLM | `CandidateRunner` → existing `LlmClient` | `evolution.candidate` |

The CLI model picker and profile wizard use `ModelCatalog`; they do not call
the HTTP model-list functions directly. Local file I/O, the optional local
`clj-kondo` subprocess, and the opt-in `StoreEngine` (memory / DuckDB JDBC)
are not network boundaries. DuckDB never auto-installs extensions.

The evolution supervisor also protocol-isolates local process and repository
effects (`CommandRunner`, `WorkspaceManager`, `Board`, `Evaluator`, and
`EvolutionLedger`). Its command implementation accepts allowlisted argv
vectors only and uses OS network isolation for offline gates; it never exposes
an unrestricted shell to the model. Candidate LLM traffic remains behind the
existing `LlmClient` implementation boundary.
Verifier isolation is platform-specific and fail-closed: `sandbox-exec` on
macOS and Bubblewrap on Linux. A missing or failed backend rejects the command
before its argv is started. Linux Bubblewrap uses an unshared network
namespace and exposes only the candidate worktree as writable.

When a secret store is active, runtime-authored tools run in SCI and cannot
open sockets, use Java interop, load dependencies, or receive the host
interceptor context. Their only I/O path is
`lateralus.runtime/call-tool`, which accepts operator-allowlisted host tool
names. Secret handles resolve only inside a separately configured host-tool
capability, so plaintext never enters model-authored code.

New network-backed capabilities must add:

1. A protocol consumed by tools/interceptors.
2. Closed or explicitly open Malli schemas for implementation inputs/outputs.
3. `m/=>` declarations on leaf I/O and constructor functions.
4. `malli.instrument/instrument!` enabled when the namespace loads.
5. Offline tests using injected transport functions or protocol fakes.
