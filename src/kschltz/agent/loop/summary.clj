(ns kschltz.agent.loop.summary
  "Summary-turn helpers: strip tool-call scaffold, coerce empty-content
   tool_calls after tools were removed, condense history on retry, and
   synthesize a prose fallback from tool results when the model never
   emits a final answer."
  (:require [clojure.string :as str]))

(def max-digest-chars
  "Per-result cap in synthesized / condensed digests."
  400)

(defn strip-tool-call-scaffold
  "Remove assistant `:tool_calls` and rewrite `:role \"tool\"` results
   to system context so a tool-happy model cannot echo prior calls."
  [messages]
  (into []
        (keep (fn [m]
                (cond
                  (and (= "assistant" (:role m)) (seq (:tool_calls m)))
                  (let [c (:content m)]
                    (when (and (string? c) (not (str/blank? c)))
                      (dissoc m :tool_calls)))

                  (= "tool" (:role m))
                  {:role "system"
                   :content (str "Tool " (or (:name m) "?") " returned: " (:content m))}

                  :else m)))
        messages))

(defn- truncate
  [s]
  (let [s (str s)
        n (count s)]
    (if (<= n max-digest-chars)
      s
      (str (subs s 0 max-digest-chars) "…"))))

(defn synthesize-from-results
  "Deterministic prose summary of `all-tool-results`. Returns nil when
   there are no results."
  [all-tool-results]
  (when (seq all-tool-results)
    (let [lines (for [{:keys [call result]} all-tool-results
                      :let [name (or (get-in call [:function :name]) "?")]]
                  (str "- " name " → " (truncate result)))]
      (str "The agent finished its tool work but did not produce a final "
           "prose answer. Here is what the tools returned:\n"
           (str/join "\n" lines)))))

(defn condensed-messages
  "Minimal summary history: system + user + tool-result digest.
   Used on the second summary attempt so the model has no tool-call
   pattern left to echo."
  [ctx]
  (let [state   (or (:agent/state ctx) {})
        sys     (or (:agent/system-message state) "lateralus-v2 MVP")
        user    (or (:exchange/user-text ctx) "")
        digest  (or (synthesize-from-results (:agent/all-tool-results ctx))
                    "(no tool results)")]
    [{:role "system" :content sys}
     {:role "user" :content user}
     {:role "system" :content (str "Tool results digest:\n" digest)}
     {:role "system"
      :content (str "You have finished calling tools. Using the digest "
                    "above, produce the final answer for the user. "
                    "Reply with prose only — do not call tools.")}]))

(defn malformed-summary-tool-calls?
  "True when the model emitted tool_calls with blank content after
   tools were stripped (or tool_choice is none)."
  [ctx]
  (let [req (:llm/request ctx)
        tools-gone? (or (nil? (:tools req))
                        (= "none" (:tool-choice req)))]
    (and tools-gone?
         (seq (:tool/calls ctx))
         (str/blank? (:exchange/response ctx)))))

(defn coerce-malformed-summary
  "Clear echoed tool_calls so the summary retry/fallback path treats
   the turn as an empty text response."
  [ctx]
  (if (malformed-summary-tool-calls? ctx)
    (assoc ctx
           :tool/calls []
           :exchange/response ""
           :agent/malformed-summary-tool-calls? true)
    ctx))

(defn apply-summary-request
  "Build the summary LLM request. Attempt 1 strips scaffold in place;
   attempt 2+ replaces history with a condensed digest."
  [ctx]
  (let [attempt (get ctx :agent/summary-attempts 0)
        req     (:llm/request ctx)
        msgs    (if (>= attempt 2)
                  (condensed-messages ctx)
                  (conj (strip-tool-call-scaffold (or (:messages req) []))
                        {:role "system"
                         :content (str "You have finished calling tools. "
                                       "Using the tool results above, produce "
                                       "the final answer for the user.")}))]
    (assoc ctx :llm/request (-> req
                                (assoc :messages msgs)
                                (dissoc :tools)
                                (assoc :tool-choice "none")))))

(defn apply-fallback
  "When summary retries are exhausted, keep `:agent/summary-failed?`
   and fill a blank `:exchange/response` from tool results."
  [ctx]
  (let [synth (synthesize-from-results (:agent/all-tool-results ctx))
        resp  (:exchange/response ctx)]
    (cond-> (assoc ctx :agent/summary-failed? true)
      (and (str/blank? resp) (seq synth))
      (assoc :exchange/response synth
             :agent/summary-synthesized? true))))
