(ns kschltz.agent.evolution.candidate
  "Constrained candidate runtime with supervisor-enforced exchange budgets."
  (:require [clojure.string :as str]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.schemas :as schemas]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.workspace :as workspace]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def ^:private completion-marker "EVOLUTION_DONE")

(def ^:private safe-tool-names
  #{"file_read" "file_list" "file_info" "file_search" "file_glob"
    "file_create" "file_write" "file_update" "file_patch"
    "file_change_set" "file_edits"
    "clojure_query" "clojure_add_require" "clojure_remove_def"
    "clojure_rename_symbol" "clojure_insert_form" "clojure_edit_def"
    "clojure_format_file" "clojure_lint"
    "self_status" "runtime_describe"})

(deftype CandidateTool [delegate]
  tool/Tool
  (-name [_] (tool/-name delegate))
  (-description [_] (tool/-description delegate))
  (-input-schema [_] (tool/-input-schema delegate))
  (-output-schema [_] (tool/-output-schema delegate))
  (-invoke [_ args ctx]
    (tool/-invoke delegate (assoc args :force false) ctx)))

(defn- static-registry
  [agent-map]
  (or (some :registry (:exchange-chain agent-map)) {}))

(defn- candidate-registry
  [agent-map candidate]
  (let [registry (static-registry agent-map)
        opts (or (:agent/workspace-tool-opts agent-map)
                 {:file-tools {} :clojure-tools {}})
        rebound (workspace/rebind-registry registry opts
                                           (:worktree candidate))]
    (into {}
          (map (fn [[name implementation]]
                 [name (->CandidateTool implementation)]))
          (select-keys rebound safe-tool-names))))

(defn- task-prompt
  [proposal]
  (str "Implement one bounded evolution candidate.\n"
       "Title: " (:title proposal) "\n"
       "Objective: " (:objective proposal) "\n"
       "Evidence:\n- " (str/join "\n- " (:evidence proposal)) "\n"
       "Acceptance:\n- " (str/join "\n- " (:acceptance proposal)) "\n"
       (when (seq (:paths proposal))
         (str "Expected paths:\n- " (str/join "\n- " (:paths proposal)) "\n"))
       "Work only inside the configured workspace. Do not alter evolution "
       "policy, verifier code, dependencies, secrets, git metadata, or network "
       "configuration. Use file and structured Clojure tools, then lint the "
       "changed Clojure files. End the final response with the exact marker "
       completion-marker " only when implementation is complete."))

(defn- tool-history-count
  [rt]
  (count (filter #(= "tool" (:role %))
                 (:agent/history @(:state rt)))))

(def ^:private runtime-timeout ::runtime-timeout)

(defn- send-message-bounded
  [rt prompt timeout-ms]
  (let [task (future (runtime/send-message rt prompt))
        result (deref task timeout-ms runtime-timeout)]
    (when (= runtime-timeout result)
      (future-cancel task))
    result))

(defn- candidate-agent-map
  [agent-map candidate policy]
  (let [initial (:initial-state agent-map)
        registry (candidate-registry agent-map candidate)
        chain (plugin/assemble-chain
               [(plugins.base/base-plugin)
                (plugins.tools/tools-plugin registry)])
        system-message
        (str (or (:agent/system-message initial) "lateralus-v2")
             "\nYou are an isolated implementation worker. The deterministic "
             "supervisor owns verification, git, kanban, permissions, and "
             "completion decisions. You cannot broaden your capabilities.")]
    (-> agent-map
        (dissoc :agent/plugins :agent/rebuild-chain
                :agent/factory-session :agent/workspace-tool-opts)
        (assoc :exchange-chain chain
           :initial-state
           (merge initial
                  {:agent/system-message system-message
                   :agent/workspace-root (:worktree candidate)
                   :agent/disabled-tools #{}
                   :agent/loop-opts
                   (merge (:agent/loop-opts initial)
                          {:max-tool-calls-per-exchange
                           (:max-tool-calls policy)
                           :max-tool-calls-per-turn
                           (:max-tool-calls policy)})})))))

(defn run-candidate!
  [agent-map candidate proposal policy]
  (let [rt (runtime/start (candidate-agent-map agent-map candidate policy)
                          (str "evolution-" (:id candidate)))
        started (System/nanoTime)]
    (try
      (loop [turn 1
             tool-calls 0
             last-response ""]
        (let [elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))]
          (cond
            (> elapsed-ms (:max-wall-ms policy))
            (let [state (runtime/stop rt)]
              {:status :budget-exhausted
               :turns (dec turn)
               :tool-calls tool-calls
               :token-usage (long (get-in state
                                          [:agent/token-usage :total_tokens] 0))
               :summary last-response
               :error "wall-time budget exhausted"})

            (> turn (:max-agent-turns policy))
            (let [state (runtime/stop rt)]
              {:status :budget-exhausted
               :turns (dec turn)
               :tool-calls tool-calls
               :token-usage (long (get-in state
                                          [:agent/token-usage :total_tokens] 0))
               :summary last-response
               :error "agent-turn budget exhausted"})

            (>= tool-calls (:max-tool-calls policy))
            (let [state (runtime/stop rt)]
              {:status :budget-exhausted
               :turns (dec turn)
               :tool-calls tool-calls
               :token-usage (long (get-in state
                                          [:agent/token-usage :total_tokens] 0))
               :summary last-response
               :error "tool-call budget exhausted"})

            :else
            (let [remaining-ms (max 1 (- (:max-wall-ms policy) elapsed-ms))
                  remaining-tools (- (:max-tool-calls policy) tool-calls)
                  _ (swap! (:state rt)
                           update :agent/loop-opts
                           merge
                           {:max-tool-calls-per-exchange remaining-tools
                            :max-tool-calls-per-turn remaining-tools})
                  before-tools (tool-history-count rt)
                  prompt (if (= turn 1)
                           (task-prompt proposal)
                           (str "Continue the same bounded implementation. "
                                "Inspect current workspace state, address "
                                "remaining acceptance criteria, and emit "
                                completion-marker " only when complete."))
                  result (send-message-bounded rt prompt remaining-ms)
                  timed-out? (= runtime-timeout result)
                  response (if timed-out?
                             ""
                             (str (or (:exchange/response result) "")))
                  history-delta (max 0 (- (tool-history-count rt)
                                          before-tools))
                  exchange-calls (max history-delta
                                      (count (:tool/results result)))
                  calls (+ tool-calls exchange-calls)
                  failed? (and (not timed-out?)
                               (boolean (:error/raised result)))
                  token-usage
                  (long (get-in @(:state rt)
                                [:agent/token-usage :total_tokens] 0))
                  done? (= completion-marker
                           (last (str/split-lines (str/trim response))))]
              (cond
                timed-out?
                (let [state (runtime/stop rt)]
                  {:status :budget-exhausted
                   :turns turn
                   :tool-calls calls
                   :token-usage (long (get-in state
                                              [:agent/token-usage :total_tokens]
                                              token-usage))
                   :summary response
                   :error "wall-time budget exhausted during exchange"})

                failed?
                (let [state (runtime/stop rt)]
                  {:status :failed
                   :turns turn
                   :tool-calls calls
                   :token-usage (long (get-in state
                                              [:agent/token-usage :total_tokens] 0))
                   :summary response
                   :error (str (:error/raised result))})

                (> calls (:max-tool-calls policy))
                (let [state (runtime/stop rt)]
                  {:status :budget-exhausted
                   :turns turn
                   :tool-calls calls
                   :token-usage (long (get-in state
                                              [:agent/token-usage :total_tokens] 0))
                   :summary response
                   :error "tool-call budget exhausted"})

                (> token-usage (:max-token-usage policy))
                (let [state (runtime/stop rt)]
                  {:status :budget-exhausted
                   :turns turn
                   :tool-calls calls
                   :token-usage (long (get-in state
                                              [:agent/token-usage :total_tokens]
                                              token-usage))
                   :summary response
                   :error "token budget exhausted"})

                done?
                (let [state (runtime/stop rt)]
                  {:status :completed
                   :turns turn
                   :tool-calls calls
                   :token-usage (long (get-in state
                                              [:agent/token-usage :total_tokens] 0))
                   :summary response})

                :else
                (recur (inc turn) calls response))))))
      (catch Throwable t
        (let [state (runtime/stop rt)]
          {:status :failed
           :turns 0
           :tool-calls 0
           :token-usage (long (get-in state
                                      [:agent/token-usage :total_tokens] 0))
           :summary ""
           :error (or (ex-message t) (.getName (class t)))})))))

(defrecord AgentCandidateRunner [agent-map]
  proto/CandidateRunner
  (-implement! [_ candidate proposal policy]
    (run-candidate! agent-map candidate proposal policy)))

(defn agent-candidate-runner
  [agent-map]
  (->AgentCandidateRunner agent-map))

(m/=> run-candidate!
      [:=> [:cat :map schemas/Candidate schemas/Proposal schemas/Policy]
       schemas/ImplementationResult])
(m/=> agent-candidate-runner
      [:=> [:cat :map] [:fn proto/candidate-runner?]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.evolution.candidate)]}))

(instrument!)
