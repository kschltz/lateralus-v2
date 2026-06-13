(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'net.clojars.kschltz/lateralus-v2)
(def version "0.1.0-SNAPSHOT")
(def main 'kschltz.lateralus)
(def class-dir "target/classes")

(def datalevin-jvm-opts
  ["--add-opens=java.base/java.nio=ALL-UNNAMED"
   "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
   "--enable-native-access=ALL-UNNAMED"])

(def datalevin-manifest-add-opens
  "java.base/java.nio ALL-UNNAMED java.base/sun.nio.ch ALL-UNNAMED")

(def uber-file (format "target/%s-%s.jar" lib version))
(def launcher-file "target/lateralus-v2")

(defn test "Run all the tests." [opts]
  (let [basis (b/create-basis {:aliases [:test :jvm-base]})
        cmds  (b/java-command {:basis     basis
                               :main      'clojure.main
                               :main-args ["-m" "cognitect.test-runner"]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn- uber-opts [opts]
  (assoc opts
         :lib lib :main main
         :uber-file uber-file
         :basis (b/create-basis {})
         :class-dir class-dir
         :src-dirs ["src"]
         :ns-compile [main]
         :manifest {"Add-Opens" datalevin-manifest-add-opens}))

(defn- write-launcher! [jar-path launcher-path]
  (let [launcher-dir (.getParentFile (java.io.File. launcher-path))
        jar-rel      (-> (.toPath launcher-dir)
                         (.relativize (.toPath (java.io.File. jar-path)))
                         str)
        script       (str "#!/usr/bin/env bash\n"
                          "set -euo pipefail\n"
                          "DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\n"
                          "JAR=\"$DIR/" jar-rel "\"\n"
                          "exec java "
                          (str/join " " datalevin-jvm-opts)
                          " -jar \"$JAR\" \"$@\"\n")]
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
