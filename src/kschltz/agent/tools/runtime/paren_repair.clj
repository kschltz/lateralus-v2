(ns kschltz.agent.tools.runtime.paren-repair
  "Paren/delimiter rebalancing for model-generated Clojure code.

  Before `clojure_eval` runs a code string, `repair-code` closes missing
   delimiters and re-balances stray ones so the model's output is more
   likely to read cleanly. This cuts down the most common LLM Clojure
   failure mode — forgetting a closing `)`/`]`/`}` or dropping one
   mid-form — before the reader ever sees it.

   Pipeline (mirrors the proven bhauman/clojure-mcp-light flow, but
   in-process and GraalVM-safe):
     1. detect  — edamame parses the string; an :edamame/error with
                  :edamame/opened-delimiter means a delimiter error.
     2. repair  — parinferish (pure-Clojure parinfer, indent mode) infers
                  the correct delimiter structure from indentation.
     3. verify  — edamame re-parses the repaired string; only if it is
                  now balanced do we accept the repair.

   If the repair still does not parse, `repair-code` returns the ORIGINAL
  string unchanged so `clojure_eval` surfaces the genuine reader error
   rather than silently worse code. Both libraries are pure Clojure and
   GraalVM-native-safe, so the repair runs identically on the JVM and in
   the native-image build. parinfer-rust (the fastest backend) is an
   external binary and is intentionally NOT used here so the tool stays
   self-contained and works in native-image."
  (:require [edamame.core :as edamame]
            [parinferish.core :as parinferish]))

(def ^:private parse-opts
  "Edamame options matching the Clojure reader as closely as possible:
   all standard reader features, reader conditionals allowed, unknown
   data readers passed through so model code with custom readers still
   parses for delimiter detection."
  {:all true
   :read-cond :allow
   :readers (fn [_tag] (fn [data] data))
   :auto-resolve name})

(defn delimiter-error?
  "True if `s` has an unbalanced delimiter. Non-delimiter parse errors
   (e.g. a bad reader char) return false — we only want to 'repair'
   delimiter imbalance, not mask real reader errors."
  [s]
  (try
    (edamame/parse-string-all s parse-opts)
    false
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (and (= :edamame/error (:type data))
             (contains? data :edamame/opened-delimiter))))
    (catch Exception _ false)))

(defn balanced?
  "True if `s` parses cleanly with no delimiter error."
  [s]
  (not (delimiter-error? s)))

(defn- parinfer-repair
  "Run parinferish indent-mode on `s`; returns the repaired string or
   nil on failure."
  [s]
  (try
    (parinferish/flatten (parinferish/parse s {:mode :indent}))
    (catch Exception _ nil)))

(defn repair-code
  "Return a map describing the repair of `s`:
     {:code <string> :repaired? <bool> :method <nil|:parinfer>}

   - When `s` is already balanced, returns it unchanged with
     :repaired? false.
   - When a delimiter error is detected, runs parinferish indent-mode
     and verifies the result parses cleanly; if so returns it with
     :repaired? true :method :parinfer.
   - If the repair still doesn't parse, returns the original `s`
     unchanged with :repaired? false so the caller (eval) surfaces the
     genuine reader error instead of silently worse code.

   Nil-safe: a nil or empty `s` is returned as-is with :repaired? false."
  [s]
  (cond
    (or (nil? s) (empty? s)) {:code s :repaired? false :method nil}
    (balanced? s)            {:code s :repaired? false :method nil}
    :else
    (let [repaired (parinfer-repair s)]
      (if (and repaired (balanced? repaired))
        {:code repaired :repaired? true :method :parinfer}
        {:code s :repaired? false :method nil}))))