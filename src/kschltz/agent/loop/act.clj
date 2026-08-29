(ns kschltz.agent.loop.act
  "Continue an exchange when the model announced work but did not call tools.

   ReAct only loops while `:tool/results` is non-empty. A planning-only
   reply (\"I'll implement X\") is non-blank and has no tool_calls, so
   `ensure-text-response` used to treat it as the final answer and the
   workbench parked on the next human turn. That is the announce-then-yield
   failure mode."
  (:require [clojure.string :as str]))

(def max-act-nudge-attempts
  "Cap on plan-then-act follow-up turns per exchange. One nudge, then
   the planning prose is accepted as the turn's answer: two nudges on
   weak/reasoning models just produced a second declaration and then a
   summary-of-declarations (the churn seen in session 828434e7), so
   more retries make announce-nothing *worse*, not better."
  1)

(def system-guidance
  "When tools can do the work, call them in this turn. Do not announce a plan and wait for another user message.")

(def nudge-content
  "You described work but did not call any tools. Call the tools now to do the work. Do not restate the plan or wait for another user turn.")

(def ^:private future-self
  #"(?i)\b(?:i(?:['’]ll| will| am going to|['’]m going to)|let me(?! know)|let'?s)\b")

(def ^:private act-verb
  #"(?i)\b(?:implement|build|create|write|add|fix|edit|update|patch|reload|call|use|run|load|make|submit|install|apply|change|wire|refactor)\b")

(def ^:private plan-marker
  #"(?i)(?:\bhere(?:['’]s| is) (?:my |the )?plan\b|\bthe plan(?: is|:)\b|\bstep\s*1\b|\bfirst(?:ly)?[,:]?\s+i\b|\bnext i (?:will|['’]ll)\b)")

(defn disabled?
  "Act-nudge is on unless loop-opts explicitly sets `:act-nudge?` false."
  [loop-opts]
  (false? (:act-nudge? loop-opts)))

(defn planning-only?
  "True when the assistant text announces upcoming tool work and this
   turn emitted no tool_calls. Registry must be non-empty."
  [response {:keys [tool-calls registry loop-opts]}]
  (and (not (disabled? loop-opts))
       (seq registry)
       (empty? tool-calls)
       (not (str/blank? response))
       (or (and (re-find future-self response)
                (re-find act-verb response))
           (re-find plan-marker response))))

(defn merge-system-guidance
  "Append `system-guidance` onto `:agent/system-append` when tools exist."
  [prior]
  (cond
    (string? prior) (str prior "\n\n" system-guidance)
    (sequential? prior) (conj (vec prior) system-guidance)
    :else system-guidance))

(defn apply-nudge
  "Record the planning reply in messages and append a system nudge.
   Leaves `:tools` on the request so the follow-up can implement.
   If the last message is already assistant (thinking + answer, or a
   prior nudge), merge into it so the wire request does not end with
   two assistant turns."
  [ctx]
  (let [plan (str (or (:exchange/response ctx) ""))]
    (-> ctx
        (update-in [:llm/request :messages]
                   (fn [msgs]
                     (let [msgs (vec (or msgs []))
                           last (peek msgs)]
                       (if (and last (= "assistant" (:role last)))
                         (let [prev (str (:content last))]
                           (conj (pop msgs)
                                 (assoc last :content
                                        (if (or (str/blank? prev) (= prev plan))
                                          plan
                                          (str prev "\n\n" plan)))))
                         (conj msgs {:role "assistant" :content plan})))))
        (update-in [:llm/request :messages] conj
                   {:role "system" :content nudge-content})
        (assoc :agent/act-nudged? true))))
