(ns kschltz.agent.tools.examples
  "Example Tool implementations for the lateralus tool-calling loop.

   These are intentionally simple and safe: `time/now` returns the
   current UTC time, and `calculator/eval` evaluates a whitelist of
   arithmetic expressions written as Clojure EDN.

   Each tool implements the `kschltz.agent.tool/Tool` protocol and
   declares Malli schemas for its input and output, so `invoke-tool`
   validates both sides automatically."
  (:require [clojure.edn :as edn]
            [kschltz.agent.tool :as tool]))

(defn- now-iso
  "Current UTC time as an ISO-8601 string."
  []
  (str (java.time.Instant/now)))

(defn- safe-calc
  "Evaluate a small whitelist of arithmetic expressions written as
   Clojure EDN, e.g. \"(+ 1 2 3)\". Anything outside `+ - * / max min`
   with numeric args is rejected."
  [expr-str]
  (let [form (edn/read-string expr-str)
        op   (and (seq? form) (first form))
        nums (rest form)]
    (if (and (#{'+ '- '* '/ 'max 'min} op)
             (seq nums)
             (every? number? nums))
      (str (apply ({'+ + '- - '* * '/ /
                    'max max 'min min} op)
                   nums))
      (throw (ex-info "Unsupported calculator expression"
                      {:expr expr-str
                       :allowed-ops ["+" "-" "*" "/" "max" "min"]})))))

(def InputSchema:TimeNow
  "Empty input schema for time/now."
  [:map])

(def OutputSchema:IsoString
  "Output schema for tools that return ISO-8601 strings."
  :string)

(def InputSchema:CalculatorEval
  "Input schema for calculator/eval."
  [:map
   [:expression :string]])

(deftype TimeNowTool []
  tool/Tool
  (-name [_] "time/now")
  (-description [_] "Return the current UTC date and time as an ISO-8601 string.")
  (-input-schema [_] InputSchema:TimeNow)
  (-output-schema [_] OutputSchema:IsoString)
  (-invoke [_ _args] (now-iso)))

(deftype CalculatorEvalTool []
  tool/Tool
  (-name [_] "calculator/eval")
  (-description [_] "Evaluate a simple arithmetic expression. Use prefix notation, e.g. (+ 1 2 3), (* 4 5), (/ 10 2).")
  (-input-schema [_] InputSchema:CalculatorEval)
  (-output-schema [_] OutputSchema:IsoString)
  (-invoke [_ args] (safe-calc (:expression args))))

(defn time-now
  "Return a new `time/now` Tool instance."
  []
  (->TimeNowTool))

(defn calculator-eval
  "Return a new `calculator/eval` Tool instance."
  []
  (->CalculatorEvalTool))

(defn example-registry
  "Return a map of example tool name -> Tool instance."
  []
  {"time/now"        (time-now)
   "calculator/eval" (calculator-eval)})
