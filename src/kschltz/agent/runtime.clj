(ns kschltz.agent.runtime
  "Agent outer loop. Thin layer between caller (CLI, test, web server)
   and the chain.

   MVP scope (this file):
   - Single-threaded: `send-message` runs the chain synchronously on
     the caller thread.
   - Bookkeeping only: build a per-exchange ctx with traceability
     IDs (session-id, user-msg-id, assistant-msg-id), call
     `chain/execute` with the chain from agent-map, then merge
     `:agent/state-delta` from the final ctx into the runtime's
     state atom.
   - `stop` returns the current state.

   Out of scope (follow-up):
   - A queue + worker thread. The MVP CLI is single-user / one-prompt-
     at-a-time; decoupling send from chain execution is not yet
     required. When it is, swap the synchronous call inside
     `send-message` for a `LinkedBlockingQueue` + `Thread` that takes
     from the queue and writes to a per-call out-chan; the test
     surface stays the same.
   - Concurrent sessions. Single runtime per session.
   - Persistence. The state atom is in-memory only.

   Design rationale for keeping this synchronous:
   The runtime's job is bookkeeping, not concurrency. Adding a
   thread for the sake of \"async send\" without a real consumer
   (a UI thread, a request handler) just adds wakeup races and
   makes the test surface (chain execution, state merge) harder
   to pin. The auditor flagged 7 turns of double-read bugs on the
   threaded version; this synchronous design has no race surface.

   LOC target: < 150 (per plan)."
  (:require [kschltz.agent.chain :as chain]
            [kschltz.agent.exchange :as exchange]))

;; ---- Runtime protocol ----
;; The runtime is the thin outer-loop layer between the caller
;; (CLI, test, web server) and the chain. It generates per-exchange
;; IDs, calls chain/execute, and merges :agent/state-delta into its
;; own state atom. The protocol is defined here (the canonical use
;; site) so callers can write `runtime/send-message r "hi"` and tests
;; can assert `(satisfies? runtime/AgentRuntime r)`.
;;
;; Named `AgentRuntime` (not `Runtime`) to avoid the JDK clash
;; with `java.lang.Runtime`.
;;
;; MVP scope: synchronous (send-message runs the chain on the
;; caller thread). A queue + worker is a follow-up.

(defprotocol AgentRuntime
  "The agent outer-loop runtime contract.

   send-message runs ONE exchange synchronously. It returns the
   final ctx. The runtime's state atom is updated by merging
   :agent/state-delta from the final ctx.

   stop returns the current merged state. It does not terminate
   any worker thread (MVP: there is no worker thread)."
  (session-id [runtime] "Stable ID for the lifetime of this runtime.")
  (send-message [runtime user-text] "Run one exchange.")
  (stop [runtime] "Return the current merged state."))

(defrecord RuntimeRecord [state agent-map session-id]
  AgentRuntime
  (session-id [_] session-id)
  (send-message [this user-text]
    (let [user-msg-id      (str (random-uuid))
          assistant-msg-id (str (random-uuid))
          base-state       @(:state this)
          ctx              {:exchange/session-id       session-id
                            :exchange/user-msg-id      user-msg-id
                            :exchange/assistant-msg-id assistant-msg-id
                            :exchange/user-text        user-text
                            :agent/state               base-state
                            ;; Forward the agent-map's LlmClient +
                            ;; any other refs the bind-llm-client
                            ;; stage will look up. We only forward
                            ;; the namespaced keys that the chain
                            ;; expects; the agent-map is otherwise
                            ;; a lifecycle concern (Integrant owns
                            ;; the resources).
                            :agent/llm-client         (:agent/llm-client agent-map)}
          chain-to-run     (get-in agent-map [:exchange-chain]
                                   exchange/default-exchange-chain)
          result           (chain/execute ctx chain-to-run)
          delta            (:agent/state-delta result)
          ;; Plain merge: state-delta is a flat key-set update; last
          ;; write wins per key. If a stage needs deep-merge semantics
          ;; for a particular key, it should emit the new value
          ;; already-merged (the stage is closer to the data than the
          ;; runtime is).
          merged           (merge base-state (or delta {}))]
      (reset! (:state this) merged)
      result))
  (stop [_]
    @state))

(defn start
  "Create a runtime for the given agent-map.

   1-arity: generates a fresh session-id (random-uuid).
   2-arity: uses the given session-id (useful for tests and
   for resuming a session — though MVP has no persistence, so
   \"resuming\" is a future feature).

   The agent-map must include `:exchange-chain` (the list of
   interceptors to run per exchange). If it doesn't, the default
   exchange chain is used.

   The agent-map may include `:initial-state` (a map) which seeds
   the runtime's state atom at start. The state is the place
   chain stages read persistent context from (e.g. LLM
   config under :base-url / :api-key / :model, system message
   under :agent/system-message, accumulated history, etc.). If
   `:initial-state` is absent, the state starts empty."
  ([agent-map]
   (start agent-map (str (random-uuid))))
  ([agent-map session-id]
   (map->RuntimeRecord
    {:state      (atom (:initial-state agent-map {}))
     :agent-map  agent-map
     :session-id session-id})))