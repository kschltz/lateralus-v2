(ns kschltz.agent.portal.protocol-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.portal.protocol :as proto]
            [kschltz.agent.portal.session :as session]))

(deftest wrappers-delegate
  (let [s (session/create-session {:session-id "p"})]
    (try
      (is (nil? (proto/publish! s {:type :system :text "hi"})))
      (is (= 1 (count (:turns @(session/transcript-atom s)))))
      (finally
        (proto/close! s)))))
