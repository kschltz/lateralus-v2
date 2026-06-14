(ns kschltz.agent.system
  "Integrant system definition for the lateralus-v2 agent.

   Components:
     :lateralus/llm-client       LlmClient implementation (stub for MVP)
     :lateralus/embedder         Embedder impl (no-op for MVP)
     :lateralus/memory-backend   MemoryBackend impl (noop stub for MVP;
                                   a real persistent store — Datalevin,
                                   SQLite, LMDB, flat files, etc. — is
                                   a follow-up that satisfies the same
                                   MemoryBackend protocol)
     :lateralus/plugins          Seq of plugin maps to assemble
     :lateralus/agent            Agent entry: assembled chain + clients
                                  resolved at init time

   MVP scope: no real persistent memory backend, no real embedding.
   The MemoryBackend noop impl is the MVP; HTTP/ONNX embedders and
   a real memory store are follow-ups. The LlmClient stub is the
   MVP default; the HTTP impl (kschltz.agent.llm.http) is wired
   when the Integrant config passes `:impl :http` for
   `:lateralus/llm-client`.

   Halt policy: only keys with real resources to release define
   `halt-key!` (currently just `:lateralus/memory-backend`).
   Integrant skips keys with no `halt-key!` defined, which is the
   correct behavior — defining a no-op halt is misleading."
  (:require [integrant.core :as ig]
            [kschltz.agent.exchange :as exchange]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.memory :as plugins.memory]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.noop-backend :as noop-memory]
            [kschltz.agent.memory.proximum-backend :as proximum-memory]
            [kschltz.agent.memory.protocol :as memory-protocol]))

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
    :noop (embedding/noop-embedder)))

(defmethod ig/init-key :lateralus/memory-backend [_ {:keys [impl embedder] :as opts}]
  ;; MVP default: :noop. :proximum is an optional durable HNSW backend.
  ;; The backend receives the resolved :embedder so it can embed message
  ;; content at store time.
  (case (or impl :noop)
    :noop     (noop-memory/backend)
    :proximum (proximum-memory/backend (cond-> opts
                                         (not (contains? opts :embedder))
                                         (assoc :embedder (embedding/noop-embedder))))))

(defmethod ig/init-key :lateralus/plugins [_ {:keys [plugins]}]
  ;; The base plugin is always prepended so that user plugins are
  ;; assembled around the default exchange chain. A config that
  ;; explicitly sets `:plugins []` gets just the base chain.
  (vec (cons (plugins.base/base-plugin) plugins)))

(defmethod ig/init-key :lateralus/base-plugin [_ _]
  (plugins.base/base-plugin))

(defmethod ig/init-key :lateralus/memory-plugin [_ opts]
  (plugins.memory/memory-plugin opts))

(defmethod ig/init-key :lateralus/agent
  [_ {:keys [plugins llm-client embedder memory-backend llm-config]}]
  ;; The agent-map is what the runtime consumes. `:initial-state`
  ;; seeds the runtime's state atom so compose-context sees the
  ;; LLM config (:base-url / :api-key / :model) and any other
  ;; persistent context. The state atom is the only place chain
  ;; stages should read persistent context from.
  (let [llm-config (or llm-config {})
        assembled (plugin/assemble-chain (or plugins []))]
    {:agent/llm-client  llm-client    ; read by `bind-llm-client` stage
     :embedder          embedder
     :memory-backend    memory-backend
     :assembled         assembled
     ;; If plugins assembled an empty chain, fall back to the legacy
     ;; hardcoded default exchange chain (e.g. a test config with
     ;; `:plugins []`). Otherwise the assembled chain is the live chain.
     :exchange-chain    (if (seq assembled) assembled exchange/default-exchange-chain)
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

   MVP defaults: stub LLM + noop embedder + noop memory. To use
   the real LlmClient HTTP impl, set
   `:lateralus/llm-client {:impl :http :base-url ... :api-key ... :model ...}`
   in the runtime config. A real memory store is a follow-up.
   A real embedder is also a follow-up (no MVP gate)."
  {:lateralus/llm-client     {:impl :stub}
   :lateralus/llm-config     {}
   :lateralus/embedder       {:method :noop}
   :lateralus/memory-backend {:impl :noop
                              :embedder (ig/ref :lateralus/embedder)}
   :lateralus/base-plugin    {}
   :lateralus/memory-plugin  {:backend  (ig/ref :lateralus/memory-backend)
                              :embedder (ig/ref :lateralus/embedder)
                              :top-y    3
                              :last-n   5}
   :lateralus/plugins        {:plugins [(ig/ref :lateralus/memory-plugin)]}
   :lateralus/agent          {:plugins        (ig/ref :lateralus/plugins)
                              :llm-client     (ig/ref :lateralus/llm-client)
                              :llm-config     (ig/ref :lateralus/llm-config)
                              :embedder       (ig/ref :lateralus/embedder)
                              :memory-backend (ig/ref :lateralus/memory-backend)}})
