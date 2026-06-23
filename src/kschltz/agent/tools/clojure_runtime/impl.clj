(ns kschltz.agent.tools.clojure-runtime.impl
  "Default ClojureRuntime implementation.

   Maintains per-session evaluation namespaces and uses Clojure 1.12
   `clojure.repl.deps` for dynamic classpath extension. A
   DynamicClassLoader is ensured on the current thread before any
   dependency operation."
  (:require [clojure.edn :as edn]
            [clojure.repl.deps :as repl-deps]
            [clojure.string :as str]
            [kschltz.agent.tools.clojure-runtime.protocol :as protocol])
  (:import [clojure.lang DynamicClassLoader LineNumberingPushbackReader]
           [java.io StringReader]))

(defn- validation-error [phase explain]
  (ex-info "Clojure runtime input validation failed"
           {:phase phase :problems explain}))

(defn- assert-valid! [schema value phase]
  (when-let [problems (protocol/explain schema value)]
    (throw (validation-error phase problems))))

(defn- ensure-dynamic-classloader!
  "Ensure the current thread has a DynamicClassLoader so add-lib works."
  []
  (let [^ClassLoader cl (.getContextClassLoader (Thread/currentThread))]
    (when-not (instance? DynamicClassLoader cl)
      (let [dcl (DynamicClassLoader. cl)]
        (.setContextClassLoader (Thread/currentThread) dcl)
        dcl))))

(defn- with-repl-deps
  "Run `f` with DynamicClassLoader and *repl* bound for tools.deps."
  [f]
  (ensure-dynamic-classloader!)
  (binding [*repl* true]
    (f)))

(defn- session-ns-sym
  "Derive a stable, valid namespace symbol for `session-id`."
  [session-id]
  (symbol "lateralus.agent.repl"
          (str/replace session-id #"[^a-zA-Z0-9_]" "_")))

(defn- init-eval-ns!
  "Create `ns-sym` and refer clojure.core for eval."
  [ns-sym]
  (binding [*ns* (create-ns ns-sym)]
    (clojure.core/refer-clojure))
  ns-sym)

(defn- ensure-session-ns!
  "Create or return the eval namespace for `session-id`."
  [sessions session-id]
  (get (swap! sessions
              (fn [m]
                (if (contains? m session-id)
                  m
                  (let [ns-sym (init-eval-ns! (session-ns-sym session-id))]
                    (assoc m session-id {:ns-sym ns-sym})))))
       session-id))

(defn- read-forms
  "Read all top-level forms from `code`, or throw :phase :parse."
  [code]
  (let [r (LineNumberingPushbackReader. (StringReader. code))]
    (loop [forms []]
      (let [form (try
                   (clojure.core/read r false ::eof)
                   (catch Throwable t
                     (throw (ex-info (str "Failed to read Clojure form: " (ex-message t))
                                     {:phase :parse}
                                     t))))]
        (if (= form ::eof)
          (vec forms)
          (recur (conj forms form)))))))

(defn- value-type [v]
  (cond
    (nil? v) "nil"
    (string? v) "string"
    (number? v) "number"
    (boolean? v) "boolean"
    (keyword? v) "keyword"
    (symbol? v) "symbol"
    (map? v) "map"
    (vector? v) "vector"
    (list? v) "list"
    (set? v) "set"
    (sequential? v) "sequential"
    :else (.getName (class v))))

(defn- eval-forms
  "Evaluate `forms` in `ns-sym`, capturing stdout."
  [ns-sym forms]
  (let [form-count (count forms)]
    (binding [*ns* (create-ns ns-sym)
              *file* "<clojure/eval>"]
      (loop [forms forms
             last-value nil
             stdout (StringBuilder.)]
        (if (empty? forms)
          {:value last-value
           :stdout (str stdout)
           :forms-evaluated form-count}
          (let [form (first forms)
                out-writer (java.io.StringWriter.)
                value (binding [*out* (java.io.PrintWriter. out-writer true)]
                        (eval form))
                chunk (.toString out-writer)]
            (when (pos? (count chunk))
              (.append stdout chunk))
            (recur (rest forms) value stdout)))))))

(defn- lib-symbol [lib-str]
  (symbol lib-str))

(defn- libs->symbols [libs]
  (mapv (comp str lib-symbol) libs))

(defn- read-deps-libs
  "Read :deps (and optional alias :deps) from a deps.edn file."
  [deps-edn-path aliases]
  (let [basis (edn/read-string (slurp deps-edn-path))
        alias-deps (when (seq aliases)
                     (apply merge {}
                            (keep (fn [a]
                                    (get-in basis [:aliases a :deps]))
                                  aliases)))]
    (merge (:deps basis {}) alias-deps)))

(defn- default-deps-edn-path
  "Return a project deps.edn path when present."
  []
  (when (.exists (java.io.File. "deps.edn"))
    "deps.edn"))

(deftype DefaultClojureRuntime [enabled? sessions deps-edn-path max-code-bytes]
  protocol/ClojureRuntime
  (-eval [_ input]
    (assert-valid! protocol/EvalInput input :validation)
    (when-not enabled?
      (throw (ex-info "Clojure runtime eval is disabled in config"
                      {:phase :disabled})))
    (let [{:keys [session-id code ns]} input]
      (when (> (count code) max-code-bytes)
        (throw (ex-info (str "Code exceeds max size of " max-code-bytes " bytes")
                        {:phase :validation :max-code-bytes max-code-bytes})))
      (let [{:keys [ns-sym]} (ensure-session-ns! sessions session-id)
            target-ns (if (and ns (not (str/blank? ns)))
                        (init-eval-ns! (symbol ns))
                        ns-sym)
            forms (read-forms code)]
        (when (empty? forms)
          (throw (ex-info "No Clojure forms found in code"
                          {:phase :parse})))
        (try
          (let [{:keys [value stdout forms-evaluated]}
                (eval-forms target-ns forms)]
            {:value (pr-str value)
             :type (value-type value)
             :stdout (when (not (str/blank? stdout)) stdout)
             :ns (str target-ns)
             :forms-evaluated forms-evaluated})
          (catch Throwable t
            (throw (ex-info (str "Evaluation failed: " (ex-message t))
                            {:phase :eval :ns (str target-ns)}
                            t)))))))

  (-add-lib [_ input]
    (assert-valid! protocol/AddLibInput input :validation)
    (when-not enabled?
      (throw (ex-info "Clojure runtime eval is disabled in config"
                      {:phase :disabled})))
    (ensure-session-ns! sessions (:session-id input))
    (let [{:keys [session-id lib coord]} input
          lib-sym (lib-symbol lib)]
      (try
        (let [loaded (with-repl-deps
                       #(if coord
                          (repl-deps/add-lib lib-sym coord)
                          (repl-deps/add-lib lib-sym)))]
          {:session-id session-id
           :libs (libs->symbols (or loaded [lib-sym]))})
        (catch Throwable t
          (throw (ex-info (str "add-lib failed: " (ex-message t))
                          {:phase :deps :lib lib}
                          t))))))

  (-add-libs [_ input]
    (assert-valid! protocol/AddLibsInput input :validation)
    (when-not enabled?
      (throw (ex-info "Clojure runtime eval is disabled in config"
                      {:phase :disabled})))
    (ensure-session-ns! sessions (:session-id input))
    (let [{:keys [session-id libs]} input
          lib-coords (into {}
                           (map (fn [[k v]] [(lib-symbol k) v])
                                libs))]
      (try
        (let [loaded (with-repl-deps #(repl-deps/add-libs lib-coords))]
          {:session-id session-id
           :libs (libs->symbols (or loaded (keys lib-coords)))})
        (catch Throwable t
          (throw (ex-info (str "add-libs failed: " (ex-message t))
                          {:phase :deps :libs (keys libs)}
                          t))))))

  (-sync-deps [this input]
    (assert-valid! protocol/SyncDepsInput input :validation)
    (when-not enabled?
      (throw (ex-info "Clojure runtime eval is disabled in config"
                      {:phase :disabled})))
    (ensure-session-ns! sessions (:session-id input))
    (let [{:keys [session-id aliases deps-edn-path]} input
          basis-path (or deps-edn-path (.-deps-edn-path this) (default-deps-edn-path))]
      (when (or (nil? basis-path) (not (.exists (java.io.File. basis-path))))
        (throw (ex-info "No deps.edn file found for sync-deps"
                        {:phase :deps :deps-edn-path basis-path})))
      (try
        (let [lib-coords (read-deps-libs basis-path aliases)
              loaded (with-repl-deps #(repl-deps/add-libs lib-coords))]
          {:session-id session-id
           :libs (libs->symbols (or loaded (keys lib-coords)))})
        (catch Throwable t
          (throw (ex-info (str "sync-deps failed: " (ex-message t))
                          {:phase :deps}
                          t))))))

  (-reset [_ input]
    (assert-valid! protocol/ResetInput input :validation)
    (let [{:keys [session-id]} input]
      (when-let [{:keys [ns-sym]} (get @sessions session-id)]
        (remove-ns ns-sym))
      (swap! sessions dissoc session-id)
      {:session-id session-id
       :reset? true})))

(defn runtime
  "Build a `DefaultClojureRuntime` from `opts`:

     :enabled?       — default true
     :deps-edn-path  — optional deps.edn for sync-deps
     :max-code-bytes — max eval code size (default 65536)"
  [{:keys [enabled? deps-edn-path max-code-bytes]
    :or {enabled? true
         max-code-bytes 65536}}]
  (->DefaultClojureRuntime enabled? (atom {}) deps-edn-path max-code-bytes))
