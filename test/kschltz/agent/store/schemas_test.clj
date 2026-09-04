(ns kschltz.agent.store.schemas-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.store.schemas :as schemas]
            [malli.core :as m]))

(deftest store-config-accepts-memory-and-duckdb
  (is (schemas/valid-store-config? {}))
  (is (schemas/valid-store-config? {:impl :memory}))
  (is (schemas/valid-store-config? {:impl :duckdb}))
  (is (schemas/valid-store-config? {:impl :duckdb :path "sessions/x.duckdb"}))
  (is (not (m/validate schemas/StoreConfig {:impl :postgres}))))

(deftest select-opts-accept-desc
  (is (m/validate schemas/SelectOpts {:order [:ts] :desc true :limit 10}))
  (is (not (m/validate schemas/SelectOpts {:limit 0}))))

(deftest where-accepts-session-and-turn
  (is (m/validate schemas/Where {:session-id "s1" :turn-id "t1" :current true}))
  (is (m/validate schemas/Table :sessions))
  (is (m/validate schemas/Table :turns))
  (is (m/validate schemas/Table :events)))
