(ns kschltz.agent.loop.trim
  "In-exchange message trimming for the ReAct tool loop.

   Cross-exchange history uses `kschltz.agent.interceptors/trim-history`
   (anchors to the most-recent user turn). Mid-exchange that anchor sits
   at the start and would keep everything, so follow-up turns use this
   namespace instead: keep leading system + first user turn + a trailing
   window, and never open the tail on an orphan `:role \"tool\"` message."
  (:require [kschltz.agent.interceptors :as ix]))

(def max-in-flight-entries
  "Cap on messages retained in `:llm/request :messages` during one
   multi-turn ReAct exchange. Independent of `ix/max-history-entries`."
  40)

(defn- drop-leading-tool-msgs
  "Advance past leading `:role \"tool\"` messages so a trimmed tail never
   opens on an orphan tool result whose `:tool_calls` opener was cut away."
  [tail]
  (loop [i 0]
    (if (and (< i (count tail))
             (= "tool" (:role (nth tail i))))
      (recur (inc i))
      (subvec tail i))))

(defn trim-in-flight-messages
  "Bound in-exchange messages by count and size. Keeps leading system
   message(s) + the first user turn + the last `max-in-flight-entries`
   follow-ups; truncates tool `:content` via `ix/truncate-tool-content`
   (`caps` raises the cap per tool name); drops leading orphan tool msgs
   after the tail slice."
  ([messages] (trim-in-flight-messages messages nil))
  ([messages caps]
   (let [msgs (vec messages)
         n    (count msgs)]
     (if (<= n max-in-flight-entries)
       (mapv #(ix/truncate-tool-content % caps) msgs)
       (let [first-user-idx (->> msgs
                                 (map-indexed vector)
                                 (some (fn [[i m]] (when (= "user" (:role m)) i))))
             head-end (cond
                        first-user-idx (inc first-user-idx)
                        (= "system" (:role (first msgs))) 1
                        :else 0)
             head       (subvec msgs 0 head-end)
             tail-count (max 1 (- max-in-flight-entries head-end))
             raw-tail   (subvec msgs (- n tail-count))
             tail       (drop-leading-tool-msgs raw-tail)
             combined   (into head tail)]
         (mapv #(ix/truncate-tool-content % caps) combined))))))
