(ns kschltz.agent.workbench.cite-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.workbench.cite :as cite]))

(deftest sanitize-expands-prefix-and-drops-fakes
  (let [ids ["8a3a04ff-66fa-4b74-a83a-270f09f55bc3"]
        text "see @portal/8a3a04ff and also @portal/2a9f41c3 please"]
    (is (= (str "see @portal/8a3a04ff-66fa-4b74-a83a-270f09f55bc3 and also "
                "`(invalid @portal cite — call portal_submit and use its :cite)` please")
           (cite/sanitize-portal-cites text ids)))))

(deftest needs-repair-when-claim-without-submit
  (is (true? (cite/needs-portal-repair?
              "live in Portal: @portal/deadbeef"
              [])))
  (is (false? (cite/needs-portal-repair?
               "live in Portal: @portal/abc"
               [{:call {:function {:name "portal_submit"}}
                 :result "{\"ok\":true,\"cite\":\"@portal/abc-def\",\"ref\":{\"id\":\"abc-def\"}}"}]))))

(deftest assistant-text-guard-rewrites
  (let [g (cite/assistant-text-guard
           "Tiny table: @portal/cfe479cc"
           [{:call {:function {:name "portal_submit"}}
             :result "{\"ok\":true,\"cite\":\"@portal/cfe479cc-5fba-4968-8db7-517ff6724195\",\"ref\":{\"id\":\"cfe479cc-5fba-4968-8db7-517ff6724195\"}}"}]
           ["cfe479cc-5fba-4968-8db7-517ff6724195"])]
    (is (false? (:needs-repair? g)))
    (is (str/includes? (:text g) "@portal/cfe479cc-5fba-4968-8db7-517ff6724195"))))
