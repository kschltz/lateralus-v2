(ns check-open-design-mcp
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.system]
            [kschltz.agent.tool :as tool]))

(def config (ig/read-string (slurp (io/file "resources/lateralus/open-design-mcp.edn"))))

(def system (ig/init config))

(def registry (-> system :lateralus/tool-registry))

(println "=== Open Design MCP tools registered ===")
(doseq [tool-name (sort (filter #(str/starts-with? % "open_design_") (keys registry)))]
  (println tool-name))

(println "\n=== Total tools in :lateralus/tool-registry ===" (count registry))

(println "\n=== Calling open_design_list_projects ===")
(let [list-projects (get registry "open_design_list_projects")]
  (println (tool/invoke-tool list-projects {} {})))

(ig/halt! system)
(println "\nHalted.")

(defn -main [& _])
