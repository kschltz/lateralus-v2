(ns kschltz.agent.memory.bm25
  "BM25 scoring and inverted-index utilities.

   Builds per-document term frequencies, IDF statistics, and computes BM25
   scores for a set of query tokens against an indexed corpus."
  (:require [clojure.string :as str]))

(defn tokenize
  "Lowercase, split on non-alphanumeric, drop empty/short tokens. Returns a set."
  [text]
  (->> (str/split (str/lower-case (or text "")) #"[^a-z0-9]+")
       (remove #(< (count %) 2))
       (set)))

(defn token-seq
  "Like `tokenize` but returns a vector (preserving multiplicity for doc length)."
  [text]
  (->> (str/split (str/lower-case (or text "")) #"[^a-z0-9]+")
       (remove #(< (count %) 2))
       (vec)))

(defn compute-idf
  "Compute IDF map term -> log((N - df + 0.5) / (df + 0.5)) for a corpus."
  [N term-doc-freq]
  (into {} (map (fn [[term df]]
                  [term (max 0.01 (Math/log (/ (+ (- N df) 0.5)
                                               (+ df 0.5))))]))
        term-doc-freq))

(defn bm25-score
  "BM25 score for a single document statistic map against query tokens."
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

(defn corpus-stats
  "Build per-document BM25 statistic maps from messages and an inverted index.

   `inverted-index` shape: {term {msg-id [tf]}}"
  [messages inverted-index]
  (let [doc-lengths (zipmap (map :msg-id messages)
                            (map #(count (token-seq (:content %))) messages))
        avg-doc-length (if (seq doc-lengths)
                         (/ (reduce + (vals doc-lengths))
                            (count doc-lengths))
                         0.0)]
    (into {} (map (fn [msg]
                    (let [mid (:msg-id msg)
                          term-freq (into {} (keep (fn [[term postings]]
                                                     (when-let [tf (get postings mid)]
                                                       [term (first tf)]))
                                                   inverted-index))]
                      [mid {:term-freq term-freq
                            :doc-length (get doc-lengths mid 0)
                            :avg-doc-length avg-doc-length}]))
                  messages))))

(defn build-inverted-index
  "Build {term {msg-id [tf]}} from a message list."
  [messages]
  (reduce (fn [inv msg]
            (let [mid (:msg-id msg)
                  terms (token-seq (:content msg))]
              (reduce (fn [inv term]
                        (update-in inv [term mid]
                                   (fn [old]
                                     [(inc (or (first old) 0))])))
                      inv
                      terms)))
          {}
          messages))
