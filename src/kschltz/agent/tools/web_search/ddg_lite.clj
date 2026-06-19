(ns kschltz.agent.tools.web-search.ddg-lite
  "DuckDuckGo Lite provider for the web search tool.

   Uses the no-JavaScript HTML endpoint at https://lite.duckduckgo.com/lite
   with a form POST. No API key is required. HTML is parsed with a small,
   dependency-light extractor so the namespace remains usable in the
   native-image build.

   Result URLs and snippets are run through the guard pipeline before
   being returned. Page fetching reuses the same HTML stripping and URL
   validation."
  (:require [clojure.string :as str]
            [hato.client :as http]
            [kschltz.agent.tools.web-search.guards :as guards]
            [kschltz.agent.tools.web-search.protocol :as protocol])
  (:import [java.net URLEncoder]))

(def ^:private base-url
  "https://lite.duckduckgo.com/lite/")

(def ^:private user-agents
  "Rotate a few real browser user agents so repeated requests from the
   tool are less likely to be rate-limited by DuckDuckGo Lite."
  ["Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
   "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
   "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"])

(defn- browser-headers
  "Return a minimal set of browser-like headers. DDG Lite rejects
   bare programmatic requests; this combination is enough to pass
   its bot detection while staying decodable by the JVM HTTP client.
   We avoid brotli because the JVM client does not auto-decode it."
  [query]
  {"User-Agent"      (nth user-agents (mod (hash query) (count user-agents)))
   "Accept"          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
   "Accept-Language" "en-US,en;q=0.9"
   "Accept-Encoding" "gzip"
   "Referer"         "https://duckduckgo.com/"})

(defn- encode-query [q]
  (URLEncoder/encode (str q) "UTF-8"))

(defn- request-once [url opts user-agent]
  (http/request (merge {:method            :get
                        :url              (str url "?q=" (encode-query (:query opts))
                                               "&kl=" (encode-query (or (:language opts) "en-us")))
                        :headers          (assoc (browser-headers (:query opts)) "User-Agent" user-agent)
                        :throw-exceptions? false
                        :redirect-policy   :always}
                       (select-keys opts [:timeout-ms]))))

(defn- landing-page? [html]
  "DuckDuckGo Lite sometimes returns a 202 landing page with no results
   instead of the result table. Detect that so we can retry."
  (and (string? html)
       (not (re-find #"(?i)result-link|class=\"result\"|resultSnippet|result__snippet" html))))

(defn- request [url opts]
  "Request DuckDuckGo Lite with retry/backoff and rotating user agents.
   DDG Lite is a free, scraped endpoint and can return a bot-detection
   landing page when too many requests come from the same IP. We surface
   that clearly instead of silently returning empty results."
  (loop [attempt 1
         delay-ms 500]
    (let [user-agent (nth user-agents (mod (+ attempt (hash (:query opts "x")))
                                           (count user-agents)))
          resp       (request-once url opts user-agent)
          html       (:body resp)
          ok?        (and (<= 200 (:status resp) 299)
                          (not (landing-page? html)))]
      (if ok?
        resp
        (if (>= attempt 3)
          resp
          (do
            (Thread/sleep delay-ms)
            (recur (inc attempt) (* 2 delay-ms))))))))

(defn- strip-bounce
  "Remove DuckDuckGo's `/l/?...` bounce wrapper if present."
  [url]
  (cond
    (str/starts-with? url "//") (str "https:" url)
    (str/starts-with? url "/l/?") (when-let [target (second (re-find #"uddg=([^&]+)" url))]
                                   (java.net.URLDecoder/decode target "UTF-8"))
    :else url))

(defn- tag-text
  "Extract the text content between an opening tag and the next
   closing tag of the same name. Very small and tuned to DDG Lite."
  [html tag-name start-idx]
  (let [close (str "</" tag-name ">")
        end (str/index-of html close start-idx)]
    (if end
      (guards/strip-html (subs html start-idx end) 1024)
      "")))

(defn- row-text
  "Return the visible text inside a <tr>...</tr> fragment, stripping
   residual HTML tags and collapsing whitespace."
  [row]
  (-> row
      (str/replace #"<script[^>]*>.*?</script>" "")
      (str/replace #"<style[^>]*>.*?</style>" "")
      (str/replace #"<[^>]+>" " ")
      (str/replace #"&nbsp;" " ")
      (str/trim)
      (str/replace #"\s+" " ")))

(defn- link-in-row
  "Return [href title] for the first <a> in a row fragment, with the
   DuckDuckGo bounce wrapper removed."
  [row]
  (when-let [[_ href] (re-find #"<a[^>]*href=\"([^\"]+)\"[^>]*>" row)]
    (let [url (strip-bounce href)]
      (when (and (seq url)
                 (not (str/starts-with? url "#"))
                 (not (str/starts-with? url "/"))
                 (not (str/starts-with? url "javascript:"))
                 (or (str/starts-with? url "http://")
                     (str/starts-with? url "https://")))
        [url (tag-text row "a" (+ (str/index-of row "<a") (count (re-find #"<a[^>]*>" row))))]))))

(defn- parse-result-rows
  "DDG Lite renders each result as three consecutive <tr> rows:
   1) number + title link, 2) snippet, 3) URL. Group rows into triples
   and extract title, snippet and URL from each triple."
  [html]
  (let [rows (vec (for [[row] (re-seq #"(?is)<tr[^>]*>(.*?)</tr>" html)]
                    row))
        triples (partition 3 3 nil rows)]
    (vec (for [[title-row snippet-row url-row] triples
               :let [[url title] (link-in-row title-row)
                     snippet-text (row-text snippet-row)
                     url-text (row-text url-row)]
               :when url]
           {:url     url
            :title   title
            :snippet (if (seq snippet-text) snippet-text title)
            :display-url url-text}))))

(defn- parse-results
  "Parse DuckDuckGo Lite HTML into search result maps."
  [html]
  (let [results (parse-result-rows html)]
    (mapv (fn [{:keys [title url snippet]}]
            {:title   title
             :url     url
             :snippet (if (> (count snippet) 480)
                        (subs snippet 0 480)
                        snippet)})
          results)))

(deftype DuckDuckGoLiteProvider [config]
  protocol/WebSearchProvider
  (-search [_ query opts]
    (let [merged (merge guards/default-guard-config config opts)
          {:keys [ok error]} (guards/sanitize-query query merged)]
      (if error
        (throw (ex-info error {:query query :phase :query-guard}))
        (let [resp (request base-url (assoc opts :query ok))
              html (:body resp)]
          (when (and (>= (:status resp) 400) (< (:status resp) 600))
            (throw (ex-info (format "DuckDuckGo Lite returned HTTP %d" (:status resp))
                            {:status (:status resp) :body (subs html 0 (min 200 (count html)))})))
          (let [raw-results (parse-results html)
                guarded   (guards/guard-results raw-results merged)
                capped    (vec (take (:max-result-count merged 10) guarded))]
            {:provider :ddg-lite
             :query    ok
             :results  capped})))))

  (-fetch-page [_ url opts]
    (let [merged (merge guards/default-guard-config config opts)
          url-check (guards/validate-url url merged)]
      (when (= :error (first url-check))
        (throw (ex-info (:error url-check) {:url url :phase :url-guard})))
      (let [resp (http/request (merge {:method            :get
                                       :url               url
                                       :headers           {"User-Agent" "lateralus-web-search/1.0"}
                                       :throw-exceptions? false
                                       :redirect-policy   :always}
                                      (select-keys merged [:timeout-ms])))
            status (:status resp)]
        (when (and (>= status 400) (< status 600))
          (throw (ex-info (format "Fetch returned HTTP %d" status) {:url url :status status})))
        (let [body  (:body resp "")
              bytes (.getBytes body "UTF-8")
              max-b (:max-page-bytes merged (* 2 1024 1024))]
          (when (> (count bytes) max-b)
            (throw (ex-info (format "Page too large: %d bytes (limit %d)" (count bytes) max-b)
                            {:url url :size (count bytes)})))
          (let [plain (guards/strip-html body max-b)]
            {:url  url
             :title (second (re-find #"(?i)<title[^>]*>([^<]*)</title>" body))
             :body  plain}))))))

(defn provider
  "Create a DuckDuckGo Lite WebSearchProvider with optional `config`.
   `config` overrides the default guard configuration."
  ([] (provider {}))
  ([config]
   (->DuckDuckGoLiteProvider config)))
