(ns kschltz.agent.workbench.hub
  "In-memory workbench session: chat transcript, human inbox, portal refs."
  (:require [clojure.string :as str]
            [kschltz.agent.workbench.schemas :as schemas])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn- now-ms [] (System/currentTimeMillis))
(defn- new-id [] (str (random-uuid)))

(defn create-hub
  "Create a fresh workbench hub atom + inbox."
  [{:keys [session-id]
    :or   {session-id (str (random-uuid))}}]
  {:session-id session-id
   :inbox      (LinkedBlockingQueue.)
   :state      (atom {:session-id    session-id
                      :status        :idle
                      :status-detail nil
                      :turns         []
                      :refs          {}
                      :portal-url    nil
                      :rev           0})})

(defn- bump!
  [state-atom f]
  (swap! state-atom
         (fn [s]
           (-> (f s)
               (update :rev (fnil inc 0))))))

(defn set-portal-url!
  [hub url]
  (bump! (:state hub) #(assoc % :portal-url url)))

(defn publish-turn!
  "Append a chat turn. `event` keys: :role/:type :text :thinking :refs :id"
  [hub event]
  (let [role (or (:role event) (:type event) :system)
        turn (cond-> {:id   (or (:id event) (new-id))
                      :role role
                      :ts   (or (:ts event) (now-ms))}
               (some? (:text event))     (assoc :text (:text event))
               (some? (:thinking event)) (assoc :thinking (:thinking event))
               (seq (:refs event))       (assoc :refs (:refs event)))]
    (bump! (:state hub) #(update % :turns (fnil conj []) turn))
    turn))

(defn put-ref!
  "Store a portal ref chip; returns the public ref map (no :value)."
  [hub {:keys [id preview path label value]}]
  (let [id  (or id (new-id))
        ref (cond-> {:id id :preview (str (or preview (pr-str value)))}
              path  (assoc :path (str path))
              label (assoc :label (str label)))]
    (bump! (:state hub)
           (fn [s]
             (assoc-in s [:refs id]
                       (cond-> ref
                         (some? value) (assoc :value value)))))
    (dissoc ref :value)))

(defn get-ref
  [hub id]
  (get-in @(:state hub) [:refs id]))

(defn set-status!
  "Set session status. Optional `detail` is a short UI hint string."
  ([hub status] (set-status! hub status nil))
  ([hub status detail]
   (bump! (:state hub)
          #(assoc %
                  :status status
                  :status-detail detail))))

(defn enqueue-human!
  "Enqueue a human chat message (text + optional refs).
   Publishes the user turn immediately so the web UI updates before
   the session loop dequeues and starts the model."
  [hub message]
  (let [msg  (schemas/decode-message message)
        text (str/trim (str (:text msg)))
        refs (vec (or (:refs msg) []))]
    (when (schemas/blank? text)
      (throw (ex-info "Blank chat message" {:message message})))
    ;; Publish + queued status before unblock so the UI updates first;
    ;; `await-human!` then advances status to `:running`.
    (publish-turn! hub {:role :user :text text :refs refs})
    (set-status! hub :queued "message accepted — starting soon")
    (.put ^LinkedBlockingQueue (:inbox hub)
          {:text text :refs refs})
    {:ok true}))

(defn await-human!
  "Park until a human message arrives. Returns {:text :refs}.
   The user turn is published by `enqueue-human!` (not here)."
  ([hub] (await-human! hub {}))
  ([hub {:keys [timeout-ms] :or {timeout-ms 0}}]
   (set-status! hub :waiting "ready for your next message")
   (let [inbox ^LinkedBlockingQueue (:inbox hub)
         msg   (if (pos? (long timeout-ms))
                 (.poll inbox (long timeout-ms) TimeUnit/MILLISECONDS)
                 (.take inbox))]
     (when (nil? msg)
       (throw (ex-info "Workbench await-human timed out"
                       {:timeout-ms timeout-ms})))
     (set-status! hub :running "model working…")
     msg)))

(defn snapshot
  "Client-facing state (no raw :value payloads)."
  [hub]
  (let [s @(:state hub)]
    (-> s
        (update :refs
                (fn [refs]
                  (into {}
                        (map (fn [[id r]]
                               [id (dissoc r :value)]))
                        refs))))))

(defn format-prompt
  "Build the string prompt sent to the agent from a human message + refs."
  [{:keys [text refs]}]
  (if (seq refs)
    (str text
         "\n\nAttached portal refs:\n"
         (str/join "\n"
                   (map (fn [r]
                          (str "- @portal/" (:id r)
                               (when (:label r) (str " (" (:label r) ")"))
                               ": " (:preview r)))
                        refs)))
    text))
