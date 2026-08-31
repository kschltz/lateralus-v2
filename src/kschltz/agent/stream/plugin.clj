(ns kschltz.agent.stream.plugin
  "Partial plugin: open a metadata turn, wrap the LLM client so
   tokens/thinking stream onto the bus, and finalize historic state."
  (:require [kschltz.agent.llm.stream :as llm.stream]
            [kschltz.agent.stream.bus :as bus]
            [kschltz.agent.stream.protocol :as proto]))

(defn- emit-fn
  [stream-bus turn-id]
  (fn [event]
    (when (and stream-bus turn-id)
      (bus/emit! stream-bus turn-id event))))

(defn- wrap-llm
  [ctx stream-bus turn-id]
  (let [client (or (:llm/client ctx) (:llm/client (:agent/agent-map ctx)))]
    (cond-> ctx
      client (assoc :llm/client
                    (llm.stream/wrap-client client (emit-fn stream-bus turn-id))))))

(defn- seed-interceptor
  [stream-bus]
  {:name ::seed-stream
   :slot :guard
   :enter (fn [ctx]
            (if-not (proto/stream-bus? stream-bus)
              ctx
              (let [existing (:stream/turn-id ctx)
                    current  (bus/current-id stream-bus)
                    live     (or (when existing
                                   (when (:live? (bus/snapshot stream-bus existing))
                                     existing))
                                 (when current
                                   (when (:live? (bus/snapshot stream-bus current))
                                     current)))
                    turn-id  (or live
                                 (bus/open-turn!
                                  stream-bus
                                  {:session-id (:exchange/session-id ctx)
                                   :user-text  (:exchange/user-text ctx)}))]
                (-> ctx
                    (assoc :agent/stream-bus stream-bus
                           :stream/turn-id turn-id)
                    (wrap-llm stream-bus turn-id)))))})

(defn- emit-tool-result-events!
  "Write every guarded `:agent/all-tool-results` entry onto the live turn.
   Follow-up dispatch replaces `:tool/results` (including with `[]` on the
   final text response), so the accumulated vector is the audit source."
  [stream-bus ctx]
  (let [turn-id (:stream/turn-id ctx)
        results (or (:agent/all-tool-results ctx) [])]
    (when (and (proto/stream-bus? stream-bus) turn-id (seq results))
      (doseq [{:keys [call result]} results]
        (bus/emit! stream-bus turn-id
                   (llm.stream/event
                    :tool-result
                    {:tool-name (get-in call [:function :name])
                     ;; `plugins.secrets` has already scrubbed the
                     ;; tools-stage ctx. Preserve the exact guarded
                     ;; result for Workbench lifecycle auditability.
                     :tool-result (let [s (str result)
                                        cap 20000]
                                    (if (> (count s) cap)
                                      (str (subs s 0 cap)
                                           "\n... [audit result truncated]")
                                      s))}))))))

(defn- tool-results-interceptor
  "Emit every guarded result after the ReAct loop settles. This interceptor
   follows the turn closer in the assembled `:observe` slot so reverse leave
   order emits results before closing the bus on the success path."
  [stream-bus]
  {:name ::stream-tools
   :slot :observe
   :leave (fn [ctx]
            (emit-tool-result-events! stream-bus ctx)
            ctx)})

(defn- observe-interceptor
  [stream-bus]
  {:name ::stream-observe
   :slot :observe
   :leave (fn [ctx]
            (let [turn-id (:stream/turn-id ctx)]
              (when (and (proto/stream-bus? stream-bus) turn-id)
                (when-let [all (:agent/all-tool-results ctx)]
                  (when (seq all)
                    (bus/emit! stream-bus turn-id
                               (llm.stream/event
                                :tools
                                {:tool-name (str (count all) " result(s)")}))))
                (bus/close-turn! stream-bus turn-id :done {}))
              (cond-> ctx
                turn-id (assoc :stream/turn-id turn-id))))
   :error (fn [ctx ex]
            (let [turn-id (:stream/turn-id ctx)]
              (when (and (proto/stream-bus? stream-bus) turn-id)
                ;; Leave-only `stream-tools` is popped during the error walk
                ;; and never emits. Persist guarded results before close so a
                ;; terminal LLM failure still has an auditable EVENT LOG.
                (emit-tool-result-events! stream-bus ctx)
                (bus/close-turn! stream-bus turn-id :error
                                 {:error (or (ex-message ex)
                                             (.getName (class ex)))}))
              ctx))})

(defn stream-plugin
  "Partial plugin around a live StreamBus. Empty when bus is nil."
  [stream-bus]
  (with-meta
    (if (proto/stream-bus? stream-bus)
      [(seed-interceptor stream-bus)
       (observe-interceptor stream-bus)
       (tool-results-interceptor stream-bus)]
      [])
    {:plugin/name :stream
     :plugin/rebuild (fn [] (stream-plugin stream-bus))}))
