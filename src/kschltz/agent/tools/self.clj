(ns kschltz.agent.tools.self
  "Self-awareness tool for lateralus agents.

   Exposes runtime introspection to the LLM: current time, active
   configuration, filesystem location, current context size, and
   cumulative token usage. The tool reads from the interceptor `ctx`,
   so it is registered like any other tool and needs no special
   runtime wiring.

   The tool is implemented behind the `Tool` protocol and its
   input/output is Malli-validated by the generic `invoke-tool`
   helper, satisfying the project rule that every capability
   exposed to the model must be protocol-bound and instrumented."
  (:require [cheshire.core :as json]
            [kschltz.agent.tool :as tool])
  (:import [java.time Instant]))

(defrecord SelfAwarenessTool [workspace-root]
  tool/Tool
  (-name [_] "self/status")
  (-description [_]
    "Return information about the agent's current runtime environment:
     current time, active configuration, current directory, context
     size, and cumulative token usage. No arguments required.")
  (-input-schema [_] [:map {:closed true}])
  (-output-schema [_] :string)
  (-invoke [_ _args ctx]
    (let [state       (:agent/state ctx)
          now         (Instant/now)
          iso-time    (.toString now)
          cfg         {:model       (or (:model state) "unknown")
                       :base-url    (or (:base-url state) "unknown")
                       :session-id  (or (:agent/session-id state)
                                        (:exchange/session-id ctx)
                                        "unknown")
                       :embedder    (or (:agent/embedder state)
                                       (some-> (:embedder ctx) meta :embedder/method name)
                                       "unknown")
                       :memory      (or (:agent/memory state)
                                       (some-> (:memory/backend ctx) meta :memory-backend/impl name)
                                       "unknown")}
          location    {:cwd            (System/getProperty "user.dir")
                       :workspace-root (or workspace-root "unset")}
          context     {:message-count (count (:agent/last-request-messages state))}
          usage       (:agent/token-usage state)
          payload     {:time        iso-time
                       :timezone    "UTC"
                       :configuration cfg
                       :location    location
                       :context     context
                       :tokens-used (or usage
                                        {:prompt_tokens 0
                                         :completion_tokens 0
                                         :total_tokens 0})}]
      (json/generate-string payload {:pretty true}))))

(defn self-awareness-registry
  "Return a tool registry containing only the self/status tool.

   `workspace-root` is the optional configured workspace root string
   for filesystem-aware agents."
  ([] (self-awareness-registry nil))
  ([workspace-root]
   {"self/status" (->SelfAwarenessTool workspace-root)}))
