(ns kschltz.agent.tools.web-search
  "Web search tool for the lateralus agent loop.

   Exposes `web_search` to the LLM. The tool delegates network calls to
   the WebSearchProvider protocol. The default provider is DuckDuckGo
   Lite (no API key); SearXNG is available as an opt-in, self-hosted
   alternative.

   Inputs and outputs are Malli-validated. Provider results pass through
   URL, query, HTML, exfiltration, and recursive self-activation guards.
   An optional policy model can classify snippets via the configured
   LlmClient."
  (:require [cheshire.core :as json]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.web-search.ddg-lite :as ddg]
            [kschltz.agent.tools.web-search.guards :as guards]
            [kschltz.agent.tools.web-search.policy :as policy]
            [kschltz.agent.tools.web-search.protocol :as protocol]
            [kschltz.agent.tools.web-search.schemas :as schemas]
            [kschltz.agent.tools.web-search.searxng :as searxng]))

(def ^:private InputSchema
  schemas/WebSearchInput)

(def ^:private OutputSchema
  schemas/WebSearchOutput)

(defn- select-provider
  "Return a WebSearchProvider instance for the given config.
   If `provider` is already a protocol instance, pass it through."
  [config]
  (let [p (:provider config :ddg-lite)]
    (cond
      (satisfies? protocol/WebSearchProvider p) p
      (= :ddg-lite p) (ddg/provider config)
      (= :searxng p)  (searxng/provider config)
      :else (throw (ex-info (format "Unknown web search provider: %s" p)
                            {:provider p})))))

(defn- policy-client
  "Return an LlmClient for the policy model only when enabled."
  [config ctx]
  (when (:policy-model? config false)
    (or (:llm/client ctx)
        (llm-client/stub-client))))

(defn- invoke-search
  "Run the search through `provider` and return a JSON-serializable map."
  [args ctx config]
  (let [config      (merge guards/default-guard-config config)
        query-check (guards/sanitize-query (:query args) config)]
    (when (:error query-check)
      (throw (ex-info (:error query-check) {:query (:query args) :phase :query-guard})))
    (let [provider (select-provider config)
          opts     (merge config
                          {:query        (:ok query-check)
                           :fetch?       (boolean (:fetch? args))
                           :result-count (min 20 (max 1 (or (:result-count args) 10)))})
          search-result (protocol/-search provider (:ok query-check) opts)
          guarded       (guards/guard-results (:results search-result) opts)
          with-policy   (policy/apply-policy (policy-client config ctx) guarded)
          with-bodies   (if (:fetch? args)
                          (mapv (fn [r]
                                  (if-let [url (:url r)]
                                    (try
                                      (let [fetched (protocol/-fetch-page provider url opts)]
                                        (assoc r :body (:body fetched)))
                                      (catch Throwable t
                                        (assoc r :body (format "[fetch failed: %s]" (ex-message t)))))
                                    r))
                                with-policy)
                          with-policy)
          payload       (-> search-result
                           (dissoc :opts)
                           (assoc :results with-bodies))]
      (json/generate-string payload {:pretty true}))))

(deftype WebSearchTool [config]
  tool/Tool
  (-name [_] "web_search")
  (-description [_]
    (str "Search the public web.\n"
         "Arguments:\n"
         "  query        - search string (required), e.g. \"ducks\"\n"
         "  fetch?       - if true, also fetch plain-text page bodies (default false)\n"
         "  result-count - maximum number of results (default 10, max 20)\n"
         "Returns a JSON string with :provider, :query, and :results.\n"
         "Example call: {\"query\":\"ducks\",\"result-count\":5}"))
  (-input-schema [_] InputSchema)
  (-output-schema [_] OutputSchema)
  (-invoke [_ args ctx]
    (try
      (invoke-search args ctx config)
      (catch Throwable t
        (json/generate-string {:error (ex-message t)
                               :detail (ex-data t)}
                              {:pretty true})))))

(defn web-search-registry
  "Return a tool registry containing the `web_search` tool.
   `config` is the merged guard/provider/policy configuration map."
  [config]
  {"web_search" (->WebSearchTool config)})
