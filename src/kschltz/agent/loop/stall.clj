(ns kschltz.agent.loop.stall
  "Exact-signature and arg-shape stall detection for the ReAct loop.

   Counters persist on `:agent/state-delta` and are seeded from
   `:agent/state` so they survive a rebuilt exchange ctx (each
   `send-message` starts a fresh map). Per-tool primary-arg counts
   trip even when the same failing `clojure_add_lib` is mixed with
   successful sibling calls — the live verify-round-3 failure mode
   where `every?` turn-error reset the whole-turn counter."
  (:require [cheshire.core :as json]))

(def stall-state-keys
  "Session keys copied from `:agent/state` onto a fresh exchange ctx."
  [:agent/last-tool-call-sig
   :agent/last-tool-shape
   :agent/shape-err-count
   :agent/shape-err-counts])

(def tool-primary-arg-keys
  "Tool name -> arg keys that identify the *target* of a call.
   Same primary arg + differing secondary args (add-lib `:require`)
   share one shape for stall detection."
  {"clojure_add_lib" [:lib :coords]})

(defn seed-from-state
  "Copy missing stall counters from `:agent/state` onto `ctx`."
  [ctx]
  (let [state (or (:agent/state ctx) {})]
    (reduce (fn [c k]
              (if (contains? c k)
                c
                (if-let [v (get state k)]
                  (assoc c k v)
                  c)))
            ctx
            stall-state-keys)))

(defn persist
  "Merge `patch` onto `ctx` and `:agent/state-delta` (session-durable)."
  [ctx patch]
  (-> ctx
      (merge patch)
      (update :agent/state-delta (fnil merge {}) patch)))

(defn tool-call-sig
  "Stable signature for a turn's tool calls: name + raw arguments JSON."
  [calls]
  (mapv (fn [c] {(get-in c [:function :name])
                 (get-in c [:function :arguments])})
        calls))

(defn tool-call-shape
  "Coarser signature: tool name + primary-arg subset. Unknown tools
   use the whole args string (shape = exact signature)."
  [call]
  (let [name     (get-in call [:function :name])
        args-str (get-in call [:function :arguments])
        pkeys    (get tool-primary-arg-keys name)]
    (if pkeys
      (let [parsed (try (json/parse-string args-str true)
                        (catch Throwable _ nil))]
        [name (if (map? parsed)
                (pr-str (select-keys parsed pkeys))
                args-str)])
      [name args-str])))

(defn- error-status?
  [status]
  (contains? #{:error :timeout "error" "timeout"} status))

(defn result-error-shape?
  "True when a tool result is a failure shape: JSON status error/timeout,
   `:loaded? false`, or the unavailable-tool marker."
  [result-map]
  (let [r (:result result-map)]
    (if (string? r)
      (if-let [parsed (try (json/parse-string r true) (catch Throwable _ nil))]
        (or (error-status? (:status parsed))
            (false? (:loaded? parsed)))
        (boolean (re-find #"is not available in this session" (str r))))
      false)))

(defn- primary-shape
  "Primary-arg shape when the tool is tracked; otherwise nil."
  [call]
  (when (contains? tool-primary-arg-keys (get-in call [:function :name]))
    (tool-call-shape call)))

(defn- results-by-call-id
  [results]
  (into {}
        (keep (fn [r]
                (when-let [id (get-in r [:call :id])]
                  [id r]))
              results)))

(defn update-primary-counts
  "Increment per-primary-shape error counts; reset a shape on success."
  [prev-counts calls results]
  (let [by-id (results-by-call-id results)]
    (reduce (fn [m call]
              (if-let [shape (primary-shape call)]
                (let [res (get by-id (:id call))
                      err? (and res (result-error-shape? res))]
                  (if err?
                    (update m shape (fnil inc 0))
                    (assoc m shape 0)))
                m))
            (or prev-counts {})
            calls)))

(defn decide
  "Return `{:action :continue|:exact-stall|:shape-stall :patch m}`.

   `:exact-stall` — identical name+args set as last turn.
   `:shape-stall` — same whole-turn shape for N>=2 all-error turns,
   OR any tracked primary-arg shape has error count >= 2."
  [ctx]
  (let [calls        (or (:tool/calls ctx) [])
        results      (or (:tool/results ctx) [])
        sig          (tool-call-sig calls)
        last-sig     (:agent/last-tool-call-sig ctx)
        shape        (set (mapv tool-call-shape calls))
        last-shape   (:agent/last-tool-shape ctx)
        prev-count   (get ctx :agent/shape-err-count 0)
        prev-counts  (or (:agent/shape-err-counts ctx) {})
        turn-error?  (and (seq results)
                          (every? result-error-shape? results))
        same-shape?  (and (some? last-shape) (= shape last-shape))
        new-count    (cond (not turn-error?) 0
                           same-shape?      (inc prev-count)
                           :else            1)
        new-counts   (update-primary-counts prev-counts calls results)
        primary-hit? (boolean (some (fn [[_ n]] (>= n 2)) new-counts))
        whole-hit?   (and turn-error? same-shape? (>= new-count 2))
        patch        {:agent/last-tool-call-sig sig
                      :agent/last-tool-shape    shape
                      :agent/shape-err-count    new-count
                      :agent/shape-err-counts   new-counts}]
    (cond
      (and (some? last-sig) (= sig last-sig))
      {:action :exact-stall :patch patch}

      (or whole-hit? primary-hit?)
      {:action :shape-stall :patch patch}

      :else
      {:action :continue :patch patch})))
