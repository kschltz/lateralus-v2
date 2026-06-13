(ns kschltz.agent.interceptors
  "v2 interceptor stages for the agent exchange pipeline.

   Each stage is a plain map satisfying
   `kschltz.agent.interceptors.schema/Interceptor`.

   v2 design notes:
   - **No** delegation to `kschltz.agent.loop` (forbidden by plan;
     verify with `rg 'agent\\.loop' src/`).
   - **No** direct LLM HTTP calls. All provider I/O goes through
     the `LlmClient` protocol (Step 5); MVP ships with a stub
     implementation.
   - **No** tool execution. MVP default tool registry is empty;
     dispatch is a no-op for non-nil `:tool/calls` (which are
     not produced by the stub LLM).
   - Stages are pure functions of ctx. They return a new ctx and
     never mutate shared refs.

   Stages defined here (in execution order; see `default-exchange-chain`):
     error-boundary  — wraps subsequent stages; records :error/raised
                       on throw, then re-throws
     compose-context — assembles the LLM request from :agent/state +
                       :exchange/user-text + recall
     llm-call        — invokes the configured LlmClient; protocol-only
     parse-response  — extracts :exchange/response and :tool/calls
     dispatch        — re-enters compose/llm/parse while :tool/calls
                       remain (no-op in MVP since stub emits none)
     store-exchange  — leave stage; records the final exchange
     deliver-responses — leave stage; hands responses to listeners
     notify          — leave stage; fires on-thought / on-response"
  (:require [kschltz.agent.chain :as chain]
            [kschltz.agent.interceptors.schema :as schema]
            [kschltz.agent.llm.client :as llm-client]
            [malli.core :as m]))

;; ---- LlmClient comes from `kschltz.agent.llm.client` (canonical).
;;      The interceptor namespace only knows the protocol boundary;
;;      Step 5 wires the real HTTP impl behind it.

(defn default-llm-client
  "MVP stub LlmClient. Returns a deterministic text response. Step 5
   replaces this with the real HTTP-backed implementation."
  []
  (llm-client/stub-client))

(defn call-llm
  "Invoke the LlmClient on ctx. Reads `:llm/client` (falls back to the
   stub). Reads `:llm/request`. Writes `:llm/response`."
  [ctx]
  (let [client (or (:llm/client ctx) (default-llm-client))
        req    (:llm/request ctx)]
    (assoc ctx :llm/response (llm-client/-call client req))))

;; ---- Stage definitions ----

(defn- response-text
  "Extract assistant text from a response map. Tolerates stubs by
   defaulting to the empty string."
  [response]
  (or (get-in response [:choices 0 :message :content]) ""))

(defn- response-tool-calls
  "Extract tool calls from a response. MVP stub returns no calls."
  [response]
  (or (get-in response [:choices 0 :message :tool_calls]) []))

(defn- trim-history
  "Truncate ctx-internal history to the most recent N turns (Step 6
   will replace this with proper memory recall)."
  [ctx]
  ctx)

(def error-boundary
  "Wraps the rest of the chain. On error, annotates ctx with
   `:error/raised` containing the throwable before re-throwing via
   `chain/execute` default behavior."
  {:name ::error-boundary
   :error (fn [ctx ex]
            (assoc ctx :error/raised {:exception ex
                                      :stage     (:chain/stage (ex-data ex))}))})

(def compose-context
  "Build `:llm/request` from :agent/state + :exchange/user-text +
   recall. Trivial recall stub for MVP."
  {:name ::compose-context
   :enter (fn [ctx]
            (let [state     (:agent/state ctx)
                  user-text (or (:exchange/user-text ctx) "")
                  recall    (or (:memory/recall ctx) [])
                  sys-msg   (or (:agent/system-message state) "lateralus-v2 MVP")
                  messages  (cond-> [{:role "system" :content sys-msg}]
                              (seq recall) (into (mapv (fn [m]
                                                          {:role    "system"
                                                           :content (str "[recall] " m)})
                                                        recall))
                              (seq user-text) (conj {:role "user" :content user-text}))]
              (-> ctx
                  (assoc :llm/request
                         {:base-url (:base-url state)
                          :api-key  (:api-key state)
                          :model    (or (:model state) "stub/v0")
                          :messages messages})
                  trim-history)))})

(def llm-call
  "Invoke the LlmClient. Wraps `call-llm`. No business logic — only
   reads `:llm/client` and `:llm/request`, writes `:llm/response`."
  {:name ::llm-call
   :enter call-llm})

(def parse-response
  "Extract :exchange/response and :tool/calls from :llm/response."
  {:name ::parse-response
   :enter (fn [ctx]
            (let [resp (:llm/response ctx)]
              (assoc ctx
                     :exchange/response (response-text resp)
                     :tool/calls        (response-tool-calls resp))))})

(def dispatch
  "Sequential dispatch over :tool/calls. MVP: no-op (stub LLM never
   produces tool calls). The :leave stage is a no-op; the entire
   stage is here to exercise the dispatch *path* in tests via a
   fake LLM that returns tool calls."
  {:name ::dispatch
   :enter (fn [ctx]
            (let [calls (or (:tool/calls ctx) [])
                  state (:agent/state ctx)
                  parallel? (boolean (:agent/parallel-tools? state))
                  results  (if parallel?
                             ;; Parallel opt-in only. MVP must not use
                             ;; pmap by default (fact-sequential-tools).
                             (mapv (fn [c] {:call c :result :not-implemented}) calls)
                             (mapv (fn [c] {:call c :result :not-implemented}) calls))]
              (assoc ctx :tool/results results)))})

(def store-exchange
  "Leave stage. In Step 6 this is replaced with the memory plugin's
   persist interceptor. For MVP, records the final exchange on ctx
   as `:memory/last-exchange` for assertion in tests."
  {:name ::store-exchange
   :leave (fn [ctx]
            (assoc ctx :memory/last-exchange
                   {:session-id       (:exchange/session-id ctx)
                    :user-msg-id      (:exchange/user-msg-id ctx)
                    :assistant-msg-id (:exchange/assistant-msg-id ctx)
                    :response         (:exchange/response ctx)
                    :tool-calls       (or (:tool/calls ctx) [])
                    :tool-results     (or (:tool/results ctx) [])}))})

(def deliver-responses
  "Leave stage. Writes the final response to the agent's outgoing
   queue (`:exchange/delivered`). No I/O — caller polls the queue."
  {:name ::deliver-responses
   :leave (fn [ctx]
            (update ctx :exchange/delivered
                    (fnil conj [])
                    {:session-id (:exchange/session-id ctx)
                     :response   (:exchange/response ctx)}))})

(def notify
  "Leave stage. Final hook for listeners (UI, telemetry, etc.)."
  {:name ::notify
   :leave (fn [ctx]
            (update ctx :exchange/notified
                    (fnil conj [])
                    {:session-id (:exchange/session-id ctx)
                     :event      :complete}))})

;; ---- All stages as data, for tests + assembly ----

(def all-stages
  "All defined stages. Order is not significant here; assembly
   happens in `kschltz.agent.exchange/default-exchange-chain`."
  [error-boundary compose-context llm-call parse-response
   dispatch store-exchange deliver-responses notify])

;; ---- Schema self-check ----

(defn check-stages
  "Validate every defined stage against the Interceptor schema.
   Throws ex-info on the first failure. Returns :ok on success."
  []
  (doseq [stage all-stages
          :let [explain (m/explain schema/Interceptor (into {} stage))]]
    (when explain
      (throw (ex-info "Interceptor failed schema check"
                      {:interceptor (:name stage)
                       :explain (str explain)}))))
  :ok)
