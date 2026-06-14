(ns kschltz.agent.memory.proximum-backend
  "Proximum-backed MemoryBackend.

   Vectors are stored in a pure-JVM HNSW index via
   `org.replikativ/proximum`. Message metadata (role, content,
   timestamp, session-id, msg-id) is attached to each vector, so
   Proximum doubles as the message store. This avoids adding a second
   structured store and keeps the stack native-image-friendly.

   Requirements:
     - Java 22+
     - JVM flags:
         --add-modules=jdk.incubator.vector
         --enable-native-access=ALL-UNNAMED

   Hybrid recall:
     - Recent-N: scan the index, filter by session-id metadata, sort
       by timestamp, take last N.
     - Semantic-Y: embed the query, search the HNSW index with metadata,
       filter by session-id, take top Y.
     - Results are merged, deduped by msg-id, and sorted by timestamp.

   Persistence is controlled by `:store-config`:
     - `{:backend :memory :id #uuid \" ... \"}` — in-memory, data lost on close.
     - `{:backend :file :path \" sessions/proximum \" :id #uuid \" ... \"}` —
       durable across JVM restarts.

   Writes are in-memory until `sync!` is called. By default the backend
   does NOT sync on every store-message for speed; set `:sync-on-write?`
   to true for durability on every exchange, or call `-close` (which
   syncs + closes) before shutdown."
  (:require [clojure.core.async :as a]
            [proximum.core :as prox]
            [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.protocol :as mem]))

(defn- msg->metadata
  "Build Proximum metadata for a message."
  [session-id msg]
  (-> msg
      (select-keys [:role :content :timestamp :msg-id])
      (assoc :session-id session-id)))

(defn- metadata->msg
  "Convert Proximum metadata back to a recalled message."
  [md]
  (select-keys md [:role :content :timestamp :msg-id]))

(defn- recent-messages
  "Return the last-N messages for `session-id` by scanning the index."
  [idx session-id last-n]
  (->> (seq idx)
       (keep (fn [[id _vec]]
               (when-let [md (prox/get-metadata idx id)]
                 (when (= session-id (:session-id md))
                   (metadata->msg md)))))
       (sort-by :timestamp)
       (take-last last-n)))

(defn- semantic-messages
  "Return up to top-Y semantic matches for `session-id`."
  [idx session-id top-y query-embedding]
  (when (and (pos? top-y) (seq query-embedding) (pos? (count idx)))
    (let [results (prox/search-with-metadata idx (float-array query-embedding) top-y)]
      (->> results
           (keep (fn [{:keys [metadata]}]
                   (when (= session-id (:session-id metadata))
                     (metadata->msg metadata))))
           (distinct)))))

(defn- merge-recalls
  "Merge recent + semantic results, dedupe by msg-id, sort by timestamp."
  [recent semantic]
  (->> (concat recent semantic)
       (reduce (fn [acc msg]
                 (if (contains? acc (:msg-id msg))
                   acc
                   (assoc acc (:msg-id msg) msg)))
               {})
       vals
       (sort-by :timestamp)
       vec))

(defn backend
  "Construct a Proximum MemoryBackend.

   Required opt:
     :embedder — an `Embedder` instance (used to embed message content
                 and, if no `:query-embedding` is supplied, the query).

   Optional opts:
     :store-config      — Proximum store config; defaults to in-memory.
     :dim               — vector dimension; defaults to embedder dimensions.
     :capacity          — HNSW capacity; default 10000.
     :M                 — HNSW M; default 16.
     :ef-construction   — build quality; default 200.
     :ef-search         — search quality; default 50.
     :distance          — :euclidean (default) or :cosine; use :cosine only
                          when the embedder returns normalized vectors.
     :sync-on-write?    — call `sync!` after every store; default false."
  [{:keys [embedder store-config dim capacity M ef-construction ef-search distance sync-on-write?]
    :or   {capacity        10000
           M               16
           ef-construction 200
           ef-search       50
           distance        :euclidean
           sync-on-write?  false}}]
  {:pre [(some? embedder)]}
  (let [dim       (or dim (embedding/-dimensions embedder))
        store-config (or store-config {:backend :memory :id (random-uuid)})
        idx-atom  (atom (prox/create-index
                         {:type              :hnsw
                          :dim               dim
                          :M                 M
                          :ef-construction   ef-construction
                          :ef-search         ef-search
                          :store-config      store-config
                          :capacity          capacity
                          :distance          distance}))]
    (reify mem/MemoryBackend
      (-store-message [_ session-id msg]
        (let [embedding (float-array (embedding/-embed embedder (:content msg "")))
              metadata (msg->metadata session-id msg)]
          (locking idx-atom
            (let [idx  @idx-atom
                  idx2 (prox/insert idx embedding (:msg-id msg) metadata)
                  idx3 (if sync-on-write?
                         (a/<!! (prox/sync! idx2))
                         idx2)]
              (reset! idx-atom idx3))))
        nil)

      (-recall-hybrid [_ session-id {:keys [top-y last-n query-embedding query-text]}]
        (locking idx-atom
          (let [idx     @idx-atom
                recent  (recent-messages idx session-id last-n)
                query-embedding (or query-embedding
                                    (when (seq query-text)
                                      (embedding/-embed embedder query-text)))
                semantic (semantic-messages idx session-id top-y query-embedding)]
            (merge-recalls recent semantic))))

      (-close [_]
        (locking idx-atom
          (when-let [idx @idx-atom]
            (a/<!! (prox/sync! idx))
            (a/<!! (prox/close! idx))
            (reset! idx-atom nil)))))))
