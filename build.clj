(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]))

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

(defn native-uber-opts [opts]
  (assoc opts
         :lib lib :main main
         :uber-file "target/lateralus-v2-native.jar"
         :class-dir class-dir
         :basis (b/create-basis {:aliases [:native]})
         :src-dirs ["resources" "src"]
         :ns-compile [main
                      'kschltz.agent.llm.http
                      'kschltz.agent.memory.http-embedding
                      'kschltz.agent.tool
                      'kschltz.agent.loop
                      'kschltz.agent.plugins.tools
                      'kschltz.agent.tools.filesystem
                      'kschltz.agent.tools.self
                      'kschltz.agent.tools.clojure
                      'kschltz.agent.tools.clojure-impl]
         ;; Direct-linking improves native-image size, startup, and correctness.
         :java-opts ["-Dclojure.compiler.direct-linking=true"]))

(defn- build-native-uber!
  "Copy source files to class-dir, excluding JVM-only namespaces that
   are not on the native classpath, then compile and uberjar."
  [opts]
  (b/delete {:path "target"})
  (let [exclude? #{"src/kschltz/agent/memory/proximum_backend.clj"
                   "src/kschltz/agent/memory/langchain4j_embedding.clj"}]
    ;; Recreate class-dir with filtered source tree.
    (.mkdirs (java.io.File. class-dir))
    (doseq [src-dir ["resources" "src"]
            ^java.io.File f (file-seq (java.io.File. src-dir))
            :when (.isFile f)]
      (let [rel (.substring (.getPath f) (inc (count src-dir)))
            dest (java.io.File. class-dir rel)]
        (when-not (exclude? (str src-dir "/" rel))
          (.mkdirs (.getParentFile dest))
          (b/copy-file {:src (.getPath f) :target (.getPath dest)})))))
  ;; Catch reflective calls that native-image may not be able to resolve.
  (set! *warn-on-reflection* true)
  (b/compile-clj (native-uber-opts opts))
  (b/uber (native-uber-opts opts)))

(defn- graalvm-home
  "Return GRAALVM_HOME from the environment, or throw a clear error."
  []
  (or (System/getenv "GRAALVM_HOME")
      (throw (ex-info "GRAALVM_HOME is not set" {:hint "Install GraalVM and export GRAALVM_HOME=/path/to/graalvm"}))))

(defn native-image-args
  "Return the native-image command-line vector used by the build task.
   Exposed so tests can assert the required flags are present."
  []
  [(str (graalvm-home) "/bin/native-image")
   "-cp" "target/lateralus-v2-native.jar"
   "--initialize-at-build-time=com.fasterxml.jackson"
   "-H:+UnlockExperimentalVMOptions"
   "-H:Name=target/lateralus-v2-native"
   "-H:Path=."
   "-H:Class=kschltz.lateralus"
   "-H:EnableURLProtocols=http,https"
   "--features=clj_easy.graal_build_time.InitClojureClasses"
   "--no-fallback"
   "-O2"])

(defn native "Build a native executable for the KG + BM25 backend." [opts]
  (build-native-uber! opts)
  (b/process {:command-args (native-image-args)})
  opts)

(defn uber "Build the uberjar and launcher script." [opts]
  (b/delete {:path "target"})
  (build-uber! (uber-opts opts))
  opts)
