(ns kschltz.agent.evolution.board
  "Kanban adapter for auditable evolution decisions and review handoff."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kschltz.agent.evolution.protocol :as proto]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def BoardOpts
  [:map
   [:runner [:fn proto/command-runner?]]
   [:repository-root [:string {:min 1}]]
   [:agent [:string {:min 1}]]
   [:timeout-ms {:optional true} [:int {:min 1}]]
   [:max-output-bytes {:optional true} [:int {:min 1024}]]])

(defn- execute!
  [{:keys [runner repository-root timeout-ms max-output-bytes]} argv]
  (let [result (proto/-run-command!
                runner
                {:argv argv
                 :cwd repository-root
                 :timeout-ms (or timeout-ms 30000)
                 :max-output-bytes (or max-output-bytes 262144)
                 :isolation :network-only
                 :network :deny})]
    (when-not (= :ok (:status result))
      (throw (ex-info "Kanban operation failed" {:argv argv :result result})))
    result))

(defn card-context
  [opts card-id]
  (let [result (execute! opts ["kb" "show" card-id "--json"])
        parsed (json/parse-string (:stdout result) true)
        card (:card parsed)]
    (when-not (= card-id (:id card))
      (throw (ex-info "Kanban returned the wrong card"
                      {:expected card-id :actual (:id card)})))
    card))

(defn record-decision!
  [{:keys [agent] :as opts} card-id message]
  (execute! opts ["kb" "decision" card-id message "--agent" agent])
  {:ok true :card-id card-id})

(defn handoff!
  [{:keys [agent] :as opts} card-id summary]
  (let [current (card-context opts card-id)
        lane (some-> (:lane current) name str/lower-case)
        paths (str/join ", " (:changed-paths summary))
        message (str "Verified candidate " (get-in summary [:candidate :branch])
                     " is ready for human review. Changed paths: " paths
                     ". Run: " (:run-id summary))]
    (if (= "review" lane)
      {:ok true :card-id card-id :lane :review :already-handed-off? true}
      (do
        (record-decision! opts card-id message)
        (execute! opts ["kb" "diff" card-id])
        (execute! opts ["kb" "advance" card-id "--agent" agent])
        {:ok true :card-id card-id :lane :review
         :already-handed-off? false}))))

(defrecord KbBoard [opts]
  proto/Board
  (-context [_ card-id] (card-context opts card-id))
  (-decision! [_ card-id message] (record-decision! opts card-id message))
  (-handoff! [_ card-id summary] (handoff! opts card-id summary)))

(defn kb-board
  [opts]
  (->KbBoard opts))

(m/=> card-context [:=> [:cat BoardOpts :string] :map])
(m/=> record-decision! [:=> [:cat BoardOpts :string :string] :map])
(m/=> handoff! [:=> [:cat BoardOpts :string :map] :map])
(m/=> kb-board [:=> [:cat BoardOpts] [:fn proto/board?]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.evolution.board)]}))

(instrument!)
