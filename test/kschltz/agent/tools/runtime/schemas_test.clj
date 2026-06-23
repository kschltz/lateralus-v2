(ns kschltz.agent.tools.runtime.schemas-test
  "Tests for the runtime-eval tool Malli schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [kschltz.agent.tools.runtime.protocol :as proto]
            [kschltz.agent.tools.runtime.schemas :as schemas]))

(deftest eval-input-requires-non-empty-code
  (testing "EvalInput requires a non-empty :code and allows optional :ns"
    (is (m/validate schemas/EvalInput {:code "(+ 1 2)"}))
    (is (m/validate schemas/EvalInput {:code "(+ 1 2)" :ns "user"}))
    (is (not (m/validate schemas/EvalInput {:code ""})))
    (is (not (m/validate schemas/EvalInput {})))))

(deftest add-lib-input-accepts-lib-or-coords
  (testing "AddLibInput accepts lib(+version) and/or coords; all optional"
    (is (m/validate schemas/AddLibInput {:lib "org.clojure/data.json"}))
    (is (m/validate schemas/AddLibInput {:lib "org.clojure/data.json"
                                         :version "2.5.0"}))
    (is (m/validate schemas/AddLibInput {:coords "{a/b {:mvn/version \"1\"}}"}))
    (is (m/validate schemas/AddLibInput {}))
    (is (not (m/validate schemas/AddLibInput {:lib 42})))))

(deftest loaded-libs-input-is-closed-empty-map
  (testing "LoadedLibsInput is a closed empty map"
    (is (m/validate schemas/LoadedLibsInput {}))
    (is (not (m/validate schemas/LoadedLibsInput {:unexpected true})))))

(deftest runtime-config-validates-toggles
  (testing "RuntimeConfig accepts the documented knobs and rejects bad types"
    (is (m/validate schemas/RuntimeConfig {}))
    (is (m/validate schemas/RuntimeConfig {:eval-ns "lateralus.repl"
                                           :eval-timeout-ms 1000
                                           :max-output-bytes 1024
                                           :enabled? false
                                           :network? false}))
    (is (not (m/validate schemas/RuntimeConfig {:enabled? "yes"})))
    (is (not (m/validate schemas/RuntimeConfig {:eval-timeout-ms "soon"})))))

(deftest re-exported-shapes-match-protocol
  (testing "schema re-exports point at the protocol's data shapes"
    (is (= schemas/Coords proto/Coords))
    (is (= schemas/EvalResult proto/EvalResult))
    (is (= schemas/AddLibsResult proto/AddLibsResult))))
