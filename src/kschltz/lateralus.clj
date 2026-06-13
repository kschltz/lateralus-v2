(ns kschltz.lateralus
  "Lateralus v2 entry point. -main delegates to the CLI in
   kschltz.agent.cli. See goals/lateralus-v2-rewrite/plan.md."
  (:gen-class)
  (:require [kschltz.agent.cli :as cli]))

(defn -main
  [& args]
  (apply cli/-main args))
