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
          (let [html (slurp (str url "/"))]
            (is (re-find #"CHAT" html))
            (is (re-find #"PORTAL" html)))
          (finally
            (http/stop-server! server)))))))
