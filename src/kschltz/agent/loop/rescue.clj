(ns kschltz.agent.loop.rescue
  "Rescue tool calls the model wrote as TEXT in its reply instead of the
   structured `tool_calls` field.

   Weak/reasoning models under long system prompts routinely narrate
   calls (spotify_api({\"path\": ...})) inside the thinking/answer
   channel. parse-response sees empty `:tool/calls`, dispatch runs
   nothing, and the act-nudge produces another plan paragraph — the
   announce-forever failure mode (session 828434e7).

   `pseudo-calls` scans the response for `name({...})` occurrences where
   `name` is a key of the CURRENT registry and the brace-balanced
   argument parses as JSON. Only registered names match, so prose or
   code examples about unknown tools can't trigger it. dispatch-tools
   then executes the extracted calls with real results — the tool loop
   does the work instead of the declaration becoming the final answer."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.instrument :as mi]))

(defn- extract-brace-json
  "From `s` starting at index of `{`, return the balanced-brace JSON
   substring (honoring double-quoted string literals), or nil."
  [s start]
  (when (and (<= 0 start (count s)) (= \{ (nth s start)))
    (loop [i     start
           depth 0
           mode  :code]
      (if (>= i (count s))
        nil
        (let [c (nth s i)]
          (cond
            (= mode :str)
            (cond
              (= c \\)   (recur (inc i) depth :esc)
              (= c \")   (recur (inc i) depth :code)
              :else      (recur (inc i) depth :str))

            (= mode :esc) (recur (inc i) depth :str)

            (= c \")       (recur (inc i) depth :str)
            (= c \{)       (recur (inc i) (inc depth) :code)
            (= c \})       (if (= depth 1)
                             (subs s start (inc i))
                             (recur (inc i) (dec depth) :code))
            :else          (recur (inc i) depth :code)))))))

(defn- parse-json?
  "True when `s` parses as a JSON object (cheshire when available,
   a brace/quote sanity check otherwise)."
  [s]
  (try
    (when-let [read-str (requiring-resolve 'clojure.data.json/read-str)]
      (map? (read-str s :key-fn keyword)))
    (catch Throwable _
      ;; fallback: a brace-balanced, quote-matched string is good enough
      (let [opens  (count (re-seq #"\{" s))
            closes (count (re-seq #"\}" s))]
        (and (pos? opens) (= opens closes))))))

(defn- call-shape
  "OpenAI-shaped call map with the raw JSON string as :arguments."
  [name json]
  {:function {:name name :arguments json}})

(defn- scan-call
  "All `{...}` arguments following `nm(` in `text`."
  [nm text]
  (let [needle (str nm "(")]
    (loop [idx (str/index-of text needle)
           acc []]
      (if (nil? idx)
        (vec acc)
        (let [boundary-ok? (or (zero? idx)
                               (not (re-matches #"[A-Za-z0-9_-]"
                                                (str (nth text (dec idx))))))
              brace-i (str/index-of (subs text idx) "{")]
          (if (and boundary-ok? brace-i)
            (let [json (extract-brace-json text (+ idx brace-i))]
              (recur (str/index-of text needle (inc idx))
                     (if (and json (parse-json? json))
                       (conj acc (assoc (call-shape nm json) ::pos idx))
                       acc)))
            (recur (str/index-of text needle (inc idx)) acc)))))))

(defn pseudo-calls
  "Extract registered-tool pseudo-calls from `text`. Returns a vector of
   OpenAI-shaped `{:function {:name ... :arguments ...}}` calls; the
   arguments travel as the raw JSON string (execute-tools parses them).
   Only registry names match. Identical consecutive repeats are
   collapsed so a declaration pasted in two formats doesn't double-run.
   Returns [] for blank text / empty registry / no matches."
  [registry text]
  (let [text (str text)]
    (if (or (str/blank? text) (empty? registry))
      []
      (->> (keys registry)
           (mapcat #(scan-call % text))
           ;; document order, then dedupe consecutive identical calls
           (sort-by ::pos)
           (map #(dissoc % ::pos))
           (reduce (fn [acc call]
                     (if (= (last acc) call)
                       acc
                       (conj acc call)))
                   [])))))

(m/=> pseudo-calls [:=> [:cat [:map-of :string :any] [:maybe :string]] [:vector :map]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.loop.rescue)]}))

(instrument!)