(ns kschltz.agent.evolution.wiring
  "Opt-in Integrant graph for the local evolution control plane."
  (:require [integrant.core :as ig]
            [kschltz.agent.evolution.board :as board]
            [kschltz.agent.evolution.candidate :as candidate]
            [kschltz.agent.evolution.evaluator :as evaluator]
            [kschltz.agent.evolution.ledger :as ledger]
            [kschltz.agent.evolution.process :as process]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.worktree :as worktree]
            [kschltz.agent.store.duckdb :as duckdb]
            [kschltz.agent.store.protocol :as store]
            [malli.core :as m]))

(derive :lateralus/evolution-verifier-runner
        :lateralus/evolution-command-runner)

(defn- assert-schema!
  [key schema value]
  (when-not (m/validate schema value)
    (throw (ex-info "Invalid evolution Integrant config"
                    {:key key :explain (m/explain schema value)})))
  value)

(defmethod ig/assert-key :lateralus/evolution-store [_ config]
  (assert-schema! :lateralus/evolution-store
                  [:map [:path [:string {:min 1}]]] config))

(defmethod ig/assert-key :lateralus/evolution-command-runner [_ config]
  (assert-schema! :lateralus/evolution-command-runner
                  process/RunnerOpts config))

(defmethod ig/assert-key :lateralus/evolution-board [_ config]
  (assert-schema! :lateralus/evolution-board board/BoardOpts config))

(defmethod ig/assert-key :lateralus/evolution-workspace [_ config]
  (assert-schema! :lateralus/evolution-workspace worktree/WorkspaceOpts config))

(defmethod ig/assert-key :lateralus/evolution-ledger [_ config]
  (assert-schema! :lateralus/evolution-ledger
                  [:map [:store [:fn store/store-engine?]]] config))

(defmethod ig/assert-key :lateralus/evolution-evaluator [_ config]
  (assert-schema! :lateralus/evolution-evaluator
                  [:map [:runner [:fn proto/command-runner?]]] config))

(defmethod ig/assert-key :lateralus/evolution-candidate [_ config]
  (assert-schema! :lateralus/evolution-candidate
                  [:map [:agent-map :map]] config))

(defmethod ig/init-key :lateralus/evolution-store [_ config]
  (duckdb/duckdb-store config))

(defmethod ig/halt-key! :lateralus/evolution-store [_ engine]
  (store/-close engine))

(defmethod ig/init-key :lateralus/evolution-command-runner [_ config]
  (process/local-command-runner config))

(defmethod ig/init-key :lateralus/evolution-board [_ config]
  (board/kb-board config))

(defmethod ig/init-key :lateralus/evolution-workspace [_ config]
  (worktree/git-workspace-manager config))

(defmethod ig/init-key :lateralus/evolution-ledger [_ {:keys [store]}]
  (ledger/store-ledger store))

(defmethod ig/init-key :lateralus/evolution-evaluator [_ {:keys [runner]}]
  (evaluator/command-evaluator runner))

(defmethod ig/init-key :lateralus/evolution-candidate [_ {:keys [agent-map]}]
  (candidate/agent-candidate-runner agent-map))
