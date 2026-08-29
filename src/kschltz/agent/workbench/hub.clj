(ns kschltz.agent.workbench.hub
  "In-memory workbench session: chat transcript, human inbox, portal refs."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kschltz.agent.session.protocol :as session]
            [kschltz.agent.stream.protocol :as stream]
            [kschltz.agent.workbench.schemas :as schemas])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn- now-ms [] (System/currentTimeMillis))
(defn- new-id [] (str (random-uuid)))

(defn create-hub
  "Create a fresh workbench hub atom + inbox."
  [{:keys [session-id session-title stream-bus session-store]
    :or   {session-id (str (random-uuid))}}]
  {:session-id    session-id
   :inbox         (LinkedBlockingQueue.)
   :stream-bus    stream-bus
   :session-store session-store
   :state         (atom {:session-id    session-id
                         :session-title (or session-title session-id)
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
               (some? (:turn-id event))  (assoc :turn-id (:turn-id event))
               (seq (:refs event))       (assoc :refs (:refs event)))]
    (bump! (:state hub) #(update % :turns (fnil conj []) turn))
    turn))

(defn put-ref!
  "Store a portal ref chip; returns the public ref map (no :value)."
  [hub {:keys [id preview path label value viewer]}]
  (let [id  (or id (new-id))
        ref (cond-> {:id id :preview (str (or preview (pr-str value)))}
              path   (assoc :path (str path))
              label  (assoc :label (str label))
              viewer (assoc :viewer (str viewer)))]
    (bump! (:state hub)
           (fn [s]
             (assoc-in s [:refs id]
                       (cond-> ref
                         (some? value) (assoc :value value)))))
    (dissoc ref :value)))

(defn get-ref
  [hub id]
  (get-in @(:state hub) [:refs id]))

(defn begin-turn!
  "Open a live stream turn as soon as the session loop starts working
   so the CHAT UI can show a details link before the first LLM token."
  [hub meta]
  (let [bus (:stream-bus hub)]
    (when (stream/stream-bus? bus)
      (let [id (stream/-open-turn! bus (or meta {}))]
        (bump! (:state hub) #(assoc % :current-turn-id id))
        id))))

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

(def ^:private max-portal-event-chars 4096)

(defn portal-event!
  "Receive an interaction event from an artifact rendered in Portal and
   route it into the conversation as agent-visible input.
   
   This completes the 2-way loop: `portal_submit` renders an interactive
   HTML artifact (with a small JS helper that POSTs to
   /api/portal-event), the human clicks/types in the UI, and the event
   lands here — published as a user turn prefixed with the
   ⟨portal-event⟩ marker and enqueued in the same inbox as chat
   messages, so the running session loop wakes and the model sees it on
   the next exchange.
   
   Trust model: like chat input, an event only exists because the
   human invoked a control in the artifact. The model authored the JS,
   but nothing runs without a human action. Payload discipline: must be
   a JSON map (shallow-by-convention), serialized form capped at
   `max-portal-event-chars` — oversized or non-map payloads are
   rejected with an ex-info the route turns into a 400."
  [hub payload]
  (let [fail #(throw (ex-info % {:kind :invalid-portal-event}))]
    (when-not (map? payload)
      (fail "portal event payload must be a JSON object (map)"))
    (let [json (json/generate-string payload)]
      (when (> (count json) max-portal-event-chars)
        (fail (str "portal event too large (max " max-portal-event-chars
                   " chars serialized)")))
      (let [text (str "⟨portal-event⟩ " json)]
        (publish-turn! hub {:role :user :text text :refs []})
        (set-status! hub :queued "portal event accepted — starting soon")
        (.put ^LinkedBlockingQueue (:inbox hub) {:text text :refs []})
        {:ok true :chars (count json)}))))

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
     (begin-turn! hub {:user-text (:text msg)})
     msg)))

(defn set-session-title!
  [hub title]
  (bump! (:state hub) #(assoc % :session-title (str title))))

(defn load-workspace!
  "Replace the visible transcript with a persisted session workspace."
  [hub {:keys [session-id title turns refs]}]
  (when-let [q (:inbox hub)]
    (.clear ^LinkedBlockingQueue q))
  (bump! (:state hub)
         (fn [s]
           (assoc s
                  :session-id session-id
                  :session-title (or title session-id)
                  :turns (vec (or turns []))
                  :refs (into {} (or refs {}))
                  :status :waiting
                  :status-detail "session ready"
                  :current-turn-id nil)))
  hub)

(defn snapshot
  "Client-facing state (no raw :value payloads)."
  [hub]
  (let [s   @(:state hub)
        bus (:stream-bus hub)
        sid (:session-id s)]
    (-> s
        (assoc :current-turn-id (when (stream/stream-bus? bus)
                                  (stream/-current-id bus)))
        (assoc :session {:id    sid
                         :title (or (:session-title s) sid)})
        (cond-> (session/session-store? (:session-store hub))
          (assoc :sessions (session/-list (:session-store hub))))
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
