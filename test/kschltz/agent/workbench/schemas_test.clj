(ns kschltz.agent.workbench.schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.workbench.schemas :as schemas]))

(deftest decode-config-accepts-empty
  (is (= {} (schemas/decode-config {}))))

(deftest decode-message-requires-text
  (is (= {:text "hi" :refs []}
         (schemas/decode-message {:text "hi" :refs []})))
  (is (thrown? Exception (schemas/decode-message {:refs []}))))

(deftest decode-ref
  (testing "minimal ref"
    (is (= {:id "a" :preview "1"}
           (schemas/decode-ref {:id "a" :preview "1"})))))
