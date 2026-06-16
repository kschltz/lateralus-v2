# Tool-calling loop example

This example demonstrates lateralus plugin extensibility by implementing a
complete OpenAI-shaped tool-calling loop in a self-contained plugin.

## What it shows

- A plugin that brings its own interceptor chain (`:plugin/chain`), leaving
  the base exchange chain untouched.
- Tool definitions injected into the LLM request.
- Tool execution against a local registry.
- A loop back to the LLM with tool results, capped by a recursion guard.
- `:lateralus/exchange-chain` used to override the default assembled chain.

## Tools

- `time/now` — returns the current UTC date and time as an ISO-8601 string.
- `calculator/eval` — evaluates simple arithmetic written in prefix notation,
  e.g. `(+ 1 2 3)`, `(* 4 5)`, `(/ 10 2)`. Supported operators: `+ - * / max min`.

## Requirements

- Ollama running at `http://localhost:11434/v1`.
- A model that supports tool calling, such as `deepseek-v4-flash:cloud`, `llama3.1`, or
  `mistral-nemo`.

## Run

From the repository root (after this example is merged):

```bash
clojure -M:examples -m kschltz.lateralus --config examples/tools/loop/config.edn -i
```

While the example is in a kb worktree, run from the worktree directory
and point `--config` at `examples/tools/loop/config.edn` inside it.

Then try prompts like:

```
What time is it?
```

or

```
What is (+ 13 29) times 2?
```

## Customize tools

Edit the config to pass your own `:tools` map with `:definitions` and
`:handlers`:

```clojure
:lateralus/tools-loop-plugin {:tools
                               {:definitions [{:type "function"
                                               :function {:name "my-tool"
                                                          :description "..."
                                                          :parameters {:type "object"
                                                                       :properties {}}}}]
                                :handlers {"my-tool" (fn [args] "result")}}}
```

Your tools are merged over the defaults, so you can add new tools or override
existing ones without redeclaring everything.

## How it works

1. The plugin constructs a full chain with base interceptors reused where
   appropriate (`compose-context`, `llm-call`, `parse-response`, etc.) plus
   plugin-local interceptors for tool injection, execution, and looping.
2. `:lateralus/exchange-chain` resolves the plugin to its `:plugin/chain`.
3. `:lateralus/agent` uses that explicit chain instead of assembling plugins.
4. Inside the chain, after tools execute, the `tool-loop` interceptor uses
   `chain/enqueue` to append a mini-chain that sends results back to the model.
   The loop recurses until the model returns text or a depth cap is hit.
