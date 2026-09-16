(ns kschltz.agent.evolution.ledger
  "Append-only evolution audit log backed by StoreEngine."
  (:require [clojure.edn :as edn]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.schemas :as schemas]
            [kschltz.agent.store.protocol :as store]
            [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]))

(def ^:private safe-read-opts
  {:eof {}
   :default (fn [tag value]
              (throw (ex-info "Unsupported tagged literal in evolution event"
                              {:tag tag :value value})))})

(defn- encode-row
  [event]
  {:id (:id event)
   :run-id (:run-id event)
   :type (name (:type event))
   :phase (name (:phase event))
   :ts (:ts event)
   :payload (pr-str (:payload event))})

(defn- decode-row
  [row]
  {:id (:id row)
   :run-id (:run-id row)
   :type (keyword (:type row))
   :phase (keyword (:phase row))
   :ts (:ts row)
   :payload (edn/read-string safe-read-opts (or (:payload row) "{}"))})

(defn append-event!
  [engine event]
  (when-not (schemas/valid? schemas/Event event)
    (throw (ex-info "Invalid evolution event"
                    {:event event
                     :explain (some-> (schemas/explain schemas/Event event)
                                      me/humanize)})))
  (store/-insert! engine :evolution_events (encode-row event))
  event)

(defn events
  [engine run-id]
  (mapv decode-row
        (store/-select engine :evolution_events
                       {:where {:run-id run-id}
                        :order [:ts :id]})))

(defrecord StoreEvolutionLedger [engine]
  proto/EvolutionLedger
  (-append-event! [_ event] (append-event! engine event))
  (-events [_ run-id] (events engine run-id)))

(defn store-ledger
  [engine]
  (->StoreEvolutionLedger engine))

(m/=> append-event!
      [:=> [:cat [:fn store/store-engine?] schemas/Event] schemas/Event])
(m/=> events
      [:=> [:cat [:fn store/store-engine?] :string] [:vector schemas/Event]])
(m/=> store-ledger
      [:=> [:cat [:fn store/store-engine?]] [:fn proto/evolution-ledger?]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.evolution.ledger)]}))

(instrument!)
