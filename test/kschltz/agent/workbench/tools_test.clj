(ns kschltz.agent.workbench.tools-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.workbench.hub :as hub]
            [kschltz.agent.workbench.protocol :as proto]
            [kschltz.agent.workbench.tools :as tools]))

(defn- fake-wb
  []
  (let [h (hub/create-hub {})]
    (reify proto/Workbench
      (-url [_] "http://127.0.0.1:0")
      (-portal-url [_] nil)
      (-publish! [_ event] (hub/publish-turn! h event))
      (-await-human! [_ _] {:text "x" :refs []})
      (-attach-selection! [_] nil)
      (-submit-portal! [_ label value]
        (hub/put-ref! h {:label label :preview (pr-str value) :value value}))
      (-clear-portal! [_] {:ok true})
      (-snapshot [_] (hub/snapshot h))
      (-tools [_] {})
      (-close! [_] nil))))

(deftest portal-submit-returns-cite
  (let [wb  (fake-wb)
        reg (tools/registry wb)
        out (tool/invoke-tool (get reg "portal/submit")
                              {:value {:n 1} :label "n"}
                              {})
        parsed (json/parse-string out true)
        id (get-in parsed [:ref :id])
        focus (json/parse-string
               (tool/invoke-tool (get reg "portal/focus") {:id id} {})
               true)]
    (is (true? (:ok parsed)))
    (is (string? id))
    (is (= (str "@portal/" id) (:cite parsed)))
    (is (str/includes? (:hint parsed) "Cite ONLY"))
    (is (true? (:ok focus)))
    (is (= id (get-in focus [:ref :id])))))

(deftest portal-submit-vega-becomes-html-viewer
  (let [reg (tools/registry (fake-wb))
        spec {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
              :mark "arc"
              :encoding {:theta {:field "v" :type "quantitative"}}
              :data {:values [{:v 1}]}}
        parsed (json/parse-string
                (tool/invoke-tool (get reg "portal/submit")
                                  {:value spec :label "pie" :kind "vega"}
                                  {})
                true)]
    (is (true? (:ok parsed)))
    (is (= "html" (:viewer parsed)))
    (is (str/starts-with? (:cite parsed) "@portal/"))))

(deftest portal-clear
  (let [reg (tools/registry (fake-wb))
        out (json/parse-string
             (tool/invoke-tool (get reg "portal/clear") {} {})
             true)]
    (is (true? (:ok out)))))
