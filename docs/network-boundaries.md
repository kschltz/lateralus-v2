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

The CLI model picker and profile wizard use `ModelCatalog`; they do not call
the HTTP model-list functions directly. Local file I/O, the optional local
`clj-kondo` subprocess, and the opt-in `StoreEngine` (memory / DuckDB JDBC)
are not network boundaries. DuckDB never auto-installs extensions.

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
