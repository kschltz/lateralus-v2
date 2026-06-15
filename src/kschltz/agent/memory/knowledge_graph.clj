(ns kschltz.agent.memory.knowledge-graph
  "Entity/token extraction and knowledge-graph scoring.

   A small adjacency graph maps extracted entities to the message ids they
   appear in. Query-time graph scoring rewards messages attached directly to
   query entities."
  (:require [kschltz.agent.memory.bm25 :as bm25]))

(defn default-extract
  "Default entity/term extractor. Returns a set of tokens."
  [content]
  (bm25/tokenize content))

(defn update-graph
  "Add a message id to the entity->msg-ids graph for each entity."
  [graph msg-id entities]
  (reduce (fn [g entity]
            (update g entity (fnil conj #{}) msg-id))
          graph
          entities))

(defn build-graph
  "Build an entity->msg-ids graph from a message list using default extraction."
  [messages]
  (reduce (fn [g msg]
            (update-graph g (:msg-id msg) (default-extract (:content msg))))
          {}
          messages))

(defn graph-score
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
