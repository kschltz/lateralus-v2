(ns kschltz.agent.evolution.process-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.evolution.process :as process]
            [kschltz.agent.evolution.protocol :as proto])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory
        "lateralus-evolution-process-"
        (make-array FileAttribute 0))))

(deftest runner-enforces-program-and-root-policy
  (let [root (temp-dir)
        runner (process/local-command-runner
                {:allowed-programs #{"/bin/echo"}
                 :allowed-roots [root]})
        base {:cwd root :timeout-ms 1000 :max-output-bytes 1024
              :network :allow}]
    (testing "argv executes without a shell"
      (let [result (proto/-run-command!
                    runner (assoc base :argv ["/bin/echo" "hello"]))]
        (is (= :ok (:status result)))
        (is (= "hello\n" (:stdout result)))))
    (testing "non-allowlisted executables are rejected"
      (is (= :rejected
             (:status
              (proto/-run-command!
               runner (assoc base :argv ["/usr/bin/env"]))))))))

(deftest workspace-isolation-blocks-candidate-code-from-writing-outside
  (let [root (str (doto (java.io.File.
                         (str "target/evolution-sandbox-" (random-uuid)))
                    (.mkdirs)))
        workspace (str (java.io.File. root "workspace"))
        escaped (java.io.File. root "escaped.txt")
        secret (java.io.File. root "secret.txt")
        _ (.mkdirs (java.io.File. workspace))
        _ (spit secret "operator-secret")
        runner (process/local-command-runner
                {:allowed-programs #{"/bin/sh"}
                 :allowed-roots [root]})
        command {:argv ["/bin/sh" "-c"
                        "printf escaped > ../escaped.txt"]
                 :cwd workspace :timeout-ms 1000
                 :max-output-bytes 4096 :network :deny
                 :isolation :workspace}
        result (proto/-run-command! runner command)]
    (let [inside (proto/-run-command!
                  runner
                  (assoc command
                         :argv ["/bin/sh" "-c"
                                "printf allowed > inside.txt"]))]
      (is (= :ok (:status inside)) (pr-str inside))
      (is (= "allowed" (slurp (java.io.File. workspace "inside.txt")))))
    (let [read-secret (proto/-run-command!
                       runner
                       (assoc command
                              :argv ["/bin/sh" "-c"
                                     "cat ../secret.txt"]))]
      (is (not= :ok (:status read-secret)))
      (is (not (str/includes? (:stdout read-secret) "operator-secret"))))
    (is (true? (:workspace-isolated? result)))
    (is (true? (:network-isolated? result)))
    (is (not= :ok (:status result)))
    (is (not (.exists escaped)))))

(deftest precomputed-clojure-classpath-runs-inside-workspace-sandbox
  (let [cwd (System/getProperty "user.dir")
        runner (process/local-command-runner
                {:allowed-programs #{"java"}
                 :allowed-roots [cwd]
                 :classpath-root cwd})
        result (proto/-run-command!
                runner
                {:argv ["java" "-cp" "{{workspace-classpath}}"
                        "clojure.main" "-e" "(println :ok)"]
                 :cwd cwd :timeout-ms 30000 :max-output-bytes 65536
                 :network :deny :isolation :workspace})]
    (is (= :ok (:status result)) (pr-str result))
    (is (str/includes? (:stdout result) ":ok"))
    (is (true? (:workspace-isolated? result)))))

(deftest required-isolation-fails-closed-before-command-start
  (let [root (temp-dir)
        escaped (java.io.File. root "should-not-exist.txt")
        runner (process/local-command-runner
                {:allowed-programs #{"/bin/sh"}
                 :allowed-roots [root]
                 :network-wrapper
                 (fn [argv network isolation _cwd]
                   {:argv argv
                    :network-isolated? (not= :deny network)
                    :workspace-isolated? (not= :workspace isolation)
                    :isolation-backend :none})})
        result (proto/-run-command!
                runner
                {:argv ["/bin/sh" "-c"
                        "printf unsafe > should-not-exist.txt"]
                 :cwd root :timeout-ms 1000 :max-output-bytes 4096
                 :network :deny :isolation :workspace})]
    (is (= :rejected (:status result)))
    (is (str/includes? (:error result) "isolation backend is unavailable"))
    (is (not (.exists escaped)))))

(deftest timeout-kills-process-tree-and-bounds-stream-drain
  (let [root (temp-dir)
        runner (process/local-command-runner
                {:allowed-programs #{"/bin/sh"}
                 :allowed-roots [root]
                 :network-wrapper
                 (fn [argv _network _isolation _cwd]
                   {:argv argv
                    :network-isolated? true
                    :workspace-isolated? true
                    :isolation-backend :custom})})
        started (System/nanoTime)
        result (proto/-run-command!
                runner
                {:argv ["/bin/sh" "-c" "sleep 30 & wait"]
                 :cwd root :timeout-ms 100 :max-output-bytes 4096
                 :network :deny :isolation :workspace})
        elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))]
    (is (= :timeout (:status result)) (pr-str result))
    (is (< elapsed-ms 5000) (str "elapsed-ms=" elapsed-ms))
    (is (= :custom (:isolation-backend result)))))
