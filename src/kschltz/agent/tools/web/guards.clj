(ns kschltz.agent.tools.web.guards
  "Guard pipeline for the lateralus web tool suite.

   This namespace is the *only* place that decides whether a query,
   URL, or snippet is safe to pass to a `WebProvider`. Every other
   web tool namespace (`protocol`, `schemas`, `web`, `none`,
   `mojeek`) is pure protocol or pure transformation.

   The pipeline is deliberately conservative — every toggle defaults
   to `true` (see `default-guard-config`). Operators who need to
   loosen a guard can flip one toggle without touching code.

   ## Functions

     - `sanitize-query`   — length cap + control chars + injection
                            marker scan. Returns `{:ok s}` or
                            `{:error s}`.
     - `validate-url`     — scheme/host/port/userinfo/fragment
                            checks plus DNS resolve against
                            `InetAddress` to reject private,
                            loopback, link-local, and metadata
                            endpoints. Returns `{:allow? bool
                            :url s :reason s}` (a plain map; the
                            `:allow?` key is **explicit** so
                            callers cannot accidentally treat a
                            MapEntry as a boolean — this is the
                            fix for the prior `(first url-check)`
                            bug).
     - `strip-html`       — zero-dep regex stripper that decodes
                            the five HTML entities, collapses
                            whitespace, removes `javascript:` and
                            `data:text/html` URLs, and truncates
                            to a UTF-8 byte cap.
     - `sanitize-snippet` — strips HTML and runs the
                            self-activation and exfiltration
                            scans. Returns `{:ok s}` or
                            `{:error s}`.
     - `default-guard-config` — the default config map.
     - `guard-results`    — apply `validate-url` + `sanitize-snippet`
                            to a sequence of search results, dropping
                            any that fail either guard.

   Imports are restricted to `java.net` and `clojure.string` so the
   file loads under the `:native` alias with no Hickory/Jsoup."
  (:require [clojure.string :as str])
  (:import [java.net URI InetAddress URISyntaxException]))

;; ---------------------------------------------------------------------------
;; Default guard config
;; ---------------------------------------------------------------------------

(def default-config
  "The default guard configuration. Every toggle is `true` per
   `decisions.md` §\"Defense checklist\" except `:policy-model?`
   which is `false` because it requires an LLM snippet classifier
   that is not shipped by default.

   `allowed-schemes` is `#{\"http\" \"https\"}`.
   `allowed-ports` is `#{80 443}`."
  {:max-query-length            400
   :max-result-count            20
   :max-page-bytes              2097152   ;; 2 MiB
   :max-snippet-bytes           16384     ;; 16 KiB
   :timeout-ms                  15000
   :user-agent                  "lateralus-web/0.1 (+https://github.com/kschltz/lateralus)"
   :base-url                    "https://www.mojeek.com"
   :block-private-ips?          true
   :block-loopback?             true
   :block-metadata-endpoints?   true
   :block-file-scheme?          true
   :block-protocol-relative?    true
   :block-injection-markers?    true
   :block-self-activation?      true
   :block-exfiltration-patterns? true
   :block-duplicate-query?       true
   :strip-html?                 true
   :allowed-schemes             #{"http" "https"}
   :allowed-ports               #{80 443}
   :injection-markers           #{"ignore previous" "system instruction" "you are now"
                                 "disregard" "developer mode" "jailbreak" "DAN mode"}
   :url-allow-list              []
   :url-block-list              []
   :policy-model?               false})

(defn default-guard-config
  "Return the default guard configuration. Returned by value so
   callers may freely mutate the result."
  []
  (into {} default-config))

;; ---------------------------------------------------------------------------
;; sanitize-query
;; ---------------------------------------------------------------------------

(def ^:private control-char-re
  "Control characters stripped from queries (preserves \\t, \\n, \\r
   by skipping \\x09, \\x0A, \\x0D)."
  #"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]")

(defn sanitize-query
  "Sanitize a search query against `config`. Returns `{:ok cleaned}`
   on success or `{:error reason}` when a guard fires.

   Steps:
     1. Length cap (`:max-query-length`, default 400).
     2. Strip control chars (regex
        `[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]`).
     3. Reject any `:injection-markers` substring present (when
        `:block-injection-markers?` is true)."
  [query config]
  (let [max-len   (get config :max-query-length (:max-query-length default-config))
        block?    (get config :block-injection-markers? (:block-injection-markers? default-config))
        markers   (get config :injection-markers (:injection-markers default-config))]
    (cond
      (not (string? query))
      {:error "query is not a string"}

      (> (count query) max-len)
      {:error (str "query exceeds max length " max-len)}

      :else
      (let [stripped (str/replace query control-char-re "")
            stripped-lc (str/lower-case stripped)
            markers-lc  (set (map str/lower-case markers))]
        (if block?
          (if-let [hit (some #(when (str/includes? stripped-lc %)
                                 %)
                             markers-lc)]
            {:error (str "query contains injection marker: " hit)}
            {:ok stripped})
          {:ok stripped})))))

;; ---------------------------------------------------------------------------
;; validate-url
;;
;; Returns a plain map {:allow? :boolean :url :string :reason :string}
;; — NOT a MapEntry. This is the explicit fix for the prior
;; `(first url-check)` bug where destructuring or first on a sorted
;; map gave the wrong value.
;; ---------------------------------------------------------------------------

(def ^:private metadata-hosts
  "Well-known cloud metadata endpoints that must always be blocked,
   regardless of whether DNS resolves them. Includes AWS, GCP, and
   Azure IMDS endpoints."
  #{"169.254.169.254"
    "metadata.google.internal"
    "metadata.ecs.internal"
    "100.100.100.200"
    "168.63.129.16"
    "fd00:ec2::254"})

(defn- allow-or-deny
  "Build a final `{:allow? :url :reason}` map. Centralized so every
   return site produces the same explicit shape (not a MapEntry)."
  [url allow? reason]
  {:allow? (boolean allow?) :url url :reason reason})

(defn- uri-host
  "Return the host of a `java.net.URI`, lowercased, or `nil` when
   the URI has no host component."
  [^URI u]
  (when-let [h (.getHost u)]
    (str/lower-case h)))

(defn- uri-port
  "Return the explicit port of a `java.net.URI`, or `nil` if the URI
   uses a scheme-default port. We do NOT consult
   `URI.getDefaultPort` because that returns -1 for unknown schemes
   and we want `nil` to mean \"no explicit port\"."
  [^URI u]
  (let [p (.getPort u)]
    (when (pos? p) p)))

(defn- scheme-default-port
  "Return the IANA default port for a scheme, or `nil` if unknown."
  [scheme]
  (case scheme
    "http"  80
    "https" 443
    nil))

(defn- resolve-allow-port
  "Pick the effective port from an explicit `port` or the scheme
   default. Returns `nil` when the scheme has no default."
  [scheme port]
  (or port (scheme-default-port scheme)))

(defn- ip-only?
  "True when `host` looks like an IPv4 or IPv6 literal (no DNS round
   trip needed to know we should still resolve for safety)."
  [host]
  (boolean
    (and host
         (or (re-matches #"\d+\.\d+\.\d+\.\d+" host)
             (str/starts-with? host "[")
             (str/includes? host ":")))))

(defn- address-blocked?
  "Return `[blocked? reason]` for a host resolved via
   `InetAddress/getByName`. Catches DNS errors and treats them as
   blocked (fail closed)."
  [host cfg]
  (let [block-priv (get cfg :block-private-ips? (:block-private-ips? default-config))
        block-loop (get cfg :block-loopback? (:block-loopback? default-config))
        block-meta (get cfg :block-metadata-endpoints? (:block-metadata-endpoints? default-config))]
    (try
      (let [addrs (InetAddress/getAllByName host)]
        (reduce
          (fn [_ ^InetAddress a]
            (cond
              (.isLoopbackAddress a)
              (reduced [true (str "loopback host: " host)])

              (and block-loop (.isLinkLocalAddress a))
              (reduced [true (str "link-local host: " host)])

              (and block-priv (.isSiteLocalAddress a))
              (reduced [true (str "private host: " host)])

              (and block-priv (.isAnyLocalAddress a))
              (reduced [true (str "anylocal host: " host)])

              (and block-priv (.isMulticastAddress a))
              (reduced [true (str "multicast host: " host)])

              :else nil))
          nil
          addrs))
      (catch Throwable _
        [true (str "DNS resolution failed for: " host)]))))

(defn- host-matches-list?
  "True when `host` matches any pattern in `patterns`. Supports
   exact match and `*.suffix` wildcards."
  [host patterns]
  (boolean
    (some (fn [p]
            (let [p (str/lower-case (str p))]
              (cond
                (= p host) true
                (str/starts-with? p "*.")
                (let [suffix (subs p 1)]
                  (or (= host (subs suffix 1))
                      (str/ends-with? host suffix)))
                :else false)))
          patterns)))

(defn validate-url
  "Validate a URL against `config`. Returns
   `{:allow? bool :url s :reason s}` — the `:allow?` key is
   **explicit** so the boolean cannot be confused with a MapEntry.

   Checks (in order):
     1. Protocol-relative (`//host`).
     2. `URISyntaxException` parse.
     3. Userinfo (`user:pass@host`).
     4. Fragment.
     5. Disallowed scheme (`:block-file-scheme?` blocks
        `file:`/`javascript:`/`data:`; `:allowed-schemes`
        enforces `http`/`https` by default).
     6. Allow-list / block-list.
     7. Well-known metadata endpoints.
     8. `InetAddress/getByName` resolution + private/loopback/
        link-local checks.
     9. Port allow-list (default 80, 443)."
  [url config]
  (let [cfg (merge default-config config)
        raw (str url)]
    (cond
      ;; (1) Protocol-relative: "//host/..."
      (str/starts-with? raw "//")
      (allow-or-deny raw false "protocol-relative URLs are blocked")

      ;; (2) Parse
      :else
      (try
        (let [uri (URI. raw)]
          (cond
            ;; (3) userinfo
            (not (nil? (.getUserInfo uri)))
            (allow-or-deny raw false "userinfo in URL is blocked")

            ;; (4) fragment
            (not (nil? (.getFragment uri)))
            (allow-or-deny raw false "URL fragment is blocked")

            :else
            (let [scheme (some-> (.getScheme uri) str/lower-case)
                  host   (uri-host uri)
                  port   (uri-port uri)]
              (cond
                ;; (5a) Missing scheme or host
                (or (nil? scheme) (nil? host))
                (allow-or-deny raw false "URL is missing scheme or host")

                ;; (5b) block-file-scheme? covers file: javascript: data:
                (and (:block-file-scheme? cfg)
                     (contains? #{"file" "javascript" "data"} scheme))
                (allow-or-deny raw false (str "scheme " scheme ": is blocked"))

                ;; (5c) allowed-schemes whitelist
                (not (contains? (:allowed-schemes cfg) scheme))
                (allow-or-deny raw false (str "scheme " scheme ": is not in allowed-schemes"))

                ;; (6) Well-known metadata endpoints (always block,
                ;; even if the host later appears in an allow-list).
                (contains? metadata-hosts host)
                (allow-or-deny raw false (str "well-known metadata endpoint: " host))

                ;; (7a) url-allow-list: when present and the host
                ;; matches, allow immediately without a DNS round-trip.
                ;; When present and the host does NOT match, deny.
                ;; This lets operators reach non-resolvable / internal
                ;; hosts by explicit approval while still keeping the
                ;; default fail-closed DNS behavior for unknown hosts.
                (seq (:url-allow-list cfg))
                (if (host-matches-list? host (:url-allow-list cfg))
                  (allow-or-deny raw true "host in url-allow-list")
                  (allow-or-deny raw false (str "host not in url-allow-list: " host)))

                ;; (7b) url-block-list
                (host-matches-list? host (:url-block-list cfg))
                (allow-or-deny raw false (str "host matches url-block-list: " host))

                ;; (8) IP literal check + DNS resolve + InetAddress flags
                (or (ip-only? host) (not (str/blank? host)))
                (let [[blocked? reason] (address-blocked? host cfg)]
                  (if blocked?
                    (allow-or-deny raw false reason)
                    ;; (9) Port allow-list (effective port = explicit or scheme default)
                    (let [eff-port (resolve-allow-port scheme port)]
                      (cond
                        (nil? eff-port)
                        (allow-or-deny raw false
                                       (str "cannot determine port for scheme: " scheme))

                        (not (contains? (:allowed-ports cfg) eff-port))
                        (allow-or-deny raw false
                                       (str "port " eff-port " is not in allowed-ports"))

                        :else
                        (allow-or-deny raw true "url allowed")))))))))
        (catch URISyntaxException _
          (allow-or-deny raw false "URL failed to parse"))
        (catch IllegalArgumentException _
          (allow-or-deny raw false "URL is malformed"))))))

;; ---------------------------------------------------------------------------
;; strip-html
;; ---------------------------------------------------------------------------

(def ^:private tag-re
  "Match an HTML tag (greedy on `>` so the body text stays intact)."
  #"<[^>]+>")

(def ^:private script-or-style-re
  "Strip the contents of `<script>` and `<style>` blocks entirely."
  #"(?is)<(script|style)[^>]*>.*?</\1\s*>")

(def ^:private html-comment-re
  "Strip HTML comments."
  #"(?s)<!--.*?-->")

(def ^:private js-href-re
  "Match `javascript:` and `data:text/html` URLs in href/src so we
   can wipe them out of any rendered text."
  #"(?i)\b(?:javascript|data:text/html)\s*:[^\s\"'<>]*")

(def ^:private multi-whitespace-re
  "Collapse runs of whitespace (incl. newlines) into a single space."
  #"\s+")

(defn- decode-entities
  "Decode the five most common HTML entities. Anything else stays
   as-is so we do not pull in a full entity table."
  ^String [s]
  (-> s
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&nbsp;" " ")
      (str/replace "&amp;" "&")))

(defn- truncate-utf8
  "Truncate `s` so its UTF-8 byte length is at most `max-bytes`.
   Cuts at a character boundary so we never split a multi-byte
   sequence. Returns `s` unchanged when it already fits."
  ^String [^String s max-bytes]
  (let [bytes (.getBytes s "UTF-8")]
    (if (<= (alength bytes) max-bytes)
      s
      (let [sb (StringBuilder.)]
        (loop [i 0 used 0]
          (when (< i (.length s))
            (let [cp (.codePointAt s i)
                  cp-bytes (+ used (.length (str (Character/toChars cp))))]
              (if (<= cp-bytes max-bytes)
                (do
                  (.appendCodePoint sb cp)
                  (recur (+ i (Character/charCount cp)) cp-bytes))
                (recur (.length s) max-bytes)))))
        (.toString sb)))))

(defn strip-html
  "Strip HTML tags from `s` and return `{:text s :bytes n}`.

   Steps:
     1. Remove `<script>` and `<style>` blocks entirely.
     2. Remove HTML comments.
     3. Replace remaining tags with a space.
     4. Decode the five most common HTML entities.
     5. Remove `javascript:` and `data:text/html` URLs.
     6. Trim and collapse whitespace.
     7. Truncate to `max-bytes` (UTF-8 byte length, hard cap).

   The byte count reported is the post-truncation UTF-8 length."
  [s max-bytes]
  (let [clean (-> (or s "")
                  (str/replace script-or-style-re " ")
                  (str/replace html-comment-re " ")
                  (str/replace tag-re " ")
                  decode-entities
                  (str/replace js-href-re " ")
                  (str/replace multi-whitespace-re " ")
                  str/trim)
        capped (truncate-utf8 clean (int max-bytes))
        bytes  (alength (.getBytes capped "UTF-8"))]
    {:text capped :bytes bytes}))

;; ---------------------------------------------------------------------------
;; sanitize-snippet
;; ---------------------------------------------------------------------------

(def ^:private self-activation-re
  "Detect snippet text that tries to coax the LLM into calling a
   tool by name (the classic indirect-prompt-injection shape).
   Case-sensitive (`(?-i)`)."
  #"(?-i)\{\s*\"name\"\s*:\s*\"(?:web_search|web/search|fetch|fetch_page|browse)")

(def ^:private exfil-re
  "Detect snippet text that looks like it is leaking a credential or
   secret. The trigger keyword (`secret|token|key|...`) is followed
   by a separator and an 8+ char opaque value."
  #"(?i)(internal|private|secret|token|key|password|credential)\s*[:=]\s*[\"']?[a-z0-9_\-]{8,}")

(defn sanitize-snippet
  "Sanitize a result snippet. Returns `{:ok cleaned}` on success
   or `{:error reason}` when a guard fires.

   Steps:
     1. Strip HTML + truncate to `:max-snippet-bytes`.
     2. Self-activation scan (when `:block-self-activation?`).
     3. Exfiltration-pattern scan (when
        `:block-exfiltration-patterns?`)."
  [snippet config]
  (let [cfg      (merge default-config config)
        max-b    (get cfg :max-snippet-bytes (:max-snippet-bytes default-config))
        stripped (strip-html (str snippet) max-b)
        cleaned  (:text stripped)]
    (cond
      (and (:block-self-activation? cfg)
           (re-find self-activation-re cleaned))
      {:error "snippet contains self-activation marker"}

      (and (:block-exfiltration-patterns? cfg)
           (re-find exfil-re cleaned))
      {:error "snippet matches exfiltration pattern"}

      :else
      {:ok cleaned})))

;; ---------------------------------------------------------------------------
;; guard-results
;; ---------------------------------------------------------------------------

(defn guard-results
  "Filter a sequence of search-result maps through the URL and
   snippet guards. Each result is expected to have `:url` and
   `:snippet` keys. Results whose `:url` fails `validate-url` or
   whose `:snippet` fails `sanitize-snippet` are dropped; surviving
   results are returned with their `:title`, `:url`, and `:snippet`
   preserved (snippet cleaned by `sanitize-snippet` when it survives)."
  [results config]
  (keep
    (fn [r]
      (let [{:keys [allow? reason]} (validate-url (:url r) config)
            ok-url? allow?
            snippet-resp (sanitize-snippet (str (:snippet r)) config)
            ok-snip? (some? (:ok snippet-resp))]
        (when (and ok-url? ok-snip?)
          {:title   (:title r)
           :url     (:url r)
           :snippet (or (:ok snippet-resp) "")})))
    results))