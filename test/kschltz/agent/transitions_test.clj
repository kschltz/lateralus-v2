(ns kschltz.agent.transitions-test
  "Unit tests for the transition algebra and harvest/apply bridge."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.transitions :as tr]
            [kschltz.agent.transitions.interceptors :as tr.ix]))

(deftest set-llm-validation
  (testing "valid when at least one knob is present"
    (is (tr/valid-transition? {:op :set-llm :model "m"}))
    (is (tr/valid-transition? {:op :set-llm :base-url "http://x"}))
    (is (tr/valid-transition? {:op :set-llm :api-key "k"})))
  (testing "invalid when empty or unknown keys"
    (is (not (tr/valid-transition? {:op :set-llm})))
    (is (not (tr/valid-transition? {:op :set-llm :model "m" :extra 1})))
    (is (not (tr/valid-transition? {:op :other :model "m"})))))

(deftest apply-transitions-folds-left-to-right
  (let [{:keys [state applied]}
        (tr/apply-transitions {:model "a" :base-url "http://old"}
                              [{:op :set-llm :model "b"}
                               {:op :set-llm :base-url "http://new" :api-key "secret"}
                               {:op :bogus}])]
    (is (= "b" (:model state)))
    (is (= "http://new" (:base-url state)))
    (is (= "secret" (:api-key state)))
    (is (= 2 (count applied)))))

(deftest patch-llm-request-preserves-messages
  (let [req {:model "old" :messages [{:role "user" :content "hi"}] :tools []}
        patched (tr/patch-llm-request req {:model "new" :base-url "http://x"})]
    (is (= "new" (:model patched)))
    (is (= "http://x" (:base-url patched)))
    (is (= [{:role "user" :content "hi"}] (:messages patched)))
    (is (= [] (:tools patched)))))

(deftest redact-transition-hides-api-key
  (is (= {:op :set-llm :model "m" :api-key-set true}
         (tr/redact-transition {:op :set-llm :model "m" :api-key "secret"}))))

(deftest harvest-enqueues-and-redacts
  (let [envelope (tr/encode-result
                  {:ok true
                   :transition {:op :set-llm :model "m2" :api-key "secret"}})
        {:keys [results transitions]}
        (tr.ix/harvest-transitions
         [{:call {:id "1" :function {:name "set_llm_config"}}
           :result envelope}
          {:call {:id "2" :function {:name "self_status"}}
           :result "{\"ok\":true}"}])]
    (is (= 1 (count transitions)))
    (is (= {:op :set-llm :model "m2" :api-key "secret"} (first transitions)))
    (let [parsed (json/parse-string (:result (first results)) true)]
      (is (true? (get-in parsed [:transition :api-key-set])))
      (is (nil? (get-in parsed [:transition :api-key]))))
    (is (= "{\"ok\":true}" (:result (second results))))))

(deftest harvest-rejects-invalid-transition-envelope
  (let [envelope (json/generate-string {:transition {:op "set-llm"}})
        {:keys [results transitions]}
        (tr.ix/harvest-transitions
         [{:call {:id "1"} :result envelope}])]
    (is (empty? transitions))
    (let [parsed (json/parse-string (:result (first results)) true)]
      (is (false? (:ok parsed)))
      (is (= "invalid transition" (:error parsed))))))

(deftest apply-queued-updates-state-delta-and-request
  (let [ctx {:agent/state {:model "a" :base-url "http://old"}
             :agent/transitions [{:op :set-llm :model "b" :api-key "k"}]
             :llm/request {:model "a" :base-url "http://old"
                           :messages [{:role "user" :content "x"}]}
             :agent/state-delta {:agent/history []}}
        out (tr.ix/apply-queued-transitions ctx)]
    (is (= "b" (get-in out [:agent/state :model])))
    (is (= "k" (get-in out [:agent/state :api-key])))
    (is (= "b" (get-in out [:llm/request :model])))
    (is (= "k" (get-in out [:agent/state-delta :api-key])))
    (is (= [] (:agent/transitions out)))
    (is (= [{:op :set-llm :model "b" :api-key-set true}]
           (:agent/transitions-applied out)))
    ;; history delta preserved
    (is (= [] (get-in out [:agent/state-delta :agent/history])))))

(deftest harvest-interceptor-rewrites-all-tool-results
  (let [envelope (tr/encode-result
                  {:ok true
                   :transition {:op :set-llm :model "m" :api-key "secret"}})
        entry {:call {:id "1"} :result envelope}
        ctx {:tool/results [entry]
             :agent/all-tool-results [{:call {:id "0"} :result "prior"} entry]}
        enter (:enter (tr.ix/harvest-transitions-interceptor))
        out (enter ctx)
        last-result (:result (last (:agent/all-tool-results out)))
        parsed (json/parse-string last-result true)]
    (is (= 1 (count (:agent/transitions out))))
    (is (= "prior" (:result (first (:agent/all-tool-results out)))))
    (is (nil? (get-in parsed [:transition :api-key])))
    (is (true? (get-in parsed [:transition :api-key-set])))))
