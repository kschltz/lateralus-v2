(ns kschltz.agent.portal.loop
  "Outer UI-session loop: park on human input, run one agent exchange,
   publish artifacts back into Portal. The exchange chain itself is never
   parked — only this session loop waits on the composer.

   Stdin is optionally mirrored into the same inbox so `/quit` and chat
   still work from the terminal if the Portal window/composer is unavailable."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.portal.protocol :as ui]
            [kschltz.agent.runtime :as runtime]))

(defn- tool-summary
  [tools]
  (str "The assistant used tools but produced no final text.\n"
       (str/join "\n"
                 (map (fn [{:keys [call result]}]
                        (str "- " (get-in call [:function :name])
                             ": " (pr-str result)))
                      tools))))

(defn- assistant-event
  [result]
  (let [text     (or (:exchange/response result) "")
        thinking (:exchange/thinking result)
        tools    (or (:agent/all-tool-results result) (:tool/results result) [])
        body     (cond
                   (seq text)  text
                   (seq tools) (tool-summary tools)
                   :else       "The assistant produced no response for this turn.")]
    (cond-> {:type :assistant :text body}
      (seq thinking) (assoc :thinking thinking))))

(defn- start-stdin-feeder!
  "Background thread: each stdin line is enqueued as a human reply.
   Returns a future (cancel on session end)."
  [in]
  (future
    (try
      (let [rdr (java.io.BufferedReader. (io/reader in))]
        (loop []
          (when-let [line (.readLine rdr)]
            ((requiring-resolve 'kschltz.agent.portal.jvm/reply!) {:text line})
            (recur))))
      (catch Throwable _))))

(defn run-session!
  "Drive an interactive Portal UI session until /quit or /exit.

   `runtime` — from `runtime/start`
   `agent-ui` — `AgentUi` (Portal)
   opts:
     :stdin-feeder?  mirror terminal stdin into the inbox (default true)
     :in             InputStream for the feeder (default *in*)
   Returns `:quit`."
  ([runtime agent-ui]
   (run-session! runtime agent-ui {}))
  ([runtime agent-ui {:keys [stdin-feeder? in]
                      :or   {stdin-feeder? true
                             in            *in*}}]
   (ui/publish! agent-ui
                {:type :system
                 :text "lateralus portal session — Send in Portal (or type here), /quit to exit"})
   (let [feeder (when stdin-feeder? (start-stdin-feeder! in))]
     (try
       (loop []
         (let [line (str/trim (ui/await-human! agent-ui {}))]
           (cond
             (#{"/quit" "/exit"} line)
             (do (ui/publish! agent-ui {:type :system :text "Goodbye."})
                 :quit)

             (seq line)
             (let [result (runtime/send-message runtime line)]
               (ui/publish! agent-ui (assistant-event result))
               (recur))

             :else
             (recur))))
       (finally
         (when feeder (future-cancel feeder))
         (ui/close! agent-ui))))))
