(ns kschltz.agent.tools.web.ssrf
  "Phase 3 SSRF / UA / redirect guards for the web tool suite.

   Splits out of `guards.clj` so that file stays under the 500-LOC
   quality gate. Everything here is pure-function over `java.net`:
   no Hickory/Jsoup, native-image-safe.

     - `validate-and-resolve-host` — resolve a host BEFORE connecting
       and return the pinned IP (closes the time-of-check/time-of-use
       window). Rejects loopback/link-local/site-local/any-local/
       multicast and the CGNAT 100.64.0.0/10 range that Java's
       `isSiteLocalAddress` misses.
     - `random-user-agent` — rotate a small pool of real browser UAs
       so a static UA is not a bot signal.
     - `snippet-truncation-hint` — the Unsloth-style nudge the tool
       layer appends to a search envelope.
     - `safe-redirect-target` — re-validate a 3xx `Location` per hop
       when redirect following is disabled at the HTTP client."
  (:require [clojure.string :as str]
            [kschltz.agent.tools.web.guards :as guards])
  (:import [java.net InetAddress]))

(defn- ip-text [^InetAddress a] (.getHostAddress a))

(defn- ip-string-blocked?
  "True when the IPv4 string falls in CGNAT / benchmark / doc ranges that
   `InetAddress` flag methods miss on some JDKs."
  [^String ip]
  (try
    (let [parts (str/split ip #"\.")]
      (when (= 4 (count parts))
        (let [a (Integer/parseInt (nth parts 0))
              b (Integer/parseInt (nth parts 1))
              c (Integer/parseInt (nth parts 2))]
          (or (and (= a 100) (>= b 64) (<= b 127))
              (and (= a 192) (= b 0) (> c 0) (<= c 2))
              (and (= a 198) (>= b 18) (<= b 20))
              (and (= a 198) (= b 51))))))
    (catch Throwable _ false)))

(defn- blocked-address
  "Return the first {:error} for a blocked address in `addrs`, or nil."
  [host addrs cfg]
  (let [block-priv (get cfg :block-private-ips? (:block-private-ips? guards/default-config))
        block-loop (get cfg :block-loopback? (:block-loopback? guards/default-config))]
    (reduce
      (fn [_ ^InetAddress a]
        (let [ip (ip-text a)]
          (cond
            (.isLoopbackAddress a)              (reduced {:error (str "loopback host: " host " (" ip ")")})
            (and block-loop (.isLinkLocalAddress a)) (reduced {:error (str "link-local host: " host " (" ip ")")})
            (and block-priv (.isSiteLocalAddress a)) (reduced {:error (str "private host: " host " (" ip ")")})
            (and block-priv (.isAnyLocalAddress a))   (reduced {:error (str "anylocal host: " host " (" ip ")")})
            (and block-priv (.isMulticastAddress a)) (reduced {:error (str "multicast host: " host " (" ip ")")})
            (and block-priv (ip-string-blocked? ip))  (reduced {:error (str "non-public address: " host " (" ip ")")})
            :else nil)))
      nil addrs)))

(defn validate-and-resolve-host
  "Resolve `host` and decide whether it is safe to connect to. Returns
   `{:ok ip}` (the resolved IP to PIN for the connection) or `{:error reason}`.
   Fail-closed on DNS error or non-public address. `port` unused."
  ([host] (validate-and-resolve-host host nil nil))
  ([host _port config]
   (let [cfg (merge guards/default-config config)]
     (if (str/blank? host)
       {:error "host is blank"}
       (try
         (let [addrs (InetAddress/getAllByName host)]
           (if (empty? addrs)
             {:error (str "no addresses for host: " host)}
             (or (blocked-address host addrs cfg)
                 {:ok (ip-text (first addrs))})))
         (catch Throwable e
           {:error (str "DNS resolution failed for " host ": " (.getMessage e))}))))))

(def ^:private user-agent-pool
  "Pool of current real browser User-Agent strings (Chrome/Firefox/Safari
   on macOS/Windows/Linux). Used by `random-user-agent`."
  ["Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
   "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
   "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
   "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15; rv:125.0) Gecko/20100101 Firefox/125.0"
   "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"])

(defn random-user-agent
  "Return a random browser User-Agent from the pool. The rotation defeats
   a static-UA bot signal. Providers call this as the default when
   `config :user-agent` is unset."
  []
  (let [n (count user-agent-pool)]
    (nth user-agent-pool (mod (rand-int Integer/MAX_VALUE) n))))

(def ^:private snippet-truncation-hint-text
  "IMPORTANT: these are only short snippets. To get the full page content,
   call web/search with the url parameter (e.g. {\"url\": \"https://...\"}).")

(defn snippet-truncation-hint
  "Return the snippet-truncation hint string. Exposed so the tool layer
   (`web.clj` WebSearchTool) can append it to the search results envelope."
  []
  snippet-truncation-hint-text)

(defn safe-redirect-target
  "Validate a 3xx `Location` header against `config`. Returns `{:ok url}`
   when safe to follow or `{:error reason}` when blocked. Wraps
   `guards/validate-url` so the same scheme/host/port/private-IP checks
   apply per hop."
  [location config]
  (if (str/blank? location)
    {:error "redirect Location is blank"}
    (let [{:keys [allow? reason]} (guards/validate-url location config)]
      (if allow? {:ok location} {:error (str "blocked redirect target: " reason)}))))