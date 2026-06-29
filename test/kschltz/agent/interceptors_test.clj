(ns kschltz.agent.interceptors-test
  "Tests for `kschltz.agent.interceptors` history-trimming invariants.

   These pin the bumped `max-history-entries` cap (40 -> 100) and the
   `trim-history` guarantees that the summarizer relies on:
     - the leading :role system message is preserved at index 0;
     - the hard cap of 100 non-system messages is enforced;
     - the most recent :role user turn is never dropped, even when the
       raw 100-message window would have cut it off (the anchor
       invariant that lets `body-window` exceed the cap)."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.interceptors :as ix]))

(defn- pair-seq
  "Return a vector of `n` user/assistant pairs with `prefix`-tagged
   content (`(* 2 n)` non-system messages)."
  [n prefix]
  (vec (mapcat (fn [i]
                 [{:role "user"      :content (str prefix "-q-" i)}
                  {:role "assistant" :content (str prefix "-a-" i)}])
               (range n))))

(defn- non-system-count [messages]
  (count (remove #(= "system" (:role %)) messages)))

(deftest trim-history-cap-is-100
  (testing "the max-history-entries literal is 100 and trim-history enforces it"
    (is (= 100 ix/max-history-entries)
        "max-history-entries was bumped from 40 to 100")
    (let [history (into [{:role "system" :content "SYS"}]
                        (pair-seq 60 "m"))   ; 120 non-system messages
          out     (ix/trim-history history)]
      (is (= {:role "system" :content "SYS"} (first out))
          "the leading system message is kept at index 0")
      (is (= 100 (non-system-count out))
          "trim-history caps the non-system body at exactly 100 messages")
      (is (= (count out) 101)
          "total is 1 system + 100 body messages")
      (is (= "m-a-59" (:content (peek out)))
          "the most recent assistant turn survives at the tail")
      (is (some #(= "m-q-59" (:content %)) out)
          "the most recent user turn survives the cap"))))

(deftest trim-history-preserves-system-and-anchor
  (testing "trim-history keeps the leading system message and never drops
            the most recent :role user message even when it sits before the
            raw 100-message window start (the anchor may push the body over
            the cap, which is intentional)"
    (let [;; 120 non-system messages where the ONLY user turn sits at
            ;; body index 19 — just before the raw window start (20).
            ;; The anchor invariant must pull the window back to include
            ;; it, so the result exceeds the 100 cap by one.
          body    (vec (concat (repeat 19 {:role "assistant" :content "a"})
                               [{:role "user" :content "ANCHOR-USER"}]
                               (repeat 100 {:role "assistant" :content "a"})))
          _       (is (= 120 (count body)))
          history (into [{:role "system" :content "SYS"}] body)
          out     (ix/trim-history history)]
      (is (= {:role "system" :content "SYS"} (first out))
          "the leading system message is preserved at index 0")
      (is (some #(= "ANCHOR-USER" (:content %)) out)
          "the most recent user turn is preserved even though the raw
           window would have dropped it")
      (is (> (non-system-count out) 100)
          "the anchor invariant lets the body exceed the 100 cap to
           keep the most recent user turn")
      (is (= "ANCHOR-USER"
             (:content (some #(when (= "user" (:role %)) %) out)))
          "the preserved anchor retains its :role user"))))