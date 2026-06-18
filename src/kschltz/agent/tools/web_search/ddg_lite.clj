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
  "https://lite.duckduckgo.com/lite")

(defn- encode-form [params]
  (str/join "&"
            (map (fn [[k v]]
                   (str (name k) "=" (URLEncoder/encode (str v) "UTF-8")))
                 params)))

(defn- request [url opts]
  (http/request (merge {:method           :post
                        :url              url
                        :headers          {"Content-Type" "application/x-www-form-urlencoded"
                                           "Accept"       "text/html,application/xhtml+xml"
                                           "User-Agent"   "lateralus-web-search/1.0"}
                        :body             (encode-form {:q (:query opts)
                                                        :kl (:language opts "en-us")})
                        :throw-exceptions? false
                        :redirect-policy   :always}
                       (select-keys opts [:timeout-ms]))))

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

(defn- parse-result-links
  "Extract candidate result links from DDG Lite HTML.
   Returns a vector of {:url :title} maps."
  [html]
  (let [link-re #"<a[^>]*href=\"([^\"]+)\"[^>]*>"]
    (vec (for [[full href] (re-seq link-re html)
               :let [url (strip-bounce href)]
               :when (and (seq url)
                          (not (str/starts-with? url "#"))
                          (not (str/starts-with? url "/"))
                          (not (str/starts-with? url "javascript:"))
                          (or (str/starts-with? url "http://")
                              (str/starts-with? url "https://")))]
           {:url url
            :title (tag-text html "a" (+ (str/index-of html full) (count full)))}))))

(defn- parse-results
  "Parse DuckDuckGo Lite HTML into search result maps."
  [html]
  (let [links (parse-result-links html)]
    (mapv (fn [{:keys [title url]}]
            {:title   title
             :url     url
             :snippet (if (> (count title) 240)
                        (subs title 0 240)
                        title)})
          links)))

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
