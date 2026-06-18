(ns kschltz.agent.tools.web-search.guards
  "Defensive guards for the web search tool.

   Each guard is pure, stateless, and independently toggle-able via the
   guard config map. The tool applies them before any network call and
   again on provider results before returning them to the LLM."
  (:require [clojure.string :as str])
  (:import [java.net InetAddress URI URISyntaxException]))

;; ---------------------------------------------------------------------------
;; Configuration defaults

(def default-guard-config
  "Default guard settings. Every key can be overridden from Integrant config."
  {:max-query-length 500
   :max-result-count 10
   :max-page-bytes   (* 2 1024 1024)      ; 2 MiB
   :max-snippet-bytes (* 16 1024)         ; 16 KiB per snippet
   :default-timeout-ms 15000
   :block-private-ips? true
   :block-loopback? true
   :block-metadata-endpoints? true
   :block-file-scheme? true
   :block-protocol-relative? true
   :block-injection-markers? true
   :block-self-activation? true
   :block-exfiltration-patterns? true
   :injection-markers #{"ignore previous"
                        "ignore the previous"
                        "system instruction"
                        "you are now"
                        "disregard"
                        "developer mode"
                        "DAN mode"
                        "jailbreak"
                        "\u0000"}
   :exfiltration-regex #"(?i)(?:internal|private|secret|token|key|password|credential)\s*[:=]\s*[\"']?[a-z0-9_\-]{8,}"
   :allowed-schemes #{"http" "https"}
   :url-allow-list []                       ; empty = allow all non-blocked
   :url-block-list []})

;; ---------------------------------------------------------------------------
;; Query guards

(defn sanitize-query
  "Apply query-level guards. Returns `{:ok query}` or `{:error msg}`."
  [q config]
  (let [max-len (:max-query-length config 500)
        markers (:injection-markers config #{})]
    (cond
      (not (string? q))
      {:error "Search query must be a string."}

      (str/blank? q)
      {:error "Search query must not be empty."}

      (> (count q) max-len)
      {:error (format "Query too long: %d characters (limit %d)" (count q) max-len)}

      :else
      (let [cleaned (-> q
                        (str/replace #"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]" "")
                        (str/trim))
            lower (str/lower-case cleaned)
            hit   (some #(when (str/includes? lower %) %) markers)]
        (if (and hit (:block-injection-markers? config true))
          {:error (format "Query rejected due to disallowed marker: %s" (pr-str hit))}
          {:ok cleaned})))))

;; ---------------------------------------------------------------------------
;; URL guards

(defn- parse-uri [s]
  (try (URI. s) (catch URISyntaxException _ nil)))

(defn- host->address [host]
  (try (InetAddress/getByName host) (catch Throwable _ nil)))

(defn- private-ip? [^InetAddress addr]
  (or (.isSiteLocalAddress addr)
      (.isLinkLocalAddress addr)
      (.isMulticastAddress addr)))

(defn- metadata-endpoint? [host]
  (boolean (re-matches #"(?i)169\.254\.169\.254|metadata\.google\.internal|metadata\.ecs\.internal.*" host)))

(defn- loopback? [^InetAddress addr]
  (.isLoopbackAddress addr))

(defn- allowed-scheme? [scheme config]
  (contains? (set (:allowed-schemes config #{"http" "https"})) (str/lower-case (or scheme ""))))

(defn- allow-listed? [url allow-list]
  (or (empty? allow-list)
      (let [host (some-> (parse-uri url) (.getHost) str/lower-case)]
        (some #(str/ends-with? (or host "") (str/lower-case %)) allow-list))))

(defn- block-listed? [url block-list]
  (let [host (some-> (parse-uri url) (.getHost) str/lower-case)]
    (boolean (some #(str/ends-with? (or host "") (str/lower-case %)) block-list))))

(defn validate-url
  "Return `{:ok url}` if `url` passes all URL guards, otherwise `{:error msg}`."
  [url config]
  (let [uri (parse-uri url)]
    (cond
      (nil? uri)
      {:error (format "Invalid URL: %s" (pr-str url))}

      (and (:block-protocol-relative? config true)
           (str/starts-with? url "//"))
      {:error "Protocol-relative URLs are not allowed."}

      (and (:block-file-scheme? config true)
           (= "file" (str/lower-case (or (.getScheme uri) ""))))
      {:error "file:// URLs are not allowed."}

      (not (allowed-scheme? (.getScheme uri) config))
      {:error (format "URL scheme not allowed: %s" (or (.getScheme uri) "missing"))}

      :else
      (let [host (.getHost uri)]
        (cond
          (str/blank? host)
          {:error "URL is missing a host."}

          (and (:block-metadata-endpoints? config true)
               (metadata-endpoint? host))
          {:error (format "Metadata endpoint blocked: %s" host)}

          (and (seq (:url-allow-list config))
               (not (allow-listed? url (:url-allow-list config))))
          {:error "URL is not in the allow-list."}

          (block-listed? url (:url-block-list config []))
          {:error "URL is in the block-list."}

          :else
          (let [addr (host->address host)]
            (cond
              (nil? addr)
              {:ok url}

              (and (:block-loopback? config true) (loopback? addr))
              {:error (format "Loopback address blocked: %s" host)}

              (and (:block-private-ips? config true) (private-ip? addr))
              {:error (format "Private IP address blocked: %s" host)}

              :else
              {:ok url})))))))

;; ---------------------------------------------------------------------------
;; HTML / snippet guards

(def ^:private html-tag-re #"<[^>]+>")
(def ^:private js-url-re #"(?i)javascript:|data:text/html|data:text/javascript")
(def ^:private protocol-relative-re #"(?m)(?:^|\s)//[^\s\"']+")

(defn strip-html
  "Remove HTML tags and collapse whitespace. Truncate to `max-bytes`."
  [s max-bytes]
  (let [text (-> s
                 (str/replace html-tag-re " ")
                 (str/replace "&lt;" "<")
                 (str/replace "&gt;" ">")
                 (str/replace "&amp;" "&")
                 (str/replace "&quot;" "\"")
                 (str/replace js-url-re "")
                 (str/replace protocol-relative-re "")
                 (str/trim)
                 (str/replace #"\s+" " "))
        bytes (.getBytes text "UTF-8")]
    (if (> (count bytes) max-bytes)
      (String. (java.util.Arrays/copyOf bytes max-bytes) "UTF-8")
      text)))

;; ---------------------------------------------------------------------------
;; Exfiltration / self-activation guards

(defn- contains-exfiltration-pattern? [text config]
  (boolean (re-find (:exfiltration-regex config #"$^") text)))

(defn- contains-self-activation? [text]
  (boolean (re-find #"(?i)\{\s*\"name\"\s*:\s*\"web_search\"" text)))

(defn sanitize-snippet
  "Apply result-snippet guards. Returns `{:ok text}` or `{:error msg}`."
  [snippet config]
  (let [text (strip-html snippet (:max-snippet-bytes config (* 16 1024)))]
    (cond
      (and (:block-exfiltration-patterns? config true)
           (contains-exfiltration-pattern? text config))
      {:error "Snippet rejected: possible exfiltration pattern."}

      (and (:block-self-activation? config true)
           (contains-self-activation? text))
      {:error "Snippet rejected: possible recursive tool activation."}

      :else
      {:ok text})))

;; ---------------------------------------------------------------------------
;; Result guard pipeline

(defn validate-result
  "Apply URL + snippet guards to a single provider result map.
   Returns the result with `:snippet` sanitized and `:url` validated,
   or nil if it should be dropped."
  [result config]
  (let [url (:url result)]
    (when-let [url' (and url (:ok (validate-url url config)) url)]
      (let [snippet (:snippet result "")
            sanitized (sanitize-snippet snippet config)]
        (if (:ok sanitized)
          (assoc result :url url' :snippet (:ok sanitized))
          nil)))))

(defn guard-results
  "Run the guard pipeline over a vector of provider results.
   Drops rejected results and returns the cleaned vector."
  [results config]
  (vec (keep #(validate-result % config) results)))

;; ---------------------------------------------------------------------------
;; Instrumented public schemas

(def GuardConfig
  "Malli schema for the guard configuration map."
  [:map
   [:max-query-length {:optional true} :int]
   [:max-result-count {:optional true} :int]
   [:max-page-bytes {:optional true} :int]
   [:max-snippet-bytes {:optional true} :int]
   [:default-timeout-ms {:optional true} :int]
   [:block-private-ips? {:optional true} :boolean]
   [:block-loopback? {:optional true} :boolean]
   [:block-metadata-endpoints? {:optional true} :boolean]
   [:block-file-scheme? {:optional true} :boolean]
   [:block-protocol-relative? {:optional true} :boolean]
   [:block-injection-markers? {:optional true} :boolean]
   [:block-self-activation? {:optional true} :boolean]
   [:block-exfiltration-patterns? {:optional true} :boolean]
   [:injection-markers {:optional true} [:set :string]]
   [:exfiltration-regex {:optional true} :any]
   [:allowed-schemes {:optional true} [:set :string]]
   [:url-allow-list {:optional true} [:vector :string]]
   [:url-block-list {:optional true} [:vector :string]]])
