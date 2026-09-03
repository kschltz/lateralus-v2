(ns kschltz.agent.store.memory
  "In-process StoreEngine. No native library; used by tests and air-gapped
   profiles that want the file-index façade without DuckDB."
  (:require [clojure.string :as str]
            [kschltz.agent.store.protocol :as proto]
            [malli.core :as m]
            [malli.instrument :as mi]))

(defn- row-key
  [pk-cols row]
  (if (= 1 (count pk-cols))
    (get row (first pk-cols))
    (mapv #(get row %) pk-cols)))

(defn- match-where
  [row where]
  (if (empty? where)
    true
    (let [{:keys [path path-prefix id]} where]
      (cond
        (and path (not= path (:path row))) false
        (and id (not= id (:id row))) false
        (and path-prefix
             (let [p (str (:path row))]
               (not (or (= p path-prefix)
                        (str/starts-with? p (str path-prefix "/"))))))
        false
        :else true))))

(defn- apply-order
  [rows order]
  (if (seq order)
    (sort-by (fn [row] (mapv #(get row %) order)) rows)
    rows))

(defrecord MemoryEngine [state]
  proto/StoreEngine
  (-upsert! [_ table pk-cols row]
    (let [k (row-key pk-cols row)]
      (swap! state assoc-in [table k] row)
      {:rows 1}))
  (-insert! [_ table row]
    (let [k (or (:id row) (str (random-uuid)))]
      (swap! state assoc-in [table k] (assoc row :id k))
      {:rows 1}))
  (-select [_ table {:keys [where order limit]}]
    (let [rows (->> (vals (get @state table {}))
                    (filter #(match-where % where))
                    (apply-order order)
                    vec)]
      (if limit (vec (take limit rows)) rows)))
  (-delete! [_ table where]
    (let [before (get @state table {})
          doomed (into #{}
                       (keep (fn [[k row]]
                               (when (match-where row where) k)))
                       before)]
      (swap! state update table
             (fn [m] (apply dissoc (or m {}) doomed)))
      {:rows (count doomed)}))
  (-close [_]
    nil))

(defn memory-store
  "Open an empty in-memory StoreEngine."
  ([] (memory-store {}))
  ([_opts]
   (->MemoryEngine (atom {:file_index {} :file_edits {}}))))

(m/=> memory-store
      [:function
       [:=> [:cat] [:fn proto/store-engine?]]
       [:=> [:cat :any] [:fn proto/store-engine?]]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.store.memory)]}))

(instrument!)
