(ns kschltz.agent.tools.runtime.convergence-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools.runtime.convergence :as conv]))

(deftest version-older?-compares-dotted-triples
  (is (true? (conv/version-older? "3.31.0" "3.148.0")))
  (is (false? (conv/version-older? "3.148.0" "3.31.0")))
  (is (false? (conv/version-older? "3.148.0" "3.148.0"))))

(deftest preflight-detects-encore-under-clerk
  (testing "basis encore 3.31.0 vs clerk's nippy need"
    (let [libs {'com.taoensso/encore {:mvn/version "3.31.0"}}
          hit (conv/preflight "io.github.nextjournal/clerk" libs)]
      (is (= "dep-convergence" (:reason hit)))
      (is (= "3.31.0" (get-in hit [:conflicts 0 :basis])))
      (is (= "3.148.0" (get-in hit [:conflicts 0 :needed])))))
  (testing "converged encore is clean"
    (is (nil? (conv/preflight "io.github.nextjournal/clerk"
                              {'com.taoensso/encore {:mvn/version "3.148.0"}})))))
