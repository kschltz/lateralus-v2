(ns kschltz.agent.cli.profile.setup-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.cli.profile.setup :as setup]
            [kschltz.agent.cli.profile.store :as store])
  (:import [java.util UUID]))

(defn- temp-root []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "lat-setup-" (UUID/randomUUID)))
    (.mkdirs)))

(defn- scripted
  [lines]
  (let [q (atom lines)]
    (fn []
      (let [l (first @q)]
        (swap! q rest)
        l))))

(deftest load-quietly-applies-active
  (let [root (temp-root)]
    (store/write-profile! root "default" {:backend :ollama-local :model "x"})
    (store/set-active! root "default")
    (let [opts (setup/load-quietly {} root)]
      (is (= "default" (:profile-name opts)))
      (is (= :http (get-in opts [:profile-edn :lateralus/llm-client :impl])))
      (is (= "x" (get-in opts [:profile-edn :lateralus/llm-client :model]))))))

(deftest wizard-keep-all-existing-profile
  (let [root (temp-root)
        out  (java.io.StringWriter.)]
    (store/write-profile! root "default"
                          {:backend :ollama-local :model "kept" :workbench? false})
    (store/set-active! root "default")
    ;; Use profile [1], Keep ALL [Y]
    (let [opts (setup/run-wizard {}
                 {:out out
                  :profile-root root
                  :read-line-fn (scripted ["" "Y"])
                  :list-models-fn (fn [_ _] [])})]
      (is (= "kept" (get-in opts [:profile-edn :lateralus/llm-client :model])))
      (is (str/includes? (str out) "Keep ALL current values")))))

(deftest wizard-first-run-creates-default
  (let [root (temp-root)
        out  (java.io.StringWriter.)
        ;; starter 1, then Enter through edit fields, save name default
        lines ["1"          ; starter local
               ""           ; backend keep
               ""           ; url keep
               ""           ; model keep
               ""           ; web keep
               ""           ; workbench keep
               "default"]   ; name
        opts (setup/run-wizard {}
               {:out out
                :profile-root root
                :read-line-fn (scripted lines)
                :list-models-fn (fn [_ _] [])})]
    (is (= "default" (:profile-name opts)))
    (is (= ["default"] (store/list-profiles root)))
    (is (str/includes? (str out) "never saved"))))

(deftest ensure-profile-skips-when-config-flag
  (let [opts {:config "/tmp/x.edn"}
        out  (java.io.StringWriter.)]
    (is (= opts (setup/ensure-profile opts out {:tty? true})))))

(deftest ensure-profile-quiet-without-tty
  (let [root (temp-root)
        out  (java.io.StringWriter.)]
    (store/write-profile! root "default" {:backend :ollama-cloud :model "c"})
    (store/set-active! root "default")
    (let [opts (setup/ensure-profile {} out {:tty? false :profile-root root})]
      (is (= "c" (get-in opts [:profile-edn :lateralus/llm-client :model]))))))


(deftest ensure-profile-tolerates-nil-read-line-fn-seam
  (testing "run-cli passes :read-line-fn nil from destructuring; must not NPE"
    (let [root (temp-root)
          out  (java.io.StringWriter.)
          lines (atom ["1" "" "" "" "" "" "default"])
          opts (setup/ensure-profile {:action :interactive} out
                 {:tty? true
                  :profile-root root
                  :read-line-fn nil
                  :list-models-fn (fn [_ _] [])})]
      ;; nil seam must resolve to default-read-line; with no console in CI
      ;; that falls through to read-line → EOF → no-tty → unchanged opts.
      ;; Drive a second call with a scripted fn to prove the happy path still works
      ;; after the nil-tolerant `or` fix (same code path).
      (let [opts2 (setup/ensure-profile {:action :interactive} (java.io.StringWriter.)
                    {:tty? true
                     :profile-root root
                     :read-line-fn (scripted ["1" "" "" "" "" "" "default"])
                     :list-models-fn (fn [_ _] [])})]
        (is (= "default" (:profile-name opts2)))
        (is (some? (:profile-edn opts2)))
        ;; And the nil seam must not throw:
        (is (map? opts))))))
