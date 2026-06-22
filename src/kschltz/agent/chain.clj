(ns kschltz.agent.chain
  "Minimal Pedestal-style interceptor engine.

   An interceptor is a plain map:
     {:name  :keyword            ; required
      :enter (fn [ctx] ctx')     ; optional
      :leave (fn [ctx] ctx')     ; optional
      :error (fn [ctx ex] ctx')} ; optional

   Semantics (synchronous, copied from Pedestal's documented behavior):
   - :enter fns run in queue order; each executed interceptor is pushed
     onto the stack
   - when the queue is empty (or `terminate` is called), :leave fns run
     in reverse stack order
   - When an exception is thrown by any `:enter` stage, the enter walk
     stops immediately. **Stages after the throwing one are not pushed
     onto the stack and will not be entered, even if an `:error`
     handler in a prior stage clears the error.** A plugin that wants
     to do post-error work (persistence, cleanup) must be placed
     BEFORE the stage that may throw, or must be enqueued by the
     throwing stage's `:enter` before the throw.
   - an exception in any stage switches to error mode: the stack is
     walked in stack-reverse calling :error. When an :error handler
     returns a ctx without ::error, the error is cleared and the leave
     phase begins from the remaining stack (the handling interceptor's
     own :leave is NOT re-run).
   - an unhandled error at the bottom of the stack is rethrown, wrapped
     in ex-info carrying :chain/stage, :interceptor/name, and any
     ex-data the original throwable carried
   - interceptors may `enqueue` more interceptors mid-flight

   Engine state lives under namespaced keys (::queue ::stack ::error) so
   it never collides with domain keys.

   Instrumentation: when the ctx carries {:chain/instrument? true
   :chain/validate (fn [ctx] nil-or-explanation)}, the validate fn runs
   after every stage; a non-nil result throws with the offending
   interceptor's name and the explanation. With the flag off, validate
   is never called."
  (:import [clojure.lang PersistentQueue]))

(def ^:private empty-queue PersistentQueue/EMPTY)

(defn enqueue
  "Append `interceptors` to the remaining execution queue of `ctx`."
  [ctx interceptors]
  (update ctx ::queue (fnil into empty-queue) interceptors))

(defn terminate
  "Drop the remaining queue; already-entered interceptors still :leave."
  [ctx]
  (assoc ctx ::queue empty-queue))

(defn- error-map
  "Build the ::error map from a throwable, preserving ex-data and tagging
   with the stage and interceptor name.

   Engine metadata (`:exception`, `:interceptor/name`, `:chain/stage`)
   takes precedence over any colliding ex-data keys. A user's ex-data
   may include `:chain/stage` etc. (rare, but possible when a plugin
   throws `(ex-info \"boom\" {:chain/stage :my-marker})`); if we merged
   the other direction, the engine's bookkeeping would be clobbered
   and the rethrow would carry the wrong stage to try-error.
   dissoc of the reserved set limits the bleed — any other ex-data
   key flows through unchanged (forwards-compat: adding a new
   ex-data key does not require a schema update here)."
  [t interceptor stage]
  (let [ex-data (when (instance? clojure.lang.IExceptionInfo t) (ex-data t))
        ;; Reserved engine keys — strip from ex-data so the merge
        ;; below doesn't clobber the engine's own values.
        reserved #{:exception :interceptor/name :chain/stage}
        ex-data-clean (when ex-data
                        (apply dissoc ex-data reserved))]
    (cond-> {:exception        t
             :interceptor/name (:name interceptor)
             :chain/stage      stage}
      ex-data-clean (merge ex-data-clean))))

(defn- check-instrumented
  [ctx interceptor stage]
  (when (and (:chain/instrument? ctx) (:chain/validate ctx))
    (when-some [explanation ((:chain/validate ctx) ctx)]
      (throw (ex-info "Interceptor returned invalid context"
                      {:interceptor/name (:name interceptor)
                       :chain/stage      stage
                       :chain/explanation explanation}))))
  ctx)

(defn- call-on-stage
  "Invoke the :chain/on-stage logging hook when :chain/log? is true.
   Swallows all errors so logging can never break the chain. Returns
   `ctx` unchanged (the hook is side-effect only)."
  [ctx interceptor stage direction]
  (when (and (:chain/log? ctx) (:chain/on-stage ctx))
    (try
      ((:chain/on-stage ctx) ctx interceptor stage direction)
      (catch Throwable t
        (binding [*out* *err*]
          (println "lateralus chain logging error:" (ex-message t))))))
  ctx)

(defn- try-stage
  "Run a :enter or :leave fn. On throw (or instrumentation throw) sets
   ::error and returns ctx without re-throwing. When :chain/log? is
   true, the :chain/on-stage hook is invoked before (:enter direction)
   and after (:leave direction) the stage fn; logging failures are
   swallowed so they never affect the chain."
  [ctx interceptor stage]
  (if-some [f (get interceptor stage)]
    (try
      (call-on-stage ctx interceptor stage :enter)
      (let [result (check-instrumented (f ctx) interceptor stage)]
        (call-on-stage result interceptor stage :leave)
        result)
      (catch Throwable t
        (assoc ctx ::error (error-map t interceptor stage))))
    ctx))

(defn- try-error
  "Run interceptor's :error handler. A handler that returns a ctx
   without ::error has handled it. We pre-dissoc ::error from the
   ctx passed to the handler so the handler observes the live
   error and decides whether to re-set it; if the handler returns
   the ctx unchanged, the dissoc is the de facto \"handled\" mark."
  [ctx interceptor]
  (if-some [f (:error interceptor)]
    (let [{:keys [exception]} (::error ctx)]
      (try
        (-> (f (dissoc ctx ::error) exception)
            (check-instrumented interceptor :error))
        (catch Throwable t
          (assoc ctx ::error (error-map t interceptor :error)))))
    ctx))

(defn- rethrow-unhandled
  [{:keys [exception] :as error}]
  (let [{:keys [interceptor/name stage] :as data} (dissoc error :exception)]
    (throw (ex-info (str "Unhandled interceptor error: " (ex-message exception))
                    (assoc data :chain/unhandled? true)
                    exception))))

(defn execute
  "Run `interceptors` (when given) over `ctx` to completion and return
   the final ctx with engine keys removed. Rethrows unhandled errors."
  ([ctx]
   ;; Phase 1: enter. Pop queue, push stack, run :enter until queue
   ;; empty or an error is set. Stack is a vector; conj appends,
   ;; peek/pop give LIFO order for the leave walk.
   (let [entered (loop [ctx ctx]
                   (let [queue (::queue ctx)]
                     (if (or (::error ctx) (empty? queue))
                       ctx
                       (let [interceptor (peek queue)
                             ctx         (-> ctx
                                             (assoc ::queue (pop queue))
                                             (update ::stack (fnil conj []) interceptor))]
                         (recur (try-stage ctx interceptor :enter))))))
         ;; Phase 2: error walk. Only runs when ::error is set. Walks
         ;; the entire stack from top to bottom calling :error on every
         ;; interceptor. This gives every interceptor a chance to
         ;; handle or observe the error. If ::error is still set after
         ;; the walk, it is rethrown at the end.
         handled (if (::error entered)
                   (loop [ctx entered]
                     (let [stack (::stack ctx)]
                       (if (seq stack)
                         (let [top (peek stack)
                               ctx (-> ctx
                                       (assoc ::stack (pop stack))
                                       (try-error top))]
                           (recur ctx))
                         ctx)))
                   entered)
         ;; Phase 3: leave. Pop stack, run :leave. Errors during :leave
         ;; re-enter phase 2 (continuing the walk).
         unwound (loop [ctx handled]
                   (let [stack (::stack ctx)]
                     (if (seq stack)
                       (let [top (peek stack)
                             ctx (try-stage (assoc ctx ::stack (pop stack)) top :leave)]
                         (recur ctx))
                       ctx)))]
     (if-some [error (::error unwound)]
       (rethrow-unhandled error)
       (dissoc unwound ::queue ::stack ::error))))
  ([ctx interceptors]
   (execute (enqueue (assoc ctx ::stack []) interceptors))))
