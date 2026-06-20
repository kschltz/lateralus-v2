(ns kschltz.agent.system
  "Integrant system definition for the lateralus-v2 agent.

   Components:
     :lateralus/llm-client       LlmClient implementation (stub for tests)
     :lateralus/embedder         Embedder impl (noop for tests; the
                                   runtime default in
                                   resources/lateralus/config.edn is
                                   LangChain4j in-process ONNX)
     :lateralus/memory-backend   MemoryBackend impl (noop for tests; the
                                   runtime default is Proximum HNSW)
     :lateralus/plugins          Vector of plugin vectors to assemble.
                                   Each plugin is a vector of interceptor
                                   maps; interceptors may declare a `:slot`
                                   keyword for stage ordering. A plugin
                                   marked `^{:plugin/complete? true}` replaces
                                   the default base exchange chain.
     :lateralus/agent            Agent entry: assembled chain + clients
                                  resolved at init time

   In-memory default (`default-config`): stub LLM + noop embedder + noop
   memory. This keeps tests fast and isolated. The runtime default lives
   in `resources/lateralus/config.edn` and selects Proximum + LangChain4j;
   `cli/build-system` loads it automatically and merges `--config PATH` over
   it.

   To use the real LlmClient HTTP impl, set
   `:lateralus/llm-client {:impl :http :base-url ... :api-key ... :model ...}`
   in the runtime config.

   Halt policy: only keys with real resources to release define
   `halt-key!` (currently just `:lateralus/memory-backend`).
   Integrant skips keys with no `halt-key!` defined, which is the
   correct behavior — defining a no-op halt is misleading."
  (:require [integrant.core :as ig]
            [malli.core :as m]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.memory :as plugins.memory]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.tools.filesystem :as tools.filesystem]
            [kschltz.agent.tools.self :as tools.self]
            [kschltz.agent.tools.clojure :as tools.clojure]
            [kschltz.agent.tools.web.web :as tools.web]
            [kschltz.agent.tools.web.schemas :as web.schemas]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.http-embedding :as http-embedding]
            [kschltz.agent.memory.noop-backend :as noop-memory]
            [kschltz.agent.memory.kg-bm25 :as kg-bm25-memory]
            [kschltz.agent.memory.protocol :as memory-protocol]))

;; Load optional JVM-only implementations when present on the classpath.
;; The native-image build excludes these source files, so the require is
;; guarded; this keeps the dependency graph explicit while allowing the
;; namespace to load without them.
(try
  (require 'kschltz.agent.memory.langchain4j-embedding)
  (require 'kschltz.agent.memory.proximum-backend)
  (require 'kschltz.agent.tools.web.mojeek)
  (catch Throwable _))

;; ---- Component definitions ----

;; ---- Malli pre-init validation ----
;;
;; `ig/assert-key` is called before any resources are allocated, so
;; malformed configs fail fast with a clear explanation of which key
;; is wrong and which fields are missing or invalid.

(defn- assert-malli!
  "Validate `value` with Malli `schema`. On failure throw an ex-info
   with `:key` and `:problems` so callers (and Integrant's wrapper)
   can surface the exact failure."
  [key schema value]
  (when-let [problems (m/explain schema value)]
    (throw (ex-info (str "Integrant config failed Malli validation for " key)
                    {:key key
                     :schema schema
                     :problems (:errors problems)}))))

(def ^:private LlmClientConfig
  "Malli schema for :lateralus/llm-client."
  [:multi {:dispatch :impl}
   [:stub [:map [:impl [:= :stub]]]]
   [:http [:map
           [:impl [:= :http]]
           [:base-url :string]
           [:model :string]
           [:api-key {:optional true} [:maybe :string]]
           [:connect-timeout-ms {:optional true} :int]
           [:request-timeout-ms {:optional true} :int]
           [:max-retries {:optional true} :int]]]])

(def ^:private EmbedderConfig
  "Malli schema for :lateralus/embedder."
  [:multi {:dispatch :method}
   [:noop [:map [:method [:= :noop]]]]
   [:http [:map
           [:method [:= :http]]
           [:base-url :string]
           [:model :string]
           [:dimensions :int]
           [:api-key {:optional true} [:maybe :string]]
           [:connect-timeout-ms {:optional true} :int]
           [:request-timeout-ms {:optional true} :int]]]
   [:langchain4j [:map [:method [:= :langchain4j]]]]])

(def ^:private MemoryBackendConfig
  "Malli schema for :lateralus/memory-backend."
  [:multi {:dispatch :impl}
   [:noop [:map [:impl [:= :noop]]]]
   [:proximum [:map
               [:impl [:= :proximum]]
               [:store {:optional true} :map]
               [:embedder {:optional true} some?]
               [:dim {:optional true} :int]
               [:capacity {:optional true} :int]
               [:M {:optional true} :int]
               [:ef-construction {:optional true} :int]
               [:ef-search {:optional true} :int]
               [:distance {:optional true} [:enum :euclidean :cosine]]
               [:sync-on-write? {:optional true} :boolean]]]
   [:kg-bm25 [:map
              [:impl [:= :kg-bm25]]
              [:store :map]
              [:top-y {:optional true} :int]
              [:last-n {:optional true} :int]
              [:rrf-k {:optional true} :int]
              [:extract-fn {:optional true} fn?]]]])

(defmethod ig/assert-key :lateralus/llm-client [_ config]
  (assert-malli! :lateralus/llm-client LlmClientConfig config))

(defmethod ig/assert-key :lateralus/embedder [_ config]
  (assert-malli! :lateralus/embedder EmbedderConfig config))

(def ^:private WebToolsConfig
  "Malli schema for :lateralus/web-tools."
  web.schemas/WebConfig)

(defmethod ig/assert-key :lateralus/web-tools [_ config]
  (assert-malli! :lateralus/web-tools WebToolsConfig config))

(defmethod ig/assert-key :lateralus/memory-backend [_ config]
  (assert-malli! :lateralus/memory-backend MemoryBackendConfig config))

;; ---- Component definitions ----

(defmethod ig/init-key :lateralus/llm-client [_ {:keys [impl] :as opts}]
  (case (or impl :stub)
    :stub (llm-client/stub-client)
    :http (llm-client/http-client opts)))

;; Separate key for the LLM config (the :base-url / :api-key /
;; :model opts) so the agent component can read the raw config
;; and seed the runtime's state. The :lateralus/llm-client key
;; holds the resolved client (a reify); this key holds the opts.
(defmethod ig/init-key :lateralus/llm-config [_ opts]
  opts)

(defmethod ig/init-key :lateralus/embedder [_ {:keys [method] :as opts}]
  (case (or method :noop)
    :noop         (embedding/noop-embedder)
    :http         (http-embedding/http-embedder opts)
    :langchain4j  (let [embedder (resolve 'kschltz.agent.memory.langchain4j-embedding/langchain4j-embedder)]
                    (embedder))))

(defmethod ig/init-key :lateralus/web-tools [_ opts]
  "Build the web tool registry from the web-tools config. The default
   provider is :none, so the registry is always present but performs no
   network I/O unless the operator opts into :mojeek."
  (tools.web/web-registry opts))

(defmethod ig/init-key :lateralus/memory-backend [_ {:keys [impl _embedder] :as opts}]
  ;; In-memory default: :noop. :proximum is the runtime default and
  ;; provides durable HNSW-backed memory when configured with a real
  ;; embedder. :kg-bm25 is a pure-Clojure, embedding-free backend
  ;; that uses BM25 sparse retrieval plus a small knowledge graph.
  ;; The backend receives the resolved :embedder so it can embed
  ;; message content at store time when needed.
  (let [method (or impl :noop)]
    (with-meta
      (case method
        :noop      (noop-memory/backend)
        :kg-bm25   (kg-bm25-memory/backend (dissoc opts :embedder))
        :proximum  (let [backend (resolve 'kschltz.agent.memory.proximum-backend/backend)]
                    (backend (cond-> opts
                               (not (contains? opts :embedder))
                               (assoc :embedder (embedding/noop-embedder))))))
      {:memory-backend/impl method})))

(defmethod ig/init-key :lateralus/plugins [_ plugins]
  ;; The base plugin is prepended automatically so user plugins are
  ;; assembled around the default exchange chain. A config that lists
  ;; no plugins gets just the base chain. A plugin marked
  ;; `^{:plugin/complete? true}` disables the auto-prepended base chain,
  ;; allowing a complete replacement chain (e.g. the tool-loop example).
  (let [complete? (some #(-> % meta :plugin/complete? true?) plugins)]
    (if complete?
      (vec plugins)
      (vec (cons (plugins.base/base-plugin) plugins)))))

(defmethod ig/init-key :lateralus/base-plugin [_ _]
  (plugins.base/base-plugin))

(defmethod ig/init-key :lateralus/memory-plugin [_ opts]
  (plugins.memory/memory-plugin opts))

(defmethod ig/init-key :lateralus/tool-registry [_ tools]
  "Integrant component that holds the map of tool name -> Tool.
   Accepts either a single registry map or a vector of registry maps
   to merge left-to-right. The merged map is consumed by
   `:lateralus/tools-plugin`, which seeds it on the context at chain
   execution time."
  (if (map? tools)
    tools
    (apply merge {} tools)))

(defmethod ig/init-key :lateralus/file-tools [_ opts]
  "Convenience Integrant component that returns the filesystem tool
   registry (`file/read`, `file/list`, `file/info`, `file/search`).
   Used by the tool-loop example config; not part of the default config
   so production agents start with an empty tool registry."
  (tools.filesystem/filesystem-registry opts))

(defmethod ig/init-key :lateralus/self-awareness-tools [_ {:keys [workspace-root]}]
  "Returns the self-awareness tool registry (`self/status`). The tool
   reads from the interceptor context, so it can be built at system
   init time like any other tool."
  (tools.self/self-awareness-registry workspace-root))

(defmethod ig/init-key :lateralus/clojure-tools [_ opts]
  "Returns the Clojure structured-editing tool registry (clojure/query,
   clojure/add-require, clojure/remove-def, clojure/rename-symbol,
   clojure/insert-form, clojure/edit-def, clojure/format-file)."
  (tools.clojure/clojure-registry opts))

(defmethod ig/init-key :lateralus/tools-plugin [_ {:keys [registry]}]
  (plugins.tools/tools-plugin registry))

(defmethod ig/init-key :lateralus/agent
  [_ {:keys [plugins llm-client embedder memory-backend llm-config]}]
  ;; The agent-map is what the runtime consumes. `:initial-state`
  ;; seeds the runtime's state atom so compose-context sees the
  ;; LLM config (:base-url / :api-key / :model) and any other
  ;; persistent context. The state atom is the only place chain
  ;; stages should read persistent context from.
  ;;
  ;; The exchange chain is assembled from `:plugins`.
  (let [llm-config (or llm-config {})
        assembled (plugin/assemble-chain (or plugins []))]
    {:agent/llm-client  llm-client    ; pre-wired into ctx as `:llm/client`
     :embedder          embedder
     :memory-backend    memory-backend
     :assembled         assembled
     :exchange-chain    assembled
     :initial-state     (merge {:agent/system-message "lateralus-v2 MVP"}
                               (select-keys llm-config
                                            [:base-url :api-key :model]))}))

;; ---- Halt ----

(defmethod ig/halt-key! :lateralus/memory-backend [_ backend]
  ;; No `satisfies?` guard: defmethod dispatch already routes the
  ;; right backend to this method. The noop backend's -close is a
  ;; no-op; a future real backend will close its store here.
  (memory-protocol/-close backend))

;; ---- System helper ----

(def default-config
  "Default Integrant config. `cli/build-system` merges a custom EDN
   file (passed via `--config PATH`) over this in-memory map using
   `ig/read-string`, so the file can use `#ig/ref` tag literals. The
   classpath resource `resources/lateralus/config.edn` is also loaded
   automatically when present; it is merged over this map before any
   `--config` file is applied.

   In-memory default (tests): stub LLM + noop embedder + noop memory.
   The runtime default in `resources/lateralus/config.edn` uses
   Proximum + LangChain4j in-process ONNX embedding."
  {:lateralus/llm-client     {:impl :stub}
   :lateralus/llm-config     {}
   :lateralus/embedder       {:method :noop}
   :lateralus/memory-backend {:impl :noop
                              :embedder (ig/ref :lateralus/embedder)}
   :lateralus/memory-plugin  {:backend  (ig/ref :lateralus/memory-backend)
                              :embedder (ig/ref :lateralus/embedder)
                              :top-y    3
                              :last-n   5}
   :lateralus/file-tools           {}
   :lateralus/self-awareness-tools {}
   :lateralus/web-tools            {:provider :none}
   :lateralus/tool-registry        [(ig/ref :lateralus/file-tools)
                                    (ig/ref :lateralus/self-awareness-tools)
                                    (ig/ref :lateralus/web-tools)]
   :lateralus/tools-plugin         {:registry (ig/ref :lateralus/tool-registry)}
   :lateralus/plugins              [(ig/ref :lateralus/memory-plugin)
                                    (ig/ref :lateralus/tools-plugin)]
   :lateralus/agent                {:plugins        (ig/ref :lateralus/plugins)
                                    :llm-client     (ig/ref :lateralus/llm-client)
                                    :llm-config     (ig/ref :lateralus/llm-config)
                                    :embedder       (ig/ref :lateralus/embedder)
                                    :memory-backend (ig/ref :lateralus/memory-backend)}})
