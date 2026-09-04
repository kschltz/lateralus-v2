(ns kschltz.agent.store.file-index-demo-llm-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [file-index-demo-llm :as demo]))

(deftest next-reply-walks-file-index-tools
  (let [step (fn [msgs]
               (get-in (demo/next-reply "file-index-demo" msgs)
                       [:choices 0 :message]))
        t0 (step [{:role "user" :content "index please"}])
        n0 (get-in t0 [:tool_calls 0 :function :name])
        t1 (step [{:role "user" :content "index please"}
                  {:role "assistant" :content "" :tool_calls (:tool_calls t0)}
                  {:role "tool" :name n0 :tool_call_id "x" :content "{\"ok\":true}"}])
        n1 (get-in t1 [:tool_calls 0 :function :name])]
    (is (= "file_reindex" n0))
    (is (= "file_write" n1))))

(deftest sse-body-includes-done
  (let [s (demo/sse-body {:model "file-index-demo"
                          :choices [{:message {:role "assistant" :content "hi"}
                                     :finish_reason "stop"}]})]
    (is (str/includes? s "data: "))
    (is (str/includes? s "data: [DONE]"))))
