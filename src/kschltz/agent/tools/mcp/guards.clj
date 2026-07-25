(ns kschltz.agent.tools.mcp.guards
  "Guards for model-bound MCP tool results.

   Reuses the web suite's injection / self-activation marker vocabulary
   so we do not invent a second unrelated list. Applies:

   - ASCII control-char stripping (keeps tab/newline)
   - max-result-bytes truncation (`truncated?`)
   - injection-marker and tool-call self-activation scans (`blocked?`)"
  (:require [clojure.string :as str]
            [kschltz.agent.tools.web.guards :as web.guards]))

(def default-max-result-bytes
  (* 64 1024))

(def ^:private control-char-re
  #"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]")

(def ^:private tool-call-markers
  "Markers that suggest a tool result is trying to re-enter the tool
   loop / impersonate a model function call. Aligned with web
   self-activation intent."
  #"\"(?:tool_calls|function_call|tool_call)\"\s*:|<\s*tool_call\s*>|invoke\s+tool\s*[:=]")

(defn strip-control-chars
  "Remove ASCII control characters except tab/newline/carriage-return."
  [s]
  (if (string? s)
    (str/replace s control-char-re "")
    ""))

(defn truncate-result
  "Truncate `s` to at most `max-bytes` UTF-16 code units (chars). Returns
   `{:text s :truncated? bool}`."
  [s max-bytes]
  (let [max-bytes (or max-bytes default-max-result-bytes)
        s (or s "")]
    (if (> (count s) max-bytes)
      {:text (str (subs s 0 max-bytes)
                  (format "\n... [truncated at %d chars]" max-bytes))
       :truncated? true}
      {:text s :truncated? false})))

(defn- injection-markers
  []
  (or (:injection-markers (web.guards/default-guard-config))
      #{}))

(defn scan-injection
  "Return `{:blocked? bool :reason (s|nil)}` for model-bound text."
  [s]
  (let [lower (str/lower-case (or s ""))]
    (cond
      (some #(str/includes? lower (str/lower-case %)) (injection-markers))
      {:blocked? true :reason "injection-marker"}

      (re-find tool-call-markers s)
      {:blocked? true :reason "self-activation"}

      :else
      {:blocked? false :reason nil})))

(defn guard-result-text
  "Apply control-char strip, truncation, and injection scans.

   Returns
   `{:text s :truncated? bool :blocked? bool :reason (s|nil)}`.
   When blocked, `:text` is replaced with a short safe notice so the
   raw payload does not re-enter the model context."
  [s {:keys [max-result-bytes block-injection-markers?]
      :or {block-injection-markers? true}}]
  (let [cleaned (strip-control-chars s)
        {:keys [text truncated?]} (truncate-result cleaned max-result-bytes)
        scan (if block-injection-markers?
               (scan-injection text)
               {:blocked? false :reason nil})]
    (if (:blocked? scan)
      {:text (str "[mcp result blocked: " (:reason scan) "]")
       :truncated? truncated?
       :blocked? true
       :reason (:reason scan)}
      {:text text
       :truncated? truncated?
       :blocked? false
       :reason nil})))
