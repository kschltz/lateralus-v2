(ns kschltz.agent.workbench.jvm
  "Workbench plugin host: HTTP chat UI + Portal visualizer + agent tools.

   Requires the optional `:workbench` (or `:portal`) deps alias."
  (:require [kschltz.agent.runtime :as runtime]
            [kschltz.agent.session.manager :as sessions]
            [kschltz.agent.session.store :as session.store]
            [kschltz.agent.workbench.hub :as hub]
            [kschltz.agent.workbench.http :as http]
            [kschltz.agent.workbench.portal :as portal]
            [kschltz.agent.workbench.protocol :as proto]
            [kschltz.agent.workbench.schemas :as schemas]
            [kschltz.agent.workbench.tools :as tools])
  (:import [java.awt Desktop]
           [java.net URI]))

(defn available?
  "True when http-kit is on the classpath."
  []
  (try
    (requiring-resolve 'org.httpkit.server/run-server)
    true
    (catch Throwable _ false)))

(defn- open-browser!
  [url]
  (try
    (when (Desktop/isDesktopSupported)
      (.browse (Desktop/getDesktop) (URI. (str url))))
    (catch Throwable _)))

(defn- preview-of [value]
  (let [s (pr-str value)]
    (if (> (count s) 160)
      (str (subs s 0 157) "...")
      s)))

(defrecord WorkbenchImpl [hub portal viz-atom portal-url http-server tools-map session-store runtime-atom]
  proto/Workbench
  (-url [_]
    (:url http-server))
  (-portal-url [_]
    portal-url)
  (-publish! [_ event]
    (hub/publish-turn! hub event))
  (-await-human! [_ opts]
    (hub/await-human! hub opts))
  (-attach-selection! [_]
    (let [sel (portal/selected portal)]
      (when (some? sel)
        (hub/put-ref! hub {:label   "selection"
                           :preview (preview-of sel)
                           :value   sel}))))
  (-submit-portal! [_ label value]
    ;; `value` is expected pre-prepared by portal tools (kind applied).
    (when portal
      (let [sub (portal/submit! portal viz-atom label value {:prepared? true})]
        (when-not (:ok sub)
          (throw (ex-info (str (:error sub)) sub)))))
    (let [ref (hub/put-ref! hub {:label   label
                                 :preview (preview-of value)
                                 :value   value
                                 :viewer  (portal/detect-viewer value)})]
      (hub/publish-turn! hub {:role :portal-ref
                              :text (str "portal/" (:id ref)
                                         (when label (str " " label)))
                              :refs [ref]})
      ref))
  (-clear-portal! [_]
    (portal/clear! portal viz-atom)
    {:ok true})
  (-portal-selection [_]
    (portal/selection portal))
  (-snapshot [_]
    (hub/snapshot hub))
  (-tools [_]
    tools-map)
  (-close! [_]
    (http/stop-server! (:server http-server))
    (portal/close! portal)
    nil))

(defn start!
  "Start the workbench plugin. opts — see `schemas/WorkbenchConfig`.

   Returns a `Workbench` record. When `:portal?` is true (default) and
   Portal is on the classpath, opens Portal for the iframe pane."
  [opts]
  (let [opts        (schemas/decode-config (or opts {}))
        sess-store  (session.store/create-store
                     (or (:sessions-dir opts) "sessions/workbench"))
        session-id  (or (:session-id opts) (str (random-uuid)))
        _           (sessions/ensure! sess-store {:id session-id :title session-id})
        runtime-atom (atom nil)
        hub         (hub/create-hub (assoc (select-keys opts [:stream-bus])
                                           :session-id session-id
                                           :session-title session-id
                                           :session-store sess-store))
        use-portal? (and (not (false? (:portal? opts)))
                         (portal/available?))
        portal-info (when (and use-portal? (not (false? (:open? opts))))
                      (portal/open! opts))
        portal*     (:portal portal-info)
        viz-atom    (:viz-atom portal-info)
        portal-url  (:url portal-info)
        _           (when portal-url
                      (hub/set-portal-url! hub portal-url))
        wb-ref      (atom nil)
        handler     (atom (fn [_]
                            {:status  503
                             :headers {"Content-Type" "text/plain"}
                             :body    "workbench starting"}))
        server      (http/start-server!
                     {:host    (or (:host opts) "127.0.0.1")
                      :port    (or (:port opts) 7860)
                      :handler (fn [req] (@handler req))})
        tools-map   (tools/registry wb-ref)
        record      (->WorkbenchImpl hub portal* viz-atom portal-url server tools-map
                                     sess-store runtime-atom)]
    (reset! wb-ref record)
    (reset! handler
            (http/make-handler
             hub
             {:attach-selection!
              (fn [] (proto/-attach-selection! @wb-ref))
              :session-ops
              {:list-sessions     #(sessions/list-sessions sess-store)
               :create-session    #(sessions/create! sess-store hub @runtime-atom %)
               :activate-session  #(sessions/activate! sess-store hub @runtime-atom %)
               :rename-session    #(sessions/rename! sess-store hub %1 %2)
               :delete-session    #(sessions/delete! sess-store hub %)}
              :settings-ops
              {:view-fn   (fn []
                            (when-let [r @runtime-atom]
                              (require 'kschltz.agent.workbench.settings-http)
                              ((resolve 'kschltz.agent.workbench.settings-http/settings-view) r)))
               :apply-fn  (fn [op]
                            (if-let [r @runtime-atom]
                              (do (require 'kschltz.agent.workbench.settings-http)
                                  ((resolve 'kschltz.agent.workbench.settings-http/apply-op!)
                                   hub r op))
                              {:ok false :error "runtime not attached yet"}))
               :models-fn (fn [q]
                            (if-let [r @runtime-atom]
                              (do (require 'kschltz.agent.workbench.settings-http)
                                  ((resolve 'kschltz.agent.workbench.settings-http/list-models) r q))
                              {:models [] :error "runtime not attached yet"}))
               :secret-ops
               (let [store (:secret-store opts)]
                 {:view-fn   (fn []
                               (require 'kschltz.agent.workbench.secrets-http)
                               ((resolve 'kschltz.agent.workbench.secrets-http/secrets-view) store))
                  :put-fn    (fn [op]
                               (require 'kschltz.agent.workbench.secrets-http)
                               ((resolve 'kschltz.agent.workbench.secrets-http/put-secret!) store op))
                  :delete-fn (fn [label]
                               (require 'kschltz.agent.workbench.secrets-http)
                               ((resolve 'kschltz.agent.workbench.secrets-http/delete-secret!) store label))})}}))
    (sessions/persist-current! sess-store hub nil)
    (hub/publish-turn! hub
                       {:role :system
                        :text (str "Workbench ready — CHAT left | PORTAL right ("
                                   (:url server)
                                   "). Agents should optimistically use "
                                   "portal_submit for HTML/SVG charts, tables, "
                                   "and other rich visuals; cite only :cite "
                                   "from the tool (chat stays thin).")})
    (binding [*out* *err*]
      (println "lateralus workbench:" (:url server))
      (when portal-url
        (println "lateralus workbench portal iframe:" portal-url)))
    (when-not (false? (:open-browser? opts))
      (open-browser! (:url server)))
    record))

(defn attach-runtime!
  "Bind the live AgentRuntime so session switch also swaps memory/state."
  [workbench runtime]
  (when-let [a (:runtime-atom workbench)]
    (reset! a runtime)
    (when-let [store (:session-store workbench)]
      (sessions/attach! store (:hub workbench) runtime
                        (runtime/session-id runtime))))
  workbench)
