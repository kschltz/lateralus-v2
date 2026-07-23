(ns kschltz.agent.portal.session
  "Portal-free UI session state: transcript atom + blocking human inbox.

   This is the park point for the interactive UI session. The exchange
   chain never waits here — only the outer Portal/CLI session loop does."
  (:require [clojure.string :as str]
            [kschltz.agent.portal.protocol :as proto]
            [kschltz.agent.portal.schemas :as schemas])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private default-await-ms
  "Watchdog so a parked session cannot hang forever with no UI.
   0 = wait indefinitely."
  0)

;; Inbox of the currently parked Portal session. Host `reply!` writes here.
(defonce ^:private active-inbox
  (atom nil))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- new-turn-id []
  (str (random-uuid)))

(defn- event->turn
  [event]
  (let [event (schemas/decode-event event)]
    (cond-> {:id   (or (:turn-id event) (new-turn-id))
             :role (:type event)
             :ts   (or (:ts event) (now-ms))}
      (some? (:text event))      (assoc :text (:text event))
      (some? (:thinking event))  (assoc :thinking (:thinking event)))))

(defn viewer-meta
  "Metadata that selects the Lateralus Portal session viewer."
  []
  {:portal.viewer/default :kschltz.agent.portal.viewer/session})

(defn fresh-transcript
  "Empty session view atom suitable for `portal.api/open` `:value`."
  [session-id]
  (atom
   (with-meta
     {:session-id (str session-id)
      :status     :idle
      :turns      []}
     (viewer-meta))))

(defn bind-inbox!
  "Make `inbox` the target for host `reply!`."
  [inbox]
  (reset! active-inbox inbox))

(defn clear-inbox-binding!
  []
  (reset! active-inbox nil))

(defn enqueue-reply!
  "Host-side enqueue used by Portal RPC `reply!`. Returns a status map."
  [reply]
  (let [reply (schemas/decode-reply reply)
        text  (str/trim (str (:text reply)))
        inbox @active-inbox]
    (cond
      (schemas/blank-text? text)
      {:ok false :reason :blank}

      (nil? inbox)
      {:ok false :reason :no-active-session}

      :else
      (do (.put ^LinkedBlockingQueue inbox text)
          {:ok true :queued true}))))

(defrecord SessionUi [session-id transcript ^LinkedBlockingQueue inbox await-ms closed?]
  proto/AgentUi
  (-publish! [_ event]
    (when-not @closed?
      (let [turn (event->turn event)]
        (swap! transcript update :turns (fnil conj []) turn)))
    nil)

  (-await-human! [_ opts]
    (when @closed?
      (throw (ex-info "Portal UI session is closed" {:session-id session-id})))
    (bind-inbox! inbox)
    (swap! transcript assoc :status :waiting)
    (try
      (let [timeout (long (or (:timeout-ms opts) await-ms default-await-ms))
            text    (if (pos? timeout)
                      (.poll inbox timeout TimeUnit/MILLISECONDS)
                      (.take inbox))]
        (when (nil? text)
          (throw (ex-info "Portal UI await-human timed out"
                          {:session-id session-id :timeout-ms timeout})))
        (let [text (str/trim (str text))]
          (when (schemas/blank-text? text)
            (throw (ex-info "Portal UI received blank human reply"
                            {:session-id session-id})))
          (swap! transcript
                 (fn [s]
                   (-> s
                       (assoc :status :running)
                       (update :turns conj {:id   (new-turn-id)
                                            :role :user
                                            :text text
                                            :ts   (now-ms)}))))
          text))
      (finally
        ;; Keep binding while session lives so late clicks still queue;
        ;; cleared on close.
        nil)))

  (-close! [_]
    (when (compare-and-set! closed? false true)
      (swap! transcript assoc :status :closed)
      (clear-inbox-binding!)
      (.clear inbox))
    nil))

(defn create-session
  "Create a parked UI session without opening Portal (testable)."
  ([] (create-session {}))
  ([{:keys [session-id await-ms]
     :or   {session-id (str (random-uuid))
            await-ms   default-await-ms}}]
   (let [transcript (fresh-transcript session-id)
         inbox      (LinkedBlockingQueue.)]
     (->SessionUi session-id transcript inbox (long await-ms) (atom false)))))

(defn transcript-atom
  "The atom Portal should open on."
  [^SessionUi ui]
  (:transcript ui))
