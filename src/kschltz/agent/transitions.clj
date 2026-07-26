(ns kschltz.agent.transitions
  "Staged runtime-state transitions for lateralus agents.

   Interceptors and tools do not mutate the runtime atom. They enqueue
   allowlisted transition ops onto `:agent/transitions`. A commit-stage
   interceptor (see `kschltz.agent.transitions.interceptors`) folds the
   queue into the working `:agent/state` on ctx, accumulates
   `:agent/state-delta` for the outer runtime merge, patches in-flight
   `:llm/request` knobs, and clears the queue.

   This namespace owns the algebra only — no interceptor wiring, no
   Tool protocol, no Integrant keys."
  (:require [cheshire.core :as json]
            [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]))

(def llm-config-keys
  "Session LLM knobs that may be rewritten mid-exchange."
  #{:model :base-url :api-key})

(def SetLlmOp
  "Transition that updates allowlisted LLM session config keys.
   At least one of `:model`, `:base-url`, or `:api-key` must be present."
  [:and
   [:map {:closed true}
    [:op [:= :set-llm]]
    [:model {:optional true} [:string {:min 1}]]
    [:base-url {:optional true} [:string {:min 1}]]
    [:api-key {:optional true} [:string {:min 1}]]]
   [:fn {:error/message "set-llm requires at least one of :model, :base-url, :api-key"}
    (fn [op]
      (boolean (some #(contains? op %) [:model :base-url :api-key])))]])

(def Transition
  "Closed union of supported transition ops."
  [:multi {:dispatch :op}
   [:set-llm SetLlmOp]])

(def Transitions
  [:vector Transition])

(defn valid-transition?
  "True when `op` conforms to `Transition`."
  [op]
  (m/validate Transition op))

(defn explain-transition
  "Humanized Malli explanation for an invalid transition, or nil."
  [op]
  (some-> (m/explain Transition op) me/humanize))

(defn- set-llm-patch
  "Project a `:set-llm` op down to the allowlisted key map."
  [op]
  (select-keys op [:model :base-url :api-key]))

(defn apply-transition
  "Apply one validated `op` to `state`. Returns updated state.
   Unknown ops are ignored (caller should validate first)."
  [state op]
  (case (:op op)
    :set-llm (merge (or state {}) (set-llm-patch op))
    state))

(defn apply-transitions
  "Fold `ops` left-to-right over `state`. Invalid ops are skipped.
   Returns `{:state s' :applied [op…]}` where `:applied` lists the
   ops that actually contributed (valid only)."
  [state ops]
  (reduce (fn [{:keys [state applied]} op]
            (if (valid-transition? op)
              {:state   (apply-transition state op)
               :applied (conj applied op)}
              {:state state :applied applied}))
          {:state (or state {}) :applied []}
          (or ops [])))

(defn patch-llm-request
  "Return `req` with allowlisted LLM knobs overwritten from `state`.
   Preserves messages and any other request fields. No-op when `req`
   is nil."
  [req state]
  (if (nil? req)
    req
    (merge req (select-keys (or state {}) llm-config-keys))))

(defn redact-transition
  "Return a logging/model-safe copy of `op` with `:api-key` replaced
   by a boolean marker when present."
  [op]
  (cond-> (dissoc op :api-key)
    (contains? op :api-key) (assoc :api-key-set true)))

(defn transition-envelope?
  "True when a parsed tool-result map carries a `:transition` key."
  [parsed]
  (and (map? parsed) (contains? parsed :transition)))

(defn parse-tool-result
  "Parse a tool result string as JSON (keywordized). Returns nil on
   non-string or non-JSON input."
  [result]
  (when (string? result)
    (try (json/parse-string result true)
         (catch Throwable _ nil))))

(defn extract-transition
  "Pull a validated transition from a parsed tool-result map.
   Returns the transition map or nil when absent/invalid."
  [parsed]
  (when (transition-envelope? parsed)
    (let [op (:transition parsed)]
      (when (valid-transition? op) op))))

(defn model-visible-result
  "Rewrite a tool-result envelope so `:transition` never echoes the
   raw `:api-key`. Used by harvest after enqueueing the real op."
  [parsed]
  (if-not (transition-envelope? parsed)
    parsed
    (update parsed :transition redact-transition)))

(defn encode-result
  "JSON-encode a tool result map (pretty for model readability)."
  [m]
  (json/generate-string m {:pretty true}))

(m/=> valid-transition? [:=> [:cat :any] :boolean])
(m/=> apply-transition [:=> [:cat [:maybe :map] :map] :map])
(m/=> apply-transitions [:=> [:cat [:maybe :map] [:maybe [:sequential :any]]]
                         [:map
                          [:state :map]
                          [:applied [:vector :map]]]])
(m/=> patch-llm-request [:=> [:cat [:maybe :map] [:maybe :map]] [:maybe :map]])
(m/=> extract-transition [:=> [:cat [:maybe :map]] [:maybe :map]])

(defn instrument!
  "Instrument this namespace's public fns with Malli."
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.transitions)]}))

(instrument!)
