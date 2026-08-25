(ns kschltz.agent.tools.config
  "LLM session-config tools for lateralus agents.

   `set_llm_config` proposes an allowlisted `:set-llm` transition that
   the transitions harvest/apply interceptors commit onto runtime state
   (and the in-flight `:llm/request`) before the next LLM call.

   `list_llm_models` enumerates models via the `ModelCatalog` protocol
   (never calls HTTP directly).

   Both tools return JSON strings. Mutation of the runtime atom happens
   only through the transition queue — never inside `-invoke`."
  (:require [cheshire.core :as json]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.config.catalog :as catalog]
            [kschltz.agent.transitions :as tr]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def SetLlmConfigInput
  "At least one of model / base-url / api-key must be supplied."
  [:and
   [:map {:closed true}
    [:model {:optional true} [:string {:min 1}]]
    [:base-url {:optional true} [:string {:min 1}]]
    [:api-key {:optional true} [:string {:min 1}]]]
   [:fn {:error/message "provide at least one of :model, :base-url, :api-key"}
    (fn [m]
      (boolean (some #(contains? m %) [:model :base-url :api-key])))]])

(def ListLlmModelsInput
  [:map {:closed true}
   [:base-url {:optional true} [:string {:min 1}]]
   [:api-key {:optional true} [:string {:min 1}]]])

(def SetSystemMessageInput
  [:map {:closed true}
   [:message [:string {:min 1}]]])

(def SetLoopPolicyInput
  [:and
   tr/LoopOptsPatch
   [:fn {:error/message "provide at least one loop policy field"}
    (fn [m] (boolean (seq m)))]])

(defn- current-llm-config
  [ctx]
  (let [state (or (:agent/state ctx) {})]
    {:model    (:model state)
     :base-url (:base-url state)
     :api-key  (:api-key state)}))

(defn- set-llm-transition
  [args]
  (cond-> {:op :set-llm}
    (contains? args :model)    (assoc :model (:model args))
    (contains? args :base-url) (assoc :base-url (:base-url args))
    (contains? args :api-key)  (assoc :api-key (:api-key args))))

(defrecord SetLlmConfigTool []
  tool/Tool
  (-name [_] "set_llm_config")
  (-description [_]
    "Update this session's LLM configuration (model, base-url, and/or
     api-key). Changes apply before the next LLM call in this exchange
     (including ReAct follow-ups) and persist for the rest of the
     session. Provide at least one field. Does not swap the Integrant
     client implementation (stub vs http).")
  (-input-schema [_] SetLlmConfigInput)
  (-output-schema [_] :string)
  (-invoke [_ args ctx]
    (let [op     (set-llm-transition args)
          before (select-keys (current-llm-config ctx) tr/llm-config-keys)
          after  (merge before (select-keys op tr/llm-config-keys))
          view   (fn [m]
                   (cond-> (dissoc m :api-key)
                     (contains? m :api-key) (assoc :api-key-set true)))]
      (tr/encode-result
       {:ok         true
        :tool       "set_llm_config"
        :pending    "same-exchange"
        :before     (view before)
        :after      (view after)
        :transition op}))))

(defrecord SetSystemMessageTool []
  tool/Tool
  (-name [_] "set_system_message")
  (-description [_]
    "Replace the durable system message for this session through an allowlisted transition. The updated message is visible to subsequent ReAct composition in this exchange and persists for later exchanges.")
  (-input-schema [_] SetSystemMessageInput)
  (-output-schema [_] :string)
  (-invoke [_ {:keys [message]} ctx]
    (tr/encode-result
     {:ok true
      :tool "set_system_message"
      :pending "same-exchange"
      :before {:message (get-in ctx [:agent/state :agent/system-message])}
      :after {:message message}
      :transition {:op :set-system-message
                   :message message}})))

(defrecord SetLoopPolicyTool []
  tool/Tool
  (-name [_] "set_loop_policy")
  (-description [_]
    "Update allowlisted tool-loop limits for this session: max-loop-depth, max-tool-calls-per-exchange, max-tool-calls-per-turn, and per-tool content caps. The transition patches the current immutable ctx and persists via state-delta; it cannot replace interceptor code.")
  (-input-schema [_] SetLoopPolicyInput)
  (-output-schema [_] :string)
  (-invoke [_ args ctx]
    (let [before (or (:agent/loop-opts ctx) {})
          after (merge before args)]
      (tr/encode-result
       {:ok true
        :tool "set_loop_policy"
        :pending "same-exchange"
        :before before
        :after after
        :transition (assoc args :op :set-loop-opts)}))))

(defrecord ListLlmModelsTool [catalog]
  tool/Tool
  (-name [_] "list_llm_models")
  (-description [_]
    "List model ids available at the current (or overridden) OpenAI-
     compatible base-url. Optional base-url / api-key override the
     session values for this call only; they do not change session
     config — use set_llm_config for that.")
  (-input-schema [_] ListLlmModelsInput)
  (-output-schema [_] :string)
  (-invoke [_ args ctx]
    (let [cfg  (current-llm-config ctx)
          opts {:base-url (or (:base-url args) (:base-url cfg) "")
                :api-key  (if (contains? args :api-key)
                            (:api-key args)
                            (:api-key cfg))}]
      (try
        (let [ids (catalog/list-models catalog opts)]
          (json/generate-string
           {:ok       true
            :tool     "list_llm_models"
            :base-url (:base-url opts)
            :models   ids
            :count    (count ids)}
           {:pretty true}))
        (catch Throwable t
          (json/generate-string
           {:ok      false
            :tool    "list_llm_models"
            :base-url (:base-url opts)
            :error   (or (ex-message t) (.getName (class t)))
            :class   (.getName (class t))}
           {:pretty true}))))))

(defn config-registry
  "Return the session configuration tool registry.

   Opts:
     :catalog — ModelCatalog impl (default stub-catalog for offline safety;
                Integrant wires http-catalog when desired)."
  ([] (config-registry nil))
  ([{:keys [catalog]}]
   (let [cat (or catalog (catalog/stub-catalog))]
     {"set_llm_config"   (->SetLlmConfigTool)
      "set_system_message" (->SetSystemMessageTool)
      "set_loop_policy"  (->SetLoopPolicyTool)
      "list_llm_models"  (->ListLlmModelsTool cat)})))

(m/=> config-registry
      [:function
       [:=> [:cat] :map]
       [:=> [:cat [:maybe :map]] :map]])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.config)]}))

(instrument!)
