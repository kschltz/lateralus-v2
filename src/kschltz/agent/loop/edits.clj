(ns kschltz.agent.loop.edits
  "Collect project namespaces touched by successful file mutations
   so `reload_runtime` can reload them with `:from-edits true`."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn path->agent-ns
  "Map a workspace-relative or absolute src path to a Lateralus ns, or nil."
  [path]
  (when (string? path)
    (when-let [rel (second (re-find #"(?:^|/)src/(kschltz/.+?)\.clj$" path))]
      (-> rel
          (str/replace #"/" ".")
          (str/replace #"_" "-")))))

(defn- result-path
  [result-str]
  (when (string? result-str)
    (when-let [parsed (try (json/parse-string result-str true)
                           (catch Throwable _ nil))]
      (when (or (true? (:updated parsed))
                (true? (:created parsed))
                (true? (:ok parsed))
                (number? (:size parsed)))
        (:path parsed)))))

(defn collect-edited-namespaces
  "Return distinct agent ns names from this turn's tool results."
  [results]
  (->> results
       (keep (fn [r]
               (when-let [p (result-path (:result r))]
                 (path->agent-ns p))))
       distinct
       vec))

(defn merge-edited
  "Append this turn's edited nses onto ctx + state-delta."
  [ctx results]
  (let [fresh (collect-edited-namespaces results)]
    (if (seq fresh)
      (let [prior (or (:agent/edited-namespaces ctx)
                      (get-in ctx [:agent/state :agent/edited-namespaces])
                      [])
            combined (vec (distinct (into prior fresh)))]
        (-> ctx
            (assoc :agent/edited-namespaces combined)
            (update :agent/state-delta (fnil assoc {})
                    :agent/edited-namespaces combined)))
      ctx)))
