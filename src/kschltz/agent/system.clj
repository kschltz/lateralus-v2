(ns kschltz.agent.system
  "Integrant system definition for the lateralus-v2 agent.

   Components:
     :lateralus/llm-client       LlmClient implementation (stub for MVP)
     :lateralus/embedder         Embedder impl (no-op for MVP)
     :lateralus/memory-backend   MemoryBackend impl (no-op stub for MVP;
                                   Step 6 replaces with Datalevin v2)
     :lateralus/plugins          Seq of plugin maps to assemble
     :lateralus/agent            Agent entry: assembled chain + clients
                                  resolved at init time

   MVP scope: no Datalevin, no LLM HTTP, no real embedding. Each
   component is a stub that satisfies its protocol. Step 5/6 replace
   them with real implementations.

   Halt policy: only keys with real resources to release define
   `halt-key!` (currently just `:lateralus/memory-backend`).
   Integrant skips keys with no `halt-key!` defined, which is the
   correct behavior — defining a no-op halt is misleading."
  (:require [integrant.core :as ig]
            [kschltz.agent.exchange :as exchange]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.memory.protocol :as memory-protocol]
            [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.noop-backend :as noop-memory]))

;; ---- Component definitions ----

(defmethod ig/init-key :lateralus/llm-client [_ {:keys [impl] :as opts}]
  (case (or impl :stub)
    :stub (llm-client/stub-client)
    :http (llm-client/http-client opts)))

(defmethod ig/init-key :lateralus/embedder [_ {:keys [method] :as opts}]
  (case (or method :noop)
    :noop (embedding/noop-embedder)
    :http (embedding/http-embedder opts)))

(defmethod ig/init-key :lateralus/memory-backend [_ {:keys [impl] :as opts}]
  (case (or impl :noop)
    :noop (noop-memory/backend)
    :datalevin (throw (ex-info "Datalevin backend not yet implemented (Step 6)"
                               {:opts opts}))))

(defmethod ig/init-key :lateralus/plugins [_ {:keys [plugins]}]
  (vec plugins))

(defmethod ig/init-key :lateralus/agent
  [_ {:keys [plugins llm-client embedder memory-backend]}]
  {:agent/llm-client  llm-client    ; read by `bind-llm-client` stage
   :embedder          embedder
   :memory-backend    memory-backend
   :assembled         (plugin/assemble-chain (or plugins []))
   :exchange-chain    exchange/default-exchange-chain})

;; ---- Halt ----

(defmethod ig/halt-key! :lateralus/memory-backend [_ backend]
  ;; No `satisfies?` guard: defmethod dispatch already routes the
  ;; right backend to this method. The noop backend's -close is a
  ;; no-op; Step 6's Datalevin backend will close its store here.
  (memory-protocol/-close backend))

;; ---- System helper ----

(def default-config
  "Default Integrant config. Reads from
   `resources/lateralus/config.edn` at startup, falling back to this
   in-memory map when no file is present.

   MVP defaults: stub LLM + noop embedder + noop memory. Step 5/6
   replace these with real implementations (no config change
   required beyond :impl/:method)."
  {:lateralus/llm-client     {:impl :stub}
   :lateralus/embedder       {:method :noop}
   :lateralus/memory-backend {:impl :noop}
   :lateralus/plugins        {:plugins []}
   :lateralus/agent          {:plugins        (ig/ref :lateralus/plugins)
                              :llm-client     (ig/ref :lateralus/llm-client)
                              :embedder       (ig/ref :lateralus/embedder)
                              :memory-backend (ig/ref :lateralus/memory-backend)}})
