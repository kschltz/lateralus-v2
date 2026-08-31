(ns kschltz.agent.tools.factory.wiring
  "Integrant keys for the runtime tool factory."
  (:require [integrant.core :as ig]
            [kschltz.agent.tools.factory.plugin :as factory.plugin]
            [kschltz.agent.tools.factory.session :as factory.session]
            [kschltz.agent.tools.factory.tools :as factory.tools]
            [malli.core :as m]))

(def FactorySessionConfig
  [:map
   [:workspace-root {:optional true} :string]
   [:dynamic {:optional true} [:map [:enabled? {:optional true} :boolean]]]
   [:secret-store {:optional true} :any]
   [:sandbox {:optional true}
    [:map
     [:enabled? {:optional true} :boolean]
     [:call-tools {:optional true} [:set :string]]]]
   [:compiler {:optional true} :any]
   [:runtime {:optional true} :any]])

(defn- assert-malli!
  [key schema value]
  (when-let [problems (m/explain schema value)]
    (throw (ex-info (str "Integrant config failed Malli validation for " key)
                    {:key key
                     :schema schema
                     :problems (:errors problems)}))))

(defmethod ig/assert-key :lateralus/factory-session [_ config]
  (assert-malli! :lateralus/factory-session FactorySessionConfig (or config {})))

(defmethod ig/init-key :lateralus/factory-session [_ opts]
  (factory.session/factory-session (or opts {})))

(defmethod ig/init-key :lateralus/factory-tools [_ {:keys [session]}]
  (let [reg (factory.tools/factory-tools-registry session)]
    (with-meta reg
      {:registry/rebuild #(factory.tools/factory-tools-registry session)})))

(defmethod ig/init-key :lateralus/factory-plugin [_ {:keys [session]}]
  (factory.plugin/factory-plugin session))

(def default-keys
  "Keys to merge into `system/default-config`."
  {:lateralus/factory-session {:workspace-root "."
                               :dynamic {:enabled? true}}
   :lateralus/factory-tools {:session (ig/ref :lateralus/factory-session)}
   :lateralus/factory-plugin {:session (ig/ref :lateralus/factory-session)}})
