(ns kschltz.agent.evolution.protocol
  "Trusted capability boundaries used by the evolution supervisor.")

(defprotocol Board
  (-context [board card-id] "Return machine-readable card context.")
  (-decision! [board card-id message] "Append an auditable decision.")
  (-handoff! [board card-id summary]
    "Move a verified card to human review. Must never merge or complete it."))

(defprotocol WorkspaceManager
  (-create-candidate! [manager run-id proposal]
    "Create an isolated candidate worktree and return its descriptor.")
  (-diff [manager candidate] "Return the candidate diff against its base.")
  (-changed-paths [manager candidate] "Return sorted changed paths.")
  (-snapshot-candidate! [manager candidate message]
    "Commit an accepted candidate locally. Must not push or merge it.")
  (-promote-candidate! [manager candidate target-worktree]
    "Copy the verified commit onto the card worktree branch, never main.")
  (-discard-candidate! [manager candidate]
    "Remove a rejected candidate worktree without merging it."))

(defprotocol CommandRunner
  (-run-command! [runner command]
    "Run one argv-vector command under bounded policy."))

(defprotocol CandidateRunner
  (-implement! [runner candidate proposal policy]
    "Run the constrained implementation agent in the candidate worktree."))

(defprotocol Evaluator
  (-evaluate! [evaluator workspace gate-specs policy]
    "Run independent verification gates without write capabilities."))

(defprotocol EvolutionLedger
  (-append-event! [ledger event] "Append one immutable run event.")
  (-events [ledger run-id] "Return the ordered event history for a run."))

(defn board? [x] (satisfies? Board x))
(defn workspace-manager? [x] (satisfies? WorkspaceManager x))
(defn command-runner? [x] (satisfies? CommandRunner x))
(defn candidate-runner? [x] (satisfies? CandidateRunner x))
(defn evaluator? [x] (satisfies? Evaluator x))
(defn evolution-ledger? [x] (satisfies? EvolutionLedger x))
