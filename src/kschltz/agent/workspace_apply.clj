(ns kschltz.agent.workspace-apply
  "Shared workspace-root transition application for HTTP settings and tests."
  (:require [kschltz.agent.tools.factory.protocol :as factory.proto]
            [kschltz.agent.transitions :as tr]
            [kschltz.agent.workspace :as workspace]))

(defn apply-workspace-root!
  "Validate and persist `:set-workspace-root` on `runtime`.
   Returns `{:ok true}` or `{:ok false :error …}`."
  [runtime op]
  (let [root (:workspace-root op)
        err (workspace/validate-root root)]
    (cond
      err
      {:ok false :error err}

      :else
      (try
        (when-let [factory (:agent/factory-session (:agent-map runtime))]
          (when (factory.proto/runtime-tool-store? factory)
            (factory.proto/-set-workspace-root! factory root)))
        (swap! (:state runtime) tr/apply-transition op)
        {:ok true :op (:op op)}
        (catch Throwable t
          {:ok false
           :error (or (ex-message t) (.getName (class t)))})))))
