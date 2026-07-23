(ns kschltz.agent.cli.profile.store-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.cli.profile.store :as store])
  (:import [java.util UUID]))

(defn- temp-root []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "lat-prof-" (UUID/randomUUID)))
    (.mkdirs)))

(deftest write-read-list-active
  (let [root (temp-root)]
    (is (= [] (store/list-profiles root)))
    (store/write-profile! root "default"
                          {:backend :ollama-local :model "m1" :workbench? false})
    (store/write-profile! root "cloud"
                          {:backend :ollama-cloud :workbench? false})
    (is (= ["cloud" "default"] (store/list-profiles root)))
    (is (= "m1" (:model (store/read-profile root "default"))))
    (store/set-active! root "default")
    (is (= "default" (store/active-profile root)))
    (is (= "m1" (:model (store/load-active-settings root))))
    (is (false? (contains? (store/read-profile root "default") :api-key)))))
