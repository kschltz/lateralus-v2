(ns kschltz.agent.llm.stream
  "Streaming extension of LlmClient. Network I/O stays in llm.http;
   this namespace is the protocol + a wrapping client that emits
   StreamEvents while still returning a full chat response."
  (:require [kschltz.agent.llm.client :as lcm-client]
            [kschltz.agent.llm.schemas :as schemas]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def StreamEvent
  "One live metadata event from an LLM call (or the stream plugin)."
  [:map
   [:type :keyword]
   [:ts :int]
   [:text {:optional true} :string]
   [:thinking {:optional true} :string]
   [:model {:optional true} :string]
   [:finish-reason {:optional true} :string]
   [:usage {:optional true} :map]
   [:tool-name {:optional true} :string]
   [:tool-arguments {:optional true} :string]
   [:tool-result {:optional true} :string]
   [:elapsed-ms {:optional true} :int]
   [:wave {:optional true} :int]
   [:error {:optional true} :string]])

(defprotocol StreamableLlmClient
  "Optional companion to LlmClient for token-by-token providers."
  (-call-stream [client req emit!]
    "Invoke `req`, call `emit!` with StreamEvent maps as tokens arrive,
     and return the assembled chat response (same shape as -call)."))

(defn streamable?
  [client]
  (satisfies? StreamableLlmClient client))

(defn now-ms []
  (System/currentTimeMillis))

(defn event
  [type kvs]
  (merge {:type type :ts (now-ms)} kvs))

(defn- emit-assembled
  [emit! resp started-ms]
  (let [text     (schemas/extract-text resp)
        thinking (schemas/extract-thinking resp)
        calls    (schemas/extract-tool-calls resp)
        elapsed  (- (now-ms) started-ms)]
    (emit! (event :llm-start {:model (schemas/extract-model resp)
                              :elapsed-ms 0}))
    (when (seq thinking)
      (emit! (event :thinking-delta {:thinking thinking})))
    (when (seq text)
      (emit! (event :text-delta {:text text})))
    (doseq [c calls]
      (emit! (event :tool-call {:tool-name (get-in c [:function :name])
                                :tool-arguments (get-in c [:function :arguments])})))
    (emit! (event :llm-done {:model (schemas/extract-model resp)
                             :finish-reason (schemas/extract-finish-reason resp)
                             :usage (or (:usage resp) {})
                             :elapsed-ms elapsed}))
    resp))

(defrecord StreamingClient [inner emit!]
  lcm-client/LlmClient
  (-call [_client req]
    (let [emit (or emit! (:on-event req) (fn [_]))
          started (now-ms)
          req* (dissoc req :on-event)]
      (if (streamable? inner)
        (-call-stream inner req* emit)
        (emit-assembled emit (lcm-client/-call inner req*) started)))))

(defn wrap-client
  "Return an LlmClient that emits StreamEvents. Uses -call-stream when
   the inner client is streamable; otherwise emits one assembled burst."
  ([client] (wrap-client client nil))
  ([client emit!]
   (if (instance? StreamingClient client)
     (if emit! (assoc client :emit! emit!) client)
     (->StreamingClient client emit!))))

(m/=> wrap-client
      [:function
       [:=> [:cat :any] :any]
       [:=> [:cat :any :any] :any]])
(m/=> streamable? [:=> [:cat :any] :boolean])
(m/=> event [:=> [:cat :keyword :map] StreamEvent])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.llm.stream)]}))

(instrument!)
