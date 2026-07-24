(ns kschltz.agent.workbench.http-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.workbench.hub :as hub]
            [kschltz.agent.workbench.http :as http]))

(defn- available?
  []
  (try
    (requiring-resolve 'org.httpkit.server/run-server)
    true
    (catch Throwable _ false)))

(deftest request-hostname-strips-port
  (is (= "machine.tailnet.ts.net"
         (http/request-hostname
          {:headers {"host" "machine.tailnet.ts.net:7860"}})))
  (is (= "127.0.0.1"
         (http/request-hostname {:headers {"Host" "127.0.0.1:7860"}})))
  (is (= "::1"
         (http/request-hostname {:headers {"host" "[::1]:7860"}})))
  (is (= "localhost"
         (http/request-hostname {:headers {"host" "localhost"}})))
  (is (nil? (http/request-hostname {:headers {}}))))

(deftest rewrite-url-host-keeps-portal-port
  (is (= "http://machine.tailnet.ts.net:7870/"
         (http/rewrite-url-host "http://127.0.0.1:7870/"
                                "machine.tailnet.ts.net")))
  (is (= "http://machine.tailnet.ts.net:7870/ui?x=1"
         (http/rewrite-url-host "http://localhost:7870/ui?x=1"
                                "machine.tailnet.ts.net")))
  (is (= "http://127.0.0.1:7870"
         (http/rewrite-url-host "http://127.0.0.1:7870" ""))))

(deftest portal-session-id-and-same-origin-url
  (let [sid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"]
    (is (= sid (http/portal-session-id (str "http://127.0.0.1:7870?" sid))))
    (is (nil? (http/portal-session-id "http://127.0.0.1:7870")))
    (is (http/portal-session-query? (str "/?" sid)))
    (is (http/portal-session-query? "/" sid)
        "http-kit puts the UUID in :query-string")
    (is (not (http/portal-session-query? "/")))
    (is (not (http/portal-session-query? "/?foo=bar")))
    (is (= (str "http://box.tailnet.ts.net:7860/?" sid)
           (http/portal-url-for-request
            (str "http://127.0.0.1:7870?" sid)
            {:headers {"host" "box.tailnet.ts.net:7860"}})))))

(deftest portal-path-routing
  (let [sid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"]
    (is (http/portal-path? :get "/main.js" "/main.js"))
    (is (http/portal-path? :get "/rpc" "/rpc"))
    (is (http/portal-path? :post "/load" "/load"))
    (is (http/portal-path? :get "/" (str "/?" sid)))
    (is (http/portal-path? :get "/" "/" sid))
    (is (not (http/portal-path? :get "/" "/" nil)))
    (is (not (http/portal-path? :get "/app.js" "/app.js")))
    (is (not (http/portal-path? :get "/api/state" "/api/state")))))

(deftest handler-message-and-state
  (let [h (hub/create-hub {:session-id "http-test"})
        attached (atom nil)
        handler (http/make-handler
                 h
                 {:attach-selection!
                  (fn []
                    (let [ref (hub/put-ref! h {:label "sel"
                                               :preview "42"
                                               :value 42})]
                      (reset! attached ref)
                      ref))})
        state1 (handler {:request-method :get :uri "/api/state"})
        body1  (json/parse-string (:body state1) true)
        post   (handler {:request-method :post
                         :uri "/api/message"
                         :body "{\"text\":\"hello\",\"refs\":[]}"})
        attach (handler {:request-method :post :uri "/api/attach-selection"})]
    (is (= 200 (:status state1)))
    (is (= "http-test" (:session-id body1)))
    (is (= 200 (:status post)))
    (is (= "hello" (:text (hub/await-human! h {:timeout-ms 500}))))
    (is (= 200 (:status attach)))
    (is (string? (:id @attached)))))

(deftest api-state-rewrites-portal-url-to-chat-origin
  (let [sid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        h (hub/create-hub {:session-id "tailscale-test"})
        _ (hub/set-portal-url! h (str "http://127.0.0.1:7870?" sid))
        handler (http/make-handler h {})
        res (handler {:request-method :get
                      :uri "/api/state"
                      :headers {"host" "box.tailnet.ts.net:7860"}})
        body (json/parse-string (:body res) true)]
    (is (= 200 (:status res)))
    (is (= (str "http://box.tailnet.ts.net:7860/?" sid) (:portal-url body))
        "iframe must use CHAT origin/port, not private :7870")))

(deftest ^:workbench start-server-serves-index
  (when (available?)
    (testing "static index"
      (let [h (hub/create-hub {})
            handler (http/make-handler h {})
            {:keys [server url]} (http/start-server!
                                  {:host "127.0.0.1"
                                   :port 0
                                   :handler handler})]
        (try
          (is (re-find #"^http://127\.0\.0\.1:\d+$" url))
          (let [html (slurp (str url "/"))
                css  (slurp (str url "/app.css"))
                js   (slurp (str url "/app.js"))]
            (is (re-find #"CHAT" html))
            (is (re-find #"PORTAL" html))
            (is (re-find #"id=\"mobile-tabs\"" html)
                "mobile viewers get a Chat/Portal tab bar")
            (is (re-find #"viewport-fit=cover" html))
            (is (re-find #"data-mobile-pane" css)
                "CSS switches single-pane layout on narrow viewports")
            (is (re-find #"mobilePane|mobile-pane|setMobilePane" js)
                "JS drives the mobile pane switcher")
            (is (re-find #"browserPortalUrl" js)
                "JS remaps Portal iframe onto the CHAT origin"))
          (finally
            (http/stop-server! server)))))))
