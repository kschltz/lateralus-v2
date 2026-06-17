# Tool-calling loop example

This example demonstrates how to register filesystem tools with lateralus'
core tool-calling loop. The loop itself is now part of the default base
chain (`kschltz.agent.loop`); this example only wires in read-only file
operation `Tool` implementations.

## What it shows

- `:lateralus/tool-registry` maps tool names to `Tool` protocol implementations.
- `:lateralus/tools-plugin` seeds the registry on the context so the core
  loop interceptors can see the tools.
- Tool definitions are injected into the LLM request automatically.
- Tool calls returned by the model are executed and fed back to the model in
  a loop, capped by a recursion guard.

## Tools

- `file/read` — read the contents of a UTF-8 text file.
- `file/list` — list files and directories.
- `file/info` — return path metadata (exists, type, size, last modified).
- `file/search` — recursively search files for a regex pattern.

## Requirements

- Ollama running at `http://localhost:11434/v1`.
- A model that supports tool calling, such as `deepseek-v4-flash:cloud`, `llama3.1`, or
  `mistral-nemo`.

## Run

From the repository root:

```bash
clojure -M:run --config examples/tools/loop/config.edn -i
```

Then try prompts like:

```
List the files in this project.
```

```
Read the README.md file.
```

```
Search for the word "interceptors" in the src directory.
```

## Customize tools

Add your own `Tool` implementations to the registry. Each tool declares a
name, description, Malli input/output schemas, and an `invoke` method.

```clojure
(ns my.tools
  (:require [kschltz.agent.tool :as tool]))

(deftype MyTool []
  tool/Tool
  (-name [_] "my-tool")
  (-description [_] "Does something useful.")
  (-input-schema [_] [:map [:x :string]])
  (-output-schema [_] :string)
  (-invoke [_ args] (str "result for " (:x args))))

(defmethod ig/init-key :my.tool/my-tool [_ _]
  (->MyTool))
```

Then reference it in the config:

```clojure
:lateralus/tool-registry {:my-tool #ig/ref :my.tool/my-tool}
```

## How it works

1. `examples/tools/loop/config.edn` registers the example tools in
   `:lateralus/tool-registry` and includes `:lateralus/tools-plugin`.
2. `:lateralus/tools-plugin` seeds `:agent/tool-registry` on the context.
3. The base plugin's `inject-tools` interceptor reads the registry and injects
   OpenAI-shaped tool definitions into the LLM request.
4. After the model returns tool calls, the `dispatch-tools` interceptor executes
   the matching tools through the `Tool` protocol.
5. The `tool-loop` interceptor enqueues a follow-up chain that feeds the results
   back to the model. The loop recurses until the model returns text or a depth
   cap is hit.
