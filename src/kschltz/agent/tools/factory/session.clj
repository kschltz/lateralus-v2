(ns kschltz.agent.tools.factory.session
  "In-process RuntimeToolStore: compile, overlay, rehydrate, promote."
  (:require [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.compile :as compile]
            [kschltz.agent.tools.factory.promote :as promote]
            [kschltz.agent.tools.factory.protocol :as proto]
            [malli.core :as m]
            [malli.instrument :as mi]))

(defn- raise
  [phase msg data]
  (throw (ex-info msg (merge {:phase phase} data))))

(defn- dynamic-enabled?
  [config]
  (not (false? (get-in config [:dynamic :enabled?]))))

(defn- resolve-registry
  [ns-sym]
  (when-let [f (ns-resolve (the-ns ns-sym) 'registry)]
    (let [reg (f)]
      (when (map? reg) reg))))

(defn- seed-promoted
  [compiler workspace-root]
  (reduce
   (fn [acc entry]
     (let [name (:name entry)
           ns-sym (some-> entry :ns symbol)
           path (:path entry)]
       (when (and path (.isFile (java.io.File. (str path))))
         (try (load-file (str path)) (catch Throwable _)))
       (let [from-ns (when ns-sym
                       (try (get (resolve-registry ns-sym) name)
                            (catch Throwable _ nil)))
             compiled (when (and (not (tool/tool? from-ns))
                                 (proto/valid-tool-spec? (:spec entry)))
                        (proto/-compile-spec compiler (:spec entry)))
             tool (or from-ns (:tool compiled))]
         (if (tool/tool? tool)
           (assoc acc name {:spec (:spec entry)
                            :tool tool
                            :interceptor (:interceptor compiled)
                            :promoted? true
                            :entry entry})
           acc))))
   {}
   (promote/read-catalog workspace-root)))

(defn- registry-of
  [entries]
  (into {}
        (keep (fn [[name {:keys [tool]}]]
                (when (tool/tool? tool)
                  [name tool])))
        entries))

(defn- interceptors-of
  [entries slot]
  (into []
        (keep (fn [[_ {:keys [interceptor]}]]
                (when (= slot (:slot interceptor))
                  interceptor)))
        entries))

(deftype FactorySession [config compiler state]
  proto/RuntimeToolStore
  (-define! [_ spec opts]
    (when-not (dynamic-enabled? config)
      (raise :disabled
             "Dynamic tool factory is disabled; set :dynamic {:enabled? true} on :lateralus/factory-session"
             {}))
    (when-not (proto/valid-tool-spec? spec)
      (raise :compile "invalid tool spec" {:spec spec}))
    (let [reserved (set (or (:reserved-names opts) []))
          name (:name spec)]
      (when (contains? reserved name)
        (raise :collision
               (str "tool name collides with a built-in tool: " name)
               {:name name}))
      (let [compiled (proto/-compile-spec compiler spec)]
        (when-not (:ok compiled)
          (raise (keyword (or (:phase compiled) "compile"))
                 (or (:error compiled) "compile failed")
                 compiled))
        (swap! state assoc-in [:ephemeral name]
               {:spec spec
                :tool (:tool compiled)
                :interceptor (:interceptor compiled)
                :promoted? false})
        {:ok true
         :tool-name name
         :tools [name]
         :tool-count 1})))

  (-forget! [_ tool-name]
    (let [had? (or (get-in @state [:ephemeral tool-name])
                   (get-in @state [:promoted tool-name]))]
      (swap! state (fn [st]
                     (-> st
                         (update :ephemeral dissoc tool-name)
                         (update :promoted dissoc tool-name))))
      {:ok true
       :tool-name tool-name
       :removed (boolean had?)}))

  (-record-test! [_ tool-name tested-spec-id]
    (when-not (dynamic-enabled? config)
      (raise :disabled
             "Dynamic tool factory is disabled; set :dynamic {:enabled? true} on :lateralus/factory-session"
             {}))
    (let [entry (get-in @state [:ephemeral tool-name])
          current-spec-id (some-> entry :spec proto/spec-id)]
      (when-not entry
        (raise :unknown (str "unknown ephemeral runtime tool: " tool-name)
               {:tool-name tool-name}))
      (when-not (= current-spec-id tested-spec-id)
        (raise :stale-test
               (str "tool_test result is stale for current spec: " tool-name)
               {:tool-name tool-name}))
      (swap! state assoc-in [:ephemeral tool-name :tested-spec-id]
             tested-spec-id)
      {:ok true :tool-name tool-name :tested true}))

  (-promote! [_ tool-name opts]
    (when-not (dynamic-enabled? config)
      (raise :disabled
             "Dynamic tool factory is disabled; set :dynamic {:enabled? true} on :lateralus/factory-session"
             {}))
    (let [entry (or (get-in @state [:ephemeral tool-name])
                    (get-in @state [:promoted tool-name]))
          spec (:spec entry)]
      (when-not spec
        (raise :unknown (str "unknown runtime tool: " tool-name)
               {:tool-name tool-name}))
      (when (and (get-in @state [:ephemeral tool-name])
                 (not= (proto/spec-id spec) (:tested-spec-id entry)))
        (raise :untested
               (str "runtime tool must pass tool_test before promotion: "
                    tool-name)
               {:tool-name tool-name}))
      (let [status (promote/promote-spec
                    spec
                    {:workspace-root (or (:workspace-root opts)
                                         (:workspace-root config)
                                         ".")
                     :target (or (:target opts) :workspace)
                     :as-plugin (boolean (:as-plugin opts))})
            compiled (proto/-compile-spec compiler spec)
            live (cond-> {:spec spec
                          :tool (or (:tool compiled) (:tool entry))
                          :promoted? true
                          :entry (:entry status)}
                   (:interceptor compiled)
                   (assoc :interceptor (:interceptor compiled))
                   (:interceptor entry)
                   (assoc :interceptor (:interceptor entry)))]
        (swap! state (fn [st]
                       (-> st
                           (update :ephemeral dissoc tool-name)
                           (assoc-in [:promoted tool-name] live))))
        status)))

  (-registry [_]
    (let [st @state]
      (merge (registry-of (:promoted st))
             (registry-of (:ephemeral st)))))

  (-interceptors [_ slot]
    (let [st @state]
      (into (interceptors-of (:promoted st) slot)
            (interceptors-of (:ephemeral st) slot))))

  (-specs [_]
    (into {}
          (keep (fn [[name {:keys [spec]}]]
                  (when spec [name spec])))
          (:ephemeral @state)))

  (-status [_]
    (let [st @state]
      {:dynamic-enabled? (dynamic-enabled? config)
       :ephemeral (vec (sort (keys (:ephemeral st))))
       :tested (->> (:ephemeral st)
                    (keep (fn [[name {:keys [spec tested-spec-id]}]]
                            (when (= tested-spec-id (proto/spec-id spec))
                              name)))
                    sort
                    vec)
       :promoted (vec (sort (keys (:promoted st))))
       :tool-count (+ (count (:ephemeral st)) (count (:promoted st)))}))

  (-rehydrate! [_ specs]
    (let [specs (or specs {})
          existing (set (concat (keys (:ephemeral @state))
                                (keys (:promoted @state))))
          results (reduce
                   (fn [acc [name spec]]
                     (if (contains? existing name)
                       acc
                       (let [compiled (proto/-compile-spec compiler spec)]
                         (if (:ok compiled)
                           (do
                             (swap! state assoc-in [:ephemeral name]
                                    {:spec spec
                                     :tool (:tool compiled)
                                     :interceptor (:interceptor compiled)
                                     :promoted? false})
                             (update acc :rehydrated conj name))
                           (update acc :errors conj
                                   {:name name
                                    :error (:error compiled)})))))
                   {:rehydrated [] :errors []}
                   specs)
          ;; Remember the failures so the UI/model can be told — these
          ;; used to be swallowed, which made tool_define look like a
          ;; fake success (define ok, tool silently missing forever).
          errors (:errors results)]
      (swap! state assoc :last-compile-errors errors)
      (assoc results :ok (empty? errors))))

  (-dynamic-enabled? [_]
    (dynamic-enabled? config)))

(defn factory-session
  "Build a `RuntimeToolStore`.

   `config` keys: `:workspace-root`, `:dynamic {:enabled? true}`,
   optional `:compiler` / `:runtime` test seams."
  ([] (factory-session {}))
  ([config]
   (let [config (or config {})
         compiler (cond
                    (proto/tool-compiler? (:compiler config))
                    (:compiler config)
                    :else
                    (compile/jvm-compiler (:runtime config)))
         workspace (or (:workspace-root config) ".")
         promoted (try (seed-promoted compiler workspace)
                       (catch Throwable _ {}))]
     (->FactorySession config compiler
                       (atom {:ephemeral {}
                              :promoted promoted})))))

(m/=> factory-session [:=> [:cat [:maybe :map]] [:fn proto/runtime-tool-store?]])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.factory.session)]}))

(instrument!)
