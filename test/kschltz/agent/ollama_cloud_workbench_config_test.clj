(ns kschltz.agent.ollama-cloud-workbench-config-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [integrant.core :as ig]))

(def config-path
  "resources/lateralus/ollama-cloud-workbench.edn")

(deftest cloud-workbench-config-is-safe-and-complete
  (let [file (io/file config-path)]
    (is (.isFile file))
    (let [config (ig/read-string (slurp file))]
      (is (= "https://ollama.com/v1"
             (get-in config [:lateralus/llm-client :base-url])))
      (is (= "gpt-oss:20b"
             (get-in config [:lateralus/llm-client :model])))
      (is (true? (get-in config [:lateralus/workbench :enabled?])))
      (is (true? (get-in config [:lateralus/workbench :portal?])))
      (is (ig/reflike?
           (get-in config [:lateralus/workbench :secret-store])))
      (is (= :none (get-in config [:lateralus/web-tools :provider])))
      (is (false? (get-in config [:lateralus/runtime-tools :enabled?])))
      (is (false? (get-in config [:lateralus/runtime-tools :network?])))
      (is (ig/reflike?
           (get-in config [:lateralus/factory-session :secret-store])))
      (is (true? (get-in config
                         [:lateralus/factory-session :sandbox :enabled?])))
      (is (= #{"secret_check"}
             (get-in config
                     [:lateralus/factory-session :sandbox :call-tools])))
      (is (= {:labels :all}
             (get-in config
                     [:lateralus/secret-plugin
                      :capabilities
                      "secret_check"])))
      (is (= 10 (get-in config
                        [:lateralus/loop-opts :max-loop-depth])))
      (is (some #{(ig/ref :lateralus/factory-tools)}
                (:lateralus/tool-registry config)))
      (is (some #{(ig/ref :lateralus/workbench-tools)}
                (:lateralus/tool-registry config)))
      (is (= (ig/ref :lateralus/secret-plugin)
             (some #{(ig/ref :lateralus/secret-plugin)}
                   (:lateralus/plugins config)))))))
