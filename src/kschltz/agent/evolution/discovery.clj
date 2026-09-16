(ns kschltz.agent.evolution.discovery
  "Deterministic self-proposals from reproducible failing gates."
  (:require [clojure.string :as str]
            [kschltz.agent.evolution.schemas :as schemas]
            [malli.core :as m]))

(defn- evidence-text
  [result]
  (let [command (:command result)
        detail (or (not-empty (str/trim (:stderr command)))
                   (not-empty (str/trim (:stdout command)))
                   (:error command)
                   (str "exit status " (:status command)))]
    (str "Gate " (:id result) " failed: " detail)))

(defn proposal-from-failures
  [gate-specs gate-results]
  (let [specs (into {} (map (juxt :id identity)) gate-specs)
        failed (filterv #(and (:required? %) (not (:passed? %)))
                        gate-results)
        selected (or (first (filter #(= :acceptance (:kind %)) failed))
                     (first failed))]
    (when selected
      (let [spec (get specs (:id selected))]
        {:id (str "diagnose-" (:id selected))
         :title (str "Repair failing gate " (:id selected))
         :objective (str "Make required gate " (:id selected)
                         " pass without introducing regressions")
         :evidence [(evidence-text selected)]
         :acceptance [(str "Gate " (:id selected) " exits successfully")]
         :paths (vec (:candidate-paths spec))
         :risk :medium
         :source :self-diagnosis}))))

(m/=> proposal-from-failures
      [:=> [:cat [:vector schemas/GateSpec] [:vector schemas/GateResult]]
       [:maybe schemas/Proposal]])
