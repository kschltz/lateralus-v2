(ns kschltz.agent.portal.schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [kschltz.agent.portal.schemas :as schemas]))

(deftest portal-config-schema
  (is (m/validate schemas/PortalConfig {}))
  (is (m/validate schemas/PortalConfig {:enabled? true :window-title "x"}))
  (is (not (m/validate schemas/PortalConfig {:enabled? "yes"}))))

(deftest decode-event
  (is (= {:type :assistant :text "hi"}
         (schemas/decode-event {:type :assistant :text "hi"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (schemas/decode-event {:type :nope}))))

(deftest decode-reply
  (is (= {:text "hi"} (schemas/decode-reply {:text "hi"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (schemas/decode-reply {}))))
