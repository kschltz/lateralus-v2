(ns kschltz.agent.workbench.secrets-http-test
  "Tests for the secrets management HTTP surface.

   The critical invariant: the API never serves a value back. GET
   returns labels only; the plaintext of a stored secret must never
   appear in any response body, even for the label that was just put."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.secrets :as secrets]
            [kschltz.agent.workbench.hub :as hub]
            [kschltz.agent.workbench.http :as http]
            [kschltz.agent.workbench.secrets-http :as secrets-http]))

(def ^:private store
  (secrets/sealed-file-store
   {:path (str (System/getProperty "java.io.tmpdir")
               "/lat-wb-secrets-" (System/currentTimeMillis) ".sealed")
    :passphrase "ui-test-passphrase"
    :kdf-iterations 1000}))

(defn- reset-store!
  [test-fn]
  (doseq [label (secrets/-secret-labels store)]
    (secrets/-delete-secret! store label))
  (try
    (test-fn)
    (finally
      (doseq [label (secrets/-secret-labels store)]
        (secrets/-delete-secret! store label)))))

(use-fixtures :each reset-store!)

(defn- handler-with-secrets []
  (http/make-handler (hub/create-hub {:session-id "secrets-test"})
                     {:secret-ops
                      {:view-fn   #(secrets-http/secrets-view store)
                       :put-fn    #(secrets-http/put-secret! store %)
                       :delete-fn #(secrets-http/delete-secret! store %)}}))

(defn- same-origin
  [req]
  (update req :headers merge
          {"host" "127.0.0.1:7860"
           "origin" "http://127.0.0.1:7860"}))

(deftest handler-secrets-api
  (testing "empty store lists no labels; values are never present"
    (let [res  (handler-with-secrets)
          view (json/parse-string (:body (res {:request-method :get :uri "/api/secrets"})) true)]
      (is (= 200 (:status ((handler-with-secrets) {:request-method :get :uri "/api/secrets"}))))
      (is (true? (:enabled view)))
      (is (empty? (:labels view)))))
  (testing "PUT stores the secret and the value never echoes back"
    (let [handler (handler-with-secrets)
          res (handler
               (same-origin
                {:request-method :put :uri "/api/secrets"
                 :body (json/generate-string
                        {:session-id "secrets-test"
                         :label "ui-api-key"
                         :value "hunter2-secret-42"})}))
          body (json/parse-string (:body res) true)]
      (is (= 200 (:status res)))
      (is (:ok body))
      (is (not (str/includes? (:body res) "hunter2-secret-42")))
      (is (= ["ui-api-key"] (vec (:labels (json/parse-string (:body (handler {:request-method :get :uri "/api/secrets"})) true))))))
    ;; and the value IS in store (substitutable via handle)
    (is (= "hunter2-secret-42" (get (secrets/substitute-handles store {"x" "{{secret:ui-api-key}}"}) "x"))))
  (testing "DELETE removes it"
    (let [handler (handler-with-secrets)
          res (handler
               (same-origin
                {:request-method :delete
                 :uri "/api/secrets?label=ui-api-key&session-id=secrets-test"}))
          body (json/parse-string (:body res) true)]
      (is (= 200 (:status res)))
      (is (:ok body))
      (is (empty? (vec (:labels (json/parse-string (:body (handler {:request-method :get :uri "/api/secrets"})) true))))))))

(deftest handler-secrets-validation
  (let [handler (handler-with-secrets)]
    (testing "invalid label rejected with 400"
      (let [res (handler
                 (same-origin
                  {:request-method :put :uri "/api/secrets"
                   :body (json/generate-string
                          {:session-id "secrets-test"
                           :label "bad label!"
                           :value "v"})}))]
        (is (= 400 (:status res)))
        (is (str/includes? (:body res) "invalid label"))))
    (testing "blank value rejected"
      (let [res (handler
                 (same-origin
                  {:request-method :put :uri "/api/secrets"
                   :body (json/generate-string
                          {:session-id "secrets-test"
                           :label "ok"
                           :value ""})}))]
        (is (= 400 (:status res))))))
  (testing "no secret-ops configured → routes inert (no 500)"
    (let [h2 (http/make-handler (hub/create-hub {:session-id "x"}) {})
          res (h2 {:request-method :get :uri "/api/secrets"})]
      (is (not (= 500 (:status res)))))))

(deftest secret-mutations-require-same-origin-session-affinity
  (let [handler (handler-with-secrets)
        base {:request-method :put
              :uri "/api/secrets"
              :body (json/generate-string
                     {:session-id "secrets-test"
                      :label "guarded"
                      :value "guarded-secret-123"})}]
    (is (= 403 (:status (handler base)))
        "missing Origin is rejected")
    (is (= 403
           (:status
            (handler
             (assoc base :headers
                    {"host" "127.0.0.1:7860"
                     "origin" "https://attacker.example"})))))
    (is (= 409
           (:status
            (handler
             (same-origin
              (assoc base :body
                     (json/generate-string
                      {:session-id "stale-session"
                       :label "guarded"
                       :value "guarded-secret-123"})))))))
    (let [res (handler (same-origin base))]
      (is (= 200 (:status res)))
      (is (nil? (get-in res [:headers "Access-Control-Allow-Origin"]))))))
