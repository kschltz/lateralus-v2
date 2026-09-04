(ns kschltz.agent.stream.store-bus
  "StreamBus façade over StoreEngine (Option C).

   Live turns stay in an in-memory bus (64-turn cap, SSE). Closed turns
   are checkpointed to `turns` + `events` so historic snapshots survive
   eviction and process restart."
  (:require [cheshire.core :as json]
            [kschltz.agent.stream.bus :as mem]
            [kschltz.agent.stream.protocol :as proto]
            [kschltz.agent.store.protocol :as store]
            [malli.core :as m]
            [malli.instrument :as mi]))

(defn- json-str
  [x]
  (json/generate-string (or x {})))

(defn- parse-json
  [s]
  (if (and (string? s) (pos? (count s)))
    (try (json/parse-string s true)
         (catch Throwable _ {}))
    {}))

(defn- parse-json-vec
  [s]
  (if (and (string? s) (pos? (count s)))
    (try (let [v (json/parse-string s true)]
           (if (sequential? v) (vec v) []))
         (catch Throwable _ []))
    []))

(defn- persist-turn!
  [engine snap]
  (when (and engine snap (:id snap))
    (store/-upsert! engine :turns [:id]
                    {:id         (:id snap)
                     :session-id (:session-id snap)
                     :status     (or (:status snap) "done")
                     :opened-at  (:opened-at snap)
                     :closed-at  (:closed-at snap)
                     :user-text  (:user-text snap)
                     :text       (or (:text snap) "")
                     :thinking   (or (:thinking snap) "")
                     :model      (:model snap)
                     :usage      (json-str (:usage snap))
                     :error      (when-let [e (:error snap)] (str e))
                     :rev        (or (:rev snap) 0)
                     :tool-names (json-str (or (:tool-names snap) []))})
    (store/-delete! engine :events {:turn-id (:id snap)})
    (doseq [ev (:events snap)]
      (store/-insert! engine :events
                      {:turn-id (:id snap)
                       :seq     (:seq ev)
                       :type    (str (:type ev))
                       :payload (json-str ev)}))))

(defn- load-events
  [engine turn-id]
  (->> (store/-select engine :events {:where {:turn-id turn-id} :order [:seq]})
       (mapv (fn [row]
               (merge (parse-json (:payload row))
                      {:seq (:seq row)
                       :type (or (:type row) (:type (parse-json (:payload row))))})))))

(defn- historic-snapshot
  [engine turn-id]
  (when-let [row (first (store/-select engine :turns {:where {:id turn-id}}))]
    (let [status (or (:status row) "done")]
      {:id          (:id row)
       :status      status
       :live?       (= "live" status)
       :opened-at   (:opened-at row)
       :closed-at   (:closed-at row)
       :session-id  (:session-id row)
       :user-text   (:user-text row)
       :text        (or (:text row) "")
       :thinking    (or (:thinking row) "")
       :model       (:model row)
       :usage       (parse-json (:usage row))
       :error       (:error row)
       :rev         (or (:rev row) 0)
       :events      (load-events engine turn-id)
       :tool-names  (parse-json-vec (:tool-names row))})))

(defn- historic-latest
  [engine]
  (:id (first (store/-select engine :turns {:order [:opened-at] :desc true :limit 1}))))

(defrecord StoreBus [live engine]
  proto/StreamBus
  (-open-turn! [_ meta]
    (proto/-open-turn! live meta))
  (-emit! [_ turn-id event]
    (proto/-emit! live turn-id event))
  (-close-turn! [_ turn-id status extra]
    (proto/-close-turn! live turn-id status extra)
    (when-let [snap (proto/-snapshot live turn-id)]
      (persist-turn! engine snap)))
  (-snapshot [_ turn-id]
    (or (proto/-snapshot live turn-id)
        (historic-snapshot engine turn-id)))
  (-current-id [_]
    (proto/-current-id live))
  (-events-since [_ turn-id seq-n]
    (or (proto/-events-since live turn-id seq-n)
        (when-let [snap (historic-snapshot engine turn-id)]
          {:rev (:rev snap)
           :status (:status snap)
           :live? (:live? snap)
           :events (vec (filter #(> (:seq %) (long seq-n)) (:events snap)))})))
  (-latest-id [_]
    (or (proto/-latest-id live)
        (historic-latest engine))))

(defn store-bus
  "StreamBus: in-memory live + StoreEngine historic checkpoints."
  ([engine] (store-bus engine (mem/create-bus)))
  ([engine live]
   (->StoreBus live engine)))

(m/=> store-bus
      [:function
       [:=> [:cat [:fn store/store-engine?]] [:fn proto/stream-bus?]]
       [:=> [:cat [:fn store/store-engine?] [:fn proto/stream-bus?]]
        [:fn proto/stream-bus?]]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.stream.store-bus)]}))

(instrument!)
