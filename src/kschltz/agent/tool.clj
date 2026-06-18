(ns kschltz.agent.tool
  "Tool abstraction for lateralus agents.

   A `Tool` is an external capability exposed to the LLM. Every tool
   declares a name, description, and Malli schemas for its input and
   output. The `invoke-tool` helper validates the parsed arguments
   against the input schema, calls the tool with the current interceptor
   context, and validates the result against the output schema.

   This namespace isolates tool execution behind a protocol and
   instruments both sides with Malli, matching the project rule that
   every external/network dependency must be protocol-bound and
   schema-instrumented."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as json-schema]))

(defprotocol Tool
  "A callable tool exposed to the LLM."
  (-name [this] "Tool name as it appears in model requests.")
  (-description [this] "Human-readable description for the model.")
  (-input-schema [this] "Malli schema for the parsed arguments map.")
  (-output-schema [this] "Malli schema for the tool result.")
  (-invoke [this args ctx] "Execute the tool with validated `args` and the
   current interceptor `ctx`. Returns a string-serializable result."))

(defn tool?
  "Return true if `x` satisfies the `Tool` protocol."
  [x]
  (satisfies? Tool x))

(defn- parse-arguments
  "Parse the JSON arguments string that the model returned."
  [arguments]
  (try
    (json/parse-string arguments true)
    (catch Throwable _ {})))

(defn- validation-error
  "Build a model-visible error string from a Malli explanation."
  [phase _schema _value explain]
  (format "Tool %s failed Malli validation: %s"
          phase
          (pr-str (me/humanize explain))))

(defn invoke-tool
  "Call `tool` with parsed `args` and interceptor `ctx`. Validates
   `args` against the tool's input schema, executes the tool, then
   validates the result against the output schema. Returns the result
   string on success, or a model-visible error string on failure."
  [tool args ctx]
  (let [input-schema  (-input-schema tool)
        output-schema (-output-schema tool)]
    (if-let [explain (m/explain input-schema args)]
      (validation-error "input" input-schema args explain)
      (try
        (let [result (-invoke tool args ctx)]
          (if (string? result)
            (if-let [explain (m/explain output-schema result)]
              (validation-error "output" output-schema result explain)
              result)
            (format "Tool result is not a string: %s" (pr-str result))))
        (catch Throwable t
          (format "Tool execution error: %s" (ex-message t)))))))

(defn tool-definition
  "Build an OpenAI-shaped function-tool definition map for `tool`."
  [tool]
  {:type "function"
   :function {:name        (-name tool)
              :description (-description tool)
              :parameters  (or (json-schema/transform (-input-schema tool)) {:type "object"})}})

(defn execute-tools
  "Execute a seq of `calls` against a `registry` (map name -> Tool).
   Each call is expected to be OpenAI-shaped: `:function` with `:name`
   and `:arguments` (a JSON string). `ctx` is the current interceptor
   context and is passed to each tool implementation. Returns a vector
   of `{:call c :result s}` where `s` is the tool result string or an
   error message. Calls with no registered handler return a model-readable
   string listing the available tools."
  [registry ctx calls]
  (mapv (fn [call]
          (let [name (get-in call [:function :name])
                tool (get registry name)
                available (vec (sort (keys registry)))
                args-str (get-in call [:function :arguments])]
            (if tool
              {:call   call
               :result (invoke-tool tool
                                   (parse-arguments args-str)
                                   ctx)}
              {:call call
               :result (format "Tool '%s' is not available in this session. Available tools: %s"
                               name
                               (str/join ", " available))})))
        calls))
