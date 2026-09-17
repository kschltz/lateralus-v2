(ns kschltz.agent.evolution.supervisor
  "Deterministic, verifier-owned supervisor for one bounded candidate run."
  (:require [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.schemas :as schemas]
            [kschltz.agent.evolution.state :as state]
            [malli.core :as m]
            [malli.error :as me]
            [malli.instrument :as mi]))

(def Components
  [:map
   [:board [:fn proto/board?]]
   [:workspace-manager [:fn proto/workspace-manager?]]
   [:candidate-runner [:fn proto/candidate-runner?]]
   [:evaluator [:fn proto/evaluator?]]
   [:ledger [:fn proto/evolution-ledger?]]
   [:clock {:optional true} fn?]
   [:id-fn {:optional true} fn?]])

(def Request
  [:map {:closed true}
   [:repository-root [:string {:min 1}]]
   [:card-id [:string {:min 1}]]
   [:proposal schemas/Proposal]
   [:policy {:optional true} [:maybe :map]]
   [:gates [:vector {:min 1} schemas/GateSpec]]])

(defn- assert-valid!
  [schema value message]
  (when-not (schemas/valid? schema value)
    (throw (ex-info message
                    {:explain (some-> (schemas/explain schema value)
                                      me/humanize)})))
  value)

(defn- event
  [id-fn run phase type now payload]
  {:id (id-fn)
   :run-id (:id run)
   :type type
   :phase phase
   :ts now
   :payload payload})

(defn- record!
  [ledger id-fn run phase type now payload]
  (proto/-append-event! ledger (event id-fn run phase type now payload))
  run)

(defn- move!
  [ledger id-fn clock run phase payload]
  (let [now (clock)
        next (state/transition run phase now payload)]
    (record! ledger id-fn next phase :phase-entered now payload)
    next))

(defn- discard-quietly!
  [manager candidate]
  (when candidate
    (try
      (proto/-discard-candidate! manager candidate)
      (catch Throwable _ nil))))

(defn- reject!
  [components run reason payload]
  (let [{:keys [ledger workspace-manager board card-id clock id-fn]} components
        rejected (move! ledger id-fn clock run :rejected
                        {:reason reason})]
    (record! ledger id-fn rejected :rejected :run-rejected (clock)
             (merge {:reason reason} payload))
    (try
      (proto/-decision! board card-id
                        (str "Evolution run " (:id run)
                             " rejected: " reason "."))
      (catch Throwable _ nil))
    (discard-quietly! workspace-manager (:candidate run))
    rejected))

(defn evolve!
  [{:keys [board workspace-manager candidate-runner evaluator ledger]
    :as components}
   {:keys [repository-root card-id proposal policy gates] :as request}]
  (assert-valid! Components components "Invalid evolution supervisor components")
  (assert-valid! Request request "Invalid evolution request")
  (when-not (some #(= :acceptance (:kind %)) gates)
    (throw (ex-info "At least one machine-checkable acceptance gate is required"
                    {:gate-kinds (mapv :kind gates)})))
  (when-not (apply distinct? (map :id gates))
    (throw (ex-info "Evolution gate ids must be unique"
                    {:gate-ids (mapv :id gates)})))
  (let [clock (or (:clock components) #(System/currentTimeMillis))
        id-fn (or (:id-fn components) #(str (random-uuid)))
        components (assoc components :clock clock :id-fn id-fn
                          :card-id card-id)
        policy (state/normalize-policy policy)
        policy (update policy :protected-paths
                       #(vec (distinct
                              (concat %
                                      (mapcat :protected-paths gates)))))
        now (clock)
        initial {:id (id-fn)
                 :phase :observe
                 :proposal proposal
                 :policy policy
                 :started-at now
                 :updated-at now}
        current (atom initial)
        step! (fn [run phase payload]
                (let [next (move! ledger id-fn clock run phase payload)]
                  (reset! current next)
                  next))]
    (record! ledger id-fn initial :observe :run-created now
             {:card-id card-id :repository-root repository-root})
    (try
      (let [_card (proto/-context board card-id)
            target-worktree (or (:worktree _card) repository-root)
            _ (when-not (seq target-worktree)
                (throw (ex-info "Card has no worktree for review handoff"
                                {:card-id card-id})))
            proposed (step! initial :propose
                            {:reason "proposal accepted for baseline"})
            _ (proto/-decision!
               board card-id
               (str "Evolution run " (:id initial)
                    " accepted proposal " (:id proposal)
                    "; capturing baseline before isolated implementation."))
            baseline (proto/-evaluate! evaluator target-worktree gates policy)
            baselined (step! proposed :baseline
                              {:baseline baseline})
            candidate (proto/-create-candidate! workspace-manager
                                                (:id initial) proposal)
            isolated (step! baselined :isolate {:candidate candidate})
            implementing (step! isolated :implement {})
            implementation (proto/-implement! candidate-runner candidate
                                               proposal policy)
            _ (assert-valid! schemas/ImplementationResult implementation
                             "Candidate runner returned an invalid result")
            now (clock)
            budget-errors (state/budget-violations
                           (:started-at initial) now implementation policy)]
        (cond
          (not= :completed (:status implementation))
          (reject! components implementing "candidate implementation failed"
                   {:implementation implementation})

          (seq budget-errors)
          (reject! components implementing "candidate exceeded evolution budget"
                   {:violations budget-errors
                    :implementation implementation})

          :else
          (let [cleaned (proto/-clean-ephemeral! workspace-manager candidate)
                _ (when (seq cleaned)
                    (record! ledger id-fn implementing :implement
                             :ephemeral-artifacts-cleaned (clock)
                             {:paths cleaned}))
                paths (proto/-changed-paths workspace-manager candidate)
                path-errors (state/path-violations paths policy)]
            (if (seq path-errors)
              (reject! components implementing "candidate violated path policy"
                       {:violations path-errors :changed-paths paths})
              (let [verification (proto/-evaluate! evaluator
                                                   (:worktree candidate)
                                                   gates policy)
                    verified-paths
                    (proto/-changed-paths workspace-manager candidate)
                    verifier-path-errors
                    (state/path-violations verified-paths policy)
                    verifier-mutated?
                    (not= (set paths) (set verified-paths))
                    verifying (step! implementing :verify
                                      {:verification verification})
                    decision (state/gate-decision baseline verification)]
                (cond
                  (or verifier-mutated? (seq verifier-path-errors))
                  (reject! components verifying
                           "verification mutated candidate source or policy"
                           {:changed-paths-before paths
                            :changed-paths-after verified-paths
                            :violations verifier-path-errors})

                  (not (:accepted? decision))
                  (reject! components verifying
                           "candidate failed required verification"
                           {:decision decision})

                  :else
                  (let [_ (proto/-snapshot-candidate!
                           workspace-manager candidate
                           (str "evolve: " (:title proposal)))
                        diff (proto/-diff workspace-manager candidate)
                        summary {:run-id (:id initial)
                                 :proposal-id (:id proposal)
                                 :candidate candidate
                                 :changed-paths paths
                                 :gates (:gates decision)
                                 :diff diff}]
                    (record! ledger id-fn verifying :verify
                             :candidate-accepted (clock) summary)
                    (proto/-promote-candidate! workspace-manager candidate
                                               target-worktree)
                    (proto/-decision!
                     board card-id
                     (str "Evolution run " (:id initial)
                          " passed independent verification; handing candidate "
                          (:branch candidate) " to human review."))
                    (proto/-handoff! board card-id summary)
                    (let [handed-off
                          (step! verifying :handoff
                                 {:reason "awaiting human review"
                                  :diff diff})]
                      (discard-quietly! workspace-manager candidate)
                      handed-off))))))))
      (catch Throwable t
        (let [run @current
              blocked (if (contains? #{:handoff :rejected :blocked}
                                     (:phase run))
                        run
                        (step! run :blocked
                               {:reason (or (ex-message t)
                                            (.getName (class t)))}))]
          (record! ledger id-fn blocked :blocked :run-blocked (clock)
                   {:error (or (ex-message t) (.getName (class t)))
                    :data (or (ex-data t) {})})
          (when-not (= :verify (:phase run))
            (discard-quietly! workspace-manager (:candidate run)))
          blocked)))))

(m/=> evolve! [:=> [:cat Components Request] schemas/RunState])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.evolution.supervisor)]}))

(instrument!)
