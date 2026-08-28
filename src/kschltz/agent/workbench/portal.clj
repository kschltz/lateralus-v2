(ns kschltz.agent.workbench.portal
  "Portal host adapter for the workbench. Isolates djblue/portal behind
   helpers with Malli-shaped I/O at the workbench boundary.

   Visualization is driven through a watched atom so the iframe always
  reflects the latest `portal_submit` (not only tap/submit side-channel)."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [kschltz.agent.workbench.schemas :as schemas]))

(def max-value-chars
  "Soft cap so models retry smaller payloads instead of bleeding tool XML."
  100000)

(defn available?
  "True when djblue/portal is on the classpath."
  []
  (try
    (requiring-resolve 'portal.api/open)
    true
    (catch Throwable _ false)))

(defn- preview-of
  [value]
  (let [s (pr-str value)]
    (if (> (count s) 160)
      (str (subs s 0 157) "...")
      s)))

(defn- stringify-keys
  [x]
  (walk/postwalk
   (fn [node]
     (if (map? node)
       (into {}
             (map (fn [[k v]]
                    [(cond
                       (keyword? k) (name k)
                       (string? k)  k
                       :else        (str k))
                     v]))
             node)
       node))
   x))

(defn- esc-html
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn coerce-value
  "Turn LLM tool args into visualizable Clojure data.
   Models often pass JSON as a string; parse it when possible.
   Bare prose strings are left alone (edn would turn them into symbols)."
  [value]
  (cond
    (not (string? value)) value
    (str/blank? value)    value
    :else
    (let [s (str/trim value)]
      (cond
        (re-find #"(?i)^<!DOCTYPE\s+html|^<html[\s>]|^<(div|section|main|article|style|body|svg|pre|code)\b" s)
        value

        (re-find #"^[\[\{]" s)
        (or (try (json/parse-string s true) (catch Throwable _))
            (try (edn/read-string s) (catch Throwable _))
            value)

        (re-find #"^[\"\(\#\\]" s)
        (or (try (edn/read-string s) (catch Throwable _))
            value)

        :else value))))

(defn- tableish?
  [value]
  (and (sequential? value)
       (seq value)
       (every? map? value)))

(defn- htmlish?
  [value]
  (and (string? value)
       (let [s (str/trim value)]
         (or (re-find #"(?i)^<!DOCTYPE\s+html" s)
             (re-find #"(?i)^<html[\s>]" s)
             (re-find #"(?i)^<(div|section|main|article|style|body|svg)\b" s)
             (and (re-find #"(?i)<style[\s>]" s)
                  (re-find #"(?i)</?(div|section|body|html)\b" s))))))

(defn- markdownish?
  [value]
  (and (string? value)
       (let [s (str/trim value)]
         (or (re-find #"^#{1,6}\s+\S" s)
             (re-find #"^```" s)
             (re-find #"\n#{1,6}\s+\S" s)))))

(defn- hiccupish?
  [value]
  (and (vector? value)
       (keyword? (first value))))

(defn vega-lite-spec?
  "True for maps that look like Vega-Lite (keyword or string keys)."
  [value]
  (and (map? value)
       (let [schema (or (get value :$schema) (get value "$schema"))
             mark   (or (get value :mark) (get value "mark"))
             enc    (or (get value :encoding) (get value "encoding"))]
         (or (some-> schema str (str/includes? "vega-lite"))
             (and (some? mark) (some? enc))))))

(defn- codeish?
  [value]
  (and (string? value)
       (not (htmlish? value))
       (not (markdownish? value))
       (let [s (str/trim value)]
         (and (re-find #"\n" s)
              (or (re-find #"(?m)^(def |defn |function |const |let |var |class |import |export |#include)" s)
                  (re-find #"(?m)^\s*[{};]\s*$" s)
                  (re-find #"(?i)^(css|scss|html|js|ts|clj|edn):\n" s))))))

(defn vega-lite->html-doc
  "Wrap a Vega-Lite spec in a self-contained HTML page (Portal html viewer).
   Native `:portal.viewer/vega-lite` is unreliable with keywordized maps."
  [spec]
  (let [payload (json/generate-string (stringify-keys spec))]
    (str "<!DOCTYPE html>\n"
         "<html lang=\"en\"><head><meta charset=\"utf-8\"/>"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
         "<script src=\"https://cdn.jsdelivr.net/npm/vega@5\"></script>"
         "<script src=\"https://cdn.jsdelivr.net/npm/vega-lite@5\"></script>"
         "<script src=\"https://cdn.jsdelivr.net/npm/vega-embed@6\"></script>"
         "<style>html,body{margin:0;background:#111;color:#eee;"
         "font-family:system-ui,sans-serif}#vis{padding:12px}</style>"
         "</head><body><div id=\"vis\"></div><script>"
         "vegaEmbed('#vis'," payload ",{actions:false}).catch(console.error);"
         "</script></body></html>")))

(defn- portal-view
  [viewer-sym value]
  (let [kw (keyword "portal.viewer" (name viewer-sym))]
    (if-let [f (try (requiring-resolve viewer-sym) (catch Throwable _ nil))]
      (try
        (f value)
        (catch Throwable _
          value))
      ;; Portal not on the classpath (fast test suite): still tag the
      ;; intended viewer so the viz atom carries rich-surface metadata.
      (if (instance? clojure.lang.IObj value)
        (with-meta value {:portal.viewer/default kw})
        value))))

(defn detect-viewer
  "Logical viewer name for tool results / UI hints."
  [value]
  (cond
    (htmlish? value)        "html"
    (markdownish? value)    "markdown"
    (hiccupish? value)      "hiccup"
    (vega-lite-spec? value) "vega-html"
    (tableish? value)       "table"
    (and (map? value)
         (sequential? (:data value))
         (every? map? (:data value)))
    "table"
    (codeish? value)        "code"
    :else                   "inspector"))

(defn with-default-viewer
  "Pick a Portal default viewer for rich artifacts (html, table, etc.)."
  [value]
  (cond
    (htmlish? value)
    (portal-view 'portal.viewer/html value)

    (markdownish? value)
    (portal-view 'portal.viewer/markdown value)

    (hiccupish? value)
    (portal-view 'portal.viewer/hiccup value)

    (tableish? value)
    (portal-view 'portal.viewer/table value)

    (and (map? value)
         (sequential? (:data value))
         (every? map? (:data value)))
    (portal-view 'portal.viewer/table value)

    (codeish? value)
    (portal-view 'portal.viewer/code value)

    :else value))

(defn- normalize-kind
  [kind]
  (when kind
    (-> (cond
          (keyword? kind) (name kind)
          (string? kind)  kind
          :else           (str kind))
        str/lower-case
        keyword)))

(defn prepare-value
  "Coerce + normalize a tool `value` before Portal submit.
   Returns {:value :viewer} or {:error {:ok false :error ...}}."
  ([value] (prepare-value value nil))
  ([value {:keys [kind]}]
   (let [kind*   (normalize-kind kind)
         coerced (coerce-value value)
         prepared
         (case kind*
           :html
           (cond
             (string? coerced) coerced
             (hiccupish? coerced) coerced
             :else (str "<!DOCTYPE html><html><body><pre>"
                        (esc-html (pr-str coerced))
                        "</pre></body></html>"))

           :markdown
           (str (if (string? coerced) coerced (pr-str coerced)))

           :table
           coerced

           :vega
           (cond
             (vega-lite-spec? coerced)
             (vega-lite->html-doc coerced)

             (and (string? coerced)
                  (vega-lite-spec? (coerce-value coerced)))
             (vega-lite->html-doc (coerce-value coerced))

             :else coerced)

           :code
           (str (if (string? coerced) coerced (pr-str coerced)))

           ;; :auto / nil — charts as HTML; never leave bare vega maps
           (cond
             (vega-lite-spec? coerced) (vega-lite->html-doc coerced)
             :else coerced))
         viewer (detect-viewer prepared)
         size   (count (if (string? prepared) prepared (pr-str prepared)))]
     (if (> size max-value-chars)
       {:error {:ok false
                :error "portal value too large"
                :max-chars max-value-chars
                :chars size
                :hint "Submit a smaller HTML/SVG doc, or one chart per call."}}
       {:value prepared
        :viewer viewer}))))

(defn- env-int
  [name]
  (try
    (some-> (System/getenv name) not-empty Integer/parseInt)
    (catch Exception _ nil)))

(defn- advertise-host
  [bind-host]
  (or (not-empty (System/getenv "LATERALUS_WORKBENCH_PUBLIC_HOST"))
      (when (#{"0.0.0.0" "::" "[::]"} (str bind-host)) "localhost")
      (not-empty (str bind-host))
      "localhost"))

(defn- rewrite-url-host
  "Replace host in `url` with `hostname` (keep scheme/port/path/query)."
  [url hostname]
  (if (or (str/blank? (str url)) (str/blank? (str hostname)))
    url
    (try
      (let [uri    (java.net.URI. (str url))
            scheme (or (.getScheme uri) "http")
            port   (.getPort uri)
            path   (let [p (.getRawPath uri)]
                     (if (str/blank? p) "" p))
            query  (.getRawQuery uri)]
        (str scheme "://" hostname
             (when (pos? port) (str ":" port))
             path
             (when query (str "?" query))))
      (catch Exception _
        url))))

(defn open!
  "Open a Portal session for visualization (no sticky composer).
   Returns {:portal :url :viz-atom}. opts keys validated as WorkbenchConfig subset.

   Bind host follows `:portal-host`, else `LATERALUS_WORKBENCH_HOST`, else the
   CHAT `:host`, else loopback — so a Tailscale-reachable CHAT bind also opens
   Portal on that interface. The iframe URL host is rewritten per-request from
   the browser Host header (see `http/portal-url-for-request`);
   `LATERALUS_WORKBENCH_PUBLIC_HOST` only seeds the startup advertise URL."
  [opts]
  (schemas/decode-config (select-keys (or opts {})
                                      [:enabled? :host :port :portal-port :portal-host
                                       :portal? :open-browser?
                                       :app :window-title :open?]))
  (let [open     (requiring-resolve 'portal.api/open)
        url      (requiring-resolve 'portal.api/url)
        viz-atom (atom {:lateralus/workbench "ready"
                        :hint "Use portal_submit for HTML/SVG charts, tables, demos — chat stays thin."})
        portal-port (or (:portal-port opts) (env-int "LATERALUS_PORTAL_PORT"))
        ;; Prefer an explicit portal bind, then the shared workbench bind, then
        ;; the CHAT host. Loopback last — remote CHAT (0.0.0.0 / Tailscale)
        ;; must not leave Portal stranded on 127.0.0.1.
        portal-host (or (not-empty (:portal-host opts))
                        (not-empty (System/getenv "LATERALUS_WORKBENCH_HOST"))
                        (not-empty (:host opts))
                        "127.0.0.1")
        p        (open (cond-> {:window-title (or (:window-title opts) "lateralus portal")
                                :value        viz-atom
                                ;; Workbench embeds Portal in an iframe — never
                                ;; spawn a separate browser window.
                                :launcher     false}
                         (contains? opts :app) (assoc :app (:app opts))
                         (:theme opts)         (assoc :theme (:theme opts))
                         portal-port           (assoc :port portal-port)
                         portal-host           (assoc :host portal-host)))
        raw-url  (try (url p) (catch Throwable _ nil))
        adv-host (advertise-host portal-host)
        ;; Prefer Portal's own URL (includes ?<session-uuid>), but advertise
        ;; on the public host / configured port. Dropping the session id used
        ;; to break the iframe (empty Portal session + unreachable :7870).
        pub-url  (let [session (when raw-url
                                 (try (.getRawQuery (java.net.URI. (str raw-url)))
                                      (catch Exception _ nil)))
                       port    (cond
                                 (and portal-port (pos? (long portal-port)))
                                 (long portal-port)
                                 raw-url
                                 (try (let [p (.getPort (java.net.URI. (str raw-url)))]
                                        (when (pos? p) p))
                                      (catch Exception _ nil)))]
                   (when (or port session)
                     (str "http://" adv-host
                          (when port (str ":" port))
                          (when session (str "?" session)))))]
    {:portal   p
     :url      pub-url
     :viz-atom viz-atom}))

(defn close!
  [portal]
  (when portal
    (try
      ((requiring-resolve 'portal.api/close) portal)
      (catch Throwable _))))

(defn submit!
  "Push `value` into Portal's watched viz atom and also `portal.api/submit`.
   Returns {:ok true :preview :viewer} or an error map.
   opts: :kind (html|table|vega|…) and/or :prepared? true to skip prepare."
  ([portal viz-atom label value]
   (submit! portal viz-atom label value nil))
  ([portal viz-atom label value {:keys [kind prepared?] :as opts}]
   (when-not portal
     (throw (ex-info "Portal is not open" {:label label})))
   (let [prep (if prepared?
                {:value value :viewer (detect-viewer value)}
                (prepare-value value {:kind kind}))]
     (if-let [err (:error prep)]
       err
       (let [prepared  (:value prep)
             viewer    (:viewer prep)
             decorated (with-default-viewer prepared)]
         (when viz-atom
           (reset! viz-atom decorated))
         (try
           ((requiring-resolve 'portal.api/submit) decorated)
           (catch Throwable _))
         {:ok true
          :label (or label "value")
          :viewer viewer
          :preview (preview-of prepared)})))))

(defn clear!
  [portal viz-atom]
  (when portal
    (try
      ((requiring-resolve 'portal.api/clear) portal)
      (catch Throwable _)))
  (when viz-atom
   (reset! viz-atom {:lateralus/workbench "ready"
                     :hint "Use portal_submit for HTML/SVG charts, tables, demos — chat stays thin."}))
  {:ok true})

(defn selected
  "Current Portal selection, or nil."
  [portal]
  (when portal
    (try
      ((requiring-resolve 'portal.api/selected) portal)
      (catch Throwable _
        nil))))
