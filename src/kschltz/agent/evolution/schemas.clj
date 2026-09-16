(ns kschltz.agent.evolution.schemas
  "Closed data contracts for bounded self-evolution runs."
  (:require [malli.core :as m]))

(def Phase
  [:enum :observe :propose :baseline :isolate :implement :verify
   :handoff :rejected :blocked])

(def Proposal
  [:map {:closed true}
   [:id [:string {:min 1}]]
   [:title [:string {:min 1}]]
   [:objective [:string {:min 1}]]
   [:evidence [:vector {:min 1} [:string {:min 1}]]]
   [:acceptance [:vector {:min 1} [:string {:min 1}]]]
   [:paths {:optional true} [:vector [:string {:min 1}]]]
   [:risk {:optional true} [:enum :low :medium :high]]
   [:source {:optional true} [:enum :board :self-diagnosis]]])

(def Policy
  [:map {:closed true}
   [:max-wall-ms [:int {:min 1}]]
   [:max-candidates [:int {:min 1 :max 10}]]
   [:max-agent-turns [:int {:min 1 :max 50}]]
   [:max-tool-calls [:int {:min 1}]]
   [:max-token-usage [:int {:min 1}]]
   [:max-changed-files [:int {:min 1}]]
   [:max-output-bytes [:int {:min 1024}]]
   [:allowed-paths [:vector [:string {:min 1}]]]
   [:protected-paths [:vector [:string {:min 1}]]]
   [:require-network-isolation? :boolean]
   [:require-workspace-isolation? :boolean]])

(def GateSpec
  [:map {:closed true}
   [:id [:string {:min 1}]]
   [:kind [:enum :regression :acceptance]]
   [:argv [:vector {:min 1} [:string {:min 1}]]]
   [:timeout-ms [:int {:min 1}]]
   [:network [:enum :deny :allow]]
   [:candidate-paths {:optional true} [:vector [:string {:min 1}]]]
   [:protected-paths {:optional true} [:vector [:string {:min 1}]]]
   [:required? :boolean]])

(def CommandSpec
  [:map {:closed true}
   [:argv [:vector {:min 1} [:string {:min 1}]]]
   [:cwd [:string {:min 1}]]
   [:timeout-ms [:int {:min 1}]]
   [:max-output-bytes [:int {:min 1}]]
   [:env {:optional true} [:map-of :string :string]]
   [:isolation {:optional true} [:enum :none :network-only :workspace]]
   [:network [:enum :deny :allow]]])

(def CommandResult
  [:map {:closed true}
   [:status [:enum :ok :failed :timeout :rejected]]
   [:exit-code [:maybe :int]]
   [:stdout :string]
   [:stderr :string]
   [:duration-ms [:int {:min 0}]]
   [:truncated? :boolean]
   [:network-isolated? :boolean]
   [:workspace-isolated? :boolean]
   [:error {:optional true} [:maybe :string]]])

(def GateResult
  [:map {:closed true}
   [:id [:string {:min 1}]]
   [:kind [:enum :regression :acceptance]]
   [:required? :boolean]
   [:passed? :boolean]
   [:command CommandResult]])

(def Candidate
  [:map {:closed true}
   [:id [:string {:min 1}]]
   [:branch [:string {:min 1}]]
   [:worktree [:string {:min 1}]]
   [:base [:string {:min 1}]]])

(def ImplementationResult
  [:map {:closed true}
   [:status [:enum :completed :failed :budget-exhausted]]
   [:turns [:int {:min 0}]]
   [:tool-calls [:int {:min 0}]]
   [:token-usage [:int {:min 0}]]
   [:summary :string]
   [:error {:optional true} [:maybe :string]]])

(def RunState
  [:map {:closed true}
   [:id [:string {:min 1}]]
   [:phase Phase]
   [:proposal Proposal]
   [:policy Policy]
   [:started-at [:int {:min 0}]]
   [:updated-at [:int {:min 0}]]
   [:candidate {:optional true} Candidate]
   [:baseline {:optional true} [:vector GateResult]]
   [:verification {:optional true} [:vector GateResult]]
   [:diff {:optional true} :string]
   [:reason {:optional true} :string]])

(def Event
  [:map {:closed true}
   [:id [:string {:min 1}]]
   [:run-id [:string {:min 1}]]
   [:type :keyword]
   [:phase Phase]
   [:ts [:int {:min 0}]]
   [:payload :map]])

(defn valid?
  [schema value]
  (m/validate schema value))

(defn explain
  [schema value]
  (m/explain schema value))
