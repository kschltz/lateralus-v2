(ns kschltz.agent.workbench.loop
  "Outer UI-session loop for the workbench plugin: park on web chat,
   run one agent exchange, publish artifacts back into the chat pane.
   The exchange chain itself is never parked."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.session.manager :as sessions]
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

(defn- status-hint
  "Human explanation for an LLM HTTP status code."
  [status]
  (case status
    401 "The API key was rejected (HTTP 401). Check the key for this profile."
    403 "The provider refused the request (HTTP 403) — check API-key
        permissions, plan, or geo availability."
    404 "Model or endpoint not found (HTTP 404) — check the profile's model
        name and base URL."
    429 "The provider rate-limited the request (HTTP 429). It was retried
        automatically and still throttled — wait a moment and try again,
        or switch to a lighter model / different provider."
    (if (and (int? status) (>= status 500))
      (str "The LLM provider had a server error (HTTP " status ").
          Usually transient — retry shortly.")
      (str "The LLM endpoint answered HTTP " status "."))))

(defn friendly-exchange-error
  "User-facing one-liner for a failed exchange. Accepts the exception
   (when the chain rethrew) or a result map with :error/raised."
  [^Throwable ex result]
  (let [data (or (when (instance? clojure.lang.ExceptionInfo ex) (ex-data ex))
                 (when result
                   (when-let [raised (:error/raised result)]
                     (let [e (:exception raised)]
                       (when (instance? clojure.lang.ExceptionInfo e) (ex-data e))))))
        status (:status data)
        body (:body data)
        provider-msg (cond
                       (map? (:error body)) (not-empty (:message (:error body)))
                       (string? (:error body)) (not-empty (:error body))
                       (some? (:error body)) (not-empty (str (:error body)))
                       (string? body) (not-empty body))]
    (cond
      (= 429 status)
      (str "Rate limited by the LLM provider (HTTP 429"
           (when provider-msg (str ": " provider-msg)) ")."
           " The request was retried automatically and still throttled."
           " Wait a bit and try again, or switch models/providers.")

      (int? status)
      (str (status-hint status)
           (when provider-msg (str " Provider said: " provider-msg)))

      (= :transport (:kind data))
      "Could not reach the LLM endpoint (network error). Check the
      base URL / connectivity."

      :else
      (str "Exchange failed: "
           (or provider-msg (some-> ex ex-message) "unknown error")))))

(defn- raised-error-text
  "Human-readable text when the chain handled an LLM/tool throw via
   `:error/raised` (e.g. Ollama Cloud 403) — otherwise workbench looked
   like a silent empty reply."
  [result]
  (when-let [raised (:error/raised result)]
    (friendly-exchange-error (:exception raised) result)))

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

(defn- with-turn-id
  [event result]
  (if-let [tid (or (:stream/turn-id result)
                   (get-in result [:agent/state-delta :stream/turn-id]))]
    (assoc event :turn-id tid)
    event))

(defn- run-exchange!
  [runtime workbench prompt]
  (let [result (runtime/send-message runtime prompt)
        event  (guard-assistant-event result workbench)
        original (with-turn-id (public-event event) result)
        original-turn-id (:turn-id original)]
    ;; #region agent log
    (spit "/opt/cursor/logs/debug.log"
          (str (json/generate-string
                {:hypothesisId "L"
                 :location "workbench/loop.clj:run-exchange:guard"
                 :message "evaluated Portal repair guard"
                 :data {:turnId original-turn-id
                        :needsRepair (boolean (::needs-repair? event))
                        :claimsPortal (cite/claims-portal-delivery? (:exchange/response result))
                        :submitSucceeded (cite/portal-submit-succeeded?
                                          (tool-results result))
                        :toolNames (mapv #(get-in % [:call :function :name])
                                         (tool-results result))}
                 :timestamp (System/currentTimeMillis)})
               "\n")
          :append true)
    ;; #endregion
    (if-not (::needs-repair? event)
      original
      (do
        (wb/publish! workbench
                     (cond-> {:role :system
                              :text (str "Portal guard: claimed a viz without a successful "
                                         "portal_submit (or used a fake @portal id). Retrying once…")}
                       original-turn-id (assoc :turn-id original-turn-id)))
        (let [repair (runtime/send-message runtime cite/repair-prompt)
              fixed  (guard-assistant-event repair workbench)
              repaired (with-turn-id (public-event fixed) repair)]
          ;; #region agent log
          (spit "/opt/cursor/logs/debug.log"
                (str (json/generate-string
                      {:hypothesisId "L"
                       :location "workbench/loop.clj:run-exchange:repair"
                       :message "linked original and repair turns"
                       :data {:originalTurnId original-turn-id
                              :repairTurnId (:turn-id repaired)
                              :repairRole (some-> repaired :role name)
                              :stillNeedsRepair (boolean (::needs-repair? fixed))}
                       :timestamp (System/currentTimeMillis)})
                     "\n")
                :append true)
          ;; #endregion
          repaired)))))

(defn- session-command?
  [text]
  (boolean (re-find #"(?i)^/session(?:\s|$)" (str text))))

(defn- handle-session-command!
  [runtime workbench text]
  (let [store (:session-store workbench)
        hub   (:hub workbench)
        parts (vec (remove str/blank? (str/split (str text) #"\s+")))
        verb  (str/lower-case (or (second parts) "help"))
        arg   (str/join " " (drop 2 parts))]
    (if-not store
      {:role :system :text "Session catalog is not available in this workbench."}
      (try
        (case verb
          ("help" "")
          {:role :system
           :text (str "/session list | new [title] | switch <id> | rename <title> | delete <id>")}

          "list"
          {:role :system
           :text (str "Sessions:\n"
                      (str/join "\n"
                                (map (fn [s]
                                       (str (if (:active? s) "* " "  ")
                                            (:id s) " — " (:title s)))
                                     (sessions/list-sessions store))))}

          "new"
          (let [s (sessions/create! store hub runtime {:title (not-empty arg)})]
            {:role :system :text (str "Switched to new session " (:id s) " (" (:title s) ").")})

          ("switch" "use")
          (let [s (sessions/activate! store hub runtime (str/trim arg))]
            {:role :system :text (str "Active session " (:id s) " (" (:title s) ").")})

          "rename"
          (let [cur (:session-id (hub/snapshot hub))
                s   (sessions/rename! store hub cur arg)]
            {:role :system :text (str "Renamed session to " (:title s) ".")})

          "delete"
          (do (sessions/delete! store hub (str/trim arg))
              {:role :system :text (str "Deleted session " (str/trim arg) ".")})

          {:role :system
           :text (str "Unknown /session command. Try /session help.")})
        (catch Exception e
          {:role :error :text (or (ex-message e) "session command failed")})))))

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
           ;; #region agent log
           (spit "/opt/cursor/logs/debug.log"
                 (str (json/generate-string
                       {:hypothesisId "F,H,I"
                        :location "workbench/loop.clj:run-session:dequeued"
                        :message "dequeued Workbench message"
                        :data {:sessionId (:session-id (hub/snapshot (:hub workbench)))
                               :runtimeSessionId (runtime/session-id runtime)
                               :textChars (count trimmed)}
                        :timestamp (System/currentTimeMillis)})
                      "\n")
                 :append true)
           ;; #endregion
           (cond
             (#{"/quit" "/exit"} trimmed)
             (do (wb/publish! workbench {:role :system :text "Goodbye."})
                 :quit)

             (session-command? trimmed)
             (do (wb/publish! workbench
                              (handle-session-command! runtime workbench trimmed))
                 (recur))

             (seq trimmed)
             (let [h (:hub workbench)]
               (hub/set-status! h :running "model working…")
               (try
                 (let [event (run-exchange! runtime workbench prompt)]
                   ;; #region agent log
                   (spit "/opt/cursor/logs/debug.log"
                         (str (json/generate-string
                               {:hypothesisId "H,I"
                                :location "workbench/loop.clj:run-session:exchange-returned"
                                :message "Workbench exchange returned"
                                :data {:sessionId (:session-id (hub/snapshot h))
                                       :role (some-> event :role name)
                                       :hasTurnId (boolean (:turn-id event))
                                       :textChars (count (str (:text event)))}
                                :timestamp (System/currentTimeMillis)})
                              "\n")
                         :append true)
                   ;; #endregion
                   (wb/publish! workbench event)
                   (when-let [store (:session-store workbench)]
                     (sessions/persist-current! store h runtime))
                   (hub/set-status! h :waiting "ready for your next message"))
                 (catch Throwable t
                   ;; #region agent log
                   (spit "/opt/cursor/logs/debug.log"
                         (str (json/generate-string
                               {:hypothesisId "G,H,I"
                                :location "workbench/loop.clj:run-session:error"
                                :message "Workbench exchange threw"
                                :data {:sessionId (:session-id (hub/snapshot h))
                                       :exceptionClass (.getName (class t))
                                       :hasMessage (boolean (some-> t ex-message seq))
                                       :safeErrorData (select-keys (ex-data t)
                                                                  [:kind :status :phase])}
                                :timestamp (System/currentTimeMillis)})
                              "\n")
                         :append true)
                   ;; #endregion
                   (let [msg (friendly-exchange-error t nil)]
                     (wb/publish! workbench
                                  {:role :error :text msg})
                     (hub/set-status! h :error msg))))
               (recur))

             :else
             (recur))))
       (finally
         (when feeder (future-cancel feeder))
         (wb/close! workbench))))))
