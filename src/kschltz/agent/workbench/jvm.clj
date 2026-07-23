(ns kschltz.agent.workbench.jvm
  "Workbench plugin host: HTTP chat UI + Portal visualizer + agent tools.

   Requires the optional `:workbench` (or `:portal`) deps alias."
  (:require [kschltz.agent.workbench.hub :as hub]
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

(defrecord WorkbenchImpl [hub portal viz-atom portal-url http-server tools-map]
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
    (let [coerced (portal/coerce-value value)
          _       (when portal
                    (portal/submit! portal viz-atom label coerced))
          ref     (hub/put-ref! hub {:label   label
                                     :preview (preview-of coerced)
                                     :value   coerced})]
      (hub/publish-turn! hub {:role :portal-ref
                              :text (str "portal/" (:id ref)
                                         (when label (str " " label)))
                              :refs [ref]})
      ref))
  (-clear-portal! [_]
    (portal/clear! portal viz-atom)
    {:ok true})
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
        hub         (hub/create-hub opts)
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
        record      (->WorkbenchImpl hub portal* viz-atom portal-url server tools-map)]
    (reset! wb-ref record)
    (reset! handler
            (http/make-handler
             hub
             {:attach-selection!
              (fn [] (proto/-attach-selection! @wb-ref))}))
    (hub/publish-turn! hub
                       {:role :system
                        :text (str "Workbench ready — CHAT left | PORTAL right ("
                                   (:url server)
                                   "). Agents should optimistically use "
                                   "portal/submit for HTML, tables, charts, "
                                   "and other rich visuals (chat stays thin).")})
    (binding [*out* *err*]
      (println "lateralus workbench:" (:url server))
      (when portal-url
        (println "lateralus workbench portal iframe:" portal-url)))
    (when-not (false? (:open-browser? opts))
      (open-browser! (:url server)))
    record))
