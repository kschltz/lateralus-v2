(ns kschltz.agent.session.manager-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.session.manager :as mgr]
            [kschltz.agent.session.store :as store]
            [kschltz.agent.workbench.hub :as hub]))

(defn- tmp-store []
  (store/create-store
   (io/file (System/getProperty "java.io.tmpdir")
            (str "lat-mgr-" (random-uuid)))))

(deftest create-switch-rename-delete
  (let [st (tmp-store)
        h  (hub/create-hub {:session-id "one" :session-title "One" :session-store st})
        r  (runtime/start {} "one")]
    (mgr/ensure! st {:id "one" :title "One"})
    (hub/publish-turn! h {:role :user :text "hello from one"})
    (mgr/persist-current! st h r)
    (let [two (mgr/create! st h r {:title "Two"})]
      (is (= "Two" (:title two)))
      (is (= [] (:turns (hub/snapshot h))))
      (is (= (:id two) (runtime/session-id r))))
    (mgr/activate! st h r "one")
    (is (= "hello from one" (:text (last (:turns (hub/snapshot h))))))
    (is (= "one" (runtime/session-id r)))
    (mgr/rename! st h "one" "First")
    (is (= "First" (get-in (hub/snapshot h) [:session :title])))
    (is (thrown? clojure.lang.ExceptionInfo (mgr/delete! st h "one")))
    (let [cur (:session-id (hub/snapshot h))]
      (is (= "one" cur)))
    (mgr/create! st h r {:id "three" :title "Three"})
    (mgr/delete! st h "one")
    (is (nil? (some #(= "one" (:id %)) (mgr/list-sessions st))))))

(deftest refuse-switch-while-running
  (let [st (tmp-store)
        h  (hub/create-hub {:session-id "a" :session-store st})]
    (mgr/ensure! st {:id "a"})
    (mgr/ensure! st {:id "b"})
    (hub/set-status! h :running "busy")
    (is (thrown? clojure.lang.ExceptionInfo
                 (mgr/activate! st h nil "b")))))
