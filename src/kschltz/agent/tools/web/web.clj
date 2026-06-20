(ns kschltz.agent.tools.web.web
  "Tool records for the lateralus web tool suite.

   Three `Tool` implementations back the registry that the agent loop
   dispatches to:

     - `WebSearchTool`  — `web/search`
     - `WebFetchTool`   — `web/fetch`
     - `WebExtractTool` — `web/extract`

   Each tool holds a merged `config` map and dispatches through the
   `WebProvider` protocol to whatever provider was wired in by the
   factory (`:none`, `:mojeek`, `:searxng`, or a test stub). All
   guard pipeline calls happen *inside* the tool so the model-visible
   error shape is consistent across providers.

   Every `-invoke` is wrapped in a `try/catch` that emits a JSON
   envelope on failure:

       {\"error\"     <ex-message>
        \"phase\"     <ex-data :phase>
        \"provider\"  <provider name>}

   The model can pattern-match on the JSON keys regardless of which
   provider failed.

   The `web-registry` factory returns a 3-key map; the keys are the
   strings an LLM will use in a function-call request. The same
   strings are returned by `(-name _)` on each tool."
  (:require [cheshire.core :as json]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.web.guards :as guards]
            [kschltz.agent.tools.web.protocol :as protocol]
            [kschltz.agent.tools.web.schemas :as schemas]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- provider-name
  "Return the configured provider name (keyword) or `:unknown` when
   the config does not specify one. Used in the JSON error envelope
   so the model can tell which backend failed."
  [config]
  (or (:provider-name config)
      (let [p (:provider config)]
        (cond
          (keyword? p) p
          (satisfies? protocol/WebProvider p) (:provider-name (meta (class p)))
          :else :unknown))))

(defn- effective-config
  "Merge `default-guard-config` into the tool `config` so every guard
   has its toggle even when the operator did not set it explicitly.
   Returned by value — callers may mutate it."
  [config]
  (guards/default-guard-config))

(defn- envelope
  "Build a JSON error envelope from a Throwable. `phase` is the
   model's switch on what went wrong; `provider` is the backend
   name. Returns a JSON string as required by the `Tool` protocol."
  [t phase provider-name]
  (let [data (ex-data t)
        phase-kw (cond
                   (keyword? phase) phase
                   (keyword? (:phase data)) (:phase data)
                   :else (some-> phase name keyword))]
    (json/generate-string
     (cond-> {:error (ex-message t)
              :phase (if (keyword? phase-kw) (name phase-kw) (str phase-kw))
              :provider provider-name}
       (some? (:reason data)) (assoc :reason (:reason data)))
     {:pretty true})))

(defn- guard-error-envelope
  "Build a JSON error envelope for a guard rejection. `phase` is
   a keyword like `:query-guard` or `:url-guard`. Returns a JSON
   string."
  [reason phase provider-name]
  (json/generate-string
   {:error    reason
    :phase    (if (keyword? phase) (name phase) (str phase))
    :reason   reason
    :provider provider-name}
   {:pretty true}))

;; ---------------------------------------------------------------------------
;; WebSearchTool
;; ---------------------------------------------------------------------------

(deftype WebSearchTool [config]
  tool/Tool
  (-name [_]
    "web/search")
  (-description [_]
    "Search the public web. Returns a JSON envelope. Default provider (:none) does no network I/O; configure :mojeek for live results. Arguments: query (string, required), result-count (int, default 5, max 20).")
  (-input-schema [_] schemas/WebSearchInput)
  (-output-schema [_] schemas/WebSearchOutput)
  (-invoke [_ args _ctx]
    (let [provider      (:provider config)
          cfg           (merge (effective-config config) config)
          provider-name (provider-name config)
          query-check   (guards/sanitize-query (:query args) cfg)]
      (try
        (if (:error query-check)
          (guard-error-envelope (:error query-check) :query-guard provider-name)
          (let [cleaned   (:ok query-check)
                result-count (min (:max-result-count cfg)
                                  (max 1 (or (:result-count args) 5)))
                opts       (assoc cfg :result-count result-count)
                raw        (protocol/-search provider cleaned opts)
                guarded    (guards/guard-results (:results raw) cfg)]
            (json/generate-string
             {:provider (or (:provider raw) provider-name)
              :query    cleaned
              :results  guarded}
             {:pretty true})))
        (catch Throwable t
          (envelope t nil provider-name))))))

;; ---------------------------------------------------------------------------
;; WebFetchTool
;; ---------------------------------------------------------------------------

(deftype WebFetchTool [config]
  tool/Tool
  (-name [_]
    "web/fetch")
  (-description [_]
    "Fetch a URL and return the body as plain text. Default provider (:none) returns a disabled envelope. Configure :mojeek to enable. Arguments: url (string, required), max-bytes (int, optional override).")
  (-input-schema [_] schemas/WebFetchInput)
  (-output-schema [_] schemas/WebFetchOutput)
  (-invoke [_ args _ctx]
    (let [provider      (:provider config)
          cfg           (merge (effective-config config) config)
          provider-name (provider-name config)
          url-check     (guards/validate-url (:url args) cfg)]
      (try
        (if-not (:allow? url-check)
          (guard-error-envelope (:reason url-check) :url-guard provider-name)
          (let [allowed-url (:url url-check)
                override    (:max-bytes args)
                opts        (cond-> cfg
                             (some? override) (assoc :max-bytes override))
                raw         (protocol/-fetch provider allowed-url opts)]
            (json/generate-string
             {:url    (:url raw)
              :title  (:title raw)
              :body   (:body raw)
              :bytes  (:bytes raw)
              :status (:status raw)}
             {:pretty true})))
        (catch Throwable t
          (envelope t nil provider-name))))))

;; ---------------------------------------------------------------------------
;; WebExtractTool
;; ---------------------------------------------------------------------------

(deftype WebExtractTool [config]
  tool/Tool
  (-name [_]
    "web/extract")
  (-description [_]
    "Extract structured text from a snippet of HTML. No network I/O. Arguments: html (string, required), selector (string, optional).")
  (-input-schema [_] schemas/WebExtractInput)
  (-output-schema [_] schemas/WebExtractOutput)
  (-invoke [_ args _ctx]
    (let [provider      (:provider config)
          cfg           (merge (effective-config config) config)
          provider-name (provider-name config)
          opts          (cond-> cfg
                         (some? (:selector args)) (assoc :selector (:selector args)))]
      (try
        (let [raw (protocol/-extract provider (:html args) opts)]
          (json/generate-string
           (cond-> {:text           (:text raw)
                    :title          (:title raw)
                    :selectors-hit  (or (:selectors-hit raw) [])
                    :provider       (or (:provider raw) provider-name)}
             (some? (:selector args)) (assoc :selector (:selector args)))
           {:pretty true}))
        (catch Throwable t
          (envelope t nil provider-name))))))

;; ---------------------------------------------------------------------------
;; Registry factory
;; ---------------------------------------------------------------------------

(defn web-registry
  "Build the 3-tool registry for the web tool suite.

   `config` is a map with at least:
     :provider       — a `WebProvider` instance (or a `:none` /
                       `:mojeek` / `:searxng` keyword if a wiring layer
                       has not yet resolved it).
     :provider-name  — keyword naming the backend; included in JSON
                       error envelopes so the model can tell which
                       backend failed. Optional; falls back to the
                       config `:provider` keyword or `:unknown`.

   Any additional keys are passed through to the guard config and to
   each tool's `WebProvider` method via the merged `opts` map.

   Returns:
     {\"web/search\"  WebSearchTool
      \"web/fetch\"   WebFetchTool
      \"web/extract\" WebExtractTool}"
  [config]
  {"web/search"  (->WebSearchTool config)
   "web/fetch"   (->WebFetchTool config)
   "web/extract" (->WebExtractTool config)})