(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'net.clojars.kschltz/lateralus-v2)
(def version "0.1.0-SNAPSHOT")
(def main 'kschltz.lateralus)
(def class-dir "target/classes")

(def uber-file (format "target/%s-%s.jar" lib version))
(def launcher-file "target/lateralus-v2")

(defn test "Run all the tests." [opts]
  (let [basis (b/create-basis {:aliases [:test]})
        cmds  (b/java-command {:basis     basis
                               :main      'clojure.main
                              ;; Exclude the slow ^:e2e tests from the build gate.
                               :main-args ["-m" "cognitect.test-runner" "-e" ":e2e"]
                              ;; Proximum needs Vector API + FFM at runtime.
                               :jvm-opts  ["--add-modules=jdk.incubator.vector"
                                           "--enable-native-access=ALL-UNNAMED"]})]
    (b/process cmds)
    opts))

(defn- uber-opts [opts]
  (assoc opts
         :lib lib :main main
         :uber-file uber-file
         :basis (b/create-basis {})
         :class-dir class-dir
         :src-dirs ["src" "resources"]
         :ns-compile [main]
         ;; Proximum references the incubator Vector API at compile time.
         :java-opts ["--add-modules=jdk.incubator.vector"
                     "--enable-native-access=ALL-UNNAMED"]))

(defn- write-launcher! [jar-path launcher-path]
  (let [launcher-dir (.getParentFile (java.io.File. launcher-path))
        jar-rel      (-> (.toPath launcher-dir)
                         (.relativize (.toPath (java.io.File. jar-path)))
                         str)
        script       (str "#!/usr/bin/env bash\n"
                          "set -euo pipefail\n"
                          "DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\n"
                          "JAR=\"$DIR/" jar-rel "\"\n"
                          ;; Proximum requires the incubator Vector API and FFM.
                          "exec java --add-modules=jdk.incubator.vector \\\n"
                          "     --enable-native-access=ALL-UNNAMED \\\n"
                          "     -jar \"$JAR\" \"$@\"\n")]
    (spit launcher-path script)
    (.setExecutable (java.io.File. launcher-path) true true)
    (println "Wrote launcher" launcher-path)))

(defn- build-uber! [opts]
  (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
  (b/compile-clj opts)
  (b/uber opts)
  (write-launcher! (:uber-file opts) launcher-file))

(defn uber "Build the uberjar and launcher script." [opts]
  (b/delete {:path "target"})
  (build-uber! (uber-opts opts))
  opts)

(defn native "Attempt a GraalVM native-image build (stretch goal)." [opts]
  ;; Step 9 stretch target. Implementing this requires:
  ;;   - GraalVM toolchain installed locally
  ;;   - clj-easy/graal-build-time build-time dep
  ;;   - reflect-config.json for any runtime reflection
  ;;   - native-image invocation against the uber jar
  ;; If it blocks, document the blocker in README.md and CHANGELOG.md
  ;; and ship the JVM uber/launcher as the MVP distributable.
  (throw (ex-info "native-image build not yet implemented; use 'uber' for the JVM distributable"
                  {:status :deferred
                   :next-step "document blocker + JVM fallback in README.md"})))