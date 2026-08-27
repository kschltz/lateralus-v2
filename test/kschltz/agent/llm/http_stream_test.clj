(ns kschltz.agent.llm.http-stream-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.llm.http-stream :as hs]))

(deftest parse-sse-data-done-and-json
  (is (nil? (hs/parse-sse-data "event: x")))
  (is (= :done (hs/parse-sse-data "data: [DONE]")))
  (is (= {:a 1} (hs/parse-sse-data "data: {\"a\":1}"))))

(deftest consume-sse-emits-thinking-and-text
  (let [events (atom [])
        emit!  #(swap! events conj %)
        body   (str "data: " (json/generate-string
                              {:model "m"
                               :choices [{:delta {:reasoning "think "}}]})
                    "\n"
                    "data: " (json/generate-string
                              {:choices [{:delta {:content "hi"}
                                          :finish_reason "stop"}]})
                    "\n"
                    "data: [DONE]\n")
        resp   (hs/consume-sse (io/input-stream (.getBytes body "UTF-8")) emit!)]
    (is (= "hi" (get-in resp [:choices 0 :message :content])))
    (is (= "think " (get-in resp [:choices 0 :message :reasoning])))
    (is (= ["thinking-delta" "text-delta"]
           (map (comp name :type) @events)))))
