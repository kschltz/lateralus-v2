(ns kschltz.agent.workbench.http-test
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.stream :as llm.stream]
            [kschltz.agent.stream.bus :as stream.bus]
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
                "JS remaps Portal iframe onto the CHAT origin")
            (is (re-find #"focusComposer" js)
                "JS restores chat caret after send / turn complete")
            (is (re-find #"inputEl\.readOnly" js)
                "composer uses readOnly while busy so focus can stick"))
          (finally
            (http/stop-server! server)))))))

(deftest ^:workbench sse-events-stream-snapshot-after-rev-bump
  ;; Regression: /api/events used to 500 with
  ;; `Wrong number of args (2) passed to: org.httpkit.server/with-channel`
  ;; because handle-sse `requiring-resolve`d the `with-channel` macro and
  ;; called it as a function. Verify SSE now opens, streams a `data:` event
  ;; carrying a snapshot, and the snapshot reflects a published turn.
  (when (available?)
    (let [h       (hub/create-hub {:session-id "sse-test"})
          handler (http/make-handler h {})
          {:keys [server url]} (http/start-server!
                                {:host "127.0.0.1" :port 0 :handler handler})]
      (try
        (let [port   (Long/parseLong (re-find #"\d+$" url))
              events (str "http://127.0.0.1:" port "/api/events?since=0")
              conn   (doto (-> (java.net.URL. events) (.openConnection))
                       (.setConnectTimeout 2000)
                       (.setReadTimeout    2000)
                       (.setRequestProperty "Accept" "text/event-stream"))
              status (.getResponseCode ^java.net.HttpURLConnection conn)
              ctype  (.getHeaderField ^java.net.HttpURLConnection conn "Content-Type")]
          (is (= 200 status) "SSE endpoint must return 200 (not 500)")
          (is (re-find #"text/event-stream" (or ctype ""))
              "SSE must advertise text/event-stream")
          ;; The sse-loop emits a snapshot whenever hub :rev advances. Bump
          ;; rev by publishing a turn, then read the first `data:` line.
          (hub/publish-turn! h {:role :system :text "hello sse"})
          (with-open [rdr (java.io.BufferedReader.
                           (java.io.InputStreamReader.
                            (.getInputStream ^java.net.HttpURLConnection conn)))]
            (let [line (loop [n 0]
                         (let [l (.readLine rdr)]
                           (cond
                             (nil? l)                   nil
                             (string/starts-with? l "data:") l
                             (< n 4000)                 (recur (inc n))
                             :else                      nil)))]
              (is (some? line) "expected at least one `data:` event before read timeout")
              (when line
                (let [payload (json/parse-string (subs line 6) true)]
                  (is (= "sse-test" (:session-id payload))
                      "SSE snapshot carries the hub session-id")
                  (is (pos? (long (:rev payload 0)))
                      "snapshot rev advances after a publish"))))))
        (finally
          (http/stop-server! server))))))

(deftest turn-details-routes
  (let [b (stream.bus/create-bus)
        id (stream.bus/open-turn! b {:user-text "draw"})
        _ (stream.bus/emit! b id (llm.stream/event :text-delta {:text "pad"}))
        _ (stream.bus/close-turn! b id :done {})
        h (hub/create-hub {:session-id "turn-http" :stream-bus b})
        handler (http/make-handler h {})
        page (handler {:request-method :get :uri (str "/turn/" id)})
        api  (handler {:request-method :get :uri (str "/api/turns/" id)})
        miss (handler {:request-method :get :uri "/api/turns/missing"})
        body (json/parse-string (:body api) true)]
    (is (= 200 (:status page)))
    (is (re-find #"response details" (String. ^bytes (:body page) "UTF-8")))
    (is (re-find #"events-wrap" (String. ^bytes (:body page) "UTF-8")))
    (is (= 200 (:status api)))
    (is (= "pad" (:text body)))
    (is (= "draw" (:user-text body)))
    (is (= "done" (:status body)))
    (is (= 404 (:status miss)))))

(deftest current-and-live-turn-routes
  (let [b (stream.bus/create-bus)
        id (stream.bus/open-turn! b {:user-text "now"})
        h (hub/create-hub {:session-id "live-http" :stream-bus b})
        handler (http/make-handler h {})
        cur (handler {:request-method :get :uri "/api/turns/current"})
        page (handler {:request-method :get :uri "/turn/live"})
        body (json/parse-string (:body cur) true)]
    (is (= 200 (:status cur)))
    (is (= id (:id body)))
    (is (true? (:live? body)))
    (is (= 200 (:status page)))
    (stream.bus/close-turn! b id :done {})
    (let [after (json/parse-string
                 (:body (handler {:request-method :get :uri "/api/turns/current"}))
                 true)]
      (is (= id (:id after)))
      (is (false? (:live? after))))))

(deftest handler-portal-event
  "The 2-way loop over HTTP: an artifact's fetch POST reaches the hub
   inbox and transcript; bad payloads are 400s, not 500s."
  (let [h (hub/create-hub {:session-id "pe-http-test"})
        handler (http/make-handler h {})]
    (let [res (handler {:request-method :post :uri "/api/portal-event"
                        :body (json/generate-string {:payload {:control "slider" :value 42}})})]
      (is (= 200 (:status res)))
      (is (true? (-> res :body (json/parse-string true) :ok))))
    (let [msg (hub/await-human! h {:timeout-ms 2000})]
      (is (str/starts-with? (:text msg) "⟨portal-event⟩"))
      (is (str/includes? (:text msg) "slider")))
    (let [res (handler {:request-method :post :uri "/api/portal-event"
                        :body (json/generate-string "not-a-map")})]
      (is (= 400 (:status res))))
    (let [res (handler {:request-method :post :uri "/api/portal-event"
                        :body (json/generate-string {:blob (vec (range 5000))})})]
      (is (= 400 (:status res))))))
