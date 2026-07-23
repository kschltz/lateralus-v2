(ns kschltz.agent.portal.schemas
  "Malli schemas for the Portal agent UI boundary.

   All values crossing `AgentUi` / host `reply!` are validated here."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def EventType
  [:enum :user :assistant :system :tool :thinking])

(def UiEvent
  "Agent → UI publish payload."
  [:map
   [:type EventType]
   [:text {:optional true} :string]
   [:thinking {:optional true} [:maybe :string]]
   [:turn-id {:optional true} :string]
   [:ts {:optional true} :int]])

(def HumanReply
  "UI → agent reply payload (Portal RPC / register!)."
  [:map
   [:text :string]
   [:turn-id {:optional true} :string]])

(def SessionView
  "Value Portal opens on — transcript + sticky composer status."
  [:map
   [:session-id :string]
   [:status [:enum :idle :waiting :running :closed]]
   [:turns [:vector
            [:map
             [:id :string]
             [:role EventType]
             [:text {:optional true} :string]
             [:thinking {:optional true} [:maybe :string]]
             [:ts :int]]]]])

(def PortalConfig
  "Malli schema for `:lateralus/portal`."
  [:map
   [:enabled? {:optional true} :boolean]
   [:window-title {:optional true} :string]
   [:theme {:optional true} :keyword]
   [:app {:optional true} :boolean]
   [:open? {:optional true} :boolean]
   [:await-ms {:optional true} :int]
   [:session-id {:optional true} :string]])

(defn- shape-error [where value problems]
  (ex-info (str "Portal UI " (name where) " failed Malli validation")
           {:where where
            :problems (me/humanize problems)
            :value value}))

(defn decode-event
  "Validate a `UiEvent`. Throws ex-info on failure."
  [event]
  (if-let [problems (m/explain UiEvent event)]
    (throw (shape-error :event event problems))
    event))

(defn decode-reply
  "Validate a `HumanReply`. Throws ex-info on failure."
  [reply]
  (if-let [problems (m/explain HumanReply reply)]
    (throw (shape-error :reply reply problems))
    reply))

(defn decode-session
  "Validate a `SessionView` map."
  [session]
  (if-let [problems (m/explain SessionView session)]
    (throw (shape-error :session session problems))
    session))

(defn blank-text?
  [s]
  (or (nil? s) (str/blank? (str s))))
