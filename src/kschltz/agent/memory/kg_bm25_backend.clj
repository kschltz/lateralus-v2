(ns kschltz.agent.memory.kg-bm25-backend
  "File-backed, embedding-free MemoryBackend using BM25 sparse retrieval
   plus a small per-session knowledge graph.

   Storage layout (one directory per session):

     root/session-id/
       messages.edn    -- one EDN map per line, chronological order
       index.edn       -- inverted index + graph + derived stats

   Hybrid recall:
     - Recent-N: read messages.edn, filter by session-id, take last N.
     - Top-Y:    BM25(query-text) fused via RRF with a knowledge-graph
                 score derived from query entities.
     - Results are merged, deduped by :msg-id, and sorted by :timestamp.

   This backend is pure Clojure, requires no incubator JVM flags, and
   is intended as a native-image-friendly alternative to Proximum.
   It ignores :query-embedding entirely.

   Options:
     :store      -- {:backend :file :path ...} or {:backend :memory}
     :top-y      -- default top-y recall count (default 3)
     :last-n     -- default last-n recall count (default 5)
     :rrf-k      -- RRF constant (default 60)
     :extract-fn -- (fn [content] #{entity ...}), defaults to tokenize"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.memory.protocol :as mem])
  (:import [java.io File FileWriter]))

;; ---- Tokenization / entity extraction ----

(defn- tokenize
  "Lowercase, split on non-alphanumeric, drop empty/short tokens."
  [text]
  (->> (str/split (str/lower-case (or text "")) #"[^a-z0-9]+")
       (remove #(< (count %) 2))
       (set)))

(defn- default-extract
  "Default entity/term extractor. Returns a set of tokens."
  [content]
  (tokenize content))

;; ---- BM25 ----

(defn- compute-idf
  "Compute IDF map term -> log((N - df + 0.5) / (df + 0.5)) for a corpus."
  [N term-doc-freq]
  (into {} (map (fn [[term df]]
                  [term (max 0.01 (Math/log (/ (+ (- N df) 0.5)
                                               (+ df 0.5))))]))
        term-doc-freq))

(defn- bm25-score
  "BM25 score for a single document map against query tokens."
  [{:keys [term-freq doc-length avg-doc-length idfs]} query-tokens]
  (let [k1 1.2
        b  0.75]
    (reduce (fn [score token]
              (if-let [idf (get idfs token)]
                (let [tf (get term-freq token 0)
                      denom (+ 1.0 (* k1 (- 1.0 b)) (* b (/ doc-length avg-doc-length)))]
                  (+ score (* idf (/ tf (+ tf (* k1 denom))))))
                score))
            0.0
            query-tokens)))

(defn- corpus-stats
  "Build per-document BM25 statistics from the inverted index."
  [messages inverted-index]
  (let [doc-lengths (zipmap (map :msg-id messages)
                            (map #(count (tokenize (:content %))) messages))
        avg-doc-length (if (seq doc-lengths)
                         (/ (reduce + (vals doc-lengths))
                            (count doc-lengths))
                         0.0)]
    (into {} (map (fn [msg]
                    (let [mid (:msg-id msg)
                          term-freq (into {} (map (fn [[term postings]]
                                                    (when-let [tf (get postings mid)]
                                                      [term (first tf)]))
                                                  inverted-index))]
                      [mid {:term-freq term-freq
                            :doc-length (get doc-lengths mid 0)
                            :avg-doc-length avg-doc-length}]))
                  messages))))

;; ---- Graph ----

(defn- update-graph
  "Add a message to the entity->msg-ids graph."
  [graph msg-id entities]
  (reduce (fn [g entity]
            (update g entity (fnil conj #{}) msg-id))
          graph
          entities))

(defn- graph-score
  "Return a map msg-id -> score for query entities walking the graph.
   Directly attached messages score highest."
  [graph query-entities]
  (reduce (fn [scores entity]
            (if-let [hits (get graph entity)]
              (reduce (fn [s msg-id]
                        (update s msg-id (fnil + 0.0) 1.0))
                      scores
                      hits)
              scores))
          {}
          query-entities))

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

;; ---- File I/O helpers ----

(defn- session-dir
  "Return the File for a session directory."
  ^File [root session-id]
  (io/file root session-id))

(defn- ensure-dir! [^File dir]
  (.mkdirs dir))

(defn- messages-file [^File dir]
  (io/file dir "messages.edn"))

(defn- index-file [^File dir]
  (io/file dir "index.edn"))

(defn- read-lines
  "Read EDN objects one per line from a file, returning a vector."
  [^File f]
  (if (.exists ^java.io.File f)
    (with-open [rdr (io/reader f)]
      (vec (for [line (line-seq rdr)
                 :let [trimmed (str/trim line)]
                 :when (seq trimmed)]
             (read-string trimmed))))
    []))

(defn- append-line!
  "Append a single EDN map as one line to a file."
  [^File f m]
  (ensure-dir! (.getParentFile f))
  (with-open [w (FileWriter. f true)]
    (.write w (pr-str m))
    (.write w "\n")))

(defn- write-file!
  "Overwrite a file with an EDN value."
  [^File f v]
  (ensure-dir! (.getParentFile f))
  (spit f (pr-str v)))

(defn- read-index
  [^File dir]
  (let [f (index-file dir)]
    (if (.exists ^java.io.File f)
      (let [v (first (read-lines f))]
        (if (map? v) v {}))
      {})))

(defn- write-index!
  [^File dir index]
  (write-file! (index-file dir) index))

;; ---- State management ----

(defn- load-session!
  "Load messages and index from disk into the in-memory session cache."
  [state session-id]
  (let [dir (session-dir (:root @state) session-id)
        msgs (read-lines (messages-file dir))
        idx  (read-index dir)]
    (swap! state assoc-in [:sessions session-id]
           {:messages msgs
            :index    (merge {:inverted {} :graph {} :doc-count 0} idx)})))

(defn- ensure-session!
  [state session-id]
  (when-not (get-in @state [:sessions session-id])
    (load-session! state session-id)))

(defn- compute-index
  "Recompute derived index structures from a full message list."
  [messages]
  (let [inverted (reduce (fn [inv msg]
                           (let [mid (:msg-id msg)
                                 terms (tokenize (:content msg))]
                             (reduce (fn [inv term]
                                       (update-in inv [term mid]
                                                  (fn [old]
                                                    [(inc (or (first old) 0))])))
                                     inv
                                     terms)))
                         {}
                         messages)
        graph (reduce (fn [g msg]
                        (update-graph g (:msg-id msg) (tokenize (:content msg))))
                      {}
                      messages)
        doc-count (count messages)
        idfs (compute-idf doc-count (into {} (map (fn [[term postings]]
                                                    [term (count postings)]))
                                          inverted))
        stats (corpus-stats messages inverted)]
    {:inverted inverted
     :graph graph
     :doc-count doc-count
     :idfs idfs
     :stats stats}))

(defn- persist-message!
  "Append a message to disk and update in-memory index."
  [state session-id msg entities]
  (let [dir (session-dir (:root @state) session-id)
        mid (:msg-id msg)
        msgs-path (messages-file dir)]
    (append-line! msgs-path msg)
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
      (write-index! dir (select-keys (:index session) [:inverted :graph :doc-count :idfs :stats :entities])))))

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
        query-tokens (tokenize query-text)
        stats (:stats (:index session))]
    (if (and (seq query-tokens) (seq stats))
      (->> (:messages session)
           (map (fn [msg]
                  (let [st (get stats (:msg-id msg))]
                    [msg (if st
                           (bm25-score (assoc st :idfs (:idfs (:index session))) query-tokens)
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
        scores (graph-score graph query-entities)]
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
           extract-fn default-extract}}]
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
