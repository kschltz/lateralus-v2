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

(defn- under-prefix?
  [path prefix]
  (or (= path prefix)
      (str/starts-with? (str path) (str prefix "/"))))

(defn- match-where
  [row where]
  (if (or (nil? where) (empty? where))
    true
    (let [ok-path (if (contains? where :path)
                    (= (:path where) (:path row))
                    true)
          ok-id (if (contains? where :id)
                  (= (:id where) (:id row))
                  true)
          ok-prefix (if (contains? where :path-prefix)
                      (under-prefix? (:path row) (:path-prefix where))
                      true)]
      (and ok-path ok-id ok-prefix))))

(defrecord MemoryEngine [tables]
  proto/StoreEngine
  (-upsert! [_ table pk-cols row]
    (let [k (row-key pk-cols row)]
      (swap! tables assoc-in [table k] row)
      {:rows 1}))
  (-insert! [_ table row]
    (let [k (or (:id row) (str (random-uuid)))]
      (swap! tables assoc-in [table k] (assoc row :id k))
      {:rows 1}))
  (-select [_ table opts]
    (let [where (:where opts)
          order (:order opts)
          limit (:limit opts)
          all (vec (vals (get @tables table {})))
          matched (filterv #(match-where % where) all)
          ordered (if (seq order)
                    (vec (sort-by (fn [row] (vec (map #(get row %) order))) matched))
                    matched)
          ordered (if (:desc opts) (vec (rseq ordered)) ordered)]
      (if limit (vec (take (long limit) ordered)) ordered)))
  (-delete! [_ table where]
    (let [before (get @tables table {})
          doomed (into #{}
                       (keep (fn [[k row]]
                               (when (match-where row where) k)))
                       before)]
      (swap! tables update table
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
