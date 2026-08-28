(ns kschltz.agent.session.store-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.session.protocol :as proto]
            [kschltz.agent.session.store :as store]))

(defn- tmp-store []
  (store/create-store
   (io/file (System/getProperty "java.io.tmpdir")
            (str "lat-sess-" (random-uuid)))))

(deftest catalog-roundtrip
  (let [s (tmp-store)
        pub (proto/-upsert! s {:id "alpha" :title "Alpha" :turns [] :refs {}})]
    (is (= "alpha" (:id pub)))
    (is (= "Alpha" (:title pub)))
    (is (true? (:active? pub)))
    (is (= "alpha" (proto/-current-id s)))
    (proto/-upsert! s {:id "beta" :title "Beta"})
    (proto/-set-current! s "beta")
    (is (= "beta" (proto/-current-id s)))
    (is (= 2 (count (proto/-list s))))
    (is (true? (proto/-delete! s "alpha")))
    (is (= 1 (count (proto/-list s))))))

(deftest rejects-bad-id
  (let [s (tmp-store)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (proto/-upsert! s {:id "../etc" :title "no"})))))
