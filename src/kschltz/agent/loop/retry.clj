(ns kschltz.agent.loop.retry
  "Same-turn recovery when a model defines a tool and calls it in one batch.

   Dispatch runs before transition apply, so a parallel `weather_now`
   call looks unregistered. After apply refreshes the registry, retry
   those unavailable results. If a tool was defined but has no passing
   tool_test evidence, nudge the follow-up turn to test it."
  (:require [clojure.string :as str]
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
  "Ask the next LLM turn to tool_test definitions without passing evidence."
  [ctx]
  (let [untested (into []
                       (remove #(tested-ok? (:tool/results ctx) %))
                       (defined-tool-names (:tool/results ctx)))]
    (if (empty? untested)
      ctx
      (-> ctx
          (assoc :agent/runtime-tool-test-nudge untested)
          (update-in [:llm/request :messages] (fnil conj [])
                     {:role "system"
                      :content
                      (str "Runtime tool(s) now registered: "
                           (str/join ", " untested)
                           ". Call tool_test for each now with real arguments "
                           "and exact expected-output. Do not claim success or "
                           "call tool_promote until tool_test returns ok=true.")})))))

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
