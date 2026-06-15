(ns kschltz.agent.memory.kg-bm25
  "Public KG + BM25 MemoryBackend wiring.

   Delegates to focused namespaces for scoring (`bm25`), graph scoring
   (`knowledge-graph`), and file persistence (`store.file`). Satisfies
   `MemoryBackend` and ignores :query-embedding entirely."
  (:require [clojure.java.io :as io]
            [kschltz.agent.memory.bm25 :as bm25]
            [kschltz.agent.memory.knowledge-graph :as kg]
            [kschltz.agent.memory.protocol :as mem]
            [kschltz.agent.memory.store.file :as store]))

;; ---- RRF fusion ----

(defn- rrf-score
  "Reciprocal Rank Fusion score for a 1-based rank."
  [rank k]
  (/ 1.0 (+ k rank)))

(defn- rank->scores
  "Convert a ranked sequence of msg-ids into a map msg-id -> rrf score."
  [ranked k]
  (into {} (map-indexed (fn [idx msg-id]
                          [msg-id (rrf-score (inc idx) k)])
                        ranked)))

(defn- fuse-rrf
  "Fuse multiple ranked lists via RRF and return top-n msg-ids."
  [k lists top-n]
  (let [scores (apply merge-with + (map #(rank->scores % k) lists))]
    (->> scores
         (sort-by (comp - val))
         (map key)
         (take top-n))))

;; ---- State management ----

(defn- compute-index
  "Recompute derived index structures from a full message list."
  [messages]
  (let [inverted (bm25/build-inverted-index messages)
        graph (kg/build-graph messages)
        doc-count (count messages)
        idfs (bm25/compute-idf doc-count (into {} (map (fn [[term postings]]
                                                         [term (count postings)]))
                                              inverted))
        stats (bm25/corpus-stats messages inverted)]
    {:inverted inverted
     :graph graph
     :doc-count doc-count
     :idfs idfs
     :stats stats}))

(defn- load-session!
  "Load messages and index from disk into the in-memory session cache."
  [state session-id]
  (let [dir (store/session-dir (:root @state) session-id)
        msgs (store/read-lines (store/messages-file dir))
        idx  (store/read-index dir)]
    (swap! state assoc-in [:sessions session-id]
           {:messages msgs
            :index    (merge {:inverted {} :graph {} :doc-count 0} idx)})))

(defn- ensure-session!
  [state session-id]
  (when-not (get-in @state [:sessions session-id])
    (load-session! state session-id)))

(defn- persist-message!
  "Append a message to disk and update the in-memory index."
  [state session-id msg entities]
  (let [dir (store/session-dir (:root @state) session-id)
        mid (:msg-id msg)
        msgs-path (store/messages-file dir)]
    (store/append-line! msgs-path msg)
    (swap! state
           (fn [s]
             (let [session (or (get-in s [:sessions session-id])
                               {:messages [] :index {:inverted {} :graph {} :doc-count 0}})
                   msgs    (conj (:messages session) msg)
                   idx     (compute-index msgs)
                   idx'    (assoc idx :entities {mid entities})]
               (assoc-in s [:sessions session-id]
                         {:messages msgs
                          :index idx'}))))
    (let [session (get-in @state [:sessions session-id])]
      (store/write-index! dir (select-keys (:index session) [:inverted :graph :doc-count :idfs :stats :entities])))))

;; ---- Recall helpers ----

(defn- recent-messages
  "Return the last-n messages for a session."
  [state session-id last-n]
  (ensure-session! state session-id)
  (->> (get-in @state [:sessions session-id :messages] [])
       (take-last last-n)))

(defn- bm25-rank
  "Rank messages by BM25 score for query-text."
  [state session-id query-text top-y]
  (ensure-session! state session-id)
  (let [session (get-in @state [:sessions session-id])
        query-tokens (bm25/tokenize query-text)
        stats (:stats (:index session))]
    (if (and (seq query-tokens) (seq stats))
      (->> (:messages session)
           (map (fn [msg]
                  (let [st (get stats (:msg-id msg))]
                    [msg (if st
                           (bm25/bm25-score (assoc st :idfs (:idfs (:index session))) query-tokens)
                           0.0)])))
           (sort-by (comp - second))
           (take top-y)
           (map first))
      [])))

(defn- kg-rank
  "Rank messages by knowledge-graph entity overlap with query-text."
  [state session-id query-text top-y extract-fn]
  (ensure-session! state session-id)
  (let [session (get-in @state [:sessions session-id])
        query-entities (extract-fn query-text)
        graph (:graph (:index session))
        scores (kg/graph-score graph query-entities)]
    (if (seq scores)
      (->> (:messages session)
           (map (fn [msg] [msg (get scores (:msg-id msg) 0.0)]))
           (sort-by (comp - second))
           (take top-y)
           (map first))
      [])))

(defn- merge-recalls
  "Merge recent and top-y messages, dedupe by msg-id, sort by timestamp."
  [recent top-y]
  (->> (concat recent top-y)
       (reduce (fn [acc msg]
                 (if (contains? acc (:msg-id msg))
                   acc
                   (assoc acc (:msg-id msg) msg)))
               {})
       vals
       (sort-by :timestamp)
       vec))

;; ---- Backend ----

(defn- parse-store-config
  "Resolve the root directory or in-memory marker from the store config."
  [{:keys [backend path]}]
  (case (or backend :file)
    :memory {:type :memory :root nil}
    :file   {:type :file :root (io/file (or path "sessions/kg-bm25"))}
    (throw (ex-info "Unsupported kg-bm25 store backend" {:backend backend}))))

(defn backend
  "Construct a file-backed KG + BM25 MemoryBackend.

   `opts` keys:
     :store      -- {:backend :file :path ...} or {:backend :memory}
     :top-y      -- default top-y recall count
     :last-n     -- default last-n recall count
     :rrf-k      -- RRF constant (default 60)
     :extract-fn -- (fn [content] #{entity ...}), defaults to tokenize"
  [{:keys [store top-y last-n rrf-k extract-fn]
    :or   {top-y      3
           last-n     5
           rrf-k      60
           extract-fn kg/default-extract}}]
  (let [store-config (parse-store-config store)
        state        (atom (merge store-config
                                  {:sessions {}}))
        lock         (Object.)]
    (reify mem/MemoryBackend
      (mem/-store-message [backend session-id msg]
        (let [entities (extract-fn (:content msg ""))]
          (locking lock
            (case (:type @state)
              :memory (swap! state
                             (fn [s]
                               (let [session (or (get-in s [:sessions session-id])
                                                 {:messages [] :index {:inverted {} :graph {} :doc-count 0}})
                                     msgs (conj (:messages session) msg)
                                     idx  (compute-index msgs)]
                                 (assoc-in s [:sessions session-id]
                                           {:messages msgs
                                            :index idx}))))
              :file   (persist-message! state session-id msg entities))))
        nil)

      (mem/-recall-hybrid [backend session-id {:keys [top-y last-n query-text]}]
        (locking lock
          (let [recent (recent-messages state session-id last-n)]
            (if (seq query-text)
              (let [bm25-ranked (map :msg-id (bm25-rank state session-id query-text top-y))
                    kg-ranked   (map :msg-id (kg-rank state session-id query-text top-y extract-fn))
                    fused-ids   (fuse-rrf rrf-k [bm25-ranked kg-ranked] top-y)
                    session     (get-in @state [:sessions session-id])
                    msg-by-id   (zipmap (map :msg-id (:messages session))
                                        (:messages session))
                    top-msgs    (keep msg-by-id fused-ids)]
                (merge-recalls recent top-msgs))
              (merge-recalls recent [])))))

      (mem/-close [backend]
        (locking lock
          (reset! state (merge store-config {:sessions {}})))
        nil))))
