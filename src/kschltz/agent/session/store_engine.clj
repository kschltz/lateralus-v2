(ns kschltz.agent.session.store-engine
  "SessionStore façade over StoreEngine (Option C).

   Catalog rows live in the `sessions` table. Workspace payloads
   (turns / refs / agent-state) are EDN in `payload`. The file-backed
   catalog remains the default; this impl is opt-in."
  (:require [clojure.edn :as edn]
            [kschltz.agent.session.protocol :as proto]
            [kschltz.agent.session.store :as file-store]
            [kschltz.agent.store.protocol :as store]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def ^:private safe-read-opts
  {:eof nil
   :default (fn [tag val]
              (throw (ex-info "Unsupported tagged literal"
                              {:tag tag :value val})))})

(defn- now-ms [] (System/currentTimeMillis))

(defn- truthy? [v]
  (contains? #{true 1} v))

(defn- read-edn
  [s]
  (when (and (string? s) (pos? (count s)))
    (edn/read-string safe-read-opts s)))

(defn- pack
  [record]
  (pr-str (select-keys record [:turns :refs :agent-state])))

(defn- unpack
  [row]
  (when row
    (let [payload (or (read-edn (:payload row)) {})]
      (merge {:turns [] :refs {} :agent-state {}}
             payload
             {:id         (:id row)
              :title      (:title row)
              :created-at (:created-at row)
              :updated-at (:updated-at row)
              :preview    (or (:preview row) "")}))))

(defn- current-id*
  [engine]
  (:id (first (store/-select engine :sessions {:where {:current true} :limit 1}))))

(defn- write-row!
  [engine rec current?]
  (store/-upsert! engine :sessions [:id]
                  {:id         (:id rec)
                   :title      (or (:title rec) (:id rec))
                   :created-at (:created-at rec)
                   :updated-at (:updated-at rec)
                   :preview    (or (:preview rec) "")
                   :current    (boolean current?)
                   :payload    (pack rec)}))

(defn- set-current-flag!
  [engine id]
  (doseq [row (store/-select engine :sessions {})]
    (write-row! engine (unpack row) (= id (:id row)))))

(defrecord StoreSessionStore [engine]
  proto/SessionStore
  (-list [this]
    (->> (store/-select engine :sessions {:order [:updated-at] :desc true})
         (mapv #(file-store/public-record this (unpack %)))))
  (-get [_ id]
    (unpack (first (store/-select engine :sessions {:where {:id (str id)}}))))
  (-upsert! [this record]
    (let [id (str (:id record))]
      (when-not (proto/session-id? id)
        (throw (ex-info "Invalid session id"
                        {:id id :pattern (str proto/session-id-pattern)})))
      (let [prev (proto/-get this id)
            now (now-ms)
            rec (cond-> (assoc record :id id)
                  (nil? (:created-at record)) (assoc :created-at (or (:created-at prev) now))
                  true (assoc :updated-at now)
                  (nil? (:title record)) (assoc :title (or (:title prev) id)))
            cur (current-id* engine)
            become? (or (nil? cur) (= id cur))]
        (write-row! engine rec become?)
        (file-store/public-record this rec))))
  (-delete! [this id]
    (let [id (str id)
          existed? (boolean (proto/-get this id))]
      (when existed?
        (let [turns (store/-select engine :turns {:where {:session-id id}})]
          (doseq [t turns]
            (store/-delete! engine :events {:turn-id (:id t)}))
          (store/-delete! engine :turns {:session-id id}))
        (let [was-current? (= id (current-id* engine))]
          (store/-delete! engine :sessions {:id id})
          (when was-current?
            (when-let [next-id (:id (first (store/-select engine :sessions {:limit 1})))]
              (set-current-flag! engine next-id)))))
      existed?))
  (-current-id [_]
    (current-id* engine))
  (-set-current! [this id]
    (let [id (str id)]
      (when-not (proto/session-id? id)
        (throw (ex-info "Invalid session id"
                        {:id id :pattern (str proto/session-id-pattern)})))
      (when-not (proto/-get this id)
        (throw (ex-info "Unknown session" {:id id})))
      (set-current-flag! engine id)
      id)))

(defn store-session-store
  "SessionStore over `engine`."
  [engine]
  (->StoreSessionStore engine))

(m/=> store-session-store
      [:=> [:cat [:fn store/store-engine?]] [:fn proto/session-store?]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.session.store-engine)]}))

(instrument!)
