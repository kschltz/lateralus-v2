(ns kschltz.agent.loop.retry
  "Same-turn recovery when a model defines a tool and calls it in one batch.

   Dispatch runs before transition apply, so a parallel `weather_now`
   call looks unregistered. After apply refreshes the registry, retry
   those unavailable results. If a tool was defined but not invoked
   successfully, nudge a probe `tool_test` (omit expected-output). A
   same-turn successful call or `portal_submit` skips that nudge;
   promotion still requires a passing test."
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

(defn- runtime-tool-used-successfully?
  "True when this turn invoked `name` and the result is usable
   (not unavailable, not input-validation, not JSON `:ok false`)."
  [results name]
  (boolean
   (some (fn [entry]
           (let [called (get-in entry [:call :function :name])
                 result (str (:result entry))
                 parsed (tr/parse-tool-result (:result entry))]
             (and (= called name)
                  (not (unavailable-result? entry))
                  (not (str/includes? result "validation failed"))
                  (not (false? (:ok parsed))))))
         (or results []))))

(defn- portal-submit-ok?
  [results]
  (boolean
   (some (fn [entry]
           (let [parsed (tr/parse-tool-result (:result entry))]
             (and (= "portal_submit" (get-in entry [:call :function :name]))
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
  "Drive define → (optional probe) → promote → inventory verification.

   A same-turn successful call of the new tool (or a successful
   `portal_submit`) skips the test nudge — the tool is already usable.
   `tool_test` remains required before `tool_promote`."
  [ctx]
  (let [results (:tool/results ctx)
        defined (defined-tool-names results)
        untested (into []
                       (remove #(tested-ok? results %))
                       defined)
        used-or-submitted
        (into []
              (filter (fn [name]
                        (or (runtime-tool-used-successfully? results name)
                            (portal-submit-ok? results))))
              untested)
        still-untested (into [] (remove (set used-or-submitted) untested))
        tested (successful-control-tool-names results "tool_test")
        promoted (into []
                       (remove #(inventory-confirms? results %))
                       (successful-control-tool-names
                        results "tool_promote"))]
    (cond
      (seq still-untested)
      (-> ctx
          (assoc :agent/runtime-tool-test-nudge still-untested)
          (update-in [:llm/request :messages] (fnil conj [])
                     {:role "system"
                      :content
                      (str "Runtime tool(s) now registered: "
                           (str/join ", " still-untested)
                           ". Probe with tool_test using a JSON object for "
                           "arguments/args (not a string), e.g. "
                           "{\"a\":1,\"b\":2}, and omit expected-output. "
                           "Do not file_read factory source. "
                           "Live viz: return HTML/JS and portal_submit — "
                           "do not open JVM sockets. "
                           "tool_test is required only before tool_promote.")}))

      (seq tested)
      (-> ctx
          (assoc :agent/runtime-tool-promote-nudge tested)
          (update-in
           [:llm/request :messages] (fnil conj [])
           {:role "system"
            :content
            (str "tool_test passed for: " (str/join ", " tested)
                 ". Call tool_promote for each now, then call "
                 "tool_list_runtime with no arguments and verify each name "
                 "appears in promoted. Extra keys are ignored. "
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
