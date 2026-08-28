(ns kschltz.agent.session.manager
  "Coordinate SessionStore + workbench hub + optional AgentRuntime."
  (:require [clojure.string :as str]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.session.protocol :as proto]
            [kschltz.agent.session.store :as store]
            [kschltz.agent.workbench.hub :as hub]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def busy-statuses #{:queued :running})

(defn- preview-of
  [turns]
  (let [t (->> (reverse (or turns []))
               (some (fn [turn]
                       (when (and (#{:user :assistant} (:role turn))
                                  (seq (str (:text turn))))
                         turn))))]
    (if-not t
      ""
      (let [s (str/trim (str (:text t)))]
        (if (> (count s) 120) (str (subs s 0 117) "...") s)))))

(defn- assert-idle!
  [hub]
  (when (contains? busy-statuses (:status (hub/snapshot hub)))
    (throw (ex-info "Cannot change sessions while a turn is running"
                    {:status (:status (hub/snapshot hub))}))))

(defn persist-current!
  "Write the live hub (and runtime state, when attached) to the catalog."
  [sess-store hub runtime]
  (let [snap (hub/snapshot hub)
        id   (or (:session-id snap) (proto/-current-id sess-store))]
    (when id
      (let [prev (proto/-get sess-store id)
            rec  {:id          id
                  :title       (or (:session-title snap) (:title prev) id)
                  :created-at  (or (:created-at prev)
                                   (:ts (first (:turns snap))))
                  :preview     (preview-of (:turns snap))
                  :turns       (vec (or (:turns snap) []))
                  :refs        (or (:refs snap) {})
                  :agent-state (if runtime
                                 (runtime/export-state runtime)
                                 (or (:agent-state prev) {}))}]
        (proto/-upsert! sess-store rec)
        (store/public-record sess-store (proto/-get sess-store id))))))

(defn- show
  [sess-store record]
  (store/public-record sess-store record))

(defn- load-record!
  [sess-store hub runtime record]
  (hub/load-workspace! hub {:session-id (:id record)
                            :title      (:title record)
                            :turns      (or (:turns record) [])
                            :refs       (or (:refs record) {})})
  (when runtime
    (runtime/adopt-session! runtime (:id record) (:agent-state record)))
  (proto/-set-current! sess-store (:id record))
  (show sess-store (proto/-get sess-store (:id record))))

(defn ensure!
  "Create the catalog row for `id` if missing; do not switch the hub."
  [sess-store {:keys [id title]}]
  (let [id (str id)]
    (or (when-let [prev (proto/-get sess-store id)]
          (show sess-store prev))
        (proto/-upsert! sess-store
                        {:id id :title (or title id) :turns [] :refs {} :agent-state {}}))))

(defn list-sessions
  [sess-store]
  (proto/-list sess-store))

(defn current
  [sess-store]
  (when-let [id (proto/-current-id sess-store)]
    (show sess-store (proto/-get sess-store id))))

(defn create!
  "Persist the current workspace, then start a fresh session and switch to it."
  [sess-store hub runtime {:keys [id title]}]
  (assert-idle! hub)
  (persist-current! sess-store hub runtime)
  (let [id (or id (str (random-uuid)))
        title (or (not-empty (str/trim (str title))) "New session")
        rec {:id id :title title :turns [] :refs {} :agent-state {}}]
    (proto/-upsert! sess-store rec)
    (load-record! sess-store hub runtime rec)))

(defn activate!
  "Switch hub + runtime to an existing session."
  [sess-store hub runtime id]
  (assert-idle! hub)
  (let [id (str id)
        rec (proto/-get sess-store id)]
    (when-not rec
      (throw (ex-info "Unknown session" {:id id})))
    (when-not (= id (or (:session-id (hub/snapshot hub))
                        (proto/-current-id sess-store)))
      (persist-current! sess-store hub runtime))
    (load-record! sess-store hub runtime rec)))

(defn rename!
  [sess-store hub id title]
  (let [id (str id)
        title (str/trim (str title))
        rec (proto/-get sess-store id)]
    (when-not rec
      (throw (ex-info "Unknown session" {:id id})))
    (when (str/blank? title)
      (throw (ex-info "Session title is required" {:id id})))
    (proto/-upsert! sess-store (assoc rec :title title))
    (when (= id (:session-id (hub/snapshot hub)))
      (hub/set-session-title! hub title))
    (show sess-store (proto/-get sess-store id))))

(defn delete!
  "Drop a session. Refuses the last remaining or the active one."
  [sess-store hub id]
  (let [id (str id)
        rec (proto/-get sess-store id)]
    (when-not rec
      (throw (ex-info "Unknown session" {:id id})))
    (when (= id (:session-id (hub/snapshot hub)))
      (throw (ex-info "Switch sessions before deleting the active one"
                      {:id id})))
    (when (= 1 (count (proto/-list sess-store)))
      (throw (ex-info "Cannot delete the last session" {:id id})))
    (proto/-delete! sess-store id)
    {:ok true :id id}))

(defn attach!
  "Bind `runtime` to the hub's current session (or `preferred-id`)."
  [sess-store hub runtime preferred-id]
  (let [id (or preferred-id
               (:session-id (hub/snapshot hub))
               (str (random-uuid)))]
    (ensure! sess-store {:id id :title id})
    (when-not (proto/-get sess-store id)
      (ensure! sess-store {:id id}))
    (if (= id (:session-id (hub/snapshot hub)))
      (do
        (persist-current! sess-store hub runtime)
        (proto/-set-current! sess-store id)
        (current sess-store))
      (activate! sess-store hub runtime id))))

(m/=> list-sessions [:=> [:cat [:fn proto/session-store?]] :any])
(m/=> persist-current! [:=> [:cat [:fn proto/session-store?] :map :any] :any])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.session.manager)]}))

(instrument!)
