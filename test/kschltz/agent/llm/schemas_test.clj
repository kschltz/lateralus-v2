(ns kschltz.agent.llm.schemas-test
  "Tests for the LLM HTTP request/response Malli schemas.

   Covers:
     - ChatRequest / ChatResponse shape validation
     - decode-request / decode-response throw with structured
       `:problems` on bad input
     - extract-* helpers tolerate the common cases
     - Round-trip: a valid request decodes; a response with extra
       fields decodes (lenient mode is the default for responses)"
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.schemas :as schemas]))

;; ---- Request validation ----

(deftest decode-request-valid-passes
  (is (= {:model "gpt-4" :messages [{:role "user" :content "hi"}]}
         (schemas/decode-request
          {:model "gpt-4" :messages [{:role "user" :content "hi"}]}))))

(deftest decode-request-missing-model-throws
  (try
    (schemas/decode-request {:messages [{:role "user" :content "hi"}]})
    (is false "expected throw")
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (is (= "LLM HTTP request failed Malli validation" (ex-message e)))
        (is (= :request (:where d)))
        (is (vector? (:problems d)))
        (is (seq (:problems d)))))))

(deftest decode-request-bad-role-throws
  (try
    (schemas/decode-request
     {:model "gpt-4" :messages [{:role "admin" :content "x"}]})
    (is false "expected throw — 'admin' is not in the schema's :enum")
    (catch clojure.lang.ExceptionInfo e
      (is (seq (get (ex-data e) :problems))))))

;; ---- Response validation ----

(deftest decode-response-valid
  (let [resp {:model "gpt-4"
              :choices [{:message {:role "assistant" :content "hi"}}]}]
    (is (= resp (schemas/decode-response resp)))))

(deftest decode-response-no-choices-throws
  (try
    (schemas/decode-response {:model "gpt-4"})
    (is false "expected throw — :choices is required")
    (catch clojure.lang.ExceptionInfo e
      (is (seq (get (ex-data e) :problems))))))

(deftest decode-response-tool-calls
  (let [resp {:model "gpt-4"
              :choices [{:message {:role "assistant"
                                   :content ""
                                   :tool_calls [{:id "t1"
                                                  :type "function"
                                                  :function {:name "echo"
                                                             :arguments "{}"}}]}}]}]
    (is (= resp (schemas/decode-response resp)))))

;; ---- extract-* helpers ----

(deftest extract-text
  (is (= "hi" (schemas/extract-text
               {:choices [{:message {:role "assistant" :content "hi"}}]})))
  ;; Tool-calls-only assistant message: no :content at all.
  (is (= "" (schemas/extract-text
             {:choices [{:message {:role "assistant"
                                   :tool_calls [{:id "t1"}]}}]})))
  (is (= "" (schemas/extract-text {}))))

(deftest extract-tool-calls
  (is (= [] (schemas/extract-tool-calls
            {:choices [{:message {:role "assistant" :content "hi"}}]})))
  (is (= [{:id "t1"}]
         (schemas/extract-tool-calls
          {:choices [{:message {:role "assistant"
                                :content ""
                                :tool_calls [{:id "t1"}]}}]}))))

(deftest extract-model-and-finish
  (is (= "gpt-4" (schemas/extract-model {:model "gpt-4" :choices []})))
  (is (= "unknown" (schemas/extract-model {})))
  (is (= "stop" (schemas/extract-finish-reason
                {:choices [{:finish_reason "stop"}]})))
  (is (= "unknown" (schemas/extract-finish-reason {}))))

;; ---- The http-client wrapper in kschltz.agent.llm.client ----

(deftest http-client-wrapper-is-a-fn
  (testing "kschltz.agent.llm.client/http-client is a fn (defers to http.clj)"
    ;; The real impl lives in kschltz.agent.llm.http. The wrapper
    ;; in kschltz.agent.llm.client defers to it via
    ;; `requiring-resolve`. The full round-trip (request shape,
    ;; error mapping) is covered in kschltz.agent.llm.http-test.
    (is (fn? @#'kschltz.agent.llm.client/http-client))))
