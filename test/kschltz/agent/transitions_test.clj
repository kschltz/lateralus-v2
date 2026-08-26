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

(deftest runtime-policy-transition-validation
  (testing "system message and loop policy ops are closed and bounded"
    (is (tr/valid-transition? {:op :set-system-message :message "new policy"}))
    (is (not (tr/valid-transition? {:op :set-system-message :message ""})))
    (is (tr/valid-transition? {:op :set-loop-opts :max-loop-depth 8}))
    (is (tr/valid-transition?
         {:op :set-loop-opts
          :tool-content-caps {"file_read" 4096}}))
    (is (not (tr/valid-transition? {:op :set-loop-opts})))
    (is (not (tr/valid-transition?
              {:op :set-loop-opts :max-loop-depth 0})))
    (is (not (tr/valid-transition?
              {:op :set-loop-opts :arbitrary true})))))

(deftest runtime-policy-transitions-produce-durable-delta
  (let [ops [{:op :set-system-message :message "interceptors all the way down"}
             {:op :set-loop-opts
              :max-loop-depth 9
              :max-tool-calls-per-turn 4}]
        {:keys [state applied]}
        (tr/apply-transitions
         {:agent/loop-opts {:max-tool-calls-per-exchange 20}}
         ops)
        delta (tr/durable-delta {} state applied)]
    (is (= "interceptors all the way down"
           (:agent/system-message state)))
    (is (= {:max-tool-calls-per-exchange 20
            :max-loop-depth 9
            :max-tool-calls-per-turn 4}
           (:agent/loop-opts state)))
    (is (= (:agent/system-message state)
           (:agent/system-message delta)))
    (is (= (:agent/loop-opts state)
           (:agent/loop-opts delta)))))

(deftest tool-overlay-transition-is-durable-and-reversible
  (let [disable {:op :set-tool-enabled :tool-name "file_write" :enabled false}
        enable {:op :set-tool-enabled :tool-name "file_write" :enabled true}
        disabled (tr/apply-transition {} disable)
        enabled (tr/apply-transition disabled enable)
        delta (tr/durable-delta {}
                                disabled
                                [disable])]
    (is (tr/valid-transition? disable))
    (is (= ["file_write"] (:agent/disabled-tools disabled)))
    (is (= [] (:agent/disabled-tools enabled)))
    (is (= ["file_write"] (:agent/disabled-tools delta)))))

(deftest memory-policy-transition-is-merged-and-durable
  (let [op {:op :set-memory-policy
            :top-y 7
            :recall-enabled false}
        before {:agent/memory-policy {:last-n 4
                                      :persist-enabled true}}
        after (tr/apply-transition before op)
        delta (tr/durable-delta before after [op])]
    (is (tr/valid-transition? op))
    (is (= {:top-y 7
            :last-n 4
            :recall-enabled false
            :persist-enabled true}
           (:agent/memory-policy after)))
    (is (= (:agent/memory-policy after)
           (:agent/memory-policy delta)))))

(deftest runtime-reload-transition-is-allowlisted-and-durable
  (let [op {:op :reload-runtime
            :namespaces ["kschltz.agent.interceptors"
                         "kschltz.agent.interceptors"]}
        after (tr/apply-transition {} op)
        delta (tr/durable-delta {} after [op])]
    (is (tr/valid-transition? op))
    (is (not (tr/valid-transition?
              {:op :reload-runtime :namespaces ["clojure.core"]})))
    (is (= {:namespaces ["kschltz.agent.interceptors"]}
           (:agent/runtime-reload after)))
    (is (= (:agent/runtime-reload after)
           (:agent/runtime-reload delta)))))

(deftest runtime-reload-from-edits-uses-session-namespaces
  (is (tr/valid-transition? {:op :reload-runtime :from-edits true}))
  (let [after (tr/apply-transition
               {:agent/edited-namespaces ["kschltz.agent.loop"]}
               {:op :reload-runtime :from-edits true})]
    (is (= {:namespaces ["kschltz.agent.loop"]}
           (:agent/runtime-reload after)))))

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

(deftest mcp-transition-ops
  (testing "upsert/remove fold :mcp/servers"
    (let [{:keys [state applied]}
          (tr/apply-transitions {}
                                [{:op :mcp-upsert-server
                                  :server-id "fs"
                                  :config {:command "npx" :bearer-token "secret"}}
                                 {:op :mcp-refresh-server :server-id "fs"}
                                 {:op :mcp-remove-server :server-id "fs"}])]
      (is (= 3 (count applied)))
      (is (= {} (:mcp/servers state)))))
  (testing "redact hides bearer token"
    (is (= {:op :mcp-upsert-server
            :server-id "fs"
            :config {:command "npx" :bearer-token-set true}}
           (tr/redact-transition
            {:op :mcp-upsert-server
             :server-id "fs"
             :config {:command "npx" :bearer-token "secret"}})))))

(deftest durable-delta-replaces-mcp-servers
  (let [before {}
        after {:mcp/servers {"a" {:command "x"}}}
        delta (tr/durable-delta before after
                                [{:op :mcp-upsert-server
                                  :server-id "a"
                                  :config {:command "x"}}])]
    (is (= {"a" {:command "x"}} (:mcp/servers delta))))
  (let [before {:mcp/servers {"a" {:command "x"}}}
        after {:mcp/servers {}}
        delta (tr/durable-delta before after
                                [{:op :mcp-remove-server :server-id "a"}])]
    (is (= {} (:mcp/servers delta)))))


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

(deftest apply-queued-patches-loop-policy-on-current-context
  (let [ctx {:agent/state {:agent/system-message "old"
                           :agent/loop-opts {:max-loop-depth 3}}
             :agent/loop-opts {:max-loop-depth 3
                               :max-tool-calls-per-exchange 10}
             :agent/transitions
             [{:op :set-system-message :message "new"}
              {:op :set-loop-opts :max-loop-depth 6}]
             :agent/state-delta {}}
        out (tr.ix/apply-queued-transitions ctx)]
    (is (= "new" (get-in out [:agent/state :agent/system-message])))
    (is (= "new" (get-in out [:agent/state-delta :agent/system-message])))
    (is (= {:max-loop-depth 6
            :max-tool-calls-per-exchange 10}
           (:agent/loop-opts out)))
    (is (= {:max-loop-depth 6}
           (get-in out [:agent/state-delta :agent/loop-opts])))))

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
