(ns kschltz.agent.workbench.schemas
  "Malli schemas for the CHAT | Portal workbench plugin."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def Role
  [:enum :user :assistant :system :tool :thinking :portal-ref :error])

(def SessionStatus
  "Workbench session lifecycle for the chat UI."
  [:enum :idle :waiting :queued :running :error])
(def PortalRef
  "A chip referencing a value living in Portal / the workbench ref store."
  [:map
   [:id :string]
   [:preview :string]
   [:path {:optional true} :string]
   [:label {:optional true} :string]])

(def Turn
  [:map
   [:id :string]
   [:role Role]
   [:text {:optional true} :string]
   [:thinking {:optional true} [:maybe :string]]
   [:refs {:optional true} [:vector PortalRef]]
   [:ts :int]])

(def ChatMessage
  "Human → workbench POST /api/message body."
  [:map
   [:text :string]
   [:refs {:optional true} [:vector PortalRef]]])

(def WorkbenchConfig
  "Integrant `:lateralus/workbench` config."
  [:map
   [:enabled? {:optional true} :boolean]
   [:host {:optional true} :string]
   [:port {:optional true} [:int {:min 0 :max 65535}]]
   [:portal? {:optional true} :boolean]
   [:open-browser? {:optional true} :boolean]
   [:app {:optional true} :boolean]
   [:window-title {:optional true} :string]
   [:open? {:optional true} :boolean]])

(def PortalSubmitInput
  [:map
   [:value :any]
   [:label {:optional true} :string]])

(def PortalClearInput
  [:map {:closed true}])

(def PortalFocusInput
  [:map
   [:id {:optional true} :string]
   [:label {:optional true} :string]])

(defn- shape-error [where value problems]
  (ex-info (str "Workbench " (name where) " failed Malli validation")
           {:where where
            :problems (me/humanize problems)
            :value value}))

(defn decode!
  [schema where value]
  (if-let [problems (m/explain schema value)]
    (throw (shape-error where value problems))
    value))

(defn decode-config [opts]
  (decode! WorkbenchConfig :config (or opts {})))

(defn decode-message [body]
  (decode! ChatMessage :message body))

(defn decode-ref [body]
  (decode! PortalRef :ref body))

(defn blank? [s]
  (or (nil? s) (str/blank? (str s))))
