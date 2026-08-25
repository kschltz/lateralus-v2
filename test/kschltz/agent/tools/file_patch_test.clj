(ns kschltz.agent.tools.file-patch-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.file-patch :as file-patch]
            [kschltz.agent.tools.file-safety :as fs])
  (:import [java.io File]
           [java.nio.file Files]))

(def ^:private tmp-dir
  (delay
    (let [dir (File/createTempFile "lateralus-patch-test" "")]
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

(defn- temp-file
  [path content]
  (let [f (io/file @tmp-dir path)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- sha
  [^File file]
  (fs/sha256 (Files/readAllBytes (.toPath file))))

(defn- invoke
  [file args]
  (-> (tool/invoke-tool (file-patch/file-patch (str @tmp-dir))
                        (assoc args :path (.getName ^File file))
                        {})
      (json/parse-string true)))

(deftest patch-replaces-and-inserts-lines-from-snapshot
  (let [f (temp-file "sample.txt" "a\nb\nc\n")
        first-result (invoke f
                             {:expected-sha256 (sha f)
                              :patches [{:start-line 2
                                         :end-line 2
                                         :replacement "B\n"}]})]
    (is (true? (:changed first-result)))
    (is (= "a\nB\nc\n" (slurp f)))
    (is (= 1 (:patches-applied first-result)))
    (is (.exists (io/file (:backup-path first-result))))
    (is (= "a\nb\nc\n" (slurp (io/file (:backup-path first-result)))))
    (let [second-result (invoke f
                                {:expected-sha256 (:sha256 first-result)
                                 :patches [{:start-line 2
                                            :end-line 1
                                            :replacement "inserted\n"}]})]
      (is (true? (:changed second-result)))
      (is (= "a\ninserted\nB\nc\n" (slurp f))))))

(deftest patch-applies-multiple-non-overlapping-ranges
  (let [f (temp-file "multi.txt" "one\ntwo\nthree\nfour\n")
        result (invoke f
                       {:expected-sha256 (sha f)
                        :patches [{:start-line 1 :end-line 1
                                   :replacement "ONE\n"}
                                  {:start-line 4 :end-line 4
                                   :replacement "FOUR\n"}]})]
    (is (= 2 (:patches-applied result)))
    (is (= "ONE\ntwo\nthree\nFOUR\n" (slurp f)))))

(deftest stale-or-invalid-patches-perform-zero-writes
  (let [f (temp-file "safe.txt" "a\nb\nc\n")
        original (slurp f)]
    (testing "stale hash"
      (let [result (invoke f
                           {:expected-sha256 (apply str (repeat 64 "0"))
                            :patches [{:start-line 1 :end-line 1
                                       :replacement "x\n"}]})]
        (is (= "stale-file" (:error result)))
        (is (= original (slurp f)))))
    (testing "overlap"
      (let [result (invoke f
                           {:expected-sha256 (sha f)
                            :patches [{:start-line 1 :end-line 2
                                       :replacement "x\n"}
                                      {:start-line 2 :end-line 3
                                       :replacement "y\n"}]})]
        (is (= "overlap" (:error result)))
        (is (= original (slurp f)))))
    (testing "line out of range"
      (let [result (invoke f
                           {:expected-sha256 (sha f)
                            :patches [{:start-line 20 :end-line 20
                                       :replacement "x\n"}]})]
        (is (= "line-out-of-range" (:error result)))
        (is (= original (slurp f)))))))

(deftest patch-validates-clojure-before-commit
  (let [f (temp-file "source.clj" "(ns source)\n(defn ok [] true)\n")
        original (slurp f)
        result (invoke f
                       {:expected-sha256 (sha f)
                        :patches [{:start-line 2 :end-line 2
                                   :replacement "(defn broken [\n"}]})]
    (is (= "clojure-round-trip-failed" (:error result)))
    (is (= original (slurp f)))))

(deftest patch-rejects-binary-and-blocked-targets
  (testing "binary UTF-8 decoding"
    (let [f (io/file @tmp-dir "binary.dat")]
      (with-open [out (io/output-stream f)]
        (.write out (byte-array [(unchecked-byte 0xC3) (byte 0x28)])))
      (let [result (invoke f
                           {:expected-sha256 (sha f)
                            :patches [{:start-line 1 :end-line 1
                                       :replacement "text\n"}]})]
        (is (= "binary-file" (:error result))))))
  (testing "blocked path"
    (let [f (temp-file ".git/config" "secret\n")
          result (-> (tool/invoke-tool
                      (file-patch/file-patch (str @tmp-dir))
                      {:path ".git/config"
                       :expected-sha256 (sha f)
                       :patches [{:start-line 1 :end-line 1
                                  :replacement "changed\n"}]}
                      {})
                     (json/parse-string true))]
      (is (= "blocked-path" (:error result)))
      (is (= "secret\n" (slurp f))))))
