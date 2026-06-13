(ns kschltz.agent.system
  "Integrant system definition for the lateralus-v2 agent.

   Components:
     :lateralus/llm-client       LlmClient implementation (stub for MVP)
     :lateralus/embedder         Embedder impl (HTTP stub for MVP)
     :lateralus/memory-backend   MemoryBackend impl (no-op stub for MVP;
                                   Step 6 replaces with Datalevin v2)
     :lateralus/plugins          Seq of plugin maps to assemble
     :lateralus/agent            Agent entry: assembled chain + clients
                                  resolved at init time

   MVP scope: no Datalevin, no LLM HTTP, no real embedding. Each
   component is a stub that satisfies its protocol. Step 5/6 replace
   them with real implementations."
  (:require [integrant.core :as ig]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.exchange :as exchange]
            [kschltz.agent.interceptors :as ix]
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
  (case (or method :http)
    :http (embedding/http-embedder opts)
    :noop (embedding/noop-embedder)))

(defmethod ig/init-key :lateralus/memory-backend [_ {:keys [impl] :as opts}]
  (case (or impl :noop)
    :noop (noop-memory/backend)
    :datalevin (throw (ex-info "Datalevin backend not yet implemented (Step 6)"
                               {:opts opts}))))

(defmethod ig/init-key :lateralus/plugins [_ {:keys [plugins]}]
  (vec plugins))

(defmethod ig/init-key :lateralus/agent
  [_ {:keys [plugins llm-client embedder memory-backend]}]
  {:llm/client     llm-client
   :embedder       embedder
   :memory-backend memory-backend
   :assembled      (plugin/assemble-chain (or plugins []))
   :exchange-chain exchange/default-exchange-chain})

;; ---- Halt ----

(defmethod ig/halt-key! :lateralus/agent [_ _]
  ;; No resources to release yet (Step 6 will add Datalevin close).
  :halted)

(defmethod ig/halt-key! :lateralus/memory-backend [_ backend]
  (when (satisfies? memory-protocol/MemoryBackend backend)
    (memory-protocol/-close backend))
  :halted)

(defmethod ig/halt-key! :lateralus/llm-client [_ _]
  :halted)

(defmethod ig/halt-key! :lateralus/embedder [_ _]
  :halted)

(defmethod ig/halt-key! :lateralus/plugins [_ _]
  :halted)

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
