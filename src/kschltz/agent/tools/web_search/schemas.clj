(ns kschltz.agent.tools.web-search.schemas
  "Malli schemas for the web search tool, its providers, and its guards.

   The tool is exposed to the LLM, so every input and output is
   strictly validated. Guard schemas are reused by both the tool and
   the provider implementations so behavior is consistent.

   Important: the `web_search` input schema is intentionally kept simple
   (`:type string :minLength 1`) because local/quantized models are easily
   confused by JSON Schema `allOf` and empty validator objects.  The
   stricter checks (length, control characters, injection markers) are
   still enforced by the guard layer in `guards.clj`.")

(def max-query-length
  "Maximum characters the model can pass as a search query.
   DuckDuckGo Lite itself rejects queries >= 500 chars; we stay well
   below that to leave room for encoding and provider quirks."
  400)

(def default-result-count
  "Number of results returned to the model when not specified."
  5)

(def default-timeout-ms
  "Default HTTP timeout for searches and optional fetches."
  10000)

(def default-max-bytes
  "Default maximum response size for searches and fetches."
  (* 512 1024))

(def default-fetch-max-bytes
  "Default maximum page body size when fetch? is true."
  (* 256 1024))

(def QueryString
  "Sanitized search query. JSON schema is a plain string with min/max
   length so small local models interpret it correctly."
  [:string {:min 1 :max max-query-length
            :error/message "query must be a non-empty string of 1-400 characters"}])

(def UrlString
  "Any string that the guard layer will later validate as a safe URL.
   We keep the schema permissive here and enforce safety in code so
   error messages are actionable for the model."
  :string)

(def SearchInput
  "Input schema for the web_search tool."
  [:map
   [:query QueryString]
   [:fetch? {:optional true} :boolean]
   [:result-count {:optional true} :int]
   [:provider {:optional true} [:maybe :string]]])

(def SearchResult
  "Shape of one search result inside the tool output."
  [:map
   [:title :string]
   [:url UrlString]
   [:snippet :string]
   [:body {:optional true} [:maybe :string]]])

(def SearchOutput
  "Tool output: JSON string encoding a vector of SearchResult maps."
  :string)

(def WebSearchInput
  "Public alias for the tool input schema."
  SearchInput)

(def WebSearchOutput
  "Public alias for the tool output schema."
  SearchOutput)

(def ProviderOpts
  "Common provider options, consumed by the WebSearchProvider protocol."
  [:map
   [:timeout-ms {:optional true} :int]
   [:max-bytes {:optional true} :int]
   [:result-count {:optional true} :int]
   [:fetch-max-bytes {:optional true} :int]
   [:user-agent {:optional true} :string]])

(def DdgLiteConfig
  "DuckDuckGo Lite provider configuration."
  [:map
   [:provider [:= :ddg-lite]]
   [:timeout-ms {:optional true} :int]
   [:max-bytes {:optional true} :int]
   [:result-count {:optional true} :int]
   [:fetch-timeout-ms {:optional true} :int]
   [:fetch-max-bytes {:optional true} :int]
   [:user-agent {:optional true} :string]])

(def SearxngConfig
  "SearXNG provider configuration."
  [:map
   [:provider [:= :searxng]]
   [:base-url :string]
   [:timeout-ms {:optional true} :int]
   [:max-bytes {:optional true} :int]
   [:result-count {:optional true} :int]
   [:fetch-timeout-ms {:optional true} :int]
   [:fetch-max-bytes {:optional true} :int]
   [:categories {:optional true} [:maybe :string]]
   [:language {:optional true} [:maybe :string]]
   [:safesearch {:optional true} [:enum 0 1 2]]])

(def WebSearchToolConfig
  "Configuration for the web search tool and its guards."
  [:map
   [:enabled? {:optional true} :boolean]
   [:provider {:optional true} [:enum :ddg-lite :searxng]]
   [:provider-config {:optional true} [:or DdgLiteConfig SearxngConfig]]
   [:max-query-length {:optional true} :int]
   [:allow-urls {:optional true} [:maybe [:vector :string]]]
   [:block-urls {:optional true} [:maybe [:vector :string]]]
   [:block-injection-markers {:optional true} :boolean]
   [:block-private-ips? {:optional true} :boolean]
   [:block-metadata-endpoints? {:optional true} :boolean]
   [:strip-html? {:optional true} :boolean]
   [:policy-model? {:optional true} :boolean]
   [:policy-model-prompt {:optional true} [:maybe :string]]])
