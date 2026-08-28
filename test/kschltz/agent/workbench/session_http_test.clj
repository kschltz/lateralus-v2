(ns kschltz.agent.workbench.session-http-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.workbench.http :as http]
            [kschltz.agent.workbench.hub :as hub]))

(defn- parse [resp]
  (json/parse-string (:body resp) true))

(deftest session-routes
  (let [sessions (atom [{:id "a" :title "A" :active? true}])
        ops {:list-sessions #(deref sessions)
             :create-session (fn [{:keys [title]}]
                               (let [s {:id "b" :title (or title "B") :active? true}]
                                 (reset! sessions [s])
                                 s))
             :activate-session (fn [id]
                                 (reset! sessions
                                         (mapv #(assoc % :active? (= id (:id %)))
                                               @sessions))
                                 {:id id :title id :active? true})
             :rename-session (fn [id title]
                               {:id id :title title :active? true})
             :delete-session (fn [id] {:ok true :id id})}
        h (hub/create-hub {:session-id "a"})
        handler (http/make-handler h {:session-ops ops})
        listed (handler {:request-method :get :uri "/api/sessions"})
        created (handler {:request-method :post :uri "/api/sessions"
                          :body "{\"title\":\"Sketch\"}"})
        switched (handler {:request-method :post :uri "/api/sessions/a/activate"})
        renamed (handler {:request-method :patch :uri "/api/sessions/a"
                          :body "{\"title\":\"Alpha\"}"})
        deleted (handler {:request-method :delete :uri "/api/sessions/b"})]
    (is (= 200 (:status listed)))
    (is (= "A" (:title (first (:sessions (parse listed))))))
    (is (= "Sketch" (:title (parse created))))
    (is (= "a" (:id (parse switched))))
    (is (= "Alpha" (:title (parse renamed))))
    (is (true? (:ok (parse deleted))))))
