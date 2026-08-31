(ns kschltz.agent.loop.retry
  "Same-turn recovery when a model defines a tool and calls it in one batch.

   Dispatch runs before transition apply, so a parallel `weather_now`
   call looks unregistered. After apply refreshes the registry, retry
   those unavailable results. If a tool was defined but has no passing
   tool_test evidence, nudge the follow-up turn to test it."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.transitions :as tr]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def unavailable-marker
  "Exact phrase emitted by `tool/execute-tools` for a missing name."
  "is not available in this session")

(defn unavailable-result?
  [entry]
  (str/includes? (str (:result entry)) unavailable-marker))

(defn- replace-turn-results
  [all n rewritten]
  (let [all (vec (or all []))
        keep-n (max 0 (- (count all) n))]
    (into (subvec all 0 keep-n) rewritten)))

(defn defined-tool-names
  "Names successfully registered by `tool_define` in this turn's results."
  [results]
  (into []
        (keep (fn [entry]
                (let [parsed (tr/parse-tool-result (:result entry))
                      name (or (:tool-name parsed)
                               (get-in parsed [:transition :spec :name]))]
                  (when (and (:ok parsed)
                             (= "tool_define" (get-in entry [:call :function :name]))
                             (string? name)
                             (seq name))
                    name))))
        (or results [])))

(defn tested-ok?
  [results name]
  (boolean
   (some (fn [entry]
           (let [parsed (tr/parse-tool-result (:result entry))]
             (and (= "tool_test" (get-in entry [:call :function :name]))
                  (= name (:tool-name parsed))
                  (true? (:ok parsed)))))
         (or results []))))

(defn- successful-control-tool-names
  [results control-tool]
  (into []
        (keep (fn [entry]
                (let [parsed (tr/parse-tool-result (:result entry))
                      name (:tool-name parsed)]
                  (when (and (= control-tool
                                (get-in entry [:call :function :name]))
                             (true? (:ok parsed))
                             (string? name)
                             (seq name))
                    name))))
        (or results [])))

(defn- inventory-confirms?
  [results name]
  (boolean
   (some
    (fn [entry]
      (let [parsed (tr/parse-tool-result (:result entry))]
        (and (= "tool_list_runtime" (get-in entry [:call :function :name]))
             (true? (:ok parsed))
             (some #{name} (get-in parsed [:status :promoted])))))
    (or results []))))

(defn retry-now-available
  "Re-run this turn's unavailable calls whose names are now registered."
  [ctx]
  (let [registry (or (:agent/tool-registry ctx) {})
        results (vec (or (:tool/results ctx) []))]
    (if-not (some unavailable-result? results)
      ctx
      (let [retried
            (mapv (fn [{:keys [call] :as entry}]
                    (let [name (get-in call [:function :name])]
                      (if (and (unavailable-result? entry)
                               (tool/resolve-tool registry name))
                        (first (tool/execute-tools registry ctx [call]))
                        entry)))
                  results)]
        (-> ctx
            (assoc :tool/results retried)
            (update :agent/all-tool-results
                    replace-turn-results (count results) retried))))))

(defn nudge-untested-runtime-tools
  "Drive define → tool_test → promote → inventory verification."
  [ctx]
  (let [results (:tool/results ctx)
        untested (into []
                       (remove #(tested-ok? (:tool/results ctx) %))
                       (defined-tool-names results))
        tested (successful-control-tool-names results "tool_test")
        promoted (into []
                       (remove #(inventory-confirms? results %))
                       (successful-control-tool-names
                        results "tool_promote"))]
    ;; #region agent log
    (spit "/opt/cursor/logs/debug.log"
          (str (json/generate-string
                {:hypothesisId "B,C"
                 :location "loop/retry.clj:nudge-untested-runtime-tools"
                 :message "evaluated lifecycle continuation nudge"
                 :data {:depth (:agent/tool-loop-depth ctx)
                        :resultTools (mapv #(get-in % [:call :function :name])
                                           results)
                        :untested untested
                        :tested tested
                        :promoted promoted}
                 :timestamp (System/currentTimeMillis)})
               "\n")
          :append true)
    ;; #endregion
    (cond
      (seq untested)
      (-> ctx
          (assoc :agent/runtime-tool-test-nudge untested)
          (update-in [:llm/request :messages] (fnil conj [])
                     {:role "system"
                      :content
                      (str "Runtime tool(s) now registered: "
                           (str/join ", " untested)
                           ". Call tool_test for each now with real arguments "
                           "and exact expected-output. Do not claim success or "
                           "call tool_promote until tool_test returns ok=true.")}))

      (seq tested)
      (-> ctx
          (assoc :agent/runtime-tool-promote-nudge tested)
          (update-in
           [:llm/request :messages] (fnil conj [])
           {:role "system"
            :content
            (str "tool_test passed for: " (str/join ", " tested)
                 ". Call tool_promote for each now, then call "
                 "tool_list_runtime and verify each name appears in promoted. "
                 "Do not claim completion before both calls succeed.")}))

      (seq promoted)
      (-> ctx
          (assoc :agent/runtime-tool-list-nudge promoted)
          (update-in
           [:llm/request :messages] (fnil conj [])
           {:role "system"
            :content
            (str "Promotion succeeded for: " (str/join ", " promoted)
                 ". Call tool_list_runtime now and verify each appears in "
                 "promoted. Do not claim completion before inventory confirms.")}))

      :else ctx)))

(defn retry-now-available-interceptor
  "`:tools` interceptor — after apply, before compose."
  []
  {:name ::retry-now-available
   :slot :tools
   :enter (fn [ctx] (retry-now-available ctx))})

(defn nudge-untested-runtime-tools-interceptor
  "`:tools` interceptor — after retry, before compose."
  []
  {:name ::nudge-untested-runtime-tools
   :slot :tools
   :enter (fn [ctx] (nudge-untested-runtime-tools ctx))})

(m/=> retry-now-available [:=> [:cat :map] :map])
(m/=> nudge-untested-runtime-tools [:=> [:cat :map] :map])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.loop.retry)]}))

(instrument!)
