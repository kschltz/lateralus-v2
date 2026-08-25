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
            [clojure.walk :as walk]
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

(def portable-tool-name-pattern
  "Conservative function-name subset accepted across OpenAI-compatible,
   Cerebras, Anthropic, Gemini, and Bedrock APIs: start with an ASCII
   letter, then use only ASCII letters, digits, or underscores, with a
   maximum length of 64 characters."
  #"^[A-Za-z][A-Za-z0-9_]{0,63}$")

(defn portable-tool-name?
  "True when `name` is safe to expose as a function tool across common
   hosted inference APIs."
  [name]
  (and (string? name)
       (boolean (re-matches portable-tool-name-pattern name))))

(defn- assert-portable-tool-name!
  [name]
  (when-not (portable-tool-name? name)
    (throw (ex-info
            (str "Tool name must match " portable-tool-name-pattern
                 " for cross-provider compatibility: " (pr-str name))
            {:kind    :invalid-tool-name
             :name    name
             :pattern (str portable-tool-name-pattern)})))
  name)

(defn- json-schema-value
  "Normalize values emitted by Malli's JSON Schema transformer to values
   accepted by JSON encoders. Malli 0.16.4 preserves `java.util.regex.Pattern`
   instances in `:pattern`; JSON Schema requires those values to be strings."
  [schema]
  (walk/postwalk
   (fn [x]
     (if (instance? java.util.regex.Pattern x)
       (.pattern ^java.util.regex.Pattern x)
       x))
   schema))

(defn- parse-arguments
  "Parse the JSON arguments string that the model returned."
  [arguments]
  (try
    (json/parse-string arguments true)
    (catch Throwable _ {})))

(defn- validation-error
  "Build a model-visible error string from a Malli explanation.

   Names the tool and the phase (`input`/`output`) so the model can
   attribute the failure, and carries the humanized error structure
   (key paths included via `me/humanize`) so the model can see WHICH
   field failed, not just THAT something failed (audit 2026-07 rec #7:
   the old message omitted the tool name and the failing key path, so
   an `AddLibInput` mistake gave the model nothing concrete to fix)."
  [tool phase _schema _value explain]
  (format "Tool '%s' %s validation failed: %s"
          (-name tool)
          phase
          (pr-str (me/humanize explain))))

(defn invoke-tool
  "Call `tool` with parsed `args` and interceptor `ctx`. Validates
   `args` against the tool's input schema, executes the tool, then
   validates the result against the output schema. Returns the result
   string on success, or a model-visible error string on failure.

   Error envelopes are structured and attributable (audit 2026-07 rec #7):
   - validation failures name the tool + phase + humanized key path;
   - execution throws return a JSON envelope `{:tool :phase :class
     :message :error}` so the model can branch on the exception class
     without parsing prose. The `:error` field keeps the back-compat
     `Tool execution error: <msg>` one-line string so existing
     string-matching callers/tests still match."
  [tool args ctx]
  (let [input-schema  (-input-schema tool)
        output-schema (-output-schema tool)]
    (if-let [explain (m/explain input-schema args)]
      (validation-error tool "input" input-schema args explain)
      (try
        (let [result (-invoke tool args ctx)]
          (if (string? result)
            (if-let [explain (m/explain output-schema result)]
              (validation-error tool "output" output-schema result explain)
              result)
            (format "Tool '%s' result is not a string: %s"
                    (-name tool) (pr-str result))))
        (catch Throwable t
          (json/generate-string
           {:tool    (-name tool)
            :phase   "execution"
            :class   (.getName (class t))
            :message (ex-message t)
            :error   (format "Tool execution error: %s" (ex-message t))}))))))

(defn tool-definition
  "Build an OpenAI-shaped function-tool definition map for `tool`."
  [tool]
  ;; #region agent log
  (spit "/opt/cursor/logs/debug.log"
        (str (json/generate-string
              {:hypothesisId "A,B"
               :location "tool.clj:tool-definition:entry"
               :message "Converting tool input schema"
               :data {:tool (-name tool)
                      :schema (pr-str (-input-schema tool))}
               :timestamp (System/currentTimeMillis)})
             "\n")
        :append true)
  ;; #endregion
  (let [name   (assert-portable-tool-name! (-name tool))
        params (json-schema-value
                (or (json-schema/transform (-input-schema tool)) {:type "object"}))
        patterns (volatile! [])]
    (walk/postwalk
     (fn [x]
       (when (instance? java.util.regex.Pattern x)
         (vswap! patterns conj {:class (.getName (class x))
                                :value (str x)}))
       x)
     params)
    ;; #region agent log
    (spit "/opt/cursor/logs/debug.log"
          (str (json/generate-string
                {:hypothesisId "A,B,C"
                 :location "tool.clj:tool-definition:transformed"
                 :message "Inspected transformed JSON schema"
                 :data {:tool name
                        :patterns @patterns
                        :parameters (pr-str params)}
                 :timestamp (System/currentTimeMillis)})
               "\n")
          :append true)
    ;; #endregion
    ;; #region agent log
    (spit "/opt/cursor/logs/debug.log"
          (str (json/generate-string
                {:hypothesisId "D"
                 :location "tool.clj:tool-definition:exit"
                 :message "Built tool definition"
                 :data {:tool name
                        :pattern-count (count @patterns)}
                 :timestamp (System/currentTimeMillis)})
               "\n")
          :append true)
    ;; #endregion
    {:type "function"
     :function {:name        name
                :description (-description tool)
                :parameters  params}}))

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
