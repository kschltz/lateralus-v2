(ns kschltz.agent.workbench.loop
  "Outer UI-session loop for the workbench plugin: park on web chat,
   run one agent exchange, publish artifacts back into the chat pane.
   The exchange chain itself is never parked."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.workbench.hub :as hub]
            [kschltz.agent.workbench.protocol :as wb]))

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
    (cond-> {:role :assistant :text body}
      (seq thinking) (assoc :thinking thinking))))

(defn- start-stdin-feeder!
  "Background thread: each stdin line is enqueued as a human message."
  [workbench in]
  (future
    (try
      (let [rdr (java.io.BufferedReader. (io/reader in))]
        (loop []
          (when-let [line (.readLine rdr)]
            (let [trimmed (str/trim line)]
              (when (seq trimmed)
                (hub/enqueue-human! (:hub workbench) {:text trimmed :refs []})))
            (recur))))
      (catch Throwable _))))

(defn run-session!
  "Drive an interactive workbench session until /quit or /exit.

   `runtime` — from `runtime/start`
   `workbench` — `Workbench`
   opts:
     :stdin-feeder?  mirror terminal stdin into the inbox (default true)
     :in             InputStream for the feeder (default *in*)
   Returns `:quit`."
  ([runtime workbench]
   (run-session! runtime workbench {}))
  ([runtime workbench {:keys [stdin-feeder? in]
                       :or   {stdin-feeder? true
                              in            *in*}}]
   (wb/publish! workbench
                {:role :system
                 :text (str "lateralus workbench — open "
                            (wb/url workbench)
                            " (CHAT | Portal). Prefer portal/submit for rich "
                            "visuals (HTML, tables, charts); /quit to exit.")})
   (let [feeder (when stdin-feeder? (start-stdin-feeder! workbench in))]
     (try
       (loop []
         (let [msg     (wb/await-human!* workbench {})
               prompt  (hub/format-prompt msg)
               trimmed (str/trim (str (:text msg)))]
           (cond
             (#{"/quit" "/exit"} trimmed)
             (do (wb/publish! workbench {:role :system :text "Goodbye."})
                 :quit)

             (seq trimmed)
             (let [h (:hub workbench)]
               (hub/set-status! h :running "model working…")
               (try
                 (let [result (runtime/send-message runtime prompt)]
                   (wb/publish! workbench (assistant-event result))
                   (hub/set-status! h :waiting "ready for your next message"))
                 (catch Throwable t
                   (wb/publish! workbench
                                {:role :error
                                 :text (str "Exchange failed: "
                                            (or (ex-message t)
                                                (.getName (class t))))})
                   (hub/set-status! h :error
                                    (or (ex-message t) "exchange failed"))))
               (recur))

             :else
             (recur))))
       (finally
         (when feeder (future-cancel feeder))
         (wb/close! workbench))))))
