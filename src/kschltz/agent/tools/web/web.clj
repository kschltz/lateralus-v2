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
            [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.web.guards :as guards]
            [kschltz.agent.tools.web.none :as none]
            [kschltz.agent.tools.web.protocol :as protocol]
            [kschltz.agent.tools.web.schemas :as schemas]
            [kschltz.agent.tools.web.ssrf :as ssrf]))

;; Phase 3 duplicate-query circuit breaker: a per-process atom holding the
;; last normalized search query. If the model calls web/search with the
;; exact same query twice in a row, the second call short-circuits to a
;; :duplicate-query envelope instead of re-hitting the network — preventing
;; agent loops. lateralus-v2 is single-user/single-agent MVP, so one
;; web/search tool per process is the norm.
(def ^:private last-search-query (atom nil))

;; `:mojeek` is JVM-only (it depends on hickory, which the native-image
;; build excludes from the classpath). Load it lazily so this namespace
;; compiles on both JVM and native; resolving `:provider :mojeek` on native
;; raises a typed ex-info instead of ClassNotFoundException.
(defonce ^:private mojeek-load-error
  (try (require 'kschltz.agent.tools.web.mojeek) nil
       (catch Throwable t t)))

;; `:ddg` is also JVM-only — it depends on impersonator-okhttp (browser
;; TLS/HTTP2 fingerprinting via BouncyCastle-bctls) plus hickory. Same
;; lazy-load guard as :mojeek; resolving `:provider :ddg` on native raises a
;; typed ex-info.
(defonce ^:private ddg-load-error
  (try (require 'kschltz.agent.tools.web.ddg) nil
       (catch Throwable t t)))

(defn- mojeek-provider
  "Return the resolved `kschltz.agent.tools.web.mojeek/provider` factory,
   or throw a clear ex-info if the namespace is unavailable (native-image)."
  [config]
  (if-let [f (resolve 'kschltz.agent.tools.web.mojeek/provider)]
    (f config)
    (throw (ex-info "web provider :mojeek is JVM-only and is not available — hickory is excluded from the native-image classpath"
                    {:phase :provider :provider :mojeek}))))

(defn- ddg-provider
  "Return the resolved `kschltz.agent.tools.web.ddg/provider` factory,
   or throw a clear ex-info if the namespace is unavailable (native-image
   excludes impersonator + hickory)."
  [config]
  (if-let [f (resolve 'kschltz.agent.tools.web.ddg/provider)]
    (f config)
    (throw (ex-info "web provider :ddg is JVM-only and is not available — impersonator/hickory are excluded from the native-image classpath"
                    {:phase :provider :provider :ddg}))))

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
  "Return shared guard defaults. Callers merge operator `config` on top
   (`(merge (effective-config config) config)`). Provider-specific keys
   like `:base-url` / `:user-agent` must NOT live in the shared defaults."
  [_config]
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
    "Search the public web. Returns a JSON envelope with :results [{title,url,snippet}]. Default provider (:none) does no network I/O; configure :ddg (recommended) or :mojeek for live results. Arguments: query (string, required), result-count (int, default 5, max 20). On success, follow interesting URLs with web/fetch.")
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
          (let [cleaned    (:ok query-check)
                normalized (str/lower-case (str/trim cleaned))]
            ;; Phase 3 duplicate-query circuit breaker
            (if (and (:block-duplicate-query? cfg) (= normalized @last-search-query))
              (guard-error-envelope "duplicate query (circuit breaker engaged) — refine the query or use web/fetch"
                                    :duplicate-query provider-name)
              (do
                (reset! last-search-query normalized)
                (let [result-count (min (:max-result-count cfg)
                                        (max 1 (or (:result-count args) 5)))
                      opts       (assoc cfg :result-count result-count)
                      raw        (protocol/-search provider cleaned opts)
                      guarded    (guards/guard-results (:results raw) cfg)]
                  (json/generate-string
                   (cond-> {:provider (or (:provider raw) provider-name)
                            :query    cleaned
                            :results  guarded}
                     ;; Phase 3 snippet-truncation hint: nudge the model
                     ;; toward web/fetch for full content.
                     (seq guarded) (assoc :note (ssrf/snippet-truncation-hint)))
                   {:pretty true}))))))
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
    "Fetch a URL and return the body as plain text. Default provider (:none) returns a disabled envelope. Configure :ddg or :mojeek to enable. Arguments: url (string, required), max-bytes (int, optional override).")
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

(defn- resolve-provider
  "Turn the `:provider` entry of `config` into a `WebProvider` instance.
   Accepts either a keyword (`:none` / `:mojeek` / `:searxng`) or an
   already-resolved `WebProvider` record (the test seam passes records
   directly). Returns `config` with `:provider` set to the instance and
   `:provider-name` set to the backend keyword for JSON error envelopes."
  [config]
  (let [p (:provider config)]
    (cond
      (satisfies? protocol/WebProvider p)
      config

      (= :none p)
      (assoc config :provider (none/provider config) :provider-name :none)

      (= :mojeek p)
      (assoc config :provider (mojeek-provider config) :provider-name :mojeek)

      (= :ddg p)
      (assoc config :provider (ddg-provider config) :provider-name :ddg)

      :else
      (throw (ex-info (str "Unknown web provider: " (pr-str p))
                      {:phase :provider :provider p})))))


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
  (let [cfg (resolve-provider config)]
    {"web/search"  (->WebSearchTool cfg)
     "web/fetch"   (->WebFetchTool cfg)
     "web/extract" (->WebExtractTool cfg)}))