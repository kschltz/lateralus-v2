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
  (:import [java.lang Runtime]
           [java.time Instant]))

(defn- jdk-info
  []
  (let [feature (.feature (Runtime/version))]
    {:version (System/getProperty "java.version")
     :feature feature
     :clerk-graaljs-safe? (< feature 24)}))

(defn- self-update-playbook
  [ctx]
  (let [state (:agent/state ctx)
        reload (:agent/runtime-reload-status state)
        jdk (jdk-info)]
    {:use-file-then-reload
     "Prefer file_update / clojure_edit_def / file_patch for source, then reload_runtime on the changed kschltz.agent.* namespace."
     :use-add-lib
     "Use clojure_add_lib only for new Maven/Git deps. Always pass :require and check loaded? before calling the ns."
     :use-eval
     "Use clojure_eval for REPL prototypes, not as a substitute for source edits you intend to keep."
     :use-tool-define
     "Use tool_define to compile a real Tool (name, EDN Malli input-schema string, invoke string). Do not clojure_eval a substitute. Call the new name on the next LLM call this exchange. Use tool_promote to persist it."
     :last-reload reload
     :edited-namespaces (vec (:agent/edited-namespaces state))
     :jdk jdk
     :jdk-note (when-not (:clerk-graaljs-safe? jdk)
                 (str "JDK " (:feature jdk)
                      " removed sun.misc.Unsafe.ensureClassInitialized. "
                      "Clerk 0.16.x + Graal JS will fail to require. "
                      "Use JDK 22–23, exclude Graal JS, or skip Clerk."))}))

(defrecord SelfAwarenessTool [workspace-root]
  tool/Tool
  (-name [_] "self_status")
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
                       :jdk         (jdk-info)
                       :tokens-used (or usage
                                        {:prompt_tokens 0
                                         :completion_tokens 0
                                         :total_tokens 0})}]
      (json/generate-string payload {:pretty true}))))

(defn- display-key
  [k]
  (if (keyword? k)
    (if-let [ns (namespace k)]
      (str ns "/" (name k))
      (name k))
    (str k)))

(defn- runtime-summary
  [ctx workspace-root]
  (let [state (:agent/state ctx)
        agent-map (:agent/agent-map ctx)]
    {:session-id (or (:agent/session-id state)
                     (:exchange/session-id ctx))
     :workspace-root (or workspace-root "unset")
     :configuration
     {:model (:model state)
      :base-url (:base-url state)
      :api-key-set (boolean (:api-key state))
      :system-message (:agent/system-message state)
      :embedder (some-> (:embedder ctx) meta :embedder/method name)
      :memory (some-> (:memory/backend ctx) meta :memory-backend/impl name)}
     :loop-policy (or (:agent/loop-opts ctx)
                      (:agent/loop-opts agent-map)
                      {})
     :state-keys (->> (keys state) (map display-key) sort vec)
     :history-entries (count (:agent/history state))
     :runtime-reload-status (:agent/runtime-reload-status state)
     :mcp-server-ids (->> (keys (:mcp/servers state))
                          (map display-key)
                          sort
                          vec)}))

(defn- tool-descriptors
  [ctx]
  (->> (:agent/tool-registry ctx)
       vals
       (map (fn [t]
              {:name (tool/-name t)
               :description (tool/-description t)
               :input-schema (pr-str (tool/-input-schema t))
               :output-schema (pr-str (tool/-output-schema t))}))
       (sort-by :name)
       vec))

(defn- chain-descriptors
  [ctx]
  (mapv (fn [ix]
          {:name (display-key (:name ix))
           :plugin (some-> (:plugin/name ix) display-key)
           :slot (some-> (:plugin/slot ix) display-key)
           :stages (cond-> []
                     (:enter ix) (conj "enter")
                     (:leave ix) (conj "leave")
                     (:error ix) (conj "error"))})
        (or (:agent/exchange-chain ctx) [])))

(defrecord RuntimeDescribeTool [workspace-root]
  tool/Tool
  (-name [_] "runtime_describe")
  (-description [_]
    "Inspect the active Lateralus runtime as redacted data: session configuration, loop policy, state keys, registered tool contracts, the interceptor chain, and a self-update playbook. API keys and live implementation objects are never returned. Use `section` to request `summary`, `tools`, `chain`, `playbook`, or `all` (default).")
  (-input-schema [_]
    [:map {:closed true}
     [:section {:optional true} [:enum "summary" "tools" "chain" "playbook" "all"]]])
  (-output-schema [_] :string)
  (-invoke [_ {:keys [section]} ctx]
    (let [section (or section "all")
          payload (case section
                    "summary" {:summary (runtime-summary ctx workspace-root)}
                    "tools" {:tools (tool-descriptors ctx)}
                    "chain" {:chain (chain-descriptors ctx)}
                    "playbook" {:playbook (self-update-playbook ctx)}
                    {:summary (runtime-summary ctx workspace-root)
                     :tools (tool-descriptors ctx)
                     :chain (chain-descriptors ctx)
                     :playbook (self-update-playbook ctx)})]
      (json/generate-string payload {:pretty true}))))

(defn self-awareness-registry
  "Return the self-inspection tool registry.

   `workspace-root` is the optional configured workspace root string
   for filesystem-aware agents."
  ([] (self-awareness-registry nil))
  ([workspace-root]
   {"self_status" (->SelfAwarenessTool workspace-root)
    "runtime_describe" (->RuntimeDescribeTool workspace-root)}))
