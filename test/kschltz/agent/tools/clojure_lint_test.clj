(ns kschltz.agent.tools.clojure-lint-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.clojure-lint :as lint])
  (:import [java.io File IOException]))

(defn- invoke
  [t args]
  (-> (tool/invoke-tool t args {})
      (json/parse-string true)))

(deftest lint-validates-paths-and-returns-structured-diagnostics
  (let [root (doto (File/createTempFile "lateralus-lint-test" "")
               (.delete)
               (.mkdirs))
        source (io/file root "sample.clj")
        _ (spit source "(ns sample)\n(defn f [unused] 1)\n")
        calls (atom [])
        runner (fn [workspace paths]
                 (swap! calls conj [workspace paths])
                 {:ok true
                  :engine "stub-kondo"
                  :findings [{:filename (first paths)
                              :message "unused binding"}]
                  :summary {:warning 1}})
        t (lint/clojure-lint (.getAbsolutePath root) {:runner runner})
        result (invoke t {:paths ["sample.clj"]})]
    (try
      (is (true? (:ok result)))
      (is (= "stub-kondo" (:engine result)))
      (is (= 1 (count (:findings result))))
      (is (= 1 (get-in result [:summary :warning])))
      (is (= 1 (count @calls)))
      (is (= (.getCanonicalPath source)
             (-> @calls first second first)))
      (finally
        (.delete source)
        (.delete root)))))

(deftest lint-preserves-workspace-and-tool-availability-guards
  (let [root (doto (File/createTempFile "lateralus-lint-policy" "")
               (.delete)
               (.mkdirs))
        outside (File/createTempFile "lateralus-lint-outside" ".clj")
        _ (spit outside "(ns outside)")
        t (lint/clojure-lint (.getAbsolutePath root)
                             {:runner (fn [_ _] {:ok true})})]
    (try
      (is (= "outside-workspace"
             (:error (invoke t {:paths [(.getAbsolutePath outside)]}))))
      (finally
        (.delete outside)
        (.delete root))))
  (testing "missing local runner is a structured optional-capability error"
    (let [root (doto (File/createTempFile "lateralus-lint-missing" "")
                 (.delete)
                 (.mkdirs))
          source (io/file root "sample.clj")
          _ (spit source "(ns sample)")
          t (lint/clojure-lint
             (.getAbsolutePath root)
             {:runner (fn [_ _]
                        (throw (IOException. "not installed")))})]
      (try
        (let [result (invoke t {:paths ["sample.clj"]})]
          (is (= "diagnostics-unavailable" (:error result)))
          (is (string? (:message result))))
        (finally
          (.delete source)
          (.delete root))))))
