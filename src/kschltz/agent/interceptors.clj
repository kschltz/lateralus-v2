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
     `dispatch` records the (empty) results vector on ctx.
   - Stages are pure functions of ctx. They return a new ctx and
     never mutate shared refs.

   Stages defined here (in execution order; see `default-exchange-chain`):
     error-boundary   — :error handler that handles the error (clears
                        engine ::error, annotates :error/raised) so
                        subsequent :leave stages run and the
                        annotation is observable on the final ctx
     bind-llm-client  — copies the agent's Integrant-configured
                        LlmClient from :agent/llm-client onto
                        :llm/client on the per-exchange ctx so
                        llm-call actually sees the wired client
     compose-context  — assembles the LLM request from :agent/state +
                        :exchange/user-text + recall
     llm-call         — invokes the configured LlmClient; protocol-only
     parse-response   — extracts :exchange/response and :tool/calls
     dispatch         — records :tool/results (sequential mapv); MVP
                        has no real tools; see `dispatch-test`
     store-exchange   — leave stage; records the final exchange
     deliver-responses — leave stage; hands responses to listeners
     notify           — leave stage; fires on-thought / on-response"
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
  "Invoke the LlmClient on ctx. Reads `:llm/client` (set by
   `bind-llm-client` from the agent map; falls back to the stub
   when no agent client is configured). Reads `:llm/request`.
   Writes `:llm/response`."
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

;; TODO Step 6: delete this stub. It is a no-op marker so Step 6
;; has a clear `find-fn + replace` target when real history
;; trimming lands (token budget, recall window, etc.).
(defn- trim-history-stub
  "Stub for history trimming. Step 6 replaces this with a real
   implementation; see the :compose/trimmed? marker on ctx."
  [messages]
  messages)

(def bind-llm-client
  "Copy the agent's LlmClient from `:agent/llm-client` (set by the
   runtime per exchange) onto ctx as `:llm/client`. This makes the
   Integrant-configured LlmClient actually visible to the chain —
   without this stage, `llm-call` would always fall back to a fresh
   stub because the agent's client lives on the agent map, not on
   the per-exchange ctx.

   Placed first (after error-boundary) so the client is bound
   before any stage that might need it.

   Contract: only assoc `:llm/client` when a client is found (either
   on ctx or in the agent map). When no client is available, the
   stage is a no-op and `llm-call` falls back to a fresh stub.
   This avoids stamping nil into the ctx, which would break code
   that distinguishes 'absent' from 'present, nil'.

   Ctx-precedence: if `:llm/client` is already on ctx (a plugin or
   test set it explicitly), the ctx value wins — `bind-llm-client`
   is a *fallback* binding, not an override. See
   `bind-llm-client-prefers-ctx-client` in the test file.

   MVP note: only `:stub` impl is wired in `kschltz.agent.llm.client`;
   the `:http` impl throws at init time and the chain cannot recover
   from this until Step 5 lands."
  {:name ::bind-llm-client
   :enter (fn [ctx]
            (if-let [client (or (:llm/client ctx)
                                (:agent/llm-client ctx))]
              (assoc ctx :llm/client client)
              ctx))})

(def error-boundary
  "Handles any error raised by the chain. Clears the engine ::error
   key so the engine treats the error as handled and proceeds to
   the :leave phase; annotates ctx with `:error/raised` carrying
   the throwable + chain/stage. After this stage, the final ctx
   carries :error/raised and :leave stages still run."
  {:name ::error-boundary
   :error (fn [ctx ex]
            (-> ctx
                (dissoc ::chain/error)
                (assoc :error/raised
                       {:exception ex
                        :stage     (or (:chain/stage (ex-data ex))
                                       :unknown)})))})

(def compose-context
  "Build `:llm/request` from :agent/state + :exchange/user-text +
   recall. Trivial recall stub for MVP (Step 6 wires real memory)."
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
                              (seq user-text) (conj {:role "user" :content user-text}))
                  ;; TODO Step 6: replace the trim-history-stub call
                  ;; with the real implementation.
                  trimmed   (trim-history-stub messages)]
              (assoc ctx
                     :llm/request
                     {:base-url (:base-url state)
                      :api-key  (:api-key state)
                      :model    (or (:model state) "stub/v0")
                      :messages trimmed}
                     :compose/trimmed? true)))})

(def llm-call
  "Invoke the LlmClient. Wraps `call-llm`. No business logic — only
   reads `:llm/client` (set by `bind-llm-client`) and `:llm/request`,
   writes `:llm/response`."
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
  "Records `:tool/results` for the calls in `:tool/calls`. MVP
   implementation: every tool returns `:not-implemented` (no real
   tools ship in MVP). Sequential by default — `mapv`, never `pmap`
   (fact-sequential-tools). The earlier dead `:agent/parallel-tools?`
   knob was removed: there is no parallel opt-in in MVP, and
   shipping a knob that does nothing is worse than shipping none."
  {:name ::dispatch
   :enter (fn [ctx]
            (let [calls   (or (:tool/calls ctx) [])
                  results (mapv (fn [c] {:call c :result :not-implemented})
                                calls)]
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
  {:name  ::notify
   :leave (fn [ctx]
            (update ctx :exchange/notified
                    (fnil conj [])
                    {:session-id (:exchange/session-id ctx)
                     :event      :complete}))})

;; ---- All stages as data, for tests + assembly ----

(def all-stages
  "All defined stages. Order is not significant here; assembly
   happens in `kschltz.agent.exchange/default-exchange-chain`."
  [error-boundary bind-llm-client compose-context llm-call
   parse-response dispatch store-exchange deliver-responses notify])

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
