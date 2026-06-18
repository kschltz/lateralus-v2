(ns kschltz.agent.tools.web-search.searxng
  "SearXNG provider for the web search tool.

   SearXNG is a self-hosted, open-source metasearch engine. Its JSON
   API requires no API key, but the instance administrator must enable
   `search.formats: [json]` in `settings.yml`. Results are already
   structured, so this provider does less HTML scraping than DuckDuckGo
   Lite. Page fetching reuses the shared guard pipeline."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hato.client :as http]
            [kschltz.agent.tools.web-search.guards :as guards]
            [kschltz.agent.tools.web-search.protocol :as protocol])
  (:import [java.net URLEncoder]))

(defn- build-url [{:keys [base-url query categories language safesearch result-count]}]
  (let [params (cond-> [[:q query] [:format "json"]]
                 (seq categories)   (conj [:categories (str/join "," categories)])
                 language           (conj [:language language])
                 (some? safesearch) (conj [:safesearch safesearch])
                 result-count       (conj [:pageno 1]))]
    (str base-url "/search?" (str/join "&" (map (fn [[k v]]
                                                    (str (name k) "=" (URLEncoder/encode (str v) "UTF-8")))
                                                  params)))))

(defn- normalize-result
  "Turn a SearXNG JSON result entry into the common shape."
  [r]
  {:title   (str (or (:title r) (:engines r) ""))
   :url     (str (or (:url r) (:pretty_url r) ""))
   :snippet (str (or (:content r) (:snippet r) ""))})

(deftype SearXNGProvider [config]
  protocol/WebSearchProvider
  (-search [_ query opts]
    (let [merged (merge guards/default-guard-config config opts)
          {:keys [ok error]} (guards/sanitize-query query merged)]
      (if error
        (throw (ex-info error {:query query :phase :query-guard}))
        (let [base-url (or (:base-url opts) (:base-url config) "http://localhost:8888")
              url      (build-url (merge merged opts {:base-url base-url :query ok}))
              resp     (http/request (merge {:method            :get
                                             :url               url
                                             :headers           {"Accept" "application/json"
                                                                 "User-Agent" "lateralus-web-search/1.0"}
                                             :throw-exceptions? false
                                             :redirect-policy   :always}
                                            (select-keys merged [:timeout-ms])))
              body     (:body resp "")]
          (when (and (>= (:status resp) 400) (< (:status resp) 600))
            (throw (ex-info (format "SearXNG returned HTTP %d" (:status resp))
                            {:status (:status resp)
                             :body   (subs body 0 (min 200 (count body)))})))
          (let [parsed (try (json/parse-string body true)
                            (catch Throwable e
                              (throw (ex-info "Failed to parse SearXNG JSON response"
                                              {:body (subs body 0 (min 500 (count body)))
                                               :cause e}))))
                raw-results (mapv normalize-result (:results parsed []))
                guarded     (guards/guard-results raw-results merged)
                capped      (vec (take (:max-result-count merged 10) guarded))]
            {:provider :searxng
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
            status (:status resp)
            body   (:body resp "")
            bytes  (.getBytes body "UTF-8")
            max-b  (:max-page-bytes merged (* 2 1024 1024))]
        (when (and (>= status 400) (< status 600))
          (throw (ex-info (format "Fetch returned HTTP %d" status) {:url url :status status})))
        (when (> (count bytes) max-b)
          (throw (ex-info (format "Page too large: %d bytes (limit %d)" (count bytes) max-b)
                          {:url url :size (count bytes)})))
        (let [plain (guards/strip-html body max-b)]
          {:url   url
           :title (second (re-find #"(?i)<title[^\u003e]*>([^\u003c]*)</title>" body))
           :body  plain})))))

(defn provider
  "Create a SearXNG WebSearchProvider. `config` may contain `:base-url`
   and guard overrides. The `:base-url` can also be supplied per call
   via `opts`."
  ([] (provider {}))
  ([config]
   (->SearXNGProvider config)))
