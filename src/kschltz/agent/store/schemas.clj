(ns kschltz.agent.store.schemas
  "Malli schemas for StoreEngine statements and FileIndex rows."
  (:require [kschltz.agent.store.protocol :as proto]
            [malli.core :as m]))

(def Table
  [:enum :file_index :file_edits])

(def PkCols
  [:vector {:min 1} :keyword])

(def Row
  [:map-of :keyword :any])

(def Where
  [:map
   [:path {:optional true} :string]
   [:path-prefix {:optional true} :string]
   [:id {:optional true} :string]])

(def SelectOpts
  [:map
   [:where {:optional true} Where]
   [:order {:optional true} [:vector :keyword]]
   [:limit {:optional true} [:int {:min 1}]]])

(def ExecResult
  [:map [:rows :int]])

(def FileEntry
  [:map
   [:path :string]
   [:sha256 {:optional true} [:maybe :string]]
   [:size {:optional true} [:maybe :int]]
   [:mtime {:optional true} [:maybe :int]]
   [:content {:optional true} [:maybe :string]]
   [:indexed-at {:optional true} :int]])

(def FileEdit
  [:map
   [:id :string]
   [:path :string]
   [:tool :string]
   [:sha256-before {:optional true} [:maybe :string]]
   [:sha256-after {:optional true} [:maybe :string]]
   [:start-line {:optional true} [:maybe :int]]
   [:end-line {:optional true} [:maybe :int]]
   [:ts :int]])

(def StoreConfig
  "Integrant config for `:lateralus/store`."
  [:multi {:dispatch (fn [c] (keyword (or (:impl c) :memory)))}
   [:memory [:map
             [:impl {:optional true} [:= :memory]]]]
   [:duckdb [:map
             [:impl [:= :duckdb]]
             [:path {:optional true} [:maybe :string]]]]])

(def FileIndexConfig
  [:map
   [:store [:fn proto/store-engine?]]
   [:max-content-bytes {:optional true} :int]])

(defn valid-store-config?
  [config]
  (m/validate StoreConfig (or config {})))
