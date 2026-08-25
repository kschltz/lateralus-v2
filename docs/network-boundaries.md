# Network boundaries

Outbound network access is never performed directly by an interceptor or
model-facing tool. Each capability is isolated behind a protocol, and the
leaf implementation functions that perform I/O have Malli input/output
schemas with always-on namespace instrumentation.

| Capability | Protocol | Instrumented implementation |
|---|---|---|
| Chat completion | `LlmClient` | `kschltz.agent.llm.http` |
| Model discovery | `ModelCatalog` | `tools.config.catalog` → `llm.http` |
| Dense embedding | `Embedder` | `memory.http-embedding`, `langchain4j-embedding` |
| Web search/fetch | `WebProvider` | `tools.web.mojeek`, `tools.web.ddg` |
| MCP HTTP/stdio | `McpTransport`, `McpClient`, `McpSession` | `tools.mcp.http`, `transport`, `client`, `session` |
| Runtime dependency resolution | `ClojureRuntime` | `tools.runtime.jvm` |

The CLI model picker and profile wizard use `ModelCatalog`; they do not call
the HTTP model-list functions directly. Local file I/O and the optional local
`clj-kondo` subprocess are not network boundaries.

New network-backed capabilities must add:

1. A protocol consumed by tools/interceptors.
2. Closed or explicitly open Malli schemas for implementation inputs/outputs.
3. `m/=>` declarations on leaf I/O and constructor functions.
4. `malli.instrument/instrument!` enabled when the namespace loads.
5. Offline tests using injected transport functions or protocol fakes.
