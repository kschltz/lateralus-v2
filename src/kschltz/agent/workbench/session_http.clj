(ns kschltz.agent.workbench.session-http
  "HTTP routes for workbench session CRUD. Network I/O stays in http.clj;
   this ns only maps requests onto session-ops callbacks."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn- json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status  status
    :headers {"Content-Type"                "application/json; charset=utf-8"
              "Access-Control-Allow-Origin" "*"}
    :body    (json/generate-string body)}))

(defn- session-id-from-path
  [path]
  (when-let [m (re-matches #"/api/sessions/([^/]+)(?:/(activate))?" (str path))]
    (second m)))

(defn- activate-path?
  [path]
  (boolean (re-matches #"/api/sessions/[^/]+/activate" (str path))))

(defn handle
  "Return a Ring response for session routes, or nil to fall through."
  [method path body {:keys [list-sessions create-session activate-session
                            rename-session delete-session]}]
  (when (and (str/starts-with? (str path) "/api/sessions")
             (or list-sessions create-session activate-session
                 rename-session delete-session))
    (try
      (cond
        (and (= method :get) (= path "/api/sessions"))
        (json-response {:sessions (or (list-sessions) [])})

        (and (= method :post) (= path "/api/sessions"))
        (json-response (create-session {:title (:title body)
                                        :id (:id body)}))

        (and (= method :post) (activate-path? path))
        (json-response (activate-session (session-id-from-path path)))

        (and (= method :patch) (session-id-from-path path)
             (not (activate-path? path)))
        (json-response (rename-session (session-id-from-path path)
                                       (:title body)))

        (and (= method :delete) (session-id-from-path path)
             (not (activate-path? path)))
        (json-response (delete-session (session-id-from-path path)))

        :else
        (json-response 404 {:error "not found" :path path}))
      (catch clojure.lang.ExceptionInfo e
        (json-response (if (re-find #"running|Switch sessions|last session"
                                    (or (ex-message e) ""))
                         409
                         400)
                       {:error (ex-message e)
                        :data  (ex-data e)})))))
