(ns kschltz.agent.llm.http-stream
  "Parse OpenAI-compatible chat.completion.chunk SSE into a ChatResponse
   while emitting StreamEvents. No HTTP — the socket lives in llm.http."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.llm.schemas :as schemas]
            [kschltz.agent.llm.stream :as stream]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def StreamState
  [:map
   [:text :string]
   [:thinking :string]
   [:model {:optional true} [:maybe :string]]
   [:finish-reason {:optional true} [:maybe :string]]
   [:usage {:optional true} [:maybe :map]]
   [:tools :map]])

(defn- tool-entry
  [prev tc]
  (let [prev (or prev {:id "" :type "function" :function {:name "" :arguments ""}})
        fnc  (or (:function tc) {})]
    {:id   (or (not-empty (str (:id tc))) (:id prev))
     :type (or (:type tc) (:type prev) "function")
     :function {:name (str (get-in prev [:function :name])
                           (or (:name fnc) ""))
                :arguments (str (get-in prev [:function :arguments])
                                (or (:arguments fnc) ""))}}))

(defn- merge-tool-deltas
  [tools deltas]
  (reduce (fn [acc tc]
            (let [idx (or (:index tc) 0)]
              (assoc acc idx (tool-entry (get acc idx) tc))))
          (or tools {})
          (or deltas [])))

(defn- delta-thinking
  [delta msg]
  (or (when (string? (:reasoning delta)) (:reasoning delta))
      (when (string? (:reasoning_content delta)) (:reasoning_content delta))
      (when (string? (:thinking delta)) (:thinking delta))
      (when (string? (:reasoning msg)) (:reasoning msg))
      (when (string? (:reasoning_content msg)) (:reasoning_content msg))
      (when (string? (:thinking msg)) (:thinking msg))))

(defn apply-chunk
  "Fold one parsed SSE object into stream state and emit deltas."
  [state chunk emit!]
  (let [choice (first (or (:choices chunk) []))
        delta  (or (:delta choice) {})
        msg    (or (:message choice) {})
        text   (or (when (string? (:content delta)) (:content delta))
                   (when (string? (:content msg)) (:content msg)))
        think  (delta-thinking delta msg)
        tools  (or (:tool_calls delta) (:tool_calls msg))
        model  (or (:model chunk) (:model state))
        finish (or (:finish_reason choice) (:finish-reason state))
        usage  (or (:usage chunk) (:usage state))
        state  (cond-> (assoc state :model model)
                 finish (assoc :finish-reason finish)
                 usage  (assoc :usage usage)
                 (seq text)  (update :text str text)
                 (seq think) (update :thinking str think)
                 (seq tools) (update :tools merge-tool-deltas tools))]
    (when (seq think)
      (emit! (stream/event :thinking-delta {:thinking think})))
    (when (seq text)
      (emit! (stream/event :text-delta {:text text})))
    (doseq [tc tools]
      (when-let [name (get-in tc [:function :name])]
        (when (seq name)
          (emit! (stream/event :tool-call {:tool-name name
                                           :tool-arguments (str (get-in tc [:function :arguments]))})))))
    state))

(defn finish-response
  "Assemble a ChatResponse from accumulated stream state."
  [state]
  (let [calls (->> (:tools state)
                   (sort-by key)
                   (mapv (fn [[_ v]]
                           (cond-> {:id   (or (not-empty (:id v)) (str (random-uuid)))
                                    :type "function"
                                    :function {:name (or (get-in v [:function :name]) "unknown")
                                               :arguments (or (get-in v [:function :arguments]) "{}")}}))))
        msg   (cond-> {:role "assistant"
                       :content (or (:text state) "")}
                (seq calls) (assoc :tool_calls calls)
                (seq (:thinking state)) (assoc :reasoning (:thinking state)))]
    (cond-> {:model (or (:model state) "unknown")
             :choices [{:message msg
                        :finish_reason (or (:finish-reason state) "stop")}]}
      (:usage state) (assoc :usage (:usage state)))))

(defn parse-sse-data
  "Return the JSON payload of an SSE `data:` line, or ::done / nil."
  [line]
  (let [s (str/trim (str line))]
    (cond
      (not (str/starts-with? s "data:")) nil
      :else
      (let [payload (str/trim (subs s 5))]
        (cond
          (or (str/blank? payload) (= payload "[DONE]")) :done
          :else
          (try (json/parse-string payload true)
               (catch Throwable _ nil)))))))

(defn consume-sse
  "Read an SSE body from `readable`, emit StreamEvents, return ChatResponse."
  [readable emit!]
  (with-open [rdr (io/reader readable)]
    (loop [state {:text "" :thinking "" :tools {}}]
      (if-let [line (.readLine rdr)]
        (let [parsed (parse-sse-data line)]
          (cond
            (nil? parsed) (recur state)
            (= :done parsed) (finish-response state)
            :else (recur (apply-chunk state parsed emit!))))
        (finish-response state)))))

(m/=> apply-chunk [:=> [:cat StreamState :map fn?] StreamState])
(m/=> finish-response [:=> [:cat StreamState] schemas/ChatResponse])
(m/=> parse-sse-data [:=> [:cat :string] :any])
(m/=> consume-sse [:=> [:cat :any fn?] schemas/ChatResponse])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.llm.http-stream)]}))

(instrument!)
