(ns kschltz.agent.build-test
  "Tests for the native-image build configuration.

   These tests read deps.edn and build.clj directly so they do not
   need the build namespace on the test classpath, and they do not
   require a live GraalVM installation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- read-deps []
  (-> (io/file "deps.edn")
      slurp
      edn/read-string))

(defn- read-build-clj []
  (-> (io/file "build.clj")
      slurp))

(deftest native-alias-includes-graal-build-time
  (testing ":native alias depends on com.github.clj-easy/graal-build-time"
    (let [deps (read-deps)
          native-deps (get-in deps [:aliases :native :replace-deps])]
      (is (some? (get native-deps 'com.github.clj-easy/graal-build-time))
          "graal-build-time must be on the native classpath")
      ;; tools.cli was accidentally dropped by :replace-deps in earlier versions.
      (is (some? (get native-deps 'org.clojure/tools.cli))
          "tools.cli must be restored to the native classpath"))))

(deftest native-alias-enables-direct-linking
  (testing ":native alias passes -Dclojure.compiler.direct-linking=true"
    (let [jvm-opts (get-in (read-deps) [:aliases :native :jvm-opts])]
      (is (some #{"-Dclojure.compiler.direct-linking=true"} jvm-opts)
          "native build must enable direct-linking"))))

(deftest build-clj-uses-graal-build-time-feature
  (testing "build.clj invokes native-image with the clj-easy feature"
    (is (str/includes? (read-build-clj)
                       "--features=clj_easy.graal_build_time.InitClojureClasses")
        "native-image command must register the clj-easy build-time feature")))

(deftest build-clj-enables-direct-linking-for-native-compile
  (testing "build.clj passes direct-linking to the native compile step"
    (is (str/includes? (read-build-clj)
                       "-Dclojure.compiler.direct-linking=true")
        "native compile must set direct-linking")))
