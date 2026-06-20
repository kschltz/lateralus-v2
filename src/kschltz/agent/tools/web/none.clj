(ns kschltz.agent.tools.web.none
  "`:none` provider — the air-gapped default for the web tool suite.

   Implements `WebProvider` with zero network I/O. `web/search` and
   `web/fetch` raise `ex-info` with `:phase :disabled`; `web/extract`
   works via the shared zero-dep regex stripper in `guards.clj`, so
   the op is still useful for callers that already have HTML on hand.

   The factory ignores `:http-fn` (and everything else in the config
   map) — the only valid contract is `->NoneProvider`."
  (:require [clojure.string :as str]
            [kschltz.agent.tools.web.guards :as guards]
            [kschltz.agent.tools.web.protocol :as protocol]))

(def ^:private title-pattern #"(?is)<title[^>]*>(.*?)</title>")

(defn- extract-title
  "Pull the first `<title>...</title>` from `html`. Case-insensitive,
   multiline, non-greedy. Returns nil when no title is present."
  ^String [html]
  (when-let [m (re-find title-pattern (or html ""))]
    (let [raw (second m)]
      (when raw
        (-> raw
            (str/replace "&amp;" "&")
            (str/replace "&lt;" "<")
            (str/replace "&gt;" ">")
            (str/replace "&quot;" "\"")
            (str/replace "&#39;" "'")
            (str/replace "&nbsp;" " ")
            str/trim)))))

(defrecord NoneProvider []
  protocol/WebProvider
  (-search [_ _query _opts]
    (throw (ex-info "web search disabled"
                    {:phase :disabled
                     :provider :none})))
  (-fetch [_ _url _opts]
    (throw (ex-info "web fetch disabled"
                    {:phase :disabled
                     :provider :none})))
  (-extract [_ html opts]
    (let [max-bytes (or (:max-bytes opts) Integer/MAX_VALUE)
          stripped  (guards/strip-html (or html "") max-bytes)
          title     (extract-title html)]
      {:text          (:text stripped)
       :title         title
       :selectors-hit []
       :provider      :none}))
  (-capabilities [_]
    {:search?  false
     :fetch?   false
     :extract? true
     :live?    false}))

(defn provider
  "Factory for the `:none` provider.

   The config map is accepted for protocol symmetry with the live
   providers, but every key — including `:http-fn` — is ignored.
   The `:none` provider is the air-gapped default and performs no
   network I/O."
  [_config]
  (->NoneProvider))