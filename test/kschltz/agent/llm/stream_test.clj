(ns kschltz.agent.llm.stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.client :as lcm-client]
            [kschltz.agent.llm.stream :as stream]))

(defn- fake-response
  [text]
  {:choices [{:message {:role "assistant" :content text}
              :finish_reason "stop"}]
   :model "fake-model"
   :usage {:prompt_tokens 1 :completion_tokens 2}})

(defn- non-streamable-client
  [resp]
  (reify lcm-client/LlmClient
    (-call [_client _req] resp)))

(defn- streamable-client
  "Streamable inner client that emits token events then returns the response."
  [resp]
  (reify
    lcm-client/LlmClient
    (-call [_client _req] resp)
    stream/StreamableLlmClient
    (-call-stream [_client req emit!]
      (emit! (stream/event :text-delta {:text "partial"}))
      resp)))

(deftest event-merges-type-ts-and-kvs
  (let [e (stream/event :text-delta {:text "hi"})]
    (is (= :text-delta (:type e)))
    (is (int? (:ts e)))
    (is (= "hi" (:text e)))))

(deftest streamable-detects-protocol
  (is (true? (stream/streamable? (streamable-client {:choices []}))))
  (is (false? (stream/streamable? (non-streamable-client {:choices []})))))

(deftest wrap-client-wraps-and-rebinds-emit
  (let [inner  (non-streamable-client (fake-response "hello"))
        events (atom [])
        client (stream/wrap-client inner #(swap! events conj %))]
    (is (not= inner client))
    (is (lcm-client/-call client {:messages [{:role "user" :content "hi"}]}))
    (let [types (map :type @events)]
      (is (= [:llm-start :text-delta :llm-done] types))
      (is (= "hello" (:text (second @events))))
      (is (some #(= "fake-model" (:model %)) @events))
      (is (some #(= "stop" (:finish-reason %)) @events)))))

(deftest wrap-client-uses-stream-when-available
  (let [events (atom [])
        client (stream/wrap-client (streamable-client (fake-response "full"))
                                   #(swap! events conj %))]
    (is (lcm-client/-call client {}))
    (is (= [:text-delta] (map :type @events)))))

(deftest wrap-client-keeps-inner-when-already-streaming
  (let [inner  (stream/wrap-client (non-streamable-client (fake-response "x")))
        client (stream/wrap-client inner (fn [_]))]
    (is (instance? kschltz.agent.llm.stream.StreamingClient client))))
