(ns kschltz.agent.evolution.worktree
  "Git worktree isolation for candidate changes.

   Git metadata is reachable only through this operator-owned adapter; file
   tools remain unable to touch `.git`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.schemas :as schemas]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def WorkspaceOpts
  [:map
   [:runner [:fn proto/command-runner?]]
   [:repository-root [:string {:min 1}]]
   [:worktree-parent [:string {:min 1}]]
   [:base-branch [:string {:min 1}]]
   [:command-timeout-ms {:optional true} [:int {:min 1}]]
   [:max-output-bytes {:optional true} [:int {:min 1024}]]])

(defn- safe-fragment
  [value]
  (let [clean (-> (str value)
                  str/lower-case
                  (str/replace #"[^a-z0-9-]+" "-")
                  (str/replace #"(^-+|-+$)" ""))]
    (if (seq clean) clean "candidate")))

(defn- execute!
  [{:keys [runner repository-root command-timeout-ms max-output-bytes]}
   argv]
  (let [result (proto/-run-command!
                runner
                {:argv argv
                 :cwd repository-root
                 :timeout-ms (or command-timeout-ms 120000)
                 :max-output-bytes (or max-output-bytes 1048576)
                 :isolation :network-only
                 :network :deny})]
    (when-not (= :ok (:status result))
      (throw (ex-info "Git worktree operation failed"
                      {:argv argv :result result})))
    result))

(defn create-candidate!
  [{:keys [worktree-parent base-branch] :as opts} run-id proposal]
  (let [status (execute! opts ["git" "status" "--porcelain=v1"])
        _ (when-not (str/blank? (:stdout status))
            (throw (ex-info "Card worktree must be clean before evolution"
                            {:status (:stdout status)})))
        fragment (safe-fragment (str run-id "-" (:id proposal)))
        branch (str "evolve/" fragment)
        worktree (.getCanonicalPath
                  (io/file worktree-parent fragment))]
    (.mkdirs (io/file worktree-parent))
    (when (.exists (io/file worktree))
      (throw (ex-info "Candidate worktree path already exists"
                      {:worktree worktree})))
    (execute! opts ["git" "worktree" "add" "-b" branch worktree base-branch])
    {:id fragment
     :branch branch
     :worktree worktree
     :base base-branch}))

(defn changed-paths
  [opts candidate]
  (let [result (execute! (assoc opts :repository-root (:worktree candidate))
                         ["git" "status" "--porcelain=v1" "-z"
                          "--untracked-files=all"])
        entries (remove str/blank? (str/split (:stdout result) #"\u0000"))
        paths
        (loop [remaining entries
               acc []]
          (if-let [entry (first remaining)]
            (let [status (subs entry 0 (min 2 (count entry)))
                  path (if (>= (count entry) 4) (subs entry 3) entry)
                  rename? (boolean (re-find #"[RC]" status))]
              (if rename?
                (recur (nnext remaining)
                       (cond-> (conj acc path)
                         (second remaining) (conj (second remaining))))
                (recur (next remaining) (conj acc path))))
            acc))]
    (->> paths distinct sort vec)))

(defn snapshot-candidate!
  [opts candidate message]
  (let [candidate-opts (assoc opts :repository-root (:worktree candidate))]
    (execute! candidate-opts ["git" "add" "--all"])
    (execute! candidate-opts
              ["git" "-c" "user.name=Lateralus Evolution"
               "-c" "user.email=lateralus@localhost"
               "commit" "-m" message])
    {:ok true :branch (:branch candidate)}))

(defn candidate-diff
  [opts candidate]
  (:stdout
   (execute! (assoc opts :repository-root (:worktree candidate))
             ["git" "diff" "--no-ext-diff" "--binary"
              (str (:base candidate) "...HEAD")])))

(defn promote-candidate!
  [opts candidate target-worktree]
  (let [candidate-opts (assoc opts :repository-root (:worktree candidate))
        target-opts (assoc opts :repository-root target-worktree)
        status (execute! target-opts ["git" "status" "--porcelain=v1"])
        _ (when-not (str/blank? (:stdout status))
            (throw (ex-info "Target card worktree is not clean"
                            {:status (:stdout status)})))
        commit (str/trim
                (:stdout (execute! candidate-opts
                                   ["git" "rev-parse" "HEAD"])))]
    (execute! target-opts ["git" "cherry-pick" commit])
    {:ok true :commit commit :target target-worktree}))

(defn discard-candidate!
  [opts candidate]
  (execute! opts ["git" "worktree" "remove" "--force" (:worktree candidate)])
  (execute! opts ["git" "branch" "-D" (:branch candidate)])
  {:ok true :discarded (:id candidate)})

(defrecord GitWorkspaceManager [opts]
  proto/WorkspaceManager
  (-create-candidate! [_ run-id proposal]
    (create-candidate! opts run-id proposal))
  (-diff [_ candidate] (candidate-diff opts candidate))
  (-changed-paths [_ candidate] (changed-paths opts candidate))
  (-snapshot-candidate! [_ candidate message]
    (snapshot-candidate! opts candidate message))
  (-promote-candidate! [_ candidate target-worktree]
    (promote-candidate! opts candidate target-worktree))
  (-discard-candidate! [_ candidate]
    (discard-candidate! opts candidate)))

(defn git-workspace-manager
  [opts]
  (->GitWorkspaceManager opts))

(m/=> create-candidate!
      [:=> [:cat WorkspaceOpts :string schemas/Proposal] schemas/Candidate])
(m/=> changed-paths
      [:=> [:cat WorkspaceOpts schemas/Candidate] [:vector :string]])
(m/=> snapshot-candidate!
      [:=> [:cat WorkspaceOpts schemas/Candidate :string] :map])
(m/=> candidate-diff
      [:=> [:cat WorkspaceOpts schemas/Candidate] :string])
(m/=> promote-candidate!
      [:=> [:cat WorkspaceOpts schemas/Candidate :string] :map])
(m/=> discard-candidate!
      [:=> [:cat WorkspaceOpts schemas/Candidate] :map])
(m/=> git-workspace-manager
      [:=> [:cat WorkspaceOpts] [:fn proto/workspace-manager?]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.evolution.worktree)]}))

(instrument!)
