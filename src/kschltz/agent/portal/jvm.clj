(ns kschltz.agent.portal.jvm
  "Portal-backed `AgentUi` implementation.

   Requires the optional `:portal` deps alias (`djblue/portal`). Opens a
   Portal window on the session transcript atom, loads the CLJS sticky
   composer viewer via `:on-load`, and registers `reply!` for UI → host RPC."
  (:require [clojure.java.io :as io]
            [kschltz.agent.portal.protocol :as proto]
            [kschltz.agent.portal.session :as session]))

(def reply-command-name
  "Stable symbol the CLJS viewer invokes via `portal.ui.rpc/call`."
  'kschltz.agent.portal/reply!)

(defn reply!
  "Host command registered with Portal. Enqueues human composer text
   into the parked UI session inbox."
  [reply]
  (session/enqueue-reply! reply))

(defn- load-viewer!
  "Eval the sticky-composer viewer into the Portal UI cljs runtime.
   Must run after the UI has connected (`:on-load`); calling it during
   `open` blocks up to Portal's 60s RPC timeout."
  [portal]
  (let [eval-str (requiring-resolve 'portal.api/eval-str)
        src      (slurp (io/resource "kschltz/agent/portal/viewer.cljs"))]
    (when-not src
      (throw (ex-info "Missing Portal viewer resource"
                      {:resource "kschltz/agent/portal/viewer.cljs"})))
    (let [result (eval-str portal src {:verbose true})]
      (when (:error result)
        (throw (ex-info "Portal viewer eval-str failed"
                        {:result result})))
      result)))

(defn- open-portal!
  [transcript opts]
  (let [open      (requiring-resolve 'portal.api/open)
        register! (requiring-resolve 'portal.runtime/register!)]
    ;; portal.api/register! is 1-arity; runtime/register! accepts opts :name
    ;; so the CLJS viewer can call a stable symbol.
    (register! #'reply! {:name reply-command-name})
    (let [portal-ref (atom nil)
          on-load    (fn []
                       (try
                         (when-let [p @portal-ref]
                           (load-viewer! p))
                         (catch Throwable t
                           (binding [*out* *err*]
                             (println "lateralus portal: viewer load failed:"
                                      (ex-message t))
                             (when-let [d (ex-data t)]
                               (println "lateralus portal: viewer load data:"
                                        (pr-str d)))))))
          portal     (open
                      (cond-> {:value        transcript
                               :window-title (or (:window-title opts) "lateralus")
                               :on-load      on-load}
                        (contains? opts :app) (assoc :app (:app opts))
                        (:theme opts)         (assoc :theme (:theme opts))))]
      ;; Bind before UI connects so :on-load can see the session.
      ;; Do NOT eval-str here — that RPCs the UI and hangs until connect
      ;; (or 60s timeout).
      (reset! portal-ref portal)
      (when-let [url-fn (requiring-resolve 'portal.api/url)]
        (try
          (binding [*out* *err*]
            (println "lateralus portal UI:" (url-fn portal))
            (println "lateralus portal: use the sticky composer, or type in this terminal (/quit to exit)"))
          (catch Throwable _)))
      portal)))

(defrecord PortalUi [session portal]
  proto/AgentUi
  (-publish! [_ event]
    (proto/-publish! session event))
  (-await-human! [_ opts]
    (proto/-await-human! session opts))
  (-close! [_]
    (proto/-close! session)
    (when portal
      (try
        ((requiring-resolve 'portal.api/close) portal)
        (catch Throwable _)))
    nil))

(defn start!
  "Create a Portal UI session and open the window. opts:
     :session-id   string
     :window-title string
     :theme        keyword
     :app          boolean
     :await-ms     long (0 = indefinite park)
     :open?        boolean (default true; false for tests)"
  [opts]
  (let [opts    (or opts {})
        sess    (session/create-session opts)
        portal  (when-not (false? (:open? opts))
                  (open-portal! (session/transcript-atom sess) opts))]
    (->PortalUi sess portal)))

(defn available?
  "True when djblue/portal is on the classpath."
  []
  (try
    (requiring-resolve 'portal.api/open)
    true
    (catch Throwable _ false)))
