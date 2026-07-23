(ns kschltz.agent.workbench.portal
  "Portal host adapter for the workbench. Isolates djblue/portal behind
   helpers with Malli-shaped I/O at the workbench boundary.

   Visualization is driven through a watched atom so the iframe always
   reflects the latest `portal/submit` (not only tap/submit side-channel)."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kschltz.agent.workbench.schemas :as schemas]))

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
        ;; Keep HTML/markdown/code as strings for viewer selection.
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
  "True when `value` looks like rows suitable for Portal's table viewer."
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

(defn- vega-lite-spec?
  [value]
  (and (map? value)
       (or (some-> (:$schema value) str (str/includes? "vega-lite"))
           (and (contains? value :mark) (contains? value :encoding)))))

(defn- codeish?
  "Heuristic for multi-line source that is not HTML/markdown."
  [value]
  (and (string? value)
       (not (htmlish? value))
       (not (markdownish? value))
       (let [s (str/trim value)]
         (and (re-find #"\n" s)
              (or (re-find #"(?m)^(def |defn |function |const |let |var |class |import |export |#include)" s)
                  (re-find #"(?m)^\s*[{};]\s*$" s)
                  (re-find #"(?i)^(css|scss|html|js|ts|clj|edn):\n" s))))))

(defn- portal-view
  "Apply portal.viewer/* so strings (no metadata) still get the right UI."
  [viewer-sym value]
  (try
    ((requiring-resolve viewer-sym) value)
    (catch Throwable _
      value)))

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

    (vega-lite-spec? value)
    (portal-view 'portal.viewer/vega-lite value)

    (tableish? value)
    (portal-view 'portal.viewer/table value)

    (and (map? value)
         (sequential? (:data value))
         (every? map? (:data value)))
    (portal-view 'portal.viewer/table value)

    (codeish? value)
    (portal-view 'portal.viewer/code value)

    :else value))

(defn open!
  "Open a Portal session for visualization (no sticky composer).
   Returns {:portal :url :viz-atom}. opts keys validated as WorkbenchConfig subset."
  [opts]
  (schemas/decode-config (select-keys (or opts {})
                                      [:enabled? :host :port :portal? :open-browser?
                                       :app :window-title :open?]))
  (let [open     (requiring-resolve 'portal.api/open)
        url      (requiring-resolve 'portal.api/url)
        viz-atom (atom {:lateralus/workbench "ready"
                        :hint "Use portal/submit for HTML, tables, charts, code — chat stays thin."})
        p        (open (cond-> {:window-title (or (:window-title opts) "lateralus portal")
                                :value        viz-atom}
                         (contains? opts :app) (assoc :app (:app opts))
                         (:theme opts)         (assoc :theme (:theme opts))))]
    {:portal   p
     :url      (try (url p) (catch Throwable _ nil))
     :viz-atom viz-atom}))

(defn close!
  [portal]
  (when portal
    (try
      ((requiring-resolve 'portal.api/close) portal)
      (catch Throwable _))))

(defn submit!
  "Push `value` into Portal's watched viz atom and also `portal.api/submit`.
   Returns {:ok true :preview ...}.
   Rich visuals (html/table/…) become the atom root so viewers apply fully."
  [portal viz-atom label value]
  (when-not portal
    (throw (ex-info "Portal is not open" {:label label})))
  (let [coerced    (coerce-value value)
        decorated  (with-default-viewer coerced)
        ;; Root = decorated value so html/table/vega viewers win (not buried under :data).
        root       decorated]
    (when viz-atom
      (reset! viz-atom root))
    (try
      ((requiring-resolve 'portal.api/submit) decorated)
      (catch Throwable _))
    {:ok true
     :label (or label "value")
     :preview (preview-of coerced)}))

(defn clear!
  [portal viz-atom]
  (when portal
    (try
      ((requiring-resolve 'portal.api/clear) portal)
      (catch Throwable _)))
  (when viz-atom
    (reset! viz-atom {:lateralus/workbench "ready"
                      :hint "Use portal/submit for HTML, tables, charts, code — chat stays thin."}))
  {:ok true})

(defn selected
  "Current Portal selection, or nil."
  [portal]
  (when portal
    (try
      ((requiring-resolve 'portal.api/selected) portal)
      (catch Throwable _
        nil))))
