(ns kschltz.agent.tools.file-changeset-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.file-changeset :as changeset]
            [kschltz.agent.tools.file-safety :as fs])
  (:import [java.io File]))

(def ^:private tmp-dir
  (delay
    (let [dir (File/createTempFile "lateralus-changeset-test" "")]
      (.delete dir)
      (.mkdirs dir)
      (.deleteOnExit dir)
      dir)))

(use-fixtures :each
  (fn [f]
    (doseq [^File entry (reverse (file-seq @tmp-dir))]
      (when (not= entry @tmp-dir)
        (.delete entry)))
    (f)))

(defn- invoke
  [args]
  (-> (tool/invoke-tool (changeset/file-change-set (str @tmp-dir)) args {})
      (json/parse-string true)))

(deftest creates-clojure-and-updates-caller-in-one-transaction
  (let [caller (io/file @tmp-dir "src/demo/core.clj")
        created (io/file @tmp-dir "src/demo/slug.clj")]
    (io/make-parents caller)
    (spit caller "(ns demo.core)\n")
    (let [result
          (invoke
           {:changes
            [{:op :create
              :path "src/demo/slug.clj"
              :content "(ns demo.slug)\n(defn slug [s] s)\n"}
             {:op :replace
              :path "src/demo/core.clj"
              :content "(ns demo.core (:require [demo.slug :as slug]))\n"
              :expected-sha256 (fs/sha256-file (.toPath caller))}]})]
      (is (true? (:ok result)))
      (is (true? (:changed result)))
      (is (= "not-needed" (:rollback result)))
      (is (= 2 (count (:changes result))))
      (is (= "(ns demo.slug)\n(defn slug [s] s)\n" (slurp created)))
      (is (= "(ns demo.core (:require [demo.slug :as slug]))\n"
             (slurp caller))))))

(deftest invalid-change-performs-zero-writes
  (let [existing (io/file @tmp-dir "existing.txt")]
    (spit existing "before\n")
    (let [result (invoke {:changes [{:op :replace
                                    :path "existing.txt"
                                    :content "after\n"}
                                   {:op :create
                                    :path "broken.clj"
                                    :content "(ns broken\n"}]})]
      (is (false? (:ok result)))
      (is (= "clojure-round-trip-failed" (:error result)))
      (is (= "before\n" (slurp existing)))
      (is (not (.exists (io/file @tmp-dir "broken.clj")))))))

(deftest dry-run-validates-without-writing
  (let [result (invoke {:dry-run true
                        :changes [{:op :create
                                   :path "new.edn"
                                   :content "{:ready true}\n"}]})]
    (is (true? (:ok result)))
    (is (true? (:dry-run result)))
    (is (false? (:changed result)))
    (is (not (.exists (io/file @tmp-dir "new.edn"))))))

(deftest failed-commit-rolls-back-earlier-writes
  (let [existing (io/file @tmp-dir "existing.txt")
        created (io/file @tmp-dir "new.txt")
        write! fs/write-atomically!
        calls (atom 0)]
    (spit existing "before\n")
    (with-redefs [fs/write-atomically!
                  (fn [path content]
                    (let [call (swap! calls inc)]
                      (if (= call 2)
                        (throw (ex-info "injected failure" {:error :injected}))
                        (write! path content))))]
      (let [result (invoke {:changes [{:op :replace
                                      :path "existing.txt"
                                      :content "after\n"}
                                     {:op :create
                                      :path "new.txt"
                                      :content "new\n"}]})]
        (is (false? (:ok result)))
        (is (= "transaction-failed" (:error result)))
        (is (= "completed" (:rollback result)))
        (is (= "before\n" (slurp existing)))
        (is (not (.exists created)))))))

(deftest stale-duplicate-and-blocked-changes-are-rejected
  (let [existing (io/file @tmp-dir "existing.txt")]
    (spit existing "before\n")
    (testing "stale snapshot"
      (is (= "stale-file"
             (:error
              (invoke {:changes [{:op :delete
                                  :path "existing.txt"
                                  :expected-sha256 (apply str (repeat 64 "0"))}]})))))
    (testing "duplicate path"
      (is (= "duplicate-path"
             (:error
              (invoke {:changes [{:op :replace
                                  :path "existing.txt"
                                  :content "one\n"}
                                 {:op :delete
                                  :path "existing.txt"}]})))))
    (testing "blocked path"
      (is (= "blocked-path"
             (:error
              (invoke {:changes [{:op :create
                                  :path ".git/config"
                                  :content "unsafe\n"}]})))))))
