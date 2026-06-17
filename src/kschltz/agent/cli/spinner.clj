(ns kschltz.agent.cli.spinner
  "Lightweight terminal spinner for the interactive CLI.

   Runs on a background thread, prints an in-place ASCII animation on
   the same line, and can be stopped and cleared synchronously. It is
   deliberately simple: no streaming, no curses, no dependencies."
  (:import [java.io PrintWriter]))

(def ^:private frame-ms
  "Delay between spinner frames."
  200)

(defrecord Spinner [thread running? ^PrintWriter out message max-width])

(defn- frame-line
  "Build a carriage-return line for the given dot count."
  [message dot-count]
  (str "\r" message (apply str (repeat dot-count "."))))

(defn start!
  "Start a spinner writing to `out` with the given `message`.

   Returns a Spinner handle. The spinner runs on a daemon thread and
   overwrites the same terminal line until `stop!` is called. If the
   terminal does not interpret `\r`, frames simply accumulate; the
   clear step still removes the last printed frame."
  [^PrintWriter out message]
  (let [running? (atom true)
        max-width (+ (count message) 3)
        thread (Thread. (fn []
                          (loop [i 0]
                            (when @running?
                              (let [dot-count (inc (mod i 3))]
                                (.print out (frame-line message dot-count))
                                (.flush out)
                                (Thread/sleep ^long frame-ms)
                                (recur (inc i)))))))]
    (.setDaemon thread true)
    (.start thread)
    (->Spinner thread running? out message max-width)))

(defn stop!
  "Stop `spinner` and clear its line.

   Waits up to one second for the background thread to finish, then
   writes spaces over the spinner line and returns the cursor to the
   start of that line. Safe to call more than once."
  [spinner]
  (when spinner
    (reset! (:running? spinner) false)
    (.join ^Thread (:thread spinner) 1000)
    (let [^PrintWriter out (:out spinner)
          width (:max-width spinner)]
      (.print out "\r")
      (.print out (apply str (repeat width " ")))
      (.print out "\r")
      (.flush out))))
