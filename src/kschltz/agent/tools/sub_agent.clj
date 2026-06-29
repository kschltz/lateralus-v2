(ns kschltz.agent.tools.sub-agent
  "Sub-agent spawning tool for lateralus.

   Exposes `spawn_sub_agent` to the LLM. When invoked, it creates a
   fresh child runtime with isolated history, runs the supplied task,
   and returns a JSON summary containing the child's final response,
   token usage, and success flag.

   The child inherits the parent's LLM client, system message, model
   config, and logging. It receives a freshly assembled exchange chain using only the
   base plugins and a tool registry that excludes `spawn_sub_agent`,
   preventing unbounded recursion. The child
   does not inherit parent's message history, so it starts with a clean
   slate focused on the delegated task.

   The tool is implemented behind the `Tool` protocol and its
   input/output is Malli-validated by the generic `invoke-tool`
   helper, satisfying the project rule that every capability exposed
   to the model must be protocol-bound and instrumented."
  (:require [cheshire.core :as json]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.tool :as tool]))

(def ^:private tool-name
  "The name of this tool as seen by the model."
  "spawn_sub_agent")

(def ^:private SubAgentInput
  "Malli input schema for spawn_sub_agent."
  [:map
   [:task [:string {:min 1}]]
   [:max-turns {:optional true} [:int {:min 1 :max 10}]]])

(defn- parent-tools->child-tools
  "Remove the sub-agent tool from the parent's registry so children
   cannot spawn grandchildren."
  [registry]
  (dissoc (or registry {}) tool-name))

(defn- child-agent-map
  "Build an agent-map for the child runtime from the parent's ctx.

   The child inherits the parent's LLM client, system message, model
   config, and logging. It gets a fresh history, zeroed token usage,
   and a tool registry that never contains `spawn_sub_agent`. Because
   the chain is rebuilt with that filtered registry, a child cannot
   recursively spawn grandchildren even if the parent had already
   assembled a chain containing the full registry."
  [ctx max-turns]
  (let [parent-state (:agent/state ctx)
        parent-map   (:agent/agent-map ctx)
        client       (:llm/client ctx)
        parent-tools (or (:agent/tool-registry ctx) {})
        child-tools  (parent-tools->child-tools parent-tools)
        child-state  (merge
                       (select-keys parent-state
                                    [:agent/system-message :model :base-url :api-key
                                     :agent/memory :agent/embedder])
                       {:agent/token-usage {:prompt_tokens 0
                                            :completion_tokens 0
                                            :total_tokens 0}
                        :agent/history []
                        :agent/tool-registry child-tools})
        ;; Rebuild the chain with the filtered registry. Reusing the
        ;; parent's assembled chain would let the child inherit the
        ;; unfiltered registry and recurse.
        chain        (plugin/assemble-chain [(plugins.base/base-plugin)
                                            (plugins.tools/tools-plugin child-tools)])]
    (merge
     (select-keys parent-map [:agent/logging])
     {:agent/llm-client client
      :exchange-chain   chain
      :initial-state    child-state})))

(defn- run-child-runtime
  "Start a child runtime, send it `task`, stop it, and return a summary
   map. Exceptions are caught and returned as a failed result so the
   parent can decide how to proceed."
  [agent-map task max-turns]
  (let [child-id (str "sub-" (random-uuid))
        child-rt (runtime/start agent-map child-id)]
    (try
      (let [result      (runtime/send-message child-rt task)
            final-state (runtime/stop child-rt)
            response    (:exchange/response result)
            usage       (get final-state :agent/token-usage
                             {:prompt_tokens 0 :completion_tokens 0 :total_tokens 0})
            success?    (not (boolean (:error/raised result)))]
        {:task        task
         :response    response
         :success?    success?
         :token-usage usage
         :max-turns   max-turns})
      (catch Throwable t
        {:task      task
         :response  (ex-message t)
         :success?  false
         :error     (str t)
         :max-turns max-turns}))))

(deftype SubAgentTool []
  tool/Tool
  (-name [_] tool-name)
  (-description [_]
    "Spawn a focused sub-agent to complete a specific task. Provide a concise task description and an optional turn cap (1-10, default 3). The sub-agent runs in its own session with isolated history and returns a JSON summary.")
  (-input-schema [_] SubAgentInput)
  (-output-schema [_] :string)
  (-invoke [_this args ctx]
    (let [task      (:task args)
          max-turns (or (:max-turns args) 3)
          child-map (child-agent-map ctx max-turns)]
      (json/encode (run-child-runtime child-map task max-turns)))))

(defn sub-agent-tool
  "Return a `spawn_sub_agent` Tool instance."
  []
  (->SubAgentTool))

(defn sub-agent-registry
  "Return a tool registry containing only `spawn_sub_agent`.

   Add this to the merged `:lateralus/tool-registry` to let the agent
   delegate tasks to child runtimes."
  []
  {tool-name (sub-agent-tool)})
