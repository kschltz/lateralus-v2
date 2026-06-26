(ns kschltz.agent.tools.web.ddg
  "`:ddg` provider — keyless live web search via DuckDuckGo's HTML endpoint,
   reached from the JVM with a browser TLS/HTTP2 fingerprint.

   Why this provider exists: lateralus-v2's original `web_search` tool hit
   DuckDuckGo with the JDK's default TLS client and got a degraded/CAPTCHA
   page (see `ddg-captcha-blocks-scraping`). The `ddgs` Python library
   (used by Unsloth Studio) evades that via `primp`'s Rust TLS-fingerprint
   impersonation. This provider is the JVM equivalent: it uses
   `zhkl0228/impersonator-okhttp` (a BouncyCastle-bctls + forked-OkHttp
   library) to present a browser JA3/JA4 + HTTP/2 fingerprint, so
   `html.duckduckgo.com/html` returns real result HTML instead of a
   challenge page.

   CRITICAL implementation note (verified in a REPL probe, see
   `impersonator-jvm-ddg-android-preset-only-2026`): only the `android`
   preset completes the TLS handshake against DDG with this bctls
   version — `macChrome`/`ios`/`macSafari`/`macFirefox` all throw
   `illegal_parameter / invalid key_share selected` during the
   HelloRetryRequest. So the default preset chain is `[android]`; an
   operator can override via `:impersonate` in config.

   This namespace is JVM-only. `impersonator-okhttp` is in the top-level
   `:deps` of `deps.edn` only (never `:native :replace-deps`); like
   `:mojeek`, this namespace is loaded behind a guarded `try/require` in
   `web.clj` and `system.clj` so native-image builds stay clean and
   `:provider :ddg` raises a typed ex-info on native instead of a CNFE.

   The HTTP path is fully decoupled via the `:http-fn` test seam
   (same hato-shaped `{:status :body :headers}` contract as `:mojeek`),
   so unit tests pass a stub returning canned DDG HTML — no live
   network, no impersonator dep needed in tests."
  (:require [clojure.string :as str]
            [hato.client :as hato]
            [hickory.core :as hickory]
            [hickory.select :as hs]
            [kschltz.agent.tools.web.guards :as guards]
            [kschltz.agent.tools.web.ssrf :as ssrf]
            [kschltz.agent.tools.web.protocol :as protocol])
  (:import [java.net URL URLEncoder URLDecoder URI]
           [com.github.zhkl0228.impersonator ImpersonatorFactory]
           [okhttp3 OkHttpClientFactory Request Request$Builder]))

;; ---------------------------------------------------------------------------
;; Selectors for DuckDuckGo's html.duckduckgo.com/html results page.
;; DDG renders results as:
;;   <div class="result results_links results_links_deep web-result">
;;     <h2 class="result__title"><a class="result__a" href="...">Title</a></h2>
;;     <a class="result__url" href="...">display url</a>
;;     <a class="result__snippet" href="...">snippet text</a>
;;   </div>
;; The title link href is a DDG redirect (`//duckduckgo.com/l/?uddg=<enc>`)
;; OR a direct URL; we decode `uddg=` to recover the real destination.
;; ---------------------------------------------------------------------------

(def ^:private result-block
  (hs/and (hs/tag :div) (hs/class "result")))

(def ^:private result-link
  (hs/and (hs/tag :a) (hs/class "result__a")))

(def ^:private result-snippet
  (hs/and (hs/tag :a) (hs/class "result__snippet")))

;; ---------------------------------------------------------------------------
;; HTML stripping — shared with the rest of the web tool suite.
;; `try/require` guards.clj; fall back to a private regex stripper so
;; this namespace loads even before guards.clj is on the classpath.
;; ---------------------------------------------------------------------------

(def ^:private html-tag-re #"<[^>]+>")
(def ^:private ws-re #"\s+")

(defn- fallback-strip-html
  ^String [^String s ^Long max-bytes]
  (let [text (-> (or s "")
                 (str/replace #"(?is)<script[^>]*>.*?</script>" " ")
                 (str/replace #"(?is)<style[^>]*>.*?</style>" " ")
                 (str/replace html-tag-re " ")
                 (str/replace "&lt;" "<")
                 (str/replace "&gt;" ">")
                 (str/replace "&amp;" "&")
                 (str/replace "&quot;" "\"")
                 (str/replace "&#39;" "'")
                 (str/replace "&nbsp;" " ")
                 str/trim
                 (str/replace ws-re " "))
        bytes (.getBytes text "UTF-8")]
    (if (and max-bytes (pos? max-bytes) (> (count bytes) max-bytes))
      (String. (java.util.Arrays/copyOf bytes (int max-bytes)) "UTF-8")
      text)))

(def ^:private strip-html
  (or (try
        (require 'kschltz.agent.tools.web.guards)
        (resolve 'kschltz.agent.tools.web.guards/strip-html)
        (catch Throwable _ nil))
      fallback-strip-html))

(def ^:private title-pattern #"(?is)<title[^>]*>(.*?)</title>")

(defn- extract-title
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
;; URL helpers
;; ---------------------------------------------------------------------------

(defn- build-search-url
  "Build `https://html.duckduckgo.com/html/?q=<encoded>`. DDG's HTML
   endpoint is keyless and requires no API key."
  ^String [base-url ^String q]
  (str (or base-url "https://html.duckduckgo.com")
       "/html/?q=" (URLEncoder/encode (or q "") "UTF-8")))

(defn- decode-uddg
  "DDG html result hrefs are redirect-styled:
   `//duckduckgo.com/l/?uddg=<urlencoded real url>&rut=...`. Extract and
   URL-decode the `uddg` value. Returns the original href unchanged when
   it is not a DDG redirect (some results link directly)."
  ^String [^String href]
  (let [h (or href "")]
    (if (str/includes? h "uddg=")
      (try
        (let [uri  (URI. (str/replace h #"^//" "https://"))
              q    (.getQuery uri)]
          (some (fn [kv]
                  (let [[k v] (str/split kv #"=" 2)]
                    (when (= k "uddg")
                      (URLDecoder/decode v "UTF-8"))))
                (str/split (or q "") #"&")))
        (catch Throwable _ h))
      h)))

;; ---------------------------------------------------------------------------
;; Hickory helpers
;; ---------------------------------------------------------------------------

(defn- node->text
  ^String [node]
  (when node
    (cond
      (string? node)  node
      (map? node)     (case (:type node)
                        :element  (str/trim (str/join " " (map node->text (:content node))))
                        :document (str/trim (str/join " " (map node->text (:content node))))
                        "")
      (sequential? node) (str/trim (str/join " " (map node->text node)))
      :else "")))

(defn- first-attr
  [node k]
  (when (and node (map? node) (:tag node))
    (let [attrs (:attrs node {})]
      (or (get attrs k)
          (get attrs (str/lower-case (name k)))))))

(defn- collect-results
  [blocks max-result-count]
  (let [take-n (or max-result-count 10)]
    (vec
     (keep (fn [block]
             (let [link-node    (first (hs/select result-link block))
                   snippet-node (first (hs/select result-snippet block))
                   title        (node->text link-node)
                   raw-href     (first-attr link-node :href)
                   url         (some-> raw-href decode-uddg)
                   snippet     (node->text snippet-node)]
               (when (and url (not (str/blank? url)))
                 {:title   (or title "")
                  :url     url
                  :snippet (or snippet "")})))
           (take take-n blocks)))))

;; ---------------------------------------------------------------------------
;; Default :http-fn via impersonator (browser TLS/HTTP2 fingerprint).
;; The test seam: if config supplies `:http-fn`, it is used instead and
;; no impersonator dep is needed. The real path builds an OkHttp client
;; from `ImpersonatorFactory/<preset>` (default `android`).
;; ---------------------------------------------------------------------------

(def ^:private default-impersonate :android)

(defn- preset-fn
  "Resolve an ImpersonatorFactory preset keyword to a 0-ary fn returning
   an ImpersonatorApi. Defaults to `android` (the only preset verified
   to complete the TLS handshake against DDG with this bctls version)."
  [kw]
  (case (or kw default-impersonate)
    :macChrome  #(ImpersonatorFactory/macChrome)
    :macSafari  #(ImpersonatorFactory/macSafari)
    :macFirefox #(ImpersonatorFactory/macFirefox)
    :ios        #(ImpersonatorFactory/ios)
    :android    #(ImpersonatorFactory/android)
    #(ImpersonatorFactory/android)))

(def ^:private default-user-agent
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

(defn- impersonator-request
  "Execute `req` via an impersonator OkHttp client and return a
   hato-shaped map `{:status n :body s :headers m}`. Never throws —
   non-2xx surfaces as a normal response map. A TLS handshake failure
   surfaces as `{:status -1 :body \"<tls-err: ...>\"}`."
  [{:keys [url headers impersonate]}]
  (try
    (let [api    ((preset-fn impersonate))
          client (-> (OkHttpClientFactory/create api) (.newHttpClient))
          rb     (-> (Request$Builder.) (.url url))
          rb     (reduce (fn [b [k v]] (.header b k v)) rb (or headers {}))
          req    (.build rb)
          resp   (.execute (.newCall client req))
          body   (try (.string (.body resp)) (catch Exception e (str "<body-err: " (.getMessage e) ">")))
          ;; Build a header map so callers (and the redirect guard) can read
          ;; `Location` etc. okhttp3.Headers is name->value; iterate names.
          hdr-map (into {}
                        (for [nm (iterator-seq (.iterator (.names (.headers resp))))]
                          [nm (.get (.headers resp) nm)]))]
      {:status  (.code resp)
       :body    body
       :headers hdr-map})
    (catch Exception e
      {:status -1 :body (str "<tls-err: " (.getMessage e) ">") :headers {}})))

(defn- default-http-fn
  "Default HTTP wrapper. Resolves to impersonator when available (JVM);
   on native-image impersonator is absent, so fall back to hato (which
   will get a degraded page but still return a structured response rather
   than a CNFE). Tests never use this — they pass `:http-fn`."
  [req]
  (if (try (require 'com.github.zhkl0228.impersonator.ImpersonatorFactory) true
           (catch Throwable _ false))
    (impersonator-request req)
    (hato/request (assoc req :throw-exceptions false :coerce :always))))

;; ---------------------------------------------------------------------------
;; Provider record
;; ---------------------------------------------------------------------------

(defrecord DdgProvider [config]
  protocol/WebProvider

  (-search [_ query opts]
    (let [cfg           (merge config opts)
          base-url      (or (:base-url cfg) "https://html.duckduckgo.com")
          user-agent    (or (:user-agent cfg) default-user-agent)
          timeout-ms    (or (:timeout-ms cfg) 15000)
          max-results   (or (:max-result-count cfg) 10)
          http-fn       (or (:http-fn cfg) default-http-fn)
          url           (build-search-url base-url query)
          response      (http-fn {:method       :get
                                  :url          url
                                  :headers      {"User-Agent"      user-agent
                                                 "Accept"          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                                                 "Accept-Language" "en-US,en;q=0.9"}
                                  :as           :string
                                  :timeout-ms   timeout-ms
                                  :impersonate  (:impersonate cfg)})
          status        (:status response)
          body          (or (:body response) "")]
      (cond
        (not status)
        (throw (ex-info "DuckDuckGo search failed: no HTTP response"
                        {:phase :provider :provider :ddg :url url}))

        (not (<= 200 status 299))
        (throw (ex-info (str "DuckDuckGo search failed: HTTP " status)
                        {:phase :provider :provider :ddg :url url :status status}))

        (str/blank? body)
        (throw (ex-info "DuckDuckGo search returned empty body"
                        {:phase :provider :provider :ddg :url url})))
      (let [tree     (hickory/as-hickory (hickory/parse body))
            blocks   (hs/select result-block tree)
            results  (collect-results blocks max-results)]
        (when (empty? results)
          (throw (ex-info "DuckDuckGo search returned no parseable results"
                          {:phase :provider :provider :ddg :url url :block-count (count blocks)})))
        {:results  results
         :provider :ddg
         :url      url
         :status   status})))

  (-fetch [_ url opts]
    (let [cfg          (merge config opts)
          user-agent   (or (:user-agent cfg) default-user-agent)
          timeout-ms   (or (:timeout-ms cfg) 15000)
          max-bytes    (or (:max-page-bytes cfg) 2097152)
          max-hops     (or (:max-redirects cfg) 5)
          http-fn      (or (:http-fn cfg) default-http-fn)
          req          (fn [u]
                         (http-fn {:method            :get
                                   :url               u
                                   :headers           {"User-Agent"      user-agent
                                                       "Accept"          "text/html"
                                                       "Accept-Language" "en-US,en;q=0.9"}
                                   :as                :string
                                   :timeout-ms        timeout-ms
                                   :follow-redirects  false
                                   :impersonate       (:impersonate cfg)}))
          ;; Phase 3 SSRF redirect guard: follow up to `max-hops` 3xx
          ;; redirects manually, re-validating each Location via
          ;; `guards/safe-redirect-target` so a redirect to a private IP
          ;; or disallowed scheme is blocked before the next hop.
          final       (loop [u url hops 0]
                        (let [resp   (req u)
                              status (:status resp)]
                          (cond
                            (not status)
                            {:url url :error :no-response}
                            (and (<= 300 status 399) (< hops max-hops))
                            (let [loc (get-in resp [:headers "Location"]
                                             (get-in resp [:headers "location"]))
                                  r   (ssrf/safe-redirect-target loc cfg)]
                              (if (:ok r)
                                (recur (:ok r) (inc hops))
                                {:url u :status status :error (:error r) :blocked true}))
                            :else
                            {:url u :status status :body (or (:body resp) "")})))
          status       (:status final)
          body         (or (:body final) "")]
      (cond
        (:blocked final)
        (throw (ex-info (str "DuckDuckGo fetch blocked redirect: " (:error final))
                        {:phase :url-guard :provider :ddg :url url :reason (:error final)}))

        (not status)
        (throw (ex-info "DuckDuckGo fetch failed: no HTTP response"
                        {:phase :provider :provider :ddg :url url}))

        (not (<= 200 status 299))
        (throw (ex-info (str "DuckDuckGo fetch failed: HTTP " status)
                        {:phase :provider :provider :ddg :url url :status status})))
      (let [bytes (.getBytes body "UTF-8")
            n     (count bytes)]
        (when (and max-bytes (pos? max-bytes) (> n max-bytes))
          (throw (ex-info (str "DuckDuckGo fetch exceeded :max-page-bytes (" n " > " max-bytes ")")
                          {:phase :size-cap :provider :ddg :url url :bytes n :max-bytes max-bytes})))
        (let [stripped (strip-html body max-bytes)
              title    (extract-title body)]
          {:url url :title title :body (:text stripped) :bytes n :status status}))))

  (-extract [_ html opts]
    (let [cfg       (merge config opts)
          max-bytes (or (:max-page-bytes cfg) 1048576)
          stripped  (strip-html (or html "") max-bytes)]
      {:text          (:text stripped)
       :title         (extract-title html)
       :selectors-hit []
       :provider      :ddg}))

  (-capabilities [_]
    {:search?  true
     :fetch?   true
     :extract? true
     :live?    true}))

(defn provider
  "Factory for the `:ddg` provider. `config` is the merged guard +
   provider config map. Keys this provider reads directly:

     :http-fn       — overrides the impersonator default (test seam)
     :base-url      — defaults to https://html.duckduckgo.com
     :user-agent    — defaults to a Chrome 124 macOS UA
     :impersonate   — ImpersonatorFactory preset keyword
                      (:android default; :macChrome/:ios/:macSafari/
                       :macFirefox also supported but only :android
                       completes the TLS handshake against DDG on the
                       current bctls version)
     :timeout-ms    — per-request timeout (default 15000)
     :max-page-bytes — size cap for -fetch / -extract
     :max-result-count — cap for -search result count"
  [config]
  (->DdgProvider config))