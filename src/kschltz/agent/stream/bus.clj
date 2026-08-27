(ns kschltz.agent.stream.bus
  "In-memory StreamBus: live current turn + historic snapshots."
  (:require [kschltz.agent.stream.protocol :as proto]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def ^:private max-turns
  "Cap retained historic turns so a long session cannot grow forever."
  64)

(defn- now-ms [] (System/currentTimeMillis))

(defn- public-event [event seq-n]
  (-> event
      (assoc :seq seq-n)
      (update :type (fn [t] (if (keyword? t) (name t) (str t))))))

(defn- public-turn [turn]
  (when turn
    {:id          (:id turn)
     :status      (name (:status turn))
     :live?       (= :live (:status turn))
     :opened-at   (:opened-at turn)
     :closed-at   (:closed-at turn)
     :session-id  (:session-id turn)
     :user-text   (:user-text turn)
     :text        (or (:text turn) "")
     :thinking    (or (:thinking turn) "")
     :model       (:model turn)
     :usage       (or (:usage turn) {})
     :error       (:error turn)
     :rev         (:rev turn)
     :events      (:events turn)
     :tool-names  (or (:tool-names turn) [])}))

(defn- apply-event [turn event]
  (let [seq-n (count (:events turn))
        pub   (public-event event seq-n)
        turn  (-> turn
                  (update :events conj pub)
                  (update :rev (fnil inc 0)))]
    (case (:type event)
      :text-delta
      (update turn :text str (:text event))
      :thinking-delta
      (update turn :thinking str (:thinking event))
      :llm-done
      (cond-> turn
        (:model event) (assoc :model (:model event))
        (:usage event) (assoc :usage (:usage event)))
      :tool-call
      (let [n (:tool-name event)]
        (cond-> turn
          (and (seq n) (not (some #{n} (:tool-names turn))))
          (update :tool-names (fnil conj []) n)))
      turn)))

(defn- evict [turns]
  (if (<= (count turns) max-turns)
    turns
    (let [ids (->> turns
                   (sort-by (comp :opened-at val))
                   (map key)
                   (take (- (count turns) max-turns)))]
      (apply dissoc turns ids))))

(defrecord MemoryBus [state]
  proto/StreamBus
  (-open-turn! [_ meta]
    (let [id (str (or (:id meta) (random-uuid)))
          turn {:id id
                :status :live
                :opened-at (now-ms)
                :session-id (:session-id meta)
                :user-text (str (:user-text meta))
                :text ""
                :thinking ""
                :events []
                :tool-names []
                :rev 0}]
      (swap! state (fn [s]
                     (-> s
                         (assoc :current-id id)
                         (update :turns evict)
                         (assoc-in [:turns id] turn))))
      id))
  (-emit! [_ turn-id event]
    (swap! state update-in [:turns turn-id]
           (fn [turn]
             (when turn
               (apply-event turn event))))
    nil)
  (-close-turn! [_ turn-id status extra]
    (swap! state
           (fn [s]
             (let [turn (get-in s [:turns turn-id])]
               (if-not turn
                 s
                     (-> s
                     (assoc :current-id (when (not= turn-id (:current-id s))
                                          (:current-id s)))
                     (assoc-in [:turns turn-id]
                               (merge turn
                                      extra
                                      {:status (or status :done)
                                       :closed-at (now-ms)})))))))
    nil)
  (-snapshot [_ turn-id]
    (public-turn (get-in @state [:turns turn-id])))
  (-current-id [_]
    (:current-id @state))
  (-events-since [_ turn-id seq-n]
    (when-let [turn (get-in @state [:turns turn-id])]
      {:rev (:rev turn)
       :status (name (:status turn))
       :live? (= :live (:status turn))
       :events (vec (filter #(> (:seq %) (long seq-n)) (:events turn)))}))
  (-latest-id [_]
    (->> (:turns @state)
         vals
         (sort-by :opened-at)
         last
         :id)))

(defn create-bus
  "In-memory stream bus."
  ([]
   (->MemoryBus (atom {:current-id nil :turns {}}))))

(defn open-turn! [bus meta] (proto/-open-turn! bus meta))
(defn emit! [bus turn-id event] (proto/-emit! bus turn-id event))
(defn close-turn! [bus turn-id status extra]
  (proto/-close-turn! bus turn-id status extra))
(defn snapshot [bus turn-id] (proto/-snapshot bus turn-id))
(defn current-id [bus] (proto/-current-id bus))
(defn events-since [bus turn-id seq-n] (proto/-events-since bus turn-id seq-n))
(defn latest-id [bus] (proto/-latest-id bus))

(m/=> create-bus [:=> [:cat] [:fn proto/stream-bus?]])
(m/=> open-turn! [:=> [:cat [:fn proto/stream-bus?] :map] :string])
(m/=> emit! [:=> [:cat [:fn proto/stream-bus?] :string :map] :any])
(m/=> close-turn! [:=> [:cat [:fn proto/stream-bus?] :string :keyword :map] :any])
(m/=> snapshot [:=> [:cat [:fn proto/stream-bus?] :string] [:maybe :map]])
(m/=> current-id [:=> [:cat [:fn proto/stream-bus?]] [:maybe :string]])
(m/=> events-since [:=> [:cat [:fn proto/stream-bus?] :string :int] [:maybe :map]])
(m/=> latest-id [:=> [:cat [:fn proto/stream-bus?]] [:maybe :string]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.stream.bus)]}))

(instrument!)
