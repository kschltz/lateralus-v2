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
