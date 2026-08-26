(ns kschltz.agent.tools.factory.compile
  "In-process ToolCompiler: EDN schema + eval of invoke / interceptor fns.

   Uses Clojure 1.12 `add-libs` (via `ClojureRuntime`) when a spec names
   extra Maven/Git coords. Reader-eval is off; evaluation is explicit."
  (:require [clojure.edn :as edn]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.protocol :as proto]
            [kschltz.agent.tools.runtime.jvm :as jvm]
            [kschltz.agent.tools.runtime.protocol :as runtime]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.io PushbackReader StringReader]))

(defn- read-form
  "Read one EDN/Clojure form with `*read-eval*` disabled."
  [source]
  (binding [*read-eval* false]
    (edn/read {:eof nil :readers *data-readers*}
              (PushbackReader. (StringReader. (str source))))))

(defn parse-input-schema
  "Parse an EDN Malli schema string. Throws `ex-info` `{:phase :compile}`."
  [edn-str]
  (let [form (try (read-form edn-str)
                  (catch Throwable t
                    (throw (ex-info (str "input-schema is not readable EDN: "
                                         (ex-message t))
                                    {:phase :compile} t))))]
    (when (nil? form)
      (throw (ex-info "input-schema EDN is empty" {:phase :compile})))
    (try
      (m/schema form)
      (catch Throwable t
        (throw (ex-info (str "input-schema is not a valid Malli schema: "
                             (ex-message t))
                        {:phase :compile} t))))
    form))

(defn parse-coords
  "EDN map of lib-symbol → coordinate map, or nil when `s` is blank."
  [s]
  (when (and (string? s) (seq s))
    (let [parsed (try (read-form s)
                      (catch Throwable t
                        (throw (ex-info (str "libs is not readable EDN: "
                                             (ex-message t))
                                        {:phase :compile} t))))]
      (when-not (map? parsed)
        (throw (ex-info "libs must be an EDN map of lib -> coordinate map"
                        {:phase :compile})))
      (into {}
            (map (fn [[k v]]
                   [(if (symbol? k) k (symbol (name k))) v]))
            parsed))))

(defn compile-fn
  "Evaluate `source` to an IFn. Throws `ex-info` `{:phase :compile}`."
  [source]
  (let [form (try (read-form source)
                  (catch Throwable t
                    (throw (ex-info (str "function source is not readable: "
                                         (ex-message t))
                                    {:phase :compile} t))))
        v (try (eval form)
               (catch Throwable t
                 (throw (ex-info (str "function source failed to evaluate: "
                                      (ex-message t))
                                 {:phase :compile} t))))]
    (when-not (ifn? v)
      (throw (ex-info "function source must evaluate to a function"
                      {:phase :compile})))
    v))

(defn- stringify
  [ret]
  (if (string? ret) ret (pr-str ret)))

(deftype RuntimeDefinedTool [spec invoke-fn schema]
  tool/Tool
  (-name [_] (:name spec))
  (-description [_] (:description spec))
  (-input-schema [_] schema)
  (-output-schema [_] :string)
  (-invoke [_ args ctx]
    (stringify (invoke-fn args ctx))))

(defn- compile-interceptor
  [spec]
  (when (some spec [:interceptor-enter :interceptor-leave :interceptor-error])
    (when-not (:interceptor-slot spec)
      (throw (ex-info "interceptor fn requires :interceptor-slot"
                      {:phase :compile})))
    (cond-> {:name (keyword "kschltz.agent.tools.factory.runtime"
                            (:name spec))
             :slot (:interceptor-slot spec)}
      (:interceptor-enter spec)
      (assoc :enter (compile-fn (:interceptor-enter spec)))
      (:interceptor-leave spec)
      (assoc :leave (compile-fn (:interceptor-leave spec)))
      (:interceptor-error spec)
      (assoc :error (compile-fn (:interceptor-error spec))))))

(defn- require-form
  [{:keys [require alias]}]
  (when (seq require)
    (if (seq alias)
      (format "(require '[%s :as %s])" require alias)
      (format "(require '[%s])" require))))

(defn- compile-spec*
  [runtime spec]
  (when-not (proto/valid-tool-spec? spec)
    (throw (ex-info (str "invalid tool spec: "
                         (pr-str (m/explain proto/ToolSpec spec)))
                    {:phase :compile})))
  (when-let [coords (parse-coords (:libs spec))]
    (let [added (runtime/-add-libs runtime coords {})]
      (when (= :error (:status added))
        (throw (ex-info (or (:error added) "add-libs failed")
                        {:phase :add-libs
                         :result added})))))
  (when-let [req (require-form spec)]
    (let [evaled (runtime/-eval runtime req {})]
      (when (contains? #{:error :timeout} (:status evaled))
        (throw (ex-info (or (:error evaled) "require failed")
                        {:phase :require
                         :result evaled})))))
  (let [schema (parse-input-schema (:input-schema spec))
        invoke-fn (compile-fn (:invoke spec))
        tool (->RuntimeDefinedTool spec invoke-fn schema)
        interceptor (compile-interceptor spec)]
    (cond-> {:ok true :tool tool :spec spec}
      interceptor (assoc :interceptor interceptor))))

(defn- fail-result
  [^Throwable t]
  (let [phase (or (:phase (ex-data t)) :compile)]
    {:ok false
     :error (or (ex-message t) (.getName (class t)))
     :phase (if (keyword? phase) (name phase) (str phase))
     :class (.getName (class t))}))

(deftype JvmToolCompiler [runtime]
  proto/ToolCompiler
  (-compile-spec [_ spec]
    (try
      (compile-spec* runtime spec)
      (catch Throwable t
        (fail-result t))))
  (-add-libs [_ coords]
    (runtime/-add-libs runtime coords {})))

(defn jvm-compiler
  "ToolCompiler backed by `runtime` (default in-process `JvmRuntime`)."
  ([] (jvm-compiler nil))
  ([runtime]
   (->JvmToolCompiler
    (if (runtime/capabilities? runtime)
      runtime
      (jvm/jvm-runtime (or runtime {}))))))

(m/=> parse-input-schema [:=> [:cat :string] :any])
(m/=> compile-fn [:=> [:cat :string] fn?])
(m/=> parse-coords [:=> [:cat [:maybe :string]] [:maybe :map]])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.factory.compile)]}))

(instrument!)
