(ns kschltz.agent.store.memory-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.store.memory :as memory]
            [kschltz.agent.store.protocol :as proto]))

(deftest upsert-select-delete-roundtrip
  (let [e (memory/memory-store)]
    (testing "upsert then select by path"
      (is (= {:rows 1}
             (proto/-upsert! e :file_index [:path]
                             {:path "/a.txt" :sha256 "abc" :content "hi"})))
      (is (= [{:path "/a.txt" :sha256 "abc" :content "hi"}]
             (proto/-select e :file_index {:where {:path "/a.txt"}}))))
    (testing "path-prefix matches children"
      (proto/-upsert! e :file_index [:path]
                      {:path "/src/b.clj" :content "x"})
      (let [rows (proto/-select e :file_index {:where {:path-prefix "/src"}})]
        (is (= ["/src/b.clj"] (mapv :path rows)))))
    (testing "insert edits and order"
      (proto/-insert! e :file_edits {:id "1" :path "/a.txt" :ts 2})
      (proto/-insert! e :file_edits {:id "2" :path "/a.txt" :ts 1})
      (is (= ["2" "1"]
             (mapv :id (proto/-select e :file_edits {:order [:ts]}))))
      (is (= ["1" "2"]
             (mapv :id (proto/-select e :file_edits {:order [:ts] :desc true})))))
    (testing "delete"
      (is (= {:rows 1} (proto/-delete! e :file_index {:path "/a.txt"})))
      (is (empty? (proto/-select e :file_index {:where {:path "/a.txt"}}))))
    (testing "session / turn / current filters"
      (proto/-upsert! e :sessions [:id]
                      {:id "s1" :current true :payload "{}"})
      (proto/-upsert! e :sessions [:id]
                      {:id "s2" :current false :payload "{}"})
      (is (= ["s1"]
             (mapv :id (proto/-select e :sessions {:where {:current true}}))))
      (proto/-insert! e :events {:turn-id "t1" :seq 0 :type "text"})
      (is (= 1 (count (proto/-select e :events {:where {:turn-id "t1"}}))))))))
