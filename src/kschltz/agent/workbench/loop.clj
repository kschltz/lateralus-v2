(ns kschltz.agent.workbench.loop
  "Outer UI-session loop for the workbench plugin: park on web chat,
   run one agent exchange, publish artifacts back into the chat pane.
   The exchange chain itself is never parked."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.workbench.cite :as cite]
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

(defn- tool-results
  [result]
  (or (:agent/all-tool-results result) (:tool/results result) []))

(defn- raised-error-text
  "Human-readable text when the chain handled an LLM/tool throw via
   `:error/raised` (e.g. Ollama Cloud 403) — otherwise workbench looked
   like a silent empty reply."
  [result]
  (when-let [raised (:error/raised result)]
    (let [ex   (:exception raised)
          data (when (instance? clojure.lang.ExceptionInfo ex) (ex-data ex))
          body (:body data)
          detail (cond
                   (and (map? body) (:error body)) (str (:error body))
                   (string? body) body
                   (ex-message ex) (ex-message ex)
                   :else "unknown error")]
      (str "Exchange failed: " detail
           (when (:status data) (str " (HTTP " (:status data) ")"))))))

(defn- raw-assistant-text
  [result]
  (let [text  (or (:exchange/response result) "")
        tools (tool-results result)
        err   (raised-error-text result)]
    (cond
      (seq text)  text
      (seq tools) (tool-summary tools)
      (seq err)   err
      (:agent/empty-retry-failed? result)
      "The model returned empty replies after retries. Check --model (cloud models need Ollama Cloud enabled / --base-url https://ollama.com/v1)."
      :else       "The assistant produced no response for this turn.")))

(defn guard-assistant-event
  "Sanitize @portal cites against hub refs; flag missing submits.
   Surfaces `:error/raised` as an `:error` turn instead of a fake empty assistant."
  [result workbench]
  (if-let [err (raised-error-text result)]
    {:role :error
     :text err
     ::needs-repair? false
     ::repaired? false}
    (let [text     (raw-assistant-text result)
          tools    (tool-results result)
          thinking (:exchange/thinking result)
          ids      (cite/known-ids-from-snapshot (wb/snapshot workbench))
          guard    (cite/assistant-text-guard text tools ids)]
      (cond-> {:role :assistant
               :text (:text guard)
               ::needs-repair? (:needs-repair? guard)
               ::repaired? (:repaired? guard)}
        (seq thinking) (assoc :thinking thinking)))))

(defn- public-event
  [event]
  (dissoc event ::needs-repair? ::repaired?))

(defn- run-exchange!
  [runtime workbench prompt]
  (let [result (runtime/send-message runtime prompt)
        event  (guard-assistant-event result workbench)]
    (if-not (::needs-repair? event)
      (public-event event)
      (do
        (wb/publish! workbench
                     {:role :system
                     :text (str "Portal guard: claimed a viz without a successful "
                                "portal_submit (or used a fake @portal id). Retrying once…")})
        (let [repair (runtime/send-message runtime cite/repair-prompt)
              fixed  (guard-assistant-event repair workbench)]
          (public-event fixed))))))

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
                           " (CHAT | Portal). Prefer portal_submit for rich "
                            "visuals (HTML/SVG charts, tables); /quit to exit.")})
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
                 (let [event (run-exchange! runtime workbench prompt)]
                   (wb/publish! workbench event)
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
