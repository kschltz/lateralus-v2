(ns kschltz.agent.tools.web-search.ddg-lite
  "DuckDuckGo provider for the web search tool.

   Uses DuckDuckGo's internal JSON endpoint at
   https://links.duckduckgo.com/d.js.  A `vqd` token must first be
   extracted from a request to https://duckduckgo.com/?q=....  No API
   key is required.

   Result URLs and snippets are run through the guard pipeline before
   being returned. Page fetching reuses the same HTML stripping and URL
   validation."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hato.client :as http]
            [kschltz.agent.tools.web-search.guards :as guards]
            [kschltz.agent.tools.web-search.protocol :as protocol])
  (:import [java.net URLEncoder]))

(def ^:private lite-base-url
  "https://lite.duckduckgo.com/lite/")

(def ^:private json-base-url
  "https://links.duckduckgo.com/d.js")

(def ^:private vqd-base-url
  "https://duckduckgo.com/")

(def ^:private user-agents
  "Rotate a few real browser user agents so repeated requests from the
   tool are less likely to be rate-limited by DuckDuckGo."
  ["Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
   "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
   "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"])

(defn- user-agent [query attempt]
  (nth user-agents (mod (+ attempt (hash (str query)))
                        (count user-agents))))

(defn- browser-headers
  "Return a minimal set of browser-like headers.  We only advertise gzip
   because the JVM HTTP client does not auto-decode brotli."
  [query attempt]
  {"User-Agent"      (user-agent query attempt)
   "Accept"          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
   "Accept-Language" "en-US,en;q=0.9"
   "Accept-Encoding" "gzip"
   "Referer"         "https://duckduckgo.com/"})

(defn- json-headers
  [query attempt]
  {"User-Agent"      (user-agent query attempt)
   "Accept"          "application/json,text/html,application/xhtml+xml"
   "Accept-Language" "en-US,en;q=0.9"
   "Accept-Encoding" "gzip"
   "Referer"         "https://duckduckgo.com/"})

(defn- encode-query [q]
  (URLEncoder/encode (str q) "UTF-8"))

(defn- http-get [url headers opts]
  (http/request (merge {:method            :get
                        :url               url
                        :headers           headers
                        :throw-exceptions? false
                        :redirect-policy   :always}
                       (select-keys opts [:timeout-ms]))))

(defn- extract-vqd
  "DuckDuckGo embeds a `vqd` token in its HTML that is required for the
   d.js JSON endpoint.  Pull it out with a regex."
  [html]
  (when (string? html)
    (some-> (re-find #"(?i)vqd\s*=\s*['\"]([^'\"]+)['\"]" html)
            second)))

(defn- fetch-vqd
  "Request the DDG search page and extract the vqd token.  Retry up to
   `max-attempts` with rotating user agents and backoff."
  [query opts]
  (loop [attempt 1
         delay-ms 500]
    (let [url      (str vqd-base-url "?q=" (encode-query query) "&kl=" (encode-query (or (:language opts) "en-us")))
          resp     (http-get url (browser-headers query attempt) opts)
          html     (:body resp)
          token    (extract-vqd html)]
      (cond
        (seq token)
        token

        (>= attempt 3)
        (throw (ex-info "Could not extract DuckDuckGo vqd token"
                        {:status (:status resp)
                         :body-preview (subs html 0 (min 300 (count html)))}))

        :else
        (do
          (Thread/sleep delay-ms)
          (recur (inc attempt) (* 2 delay-ms)))))))

(defn- js-challenge? [body]
  "DuckDuckGo sometimes returns a JS challenge instead of JSON. Detect
   it so we can fall back to the Lite HTML endpoint."
  (and (string? body)
       (boolean (re-find #"DDG\.deep\.initialize|let\s+jsa\s*=|function\(num\)" body))))

(defn- fetch-json-results
  "Call links.duckduckgo.com/d.js with the extracted vqd token.  Retry
   on failure with backoff and rotating user agents."
  [query vqd opts]
  (loop [attempt 1
         delay-ms 500]
    (let [url  (str json-base-url "?q=" (encode-query query)
                    "&vqd=" (encode-query vqd)
                    "&kl=" (encode-query (or (:language opts) "en-us"))
                    "&api=d.js")
          resp (http-get url (json-headers query attempt) opts)
          body (:body resp "")
          ok?  (and (<= 200 (:status resp) 299)
                    (seq body)
                    (not (str/includes? body "Your IP address is blocked"))
                    (not (js-challenge? body)))]
      (cond
        ok?
        resp

        (>= attempt 3)
        resp

        :else
        (do
          (Thread/sleep delay-ms)
          (recur (inc attempt) (* 2 delay-ms)))))))

(defn- json-result?
  "Filter out ads, disambiguation entries, and other non-result nodes."
  [r]
  (and (map? r)
       (string? (:u r))
       (not= "ad" (:t r))))

(defn- decode-bounce-url
  "DDG result URLs are sometimes wrapped in /l/?uddg=... bounce links.
   Handles both relative and absolute forms."
  [url]
  (cond
    (not (string? url)) nil
    (str/starts-with? url "//") (str "https:" url)

    (or (str/starts-with? url "/l/?")
        (str/starts-with? url "https://duckduckgo.com/l/?")
        (str/starts-with? url "https://lite.duckduckgo.com/l/?"))
    (when-let [target (second (re-find #"uddg=([^&]+)" url))]
      (java.net.URLDecoder/decode target "UTF-8"))

    :else url))

(defn- parse-json-results
  "Parse the d.js response body.  The body is a JSONP-style object with
   a `results` key, but the exact wrapper varies.  We try to extract the
   embedded JSON array and map entries into the common result shape."
  [body]
  (when (string? body)
    (let [json-start (str/index-of body "{")
          json-end   (when json-start
                       (+ json-start (count (subs body json-start))))
          json-str   (when json-start
                       (subs body json-start))
          parsed     (try
                       (json/parse-string json-str true)
                       (catch Throwable _
                         (try
                           (json/parse-string body true)
                           (catch Throwable _ nil))))]
      (when parsed
        (->> (:results parsed [])
             (filter json-result?)
             (mapv (fn [r]
                     (let [url (decode-bounce-url (:u r))]
                       {:url     url
                        :title   (str (:t r ""))
                        :snippet (str (:a r ""))})))
             (filter #(seq (:url %))))))))

(defn- lite-landing-page? [html]
  "DuckDuckGo Lite sometimes returns a landing page with no results."
  (and (string? html)
       (not (re-find #"(?i)result-link|class=\"result\"|resultSnippet|result__snippet" html))))

(defn- fetch-lite-results
  "Fetch from the legacy Lite HTML endpoint as a fallback."
  [query opts]
  (loop [attempt 1
         delay-ms 500]
    (let [url  (str lite-base-url "?q=" (encode-query query)
                    "&kl=" (encode-query (or (:language opts) "en-us")))
          resp (http-get url (browser-headers query attempt) opts)
          html (:body resp "")
          ok?  (and (<= 200 (:status resp) 299)
                    (not (lite-landing-page? html)))]
      (if ok?
        resp
        (if (>= attempt 2)
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
   closing tag of the same name.  Very small and tuned to DDG Lite."
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
   1) number + title link, 2) snippet, 3) URL.  Group rows into triples
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

(defn- parse-lite-results
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

(defn- fetch-search-results
  "Try the JSON endpoint first, then fall back to the Lite HTML endpoint."
  [query opts]
  (try
    (let [vqd  (fetch-vqd query opts)
          resp (fetch-json-results query vqd opts)
          body (:body resp "")
          results (parse-json-results body)]
      (if (seq results)
        {:provider :ddg-lite :query query :raw-results results :source :json}
        (let [lite-resp (fetch-lite-results query opts)
              lite-body (:body lite-resp "")]
          {:provider :ddg-lite
           :query    query
           :raw-results (parse-lite-results lite-body)
           :source   :lite})))
    (catch Throwable _
      (let [lite-resp (fetch-lite-results query opts)
            lite-body (:body lite-resp "")]
        {:provider :ddg-lite
         :query    query
         :raw-results (parse-lite-results lite-body)
         :source   :lite}))))

(deftype DuckDuckGoLiteProvider [config]
  protocol/WebSearchProvider
  (-search [_ query opts]
    (let [merged (merge guards/default-guard-config config opts)
          {:keys [ok error]} (guards/sanitize-query query merged)]
      (if error
        (throw (ex-info error {:query query :phase :query-guard}))
        (let [{:keys [raw-results]} (fetch-search-results ok opts)
              guarded (guards/guard-results raw-results merged)
              capped  (vec (take (:max-result-count merged 10) guarded))]
          {:provider :ddg-lite
           :query    ok
           :results  capped}))))

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
            {:url   url
             :title (second (re-find #"(?i)<title[^>]*>([^<]*)</title>" body))
             :body  plain}))))))

(defn provider
  "Create a DuckDuckGo Lite WebSearchProvider with optional `config`.
   `config` overrides the default guard configuration."
  ([] (provider {}))
  ([config]
   (->DuckDuckGoLiteProvider config)))
