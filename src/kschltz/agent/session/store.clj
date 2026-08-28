(ns kschltz.agent.session.store
  "File-backed SessionStore. Catalog lives at `<root>/catalog.edn`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.session.protocol :as proto]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.io File PushbackReader]))

(def ^:private safe-read-opts
  {:eof nil
   :default (fn [tag val]
              (throw (ex-info "Unsupported tagged literal"
                              {:tag tag :value val})))})

(defn- now-ms [] (System/currentTimeMillis))

(defn- public-view
  [record current-id]
  (when record
    {:id         (:id record)
     :title      (or (:title record) (:id record))
     :created-at (:created-at record)
     :updated-at (:updated-at record)
     :preview    (or (:preview record) "")
     :active?    (= (:id record) current-id)}))

(defn- read-catalog
  [^File f]
  (if (.exists f)
    (with-open [r (PushbackReader. (io/reader f))]
      (let [v (edn/read safe-read-opts r)]
        (if (map? v) v {:current-id nil :sessions {}})))
    {:current-id nil :sessions {}}))

(defn- write-catalog!
  [^File f catalog]
  (when-let [parent (.getParentFile f)]
    (.mkdirs parent))
  (spit f (pr-str catalog)))

(defn- sanitize-id
  [id]
  (let [s (str/trim (str id))]
    (when-not (proto/session-id? s)
      (throw (ex-info "Invalid session id"
                      {:id id :pattern (str proto/session-id-pattern)})))
    s))

(defrecord FileSessionStore [root-file state]
  proto/SessionStore
  (-list [_]
    (let [{:keys [current-id sessions]} @state]
      (->> (vals sessions)
           (sort-by :updated-at)
           reverse
           (mapv #(public-view % current-id)))))
  (-get [_ id]
    (get-in @state [:sessions (str id)]))
  (-upsert! [_ record]
    (let [id (sanitize-id (:id record))
          now (now-ms)
          rec (cond-> (assoc record :id id)
                (nil? (:created-at record)) (assoc :created-at now)
                true (assoc :updated-at now)
                (nil? (:title record)) (assoc :title id))]
      (swap! state
             (fn [cat]
               (-> cat
                   (assoc-in [:sessions id] rec)
                   (update :current-id #(or % id)))))
      (write-catalog! root-file @state)
      (public-view rec (:current-id @state))))
  (-delete! [_ id]
    (let [id (str id)
          existed? (boolean (get-in @state [:sessions id]))]
      (when existed?
        (swap! state
               (fn [cat]
                 (let [cat (update cat :sessions dissoc id)]
                   (cond-> cat
                     (= id (:current-id cat))
                     (assoc :current-id (some-> (:sessions cat) keys first))))))
        (write-catalog! root-file @state))
      existed?))
  (-current-id [_]
    (:current-id @state))
  (-set-current! [_ id]
    (let [id (sanitize-id id)]
      (when-not (get-in @state [:sessions id])
        (throw (ex-info "Unknown session" {:id id})))
      (swap! state assoc :current-id id)
      (write-catalog! root-file @state)
      id)))

(defn create-store
  "Open (or create) a catalog under `root` (directory path or File)."
  ([] (create-store "sessions/workbench"))
  ([root]
   (let [dir (io/file (str (or root "sessions/workbench")))
         f   (io/file dir "catalog.edn")
         cat (read-catalog f)]
     (.mkdirs dir)
     (->FileSessionStore f (atom cat)))))

(defn public-record
  [store record]
  (public-view record (proto/-current-id store)))

(m/=> create-store
      [:function
       [:=> [:cat] [:fn proto/session-store?]]
       [:=> [:cat :any] [:fn proto/session-store?]]])
(m/=> public-record [:=> [:cat [:fn proto/session-store?] [:maybe :map]] [:maybe :map]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.session.store)]}))

(instrument!)
