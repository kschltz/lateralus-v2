(ns kschltz.agent.tools.web.protocol
  "WebProvider protocol for the lateralus web tool suite.

  The web tool surface (`web_search`, `web_fetch`, `web_extract`)
   dispatches through this protocol. Each provider implements four
   methods:

     - `-search`      — fetch a result set for a query
     - `-fetch`       — fetch a URL and return its body
     - `-extract`     — pull structured text out of a snippet of HTML
     - `-capabilities` — declarative map of what the provider supports

   `-capabilities` is the only method that **must never raise**: it
   is used by the tool record and the CLI summary to branch without
   try/except. The other three raise `ex-info` with `:phase` set to
   one of:

     - `:disabled`    — provider is configured off (`:none` default)
     - `:url-guard`   — URL failed validate-url
     - `:provider`    — provider implementation error (network, parse)
     - `:size-cap`    — response exceeded `:max-page-bytes`
     - `:timeout`     — request exceeded `:timeout-ms`

   The tool record in `web.clj` catches these and emits a JSON error
   envelope so the model sees a structured failure rather than an
   opaque exception."
  (:require [clojure.string :as str]))

(defprotocol WebProvider
  "Pluggable backend for the `web_*` tool operations.

   The first three methods raise `ex-info` on failure with a `:phase`
   keyword describing where the failure occurred. `-capabilities`
   never raises."
  (-search [provider query opts]
    "Run a search for `query` and return a map of the form
     `{:results [{:title s :url s :snippet s}] :provider kw}`.
     `opts` is the merged guard + provider config map (timeout-ms,
     max-bytes, result-count, user-agent). Raises `ex-info` with
     `:phase :provider` on network/parse failure or an empty result
     set, and `:phase :timeout` on timeout. The `:none` provider
     raises `:phase :disabled`.")
  (-fetch [provider url opts]
    "Fetch `url` and return `{:url s :title s :body s :bytes n :status n}`.
     `opts` is the merged guard + provider config map. Raises
     `ex-info` with `:phase :url-guard` on URL rejection,
     `:phase :size-cap` on body-size overflow, `:phase :timeout` on
     timeout, and `:phase :provider` on non-2xx or parse failure.
     The `:none` provider raises `:phase :disabled`.")
  (-extract [provider html opts]
    "Extract structured plain text from `html` and return
     `{:text s :title (s|nil) :selectors-hit [:string]}`. Pure
     transform — no network. Raises `ex-info` with `:phase :size-cap`
     on input size overflow and `:phase :provider` on parse failure.
     The `:none` provider uses a zero-dep regex path and does not
     raise on parseable HTML.")
  (-capabilities [provider]
    "Return a map of the form
     `{:search? bool :fetch? bool :extract? bool :live? bool}`.
     This method MUST NOT raise. The tool layer and CLI summary
     rely on a successful return to branch on provider support."))