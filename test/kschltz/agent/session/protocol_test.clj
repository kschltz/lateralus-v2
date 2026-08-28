(ns kschltz.agent.session.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.session.protocol :as protocol]))

(deftest session-id-accepts-allowed-names
  (testing "UUIDs, simple names, dots, dashes, underscores"
    (doseq [id ["abc-123" "random-uuid"
                "a" "a.b_c-d" "0start-with-alnum"
                "A9" (str (java.util.UUID/randomUUID))]]
      (is (protocol/session-id? id) (pr-str id)))))

(deftest session-id-rejects-bad-names
  (testing "empty, leading punctuation, too long, non-strings"
    (doseq [id ["" "-leading-dash" ".dot" "_under"
                (apply str (repeat 65 "a")) nil :kw 42]]
      (is (not (protocol/session-id? id)) (pr-str id)))))

(deftest session-id-boundary-is-64-chars
  (is (protocol/session-id? (apply str (repeat 64 "a"))))
  (is (not (protocol/session-id? (apply str (repeat 65 "a"))))))

(deftest session-store?-checks-protocol
  (let [store (reify protocol/SessionStore)]
    (is (protocol/session-store? store))
    (is (not (protocol/session-store? {})))))
