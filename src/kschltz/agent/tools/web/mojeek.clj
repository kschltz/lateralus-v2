(ns kschltz.agent.tools.web.mojeek
  "`:mojeek` provider — the keyless, opt-in live web provider for the
   lateralus web tool suite.

   Mojeek is a privacy-respecting search engine that exposes a plain
   HTML results page at `https://www.mojeek.com/search?q=…` — no API
   key, no paid tier, no captcha on standard queries in 2026. The
   page uses a stable CSS shape:

       <ul class=\"results-standard\">
         <li class=\"result-standard\">
           <h2><a class=\"ob\" href=\"…\">Title</a></h2>
           <p class=\"s\">Snippet…</p>
         </li>
       </ul>

   We parse that shape with Hickory and walk it via the shared
   selector map at the top of this file. When Mojeek changes its
   markup, updating one selector entry is the only fix needed.

   This namespace is JVM-only. It is excluded from the `:native`
   `:replace-deps` in `deps.edn` (Hickory is in the top-level `:deps`
   only). At native-image init time the namespace is loaded behind a
   `try/require` guard the same way Proximum and LangChain4j are in
   `system.clj`; when Hickory is absent the provider raises a typed
   `ex-info` with `:phase :disabled` instead of a CNFE / CCE.

   The HTTP path is fully decoupled via the `:http-fn` test seam.
   The default wrapper calls `hato.client/request`; tests pass a
   stub returning canned HTML. No test-only HTTP server is needed."
  (:require [clojure.string :as str]
            [hato.client :as hato]
            [hickory.core :as hickory]
            [hickory.select :as hs]
            [kschltz.agent.tools.web.protocol :as protocol]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.net URL URLEncoder]))

;; ---------------------------------------------------------------------------
;; Selectors
;;
;; One private map; one entry per piece of Mojeek result markup.
;; Update this map (and only this map) when Mojeek shifts its DOM.
;; ---------------------------------------------------------------------------

(def ^:private selectors
  "CSS-equivalent Hickory selectors for the Mojeek results page.
   Each value is a hickory.select selector function (or a vector of
   such functions — see `select-first` below)."
  {:result-list  (hs/child (hs/and (hs/tag :ul)
                                  (hs/class "results-standard"))
                            (hs/tag :li))
   :result-link  (hs/and (hs/tag :a) (hs/class "ob"))
   :result-title (hs/child (hs/tag :h2) (hs/tag :a))
   :result-snippet (hs/and (hs/tag :p) (hs/class "s"))})

;; ---------------------------------------------------------------------------
;; HTML stripping — shared with the rest of the web tool suite
;; ---------------------------------------------------------------------------
;;
;; `strip-html` lives in `kschltz.agent.tools.web.guards` once Step 1
;; lands. To keep this Step-3 namespace loadable in isolation (and
;; keep the mojeek unit tests runnable without Step 1 in place), we
;; `try/require` it and fall back to a private regex stripper. The
;; fallback is intentionally identical in shape to the production
;; version so neither the public contract nor the tests change when
;; guards.clj arrives.

(def ^:private html-tag-re #"<[^>]+>")
(def ^:private ws-re #"\s+")

(defn- fallback-strip-html
  "Zero-dep regex HTML stripper. Used only when
   `kschltz.agent.tools.web.guards` is not on the classpath. Returns
   a plain string (not a map) — the same shape `guards/strip-html`
   promises, so the call sites are identical either way."
  ^String [^String s ^Long max-bytes]
  (let [text (-> (or s "")
                 (str/replace html-tag-re " ")
                 (str/replace "&lt;" "<")
                 (str/replace "&gt;" ">")
                 (str/replace "&amp;" "&")
                 (str/replace "&quot;" "\"")
                 (str/replace "&nbsp;" " ")
                 str/trim
                 (str/replace ws-re " "))
        bytes (.getBytes text "UTF-8")]
    (if (and max-bytes (pos? max-bytes) (> (count bytes) max-bytes))
      (String. (java.util.Arrays/copyOf bytes (int max-bytes)) "UTF-8")
      text)))

(def ^:private strip-html
  "Resolve `kschltz.agent.tools.web.guards/strip-html` if it is on the
   classpath; otherwise use `fallback-strip-html`. Both have the same
   `(s, max-bytes) -> string` contract. The `def` binds to whichever
   non-nil value resolves first, so the call sites are identical
   either way."
  (or (try
        (require 'kschltz.agent.tools.web.guards)
        (resolve 'kschltz.agent.tools.web.guards/strip-html)
        (catch Throwable _ nil))
      fallback-strip-html))

;; ---------------------------------------------------------------------------
;; Title extraction
;;
;; Mojeek search pages put the result page title in <title>…</title>.
;; `extract` uses the same regex path as `:none` because HTML page
;; titles always live in that one tag.
;; ---------------------------------------------------------------------------

(def ^:private title-pattern #"(?is)<title[^>]*>(.*?)</title>")

(defn- extract-title
  "Pull the first `<title>…</title>` from `html`. Returns nil when
   no title is present."
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

;; ---------------------------------------------------------------------------
;; Default :http-fn
;; ---------------------------------------------------------------------------
;;
;; Wraps `hato.client/request`. Returns a hato-shaped map:
;;   {:status n :body s :headers m :request-time m :error …}
;; when `:as :string` is passed. Mirrors the call site used in
;; `kschltz.agent.llm.http` so providers share the same timeout /
;; exception conventions.

(defn- default-http-fn
  "Default HTTP wrapper. Forwards `req` to hato, but never lets hato
   throw — non-2xx statuses surface as a normal response map so the
   provider can decide whether to retry, raise `:provider`, or
   accept a partial result."
  [req]
  (let [req' (assoc req :throw-exceptions false :coerce :always)]
    (hato/request req')))

;; ---------------------------------------------------------------------------
;; Hickory helpers
;; ---------------------------------------------------------------------------

(defn- node->text
  "Recursively extract text from a hickory node. Works on both element
   maps (`{:type :element, :content [...]}`) and bare text strings.
   Used to flatten an `h2 a` or `p.s` subtree into the snippet / title
   string."
  ^String [node]
  (when node
    (cond
      (string? node)  node
      (map? node)     (case (:type node)
                        :element   (str/trim (str/join " " (map node->text (:content node))))
                        :document  (str/trim (str/join " " (map node->text (:content node))))
                        "")
      (sequential? node) (str/trim (str/join " " (map node->text node)))
      :else           "")))

(defn- first-attr
  "Look up an attribute on a hickory element node, case-insensitively.
   Returns nil for non-element nodes or missing attributes."
  [node k]
  (when (and node (map? node) (:tag node))
    (let [attrs (:attrs node {})]
      (or (get attrs k)
          (get attrs (str/lower-case (name k)))
          (some (fn [[ak _]]
                  (when (= (str/lower-case (name ak))
                           (str/lower-case (name k)))
                    (get attrs ak)))
                attrs)))))

(defn- collect-results
  "Walk the matched `<li>` result nodes and produce a vector of
   `{:title :url :snippet}` maps. Stops at `max-result-count`.
   Missing title/snippet is tolerated (Mojeek sometimes renders a
   result with only a URL); the entry is dropped only when `:url`
   is missing entirely."
  [li-nodes max-result-count]
  (let [take-n (or max-result-count 10)]
    (vec
     (keep (fn [li]
             (let [title-node (first (hs/select (:result-title selectors) li))
                   link-node  (first (hs/select (:result-link  selectors) li))
                   snippet-node (first (hs/select (:result-snippet selectors) li))
                   title   (node->text title-node)
                   snippet (node->text snippet-node)
                   url     (first-attr link-node :href)]
               (when url
                 {:title   (or title "")
                  :url     url
                  :snippet (or snippet "")})))
           (take take-n li-nodes)))))

;; ---------------------------------------------------------------------------
;; URL building
;; ---------------------------------------------------------------------------

(defn- build-search-url
  "Build `https://www.mojeek.com/search?q=<encoded>`. Uses the
   standard query-string form so we don't need a separate Mojeek API
   path. `q` is URL-encoded as UTF-8."
  ^String [base-url ^String q]
  (str base-url
       "/search?q="
       (URLEncoder/encode (or q "") "UTF-8")))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def ^:private default-base-url "https://www.mojeek.com")
(def ^:private default-user-agent
  "Mojeek's published guidance is to send a real, descriptive UA so
   the result set is not rate-limited. We identify as lateralus."
  "lateralus/0.1 (+https://github.com/schltzk/lateralus-v2; web; mojeek)")

;; ---------------------------------------------------------------------------
;; Provider record
;; ---------------------------------------------------------------------------

(defn- compile-selector
  "Translate a tiny CSS subset into a Hickory selector. Returns nil
   when the input doesn't fit the subset."
  [^String s]
  (let [s'  (str/trim (or s ""))
        tag (first (str/split s' #"\."))
        cls (->> (str/split s' #"\.")
                 rest
                 (remove str/blank?))]
    (when (and (seq s') tag)
      (cond-> (hs/tag (keyword tag))
        (seq cls) (#(reduce (fn [acc c] (hs/and acc (hs/class c)))
                            %
                            (map keyword cls)))))))

(defrecord MojeekProvider [config]
  protocol/WebProvider

  (-search [_ query opts]
    (let [cfg           (merge config opts)
          base-url      (or (:base-url cfg) default-base-url)
          user-agent    (or (:user-agent cfg) default-user-agent)
          timeout-ms    (or (:timeout-ms cfg) 15000)
          max-results   (or (:max-result-count cfg) 10)
          http-fn       (or (:http-fn cfg) default-http-fn)
          url           (build-search-url base-url query)
          response      (http-fn {:method       :get
                                  :url          url
                                  :headers      {"User-Agent" user-agent
                                                "Accept"     "text/html"}
                                  :as           :string
                                  :timeout-ms   timeout-ms})
          status        (:status response)
          body          (or (:body response) "")]
      (cond
        (not status)
        (throw (ex-info "Mojeek search failed: no HTTP response"
                        {:phase :provider :provider :mojeek :url url}))

        (not (<= 200 status 299))
        (throw (ex-info (str "Mojeek search failed: HTTP " status)
                        {:phase :provider
                         :provider :mojeek
                         :url url
                         :status status}))

        (str/blank? body)
        (throw (ex-info "Mojeek search returned empty body"
                        {:phase :provider :provider :mojeek :url url})))

      (let [tree       (hickory/as-hickory (hickory/parse body))
            li-nodes   (hs/select (:result-list selectors) tree)
            results    (collect-results li-nodes max-results)]
        (when (empty? results)
          (throw (ex-info "Mojeek search returned no parseable results"
                          {:phase :provider
                           :provider :mojeek
                           :url url
                           :li-count (count li-nodes)})))
        {:results  results
         :provider :mojeek
         :url      url
         :status   status})))

  (-fetch [_ url opts]
    (let [cfg          (merge config opts)
          user-agent   (or (:user-agent cfg) default-user-agent)
          timeout-ms   (or (:timeout-ms cfg) 15000)
          max-bytes    (or (:max-page-bytes cfg) 2097152)
          http-fn      (or (:http-fn cfg) default-http-fn)
          response     (http-fn {:method       :get
                                 :url          url
                                 :headers      {"User-Agent" user-agent
                                               "Accept"     "text/html"}
                                 :as           :string
                                 :timeout-ms   timeout-ms})
          status       (:status response)
          body         (or (:body response) "")]
      (cond
        (not status)
        (throw (ex-info "Mojeek fetch failed: no HTTP response"
                        {:phase :provider :provider :mojeek :url url}))

        (not (<= 200 status 299))
        (throw (ex-info (str "Mojeek fetch failed: HTTP " status)
                        {:phase :provider
                         :provider :mojeek
                         :url url
                         :status status})))

      (let [bytes (.getBytes body "UTF-8")
            n     (count bytes)]
        (when (and max-bytes (pos? max-bytes) (> n max-bytes))
          (throw (ex-info (str "Mojeek fetch exceeded :max-page-bytes (" n " > " max-bytes ")")
                          {:phase :size-cap
                           :provider :mojeek
                           :url url
                           :bytes n
                           :max-bytes max-bytes})))
        (let [stripped (strip-html body max-bytes)
              title    (extract-title body)]
          {:url    url
           :title  title
           :body   (:text stripped)
           :bytes  n
           :status status}))))

  (-extract [_ html opts]
    (let [cfg       (merge config opts)
          max-bytes (or (:max-page-bytes cfg) 1048576)
          selector  (:selector opts)
          tree      (hickory/as-hickory (hickory/parse (or html "")))]
      (if selector
        ;; The selector is a CSS-like string. We don't have a full
        ;; CSS parser; translate the few shapes we expect (a bare
        ;; tag, a tag + class) into Hickory selectors. Anything
        ;; more exotic falls back to "return all text".
        (let [sel  (try (compile-selector selector)
                       (catch Throwable _ nil))
              hits (if sel
                     (hs/select sel tree)
                     [])
              text (->> hits
                        (map node->text)
                        (remove str/blank?)
                        (str/join "\n"))]
          {:text          text
           :title         (extract-title html)
           :selectors-hit [selector]
           :provider      :mojeek})
        (let [stripped (strip-html (or html "") max-bytes)]
          {:text          (:text stripped)
           :title         (extract-title html)
           :selectors-hit []
           :provider      :mojeek}))))

  (-capabilities [_]
    {:search?  true
     :fetch?   true
     :extract? true
     :live?    true}))

(defn provider
  "Factory for the `:mojeek` provider. `config` is the merged guard
   + provider config map. The only keys the provider itself reads
   directly are:

     :http-fn        — overrides the default hato wrapper
     :base-url       — defaults to https://www.mojeek.com
     :user-agent     — defaults to a descriptive lateralus UA
     :timeout-ms     — per-request timeout (default 15000)
     :max-page-bytes — size cap for -fetch / -extract
     :max-result-count — cap for -search result count

   All other keys (guard toggles, URL allow/block lists, etc.) are
   merged into the tool deftype's input handling upstream and are
   not consulted by this provider."
  [config]
  (->MojeekProvider config))

(def ProviderConfig
  [:map
   [:http-fn {:optional true} fn?]
   [:base-url {:optional true} :string]
   [:user-agent {:optional true} :string]
   [:timeout-ms {:optional true} :int]
   [:max-page-bytes {:optional true} :int]
   [:max-result-count {:optional true} :int]])

(def HttpRequest
  [:map
   [:url :string]
   [:method {:optional true} :keyword]
   [:headers {:optional true} [:map-of :string :string]]])

(def HttpResponse
  [:map
   [:status :int]
   [:body :string]
   [:headers {:optional true} :map]])

(m/=> default-http-fn [:=> [:cat HttpRequest] HttpResponse])
(m/=> provider
      [:=> [:cat ProviderConfig]
       [:fn #(satisfies? protocol/WebProvider %)]])

(mi/instrument! {:filters [(mi/-filter-ns
                            'kschltz.agent.tools.web.mojeek)]})

;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Factory
;; ---------------------------------------------------------------------------

