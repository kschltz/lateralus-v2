(ns kschltz.agent.tools.file-read-policy-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.tools.file-read-policy :as policy])
  (:import [java.io File]))

(deftest coercion-and-byte-count
  (is (= 4 (policy/safe-int "4" 1)))
  (is (= 1 (policy/safe-int "bad" 1)))
  (is (= 2 (policy/byte-count "é"))))

(deftest readable-path-policy-and-error-envelope
  (let [root (doto (File/createTempFile "lateralus-read-policy" "")
               (.delete)
               (.mkdirs))
        inside (io/file root "inside.txt")
        outside (File/createTempFile "lateralus-read-policy-outside" ".txt")]
    (spit inside "inside")
    (try
      (is (= (.getCanonicalPath inside)
             (str (policy/resolve-readable-path
                   (.getAbsolutePath root)
                   "inside.txt"
                   #{".git"}
                   false))))
      (try
        (policy/resolve-readable-path
         (.getAbsolutePath root)
         (.getAbsolutePath outside)
         #{".git"}
         false)
        (is false "outside path should throw")
        (catch clojure.lang.ExceptionInfo e
          (let [parsed (json/parse-string (policy/error-result e) true)]
            (is (= "outside-workspace" (:error parsed)))
            (is (string? (:message parsed))))))
      (finally
        (.delete inside)
        (.delete outside)
        (.delete root)))))
