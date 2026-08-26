(ns kschltz.agent.tools.workflow.engine
  "Pure scheduler: ready set, cycle detection, wave execution."
  (:require [clojure.edn :as edn]
            [kschltz.agent.tool :as tool]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.io PushbackReader StringReader]))

(defn- read-form
  [source]
  (binding [*read-eval* false]
    (edn/read {:eof nil :readers *data-readers*}
              (PushbackReader. (StringReader. (str source))))))

(defn compile-eval-run
  "Evaluate `:eval` code to `(fn [store] artifact-map)`."
  [code]
  (let [form (read-form code)
        f (eval form)]
    (when-not (ifn? f)
      (throw (ex-info "run :eval code must evaluate to a function"
                      {:phase :compile})))
    f))

(defn action-satisfied?
  "True when every produced artifact is already in `store`."
  [action store]
  (every? #(contains? store %) (:produces action)))

(defn needs-met?
  [action store]
  (every? #(contains? store %) (:needs action)))

(defn ready-set
  "Actions that can run now: needs met, produces not all present."
  [actions store]
  (into []
        (filter (fn [action]
                  (and (needs-met? action store)
                       (not (action-satisfied? action store)))))
        (vals actions)))

(defn remaining
  [actions store]
  (into []
        (filter (fn [action] (not (action-satisfied? action store))))
        (vals actions)))

(defn dependency-edges
  "Action name → actions that produce one of its `:needs` (predecessors)."
  [actions]
  (let [producers (reduce
                   (fn [acc action]
                     (reduce (fn [m id]
                               (update m id (fnil conj #{}) (:name action)))
                             acc
                             (:produces action)))
                   {}
                   (vals actions))]
    (reduce
     (fn [edges action]
       (let [preds (into #{}
                         (mapcat #(get producers % #{}))
                         (:needs action))]
         (assoc edges (:name action)
                (disj preds (:name action)))))
     {}
     (vals actions))))

(defn cycles
  "Action-name cycles in the produces→needs graph. Empty when acyclic."
  [actions]
  (let [edges (dependency-edges actions)
        nodes (vec (keys actions))
        state (atom {:color {} :found []})]
    (letfn [(dfs [node path]
              (let [color (get-in @state [:color node] :white)]
                (cond
                  (= color :gray)
                  (let [cyc (vec (concat (drop-while #(not= % node) path)
                                         [node]))]
                    (swap! state update :found conj cyc))

                  (= color :white)
                  (do
                    (swap! state assoc-in [:color node] :gray)
                    (doseq [pred (or (edges node) #{})]
                      (dfs pred (conj path node)))
                    (swap! state assoc-in [:color node] :black)))))]
      (doseq [n nodes] (dfs n []))
      (:found @state))))

(defn missing-needs
  [actions store]
  (into []
        (keep (fn [action]
                (let [miss (vec (remove #(contains? store %) (:needs action)))]
                  (when (seq miss)
                    {:action (:name action) :missing miss}))))
        (remaining actions store)))

(defn- invoke-run
  [action store ctx]
  (let [run (:run action)]
    (case (:op run)
      :literal
      (or (:values run) {})

      :eval
      (let [f (compile-eval-run (:code run))
            out (f store)]
        (if (map? out)
          out
          (throw (ex-info "eval run must return an artifact map"
                          {:phase :run :action (:name action)}))))

      :tool
      (let [reg (or (:agent/tool-registry ctx) {})
            t (get reg (:name run))]
        (when-not (tool/tool? t)
          (throw (ex-info (str "unknown tool: " (:name run))
                          {:phase :run :action (:name action)})))
        (let [raw (tool/invoke-tool t (or (:args run) {}) ctx)
              parsed (try (edn/read-string raw) (catch Throwable _ raw))]
          (if (map? parsed)
            parsed
            (into {} (map (fn [id] [id raw]) (:produces action))))))

      (throw (ex-info (str "unknown run op: " (:op run))
                      {:phase :run :action (:name action)})))))

(defn- stringify-keys
  [m]
  (into {} (map (fn [[k v]] [(name k) v]) m)))

(defn- run-one
  [action store ctx]
  (try
    (let [out (-> (invoke-run action store ctx)
                  stringify-keys
                  (select-keys (:produces action)))]
      {:ok true :action (:name action) :out out})
    (catch Throwable t
      {:ok false
       :action (:name action)
       :error (or (ex-message t) (.getName (class t)))})))

(defn apply-wave
  "Run every ready action against the same store snapshot. Members of
   one wave are independent and execute concurrently."
  [ready store ctx]
  (let [results (->> ready
                     (mapv (fn [action] (future (run-one action store ctx))))
                     (mapv deref))
        errors (into [] (remove :ok) results)
        produced (apply merge {} (map :out (filter :ok results)))]
    {:store (merge store produced)
     :wave (mapv :action (filter :ok results))
     :errors errors}))

(defn schedule
  "Run until no action is ready. Pure given `actions` + `store`."
  [actions store ctx]
  (loop [store (or store {})
         waves []
         ran []
         guard 0]
    (if (> guard 256)
      {:status :blocked
       :ran ran
       :parallel-waves waves
       :store store
       :blocked? true
       :errors [{:error "wave-limit" :guard guard}]}
      (let [ready (ready-set actions store)]
        (if (empty? ready)
          (let [left (remaining actions store)]
            (if (empty? left)
              {:status :done
               :ran ran
               :parallel-waves waves
               :store store
               :blocked? false
               :errors []}
              (let [cyc (cycles actions)
                    miss (missing-needs actions store)]
                {:status :blocked
                 :ran ran
                 :parallel-waves waves
                 :store store
                 :blocked? true
                 :blocked (mapv :name left)
                 :cycle (not-empty cyc)
                 :missing miss
                 :errors (cond-> []
                           (seq cyc) (conj {:error "cycle" :cycle cyc})
                           (seq miss) (conj {:error "missing" :missing miss}))})))
          (let [{:keys [store wave errors]} (apply-wave ready store ctx)]
            (if (seq errors)
              {:status :error
               :ran (into ran wave)
               :parallel-waves (conj waves wave)
               :store store
               :blocked? false
               :errors errors}
              (recur store
                     (conj waves wave)
                     (into ran wave)
                     (inc guard)))))))))

(m/=> ready-set [:=> [:cat :map :map] [:vector :map]])
(m/=> schedule [:=> [:cat :map :map [:maybe :map]] :map])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.workflow.engine)]}))

(instrument!)
