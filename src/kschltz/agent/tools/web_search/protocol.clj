(ns kschltz.agent.tools.web-search.protocol
  "Web search provider protocol.

   Network calls are isolated behind this protocol so the tool only
   depends on the interface. Implementations live in provider-specific
   namespaces and use hato for HTTP. Both input and output shapes are
   Malli-schemas in kschltz.agent.tools.web-search.schemas.")

(defprotocol WebSearchProvider
  "Search the public web and optionally fetch a result page."
  (-search [provider query opts]
    "Execute a web search for `query`. Returns a map with `:results`
     (a vector of search result maps) and `:provider` metadata.
     Throws on network or parsing errors.

     `opts` is provider-specific and typically contains `:timeout-ms`,
     `:max-bytes`, `:result-count`, and provider config.

     Result maps contain at minimum:
       :title   - string
       :url     - string, already validated against the safe-url guard
       :snippet - plain-text string")
  (-fetch-page [provider url opts]
    "Fetch and strip the page at `url` to plain text. Returns a map
     with `:url`, `:title`, and `:body`. Throws on network, size,
     timeout, or guard errors.

     `opts` contains `:timeout-ms`, `:max-bytes`, and guard config."))
