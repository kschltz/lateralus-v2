(ns kschltz.agent.stream.wiring
  "Integrant keys for live response/thinking streaming."
  (:require [integrant.core :as ig]
            [kschltz.agent.stream.bus :as bus]
            [kschltz.agent.stream.plugin :as stream.plugin]
            [kschltz.agent.stream.store-bus :as store-bus]
            [malli.core :as m]))

(def StreamBusConfig
  [:map
   [:enabled? {:optional true} :boolean]
   [:impl {:optional true} [:enum :memory :store]]
   [:store {:optional true} :any]])

(defn- assert-malli!
  [key schema value]
  (when-let [problems (m/explain schema value)]
    (throw (ex-info (str "Integrant config failed Malli validation for " key)
                    {:key key
                     :schema schema
                     :problems (:errors problems)}))))

(defmethod ig/assert-key :lateralus/stream-bus [_ config]
  (assert-malli! :lateralus/stream-bus StreamBusConfig (or config {})))

(defmethod ig/init-key :lateralus/stream-bus [_ opts]
  (when-not (false? (:enabled? opts))
    (case (keyword (or (:impl opts) :memory))
      :memory (bus/create-bus)
      :store (if-let [engine (:store opts)]
               (store-bus/store-bus engine)
               (throw (ex-info "stream-bus :impl :store requires :store"
                               {:opts opts})))
      (throw (ex-info "Unknown :lateralus/stream-bus :impl"
                      {:impl (:impl opts)})))))

(defmethod ig/init-key :lateralus/stream-plugin [_ {:keys [bus]}]
  (stream.plugin/stream-plugin bus))

(def default-keys
  {:lateralus/stream-bus {}
   :lateralus/stream-plugin {:bus (ig/ref :lateralus/stream-bus)}})
