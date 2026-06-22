(ns kschltz.agent.logging
  "Per-stage chain logging for lateralus-v2.

   Logging is expressed in two layers, both interceptor-shaped:
   1. Engine seam — `chain.clj` invokes the `:chain/on-stage` callback
      before and after every stage fn when `:chain/log?` is true on ctx.
      This is the only way to observe every interceptor's enter/leave
      without wrapping each one individually.
   2. Interceptor — `logging-interceptor` (slot :guard, first in the
      chain) seeds `:chain/log? true` + `:chain/on-stage` onto ctx in
      its `:enter` and flushes/closes the sink + writes an
      exchange-summary line in its `:leave` (which runs last because
      it was entered first).

   The default sink is a file at `<dir>/lateralus-<session-id>.edn`,
   one EDN-map line per stage event. `:api-key` is redacted everywhere;
   large message bodies and tool results are truncated. No new deps
   (java.io only) so the native-image build stays clean.

   Turn off via config `{:lateralus/logging {:enabled false}}` or the
   `LATERALUS_LOG_ENABLED=false` env var. Override the log directory
   with `LATERALUS_LOG_DIR`."
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io PrintWriter File]))

(def ^:private default-log-dir "logs")

(def ^:private max-message-chars 4096)
(def ^:private max-result-chars 8192)

(defprotocol LogSink
  "A per-session logging sink. Implementations must be safe for the
   single-threaded chain runtime."
  (-open [sink] "Open the sink for writing. Returns the sink.")
  (-write [sink event] "Append one event map (EDN-serializable).")
  (-close [sink] "Flush and close the sink, releasing resources."))

(defrecord FileLogSink
  [^String dir ^String session-id writer-atom]
  LogSink
  (-open [this]
    (when (nil? @writer-atom)
      (let [f (File. dir)]
        (.mkdirs f))
      (let [pw (PrintWriter.
                (io/writer (io/file dir (str "lateralus-" session-id ".edn"))
                           :append true))]
        (reset! writer-atom pw)))
    this)
  (-write [this event]
    (when-some [w @writer-atom]
      (.println w (pr-str event))
      (.flush w))
    this)
  (-close [this]
    (when-some [w @writer-atom]
      (.flush w)
      (.close w)
      (reset! writer-atom nil))
    this))

(defrecord StdoutSink []
  LogSink
  (-open [this] this)
  (-write [this event] (prn event) this)
  (-close [this] this))

(defrecord NullSink []
  LogSink
  (-open [this] this)
  (-write [this _event] this)
  (-close [this] this))

(defn- truncate
  "Truncate `s` to at most `n` chars, appending an ellipsis marker when
   truncated."
  [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 n) "...[truncated]")
      s)))

(defn- redact-message
  "Redact/truncate a single chat message map."
  [msg]
  (if (map? msg)
    (cond-> msg
      (string? (:content msg)) (assoc :content (truncate (:content msg)
                                                          max-message-chars))
      true                     (dissoc :api-key))
    msg))

(defn- redact-tool-result
  "Truncate a tool result map's `:result` string."
  [r]
  (if (map? r)
    (cond-> r
      (some? (:result r)) (assoc :result (truncate (:result r)
                                                    max-result-chars)))
    r))

(defn- redact-tool-call
  "Reduce a tool call to just its name + truncated arguments."
  [c]
  (if (map? c)
    {:id   (:id c)
     :name (get-in c [:function :name])
     :args (some-> (get-in c [:function :arguments])
                   (truncate max-message-chars))}
    c))

(defn- redact-tool-results
  "Truncate the :result of each entry in a tool-results seq."
  [rs]
  (when (seq rs)
    (mapv redact-tool-result rs)))

(defn- tool-names
  "Return just the tool names from a request's :tools vector, dropping the
   heavy, static function definitions (they repeat on every exchange)."
  [tools]
  (when (seq tools)
    (mapv (fn [t] (get-in t [:function :name])) tools)))

(defn- redact-request
  "Project :llm/request down to model, base-url, redacted message bodies,
   and tool NAMES (not definitions)."
  [req]
  (when (map? req)
    {:base-url (:base-url req)
     :model   (:model req)
     :messages (some-> (:messages req)
                       (->> (mapv redact-message)))
     :tools    (tool-names (:tools req))}))

(defn- redact-response
  "Project :llm/response down to model, usage, and truncated assistant
   content. Drops verbose provider fields."
  [resp]
  (when (map? resp)
    {:model    (:model resp)
     :usage    (:usage resp)
     :choices  (some-> (:choices resp)
                       (->> (mapv (fn [ch]
                                    (when (map? ch)
                                      {:role    (get-in ch [:message :role])
                                       :content (some-> (get-in ch [:message :content])
                                                        (truncate max-message-chars))
                                       :finish_reason (:finish_reason ch)})))))}))

(defn- redact-history
  "Truncate message bodies in a history seq."
  [hist]
  (when (seq hist)
    (mapv redact-message hist)))

(defn redact-ctx
  "Return a small, log-safe projection of `ctx`. Rather than dumping the
   full context (which carries Java objects for the LLM client, memory
   backend, embedder, tool registry, and log sink, plus the heavy
   per-exchange tool definitions), this keeps only the fields useful for
   debugging an exchange: session id, user text, response, a redacted
   agent state, a redacted LLM request (tool NAMES not definitions), a
   redacted LLM response, tool calls/results, and the loop bookkeeping
   flags. `:api-key` is never present. Engine bookkeeping keys
   (`::queue`/`::stack`/`::error`) and all `#object[...]` values are
   dropped by construction."
  [ctx]
  (let [state (when (map? (:agent/state ctx))
                (-> (:agent/state ctx)
                    (dissoc :api-key)
                    (update :history redact-history)))]
    (cond-> {:exchange/session-id        (:exchange/session-id ctx)
             :exchange/user-text         (:exchange/user-text ctx)
             :exchange/response          (some-> (:exchange/response ctx)
                                               (truncate max-message-chars))
             :agent/state                state
             :agent/tool-loop-depth      (:agent/tool-loop-depth ctx)
             :agent/loop-continuing?      (:agent/loop-continuing? ctx)
             :agent/summary-attempted     (:agent/summary-attempted ctx)
             :agent/empty-retry-attempted (:agent/empty-retry-attempted ctx)
             :agent/self-heal-attempts   (:agent/self-heal-attempts ctx)
             :llm/request                (redact-request (:llm/request ctx))
             :llm/response               (redact-response (:llm/response ctx))
             :tool/calls                 (some-> (:tool/calls ctx)
                                               (->> (mapv redact-tool-call)))
             :tool/results               (redact-tool-results (:tool/results ctx))
             :agent/all-tool-results     (redact-tool-results
                                          (:agent/all-tool-results ctx))
             :memory/recall              (some-> (:memory/recall ctx)
                                               (->> (mapv (fn [m]
                                                            (if (map? m)
                                                              (update m :content
                                                                      #(truncate % max-message-chars))
                                                              (truncate m max-message-chars))))))}
      (some? (:memory/last-exchange ctx))
      (assoc :memory/last-exchange
             (-> (:memory/last-exchange ctx)
                 (update :response #(truncate % max-message-chars))
                 (update :tool-calls (fn [cs] (some-> cs (->> (mapv redact-tool-call)))))
                 (update :tool-results redact-tool-results))))))

(defn on-stage-fn
  "Build a `:chain/on-stage` callback that writes a per-stage event to
   `sink`. Catches its own errors so a logging failure never propagates
   (the engine also guards, but belt-and-braces). Returns `ctx`."
  [sink]
  (fn [ctx interceptor stage direction]
    (try
      (-write sink {:ts        (System/currentTimeMillis)
                    :name      (:name interceptor)
                    :stage     stage
                    :direction direction
                    :ctx-view  (redact-ctx ctx)})
      (catch Throwable t
        (binding [*out* *err*]
          (println "lateralus logging error:" (ex-message t)))))
    ctx))

(defn logging-interceptor
  "Interceptor that enables per-stage logging. Place FIRST in the chain
   (slot :guard, before error-boundary) so its `:enter` runs before every
   other stage and its `:leave` runs after every other stage.

   The sink is read from `:agent/log-sink` on ctx (pre-opened by the
   runtime via `build-sink`). When no sink is present, logging is a
   no-op and the interceptor is inert."
  []
  {:name  ::logging
   :enter (fn [ctx]
            (if-some [sink (:agent/log-sink ctx)]
              (assoc ctx
                     :chain/log?     true
                     :chain/on-stage (on-stage-fn sink)
                     :chain/log-sink sink)
              ctx))
   :leave (fn [ctx]
            (when-some [sink (:chain/log-sink ctx)]
              (try
                (-write sink {:ts             (System/currentTimeMillis)
                              :event          :exchange-summary
                              :session        (:exchange/session-id ctx)
                              :tool-runs      (count (:agent/all-tool-results ctx))
                              :loop-depth     (get ctx :agent/tool-loop-depth 0)
                              :response-bytes (count (or (:exchange/response ctx) ""))})
                (-close sink)
                (catch Throwable t
                  (binding [*out* *err*]
                    (println "lateralus logging close error:"
                             (ex-message t))))))
            (dissoc ctx :chain/log? :chain/on-stage :chain/log-sink))})

(defn- env-bool
  "Parse a boolean env var. Returns `default` when unset or unparseable."
  [^String name default]
  (let [v (some-> (System/getenv name) str/lower-case)]
    (cond
      (nil? v)  default
      (#{"false" "0" "no" "off"} v) false
      (#{"true" "1" "yes" "on"} v)  true
      :else     default)))

(defn build-sink
  "Build and open a `LogSink` for `session-id` from `opts` (the resolved
   `:lateralus/logging` config map) and env vars. Returns nil when
   logging is disabled or `session-id` is nil. The env var
   `LATERALUS_LOG_ENABLED` overrides config when set; `LATERALUS_LOG_DIR`
   overrides the configured `:dir` when set."
  [opts session-id]
  (let [{:keys [enabled dir sink]}
        (merge {:enabled true :dir default-log-dir :sink :file} opts)
        env-enabled (System/getenv "LATERALUS_LOG_ENABLED")
        enabled     (if env-enabled
                      (env-bool "LATERALUS_LOG_ENABLED" true)
                      (if (nil? enabled) true enabled))
        dir         (or (System/getenv "LATERALUS_LOG_DIR")
                        dir
                        default-log-dir)]
    (when (and enabled session-id)
      (let [s (case (or sink :file)
                :file   (->FileLogSink dir session-id (atom nil))
                :stdout (->StdoutSink)
                (->FileLogSink dir session-id (atom nil)))]
        (-open s)
        s))))