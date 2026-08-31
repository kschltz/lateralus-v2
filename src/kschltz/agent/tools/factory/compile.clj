(ns kschltz.agent.tools.factory.compile
  "ToolCompiler: schema + SCI sandbox or trusted in-process eval.

   Secret-enabled sessions force SCI with no host classes/context and reject
   dependencies, requires, and interceptors. Trusted non-secret sessions may
   use Clojure 1.12 `add-libs` (via `ClojureRuntime`) when a spec names extra
   Maven/Git coords. `*read-eval*` is off; evaluation is explicit.
   Invoke/interceptor bodies are read with the FULL Clojure reader (not
   `clojure.edn`) so `#(fn-literal)` and `#\"regex\"` forms — which
   model-written bodies routinely contain — parse. `clojure.edn` raises
   `No dispatch macro for: (` there and the tool then silently failed at
   every rehydrate (regression: sessions 675706dd / 92150f99)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.protocol :as proto]
            [kschltz.agent.tools.factory.sandbox :as sandbox]
            [kschltz.agent.tools.runtime.jvm :as jvm]
            [kschltz.agent.tools.runtime.protocol :as runtime]
            [malli.core :as m]
            [malli.instrument :as mi]
            [sci.core :as sci])
  (:import [java.io PushbackReader StringReader]))

(defn- read-form
  "Read one Clojure/EDN form with `*read-eval*` disabled. Uses the full
   reader (not `clojure.edn`) because invoke/interceptor bodies
   legitimately contain `#(fn-literal)` and `#\"regex\"` dispatch macros;
   `*read-eval*` false still blocks `#=` reader eval."
  [source]
  (binding [*read-eval* false]
    (read {:eof nil :readers *data-readers*}
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

(def ^:private sandbox-allow
  "Small pure-data surface available to model-authored SCI functions.
   JVM classes, I/O, eval, namespace mutation, futures, and host vars are
   absent. `call-tool` is the sole host capability."
  '[+
    -
    *
    /
    <
    <=
    =
    not=
    >
    >=
    and
    or
    not
    if
    if-not
    when
    when-not
    cond
    case
    let
    let*
    letfn
    fn
    fn*
    loop*
    do
    str
    pr-str
    assoc
    dissoc
    get
    get-in
    assoc-in
    update
    update-in
    merge
    select-keys
    map
    mapv
    filter
    remove
    reduce
    keep
    keep-indexed
    some
    every?
    identity
    constantly
    comp
    partial
    apply
    string?
    number?
    int?
    integer?
    keyword?
    symbol?
    map?
    vector?
    set?
    seq?
    sequential?
    coll?
    nil?
    some?
    boolean?
    count
    empty?
    not-empty
    seq
    first
    second
    nth
    rest
    next
    last
    peek
    pop
    conj
    into
    vec
    set
    keys
    vals
    contains?
    keyword
    name
    namespace
    symbol
    inc
    dec
    pos?
    neg?
    zero?
    min
    max
    mod
    quot
    rem
    subs
    re-find
    re-matches
    re-seq
    clojure.string/blank?
    clojure.string/lower-case
    clojure.string/upper-case
    clojure.string/trim
    clojure.string/split
    clojure.string/join
    clojure.string/replace
    clojure.string/includes?
    clojure.string/starts-with?
    clojure.string/ends-with?
    lateralus.runtime/call-tool])

(defn sandbox-compile-fn
  "Compile `source` with SCI. No host classes or context are exposed."
  [source]
  (try
    (let [value
          (sci/eval-string
           (str source)
           {:allow sandbox-allow
            :classes {}
            :namespaces
            {'lateralus.runtime {'call-tool sandbox/call-tool}}})]
      (when-not (ifn? value)
        (throw (ex-info "sandbox function source must evaluate to a function"
                        {:phase :sandbox})))
      value)
    (catch Throwable t
      (if (= :sandbox (:phase (ex-data t)))
        (throw t)
        (throw (ex-info
                (str "sandbox rejected function source: " (ex-message t))
                {:phase :sandbox}
                t))))))

(defn- stringify
  [ret]
  (if (string? ret) ret (pr-str ret)))

(deftype RuntimeDefinedTool [spec invoke-fn schema sandbox-config]
  tool/Tool
  (-name [_] (:name spec))
  (-description [_] (:description spec))
  (-input-schema [_] schema)
  (-output-schema [_] :string)
  (-invoke [_ args ctx]
    (stringify
     (if (:enabled? sandbox-config)
       (sandbox/invoke-sandboxed
        invoke-fn args ctx (set (:call-tools sandbox-config)))
       (try
         (invoke-fn args ctx)
         (catch clojure.lang.ArityException two-arity-error
           (try
             (invoke-fn args)
             (catch clojure.lang.ArityException _
               (throw two-arity-error))))))))
  tool/ToolTrust
  (-trust-tier [_] :untrusted-runtime))

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

(def ^:private sandbox-forbidden-spec-keys
  [:libs
   :require
   :alias
   :interceptor-slot
   :interceptor-enter
   :interceptor-leave
   :interceptor-error])

(defn- compile-spec*
  [runtime compiler-config spec]
  (when-not (proto/valid-tool-spec? spec)
    (throw (ex-info (str "invalid tool spec: "
                         (pr-str (m/explain proto/ToolSpec spec)))
                    {:phase :compile})))
  (let [sandbox-config (:sandbox compiler-config)
        sandbox? (true? (:enabled? sandbox-config))
        forbidden (when sandbox?
                    (vec (filter #(contains? spec %)
                                 sandbox-forbidden-spec-keys)))]
    (when (seq forbidden)
      (throw (ex-info
              (str "sandboxed runtime tools cannot use: " forbidden)
              {:phase :sandbox :keys forbidden}))))
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
  (let [sandbox-config (:sandbox compiler-config)
        sandbox? (true? (:enabled? sandbox-config))
        schema (parse-input-schema (:input-schema spec))
        invoke-fn ((if sandbox? sandbox-compile-fn compile-fn) (:invoke spec))
        tool (->RuntimeDefinedTool spec invoke-fn schema sandbox-config)
        interceptor (when-not sandbox? (compile-interceptor spec))]
    (cond-> {:ok true :tool tool :spec spec}
      interceptor (assoc :interceptor interceptor))))

(defn- fail-result
  [^Throwable t]
  (let [phase (or (:phase (ex-data t)) :compile)]
    {:ok false
     :error (or (ex-message t) (.getName (class t)))
     :phase (if (keyword? phase) (name phase) (str phase))
     :class (.getName (class t))}))

(deftype JvmToolCompiler [runtime config]
  proto/ToolCompiler
  (-compile-spec [_ spec]
    (try
      (compile-spec* runtime config spec)
      (catch Throwable t
        (fail-result t))))
  (-add-libs [_ coords]
    (runtime/-add-libs runtime coords {})))

(defn jvm-compiler
  "ToolCompiler backed by `runtime` (default in-process `JvmRuntime`)."
  ([] (jvm-compiler nil))
  ([runtime] (jvm-compiler runtime {}))
  ([runtime config]
   (let [config (or config {})
         config (if (contains? config :sandbox?)
                  {:sandbox {:enabled? (boolean (:sandbox? config))
                             :call-tools (set (or (:call-tools config) #{}))}}
                  config)]
     (->JvmToolCompiler
      (if (runtime/capabilities? runtime)
        runtime
        (jvm/jvm-runtime (or runtime {})))
      config))))

(m/=> parse-input-schema [:=> [:cat :string] :any])
(m/=> compile-fn [:=> [:cat :string] fn?])
(m/=> sandbox-compile-fn [:=> [:cat :string] fn?])
(m/=> parse-coords [:=> [:cat [:maybe :string]] [:maybe :map]])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.factory.compile)]}))

(instrument!)
