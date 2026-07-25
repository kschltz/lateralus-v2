# Goal — MCP Client Tools for Lateralus

## Intent

Allow Lateralus to **use widely available MCP servers** (the same stdio
servers people already wire into Claude Desktop / Cursor) as first-class
agent tools.

Lateralus is an **MCP client**. v1 does not expose Lateralus as an MCP
server.

## Success criteria

1. An operator can declare MCP servers in Integrant EDN using a shape
   familiar from `mcpServers` (`command` / `args` / `env`).
2. On system init, Lateralus launches each configured stdio server,
   completes the MCP handshake, discovers tools via `tools/list`, and
   adapts them into the existing `:lateralus/tool-registry`.
3. The LLM can invoke those tools through the normal tool loop; results
   return as model-visible JSON envelopes.
4. Default config stays air-gapped: empty `:servers` means no processes
   spawned and no network/process I/O.
5. All process/JSON-RPC I/O is isolated behind a protocol with Malli
   instrumentation on implementation functions.
6. Regression guards and offline e2e (fake MCP server) run in CI-friendly
   suites; live e2e against a real published server is opt-in.

## Non-goals (v1)

- HTTP / SSE / Streamable-HTTP transports
- MCP resources and prompts
- Lateralus-as-MCP-server
- Native-image support for MCP process spawning
- Automatic import of Claude Desktop JSON (follow-up)
