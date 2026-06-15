(ns kschltz.agent.memory.store.file-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.memory.store.file :as store])
  (:import [java.io File]))

(def ^:private tmp-root
  (io/file (System/getProperty "java.io.tmpdir") (str "store-file-test-" (System/nanoTime))))

(use-fixtures :each
  (fn [test]
    (.mkdirs tmp-root)
    (try
      (test)
      (finally
        (run! io/delete-file (reverse (file-seq tmp-root)))))))

(deftest read-lines-empty-file
  (is (= [] (store/read-lines (io/file tmp-root "nonexistent" "messages.edn")))))

(deftest append-and-read-lines
  (let [f (io/file tmp-root "s1" "messages.edn")]
    (store/append-line! f {:msg-id "m1" :content "hello"})
    (store/append-line! f {:msg-id "m2" :content "world"})
    (is (= [{:msg-id "m1" :content "hello"}
            {:msg-id "m2" :content "world"}]
           (store/read-lines f)))))

(deftest write-and-read-index
  (let [dir (io/file tmp-root "s2")]
    (store/write-index! dir {:inverted {"a" {"m1" [1]}} :graph {"a" #{"m1"}}})
    (is (= {:inverted {"a" {"m1" [1]}} :graph {"a" #{"m1"}}}
           (store/read-index dir)))))

(deftest read-index-coerces-non-map-to-empty
  (let [dir (io/file tmp-root "s3")]
    (store/write-file! (store/index-file dir) "string")
    (is (= {} (store/read-index dir)))))

(deftest safe-reader-rejects-tagged-literals
  (let [f (store/messages-file (io/file tmp-root "s4"))]
    (io/make-parents f)
    (spit f "#foo [1 2]\n")
    (is (thrown? RuntimeException (store/read-lines f)))))

(deftest read-skips-empty-lines
  (let [f (store/messages-file (io/file tmp-root "s5"))]
    (io/make-parents f)
    (spit f "{:msg-id \"m1\"}\n\n{:msg-id \"m2\"}\n")
    (is (= [{:msg-id "m1"} {:msg-id "m2"}] (store/read-lines f)))))
