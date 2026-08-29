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
        out (tool/invoke-tool (get reg "portal_submit")
                              {:value {:n 1} :label "n"}
                              {})
        parsed (json/parse-string out true)
        id (get-in parsed [:ref :id])
        focus (json/parse-string
               (tool/invoke-tool (get reg "portal_focus") {:id id} {})
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
                (tool/invoke-tool (get reg "portal_submit")
                                  {:value spec :label "pie" :kind "vega"}
                                  {})
                true)]
    (is (true? (:ok parsed)))
    (is (= "html" (:viewer parsed)))
    (is (str/starts-with? (:cite parsed) "@portal/"))))

(deftest portal-clear
  (let [reg (tools/registry (fake-wb))
        out (json/parse-string
             (tool/invoke-tool (get reg "portal_clear") {} {})
             true)]
    (is (true? (:ok out)))))

(deftest portal-selected-reads-selection-back
  (let [h  (hub/create-hub {})
        wb (reify proto/Workbench
             (-url [_] "u")
             (-portal-url [_] nil)
             (-publish! [_ _])
             (-await-human! [_ _])
             (-attach-selection! [_] nil)
             (-submit-portal! [_ _ _])
             (-clear-portal! [_] {:ok true})
             (-portal-selection [_]
               {:last {:answer 42}
                :selected [{:a 1} {:b 2}]})
             (-snapshot [_] {})
             (-tools [_] {})
             (-close! [_] nil))
        reg (tools/registry wb)
        out (json/parse-string
             (tool/invoke-tool (get reg "portal_selected") {} {})
             true)]
    (is (contains? reg "portal_selected") "registry exposes the read-back tool")
    (is (true? (:ok out)))
    (is (= 2 (:count out)))
    (is (= "{:answer 42}" (:edn (:last out))))
    (is (= ["{:a 1}" "{:b 2}"] (:selected out)))))

(deftest portal-selected-truncates-and-handles-empty
  (let [big (vec (range 500))
        wb  (reify proto/Workbench
              (-portal-selection [_]
                {:last {:big big}
                 :selected (vec (repeat 30 {:x 1}))})
              (-url [_] "u") (-portal-url [_] nil)
              (-publish! [_ _]) (-await-human! [_ _])
              (-attach-selection! [_]) (-submit-portal! [_ _ _])
              (-clear-portal! [_]) (-snapshot [_])
              (-tools [_]) (-close! [_]))
        reg (tools/registry wb)
        out (json/parse-string
             (tool/invoke-tool (get reg "portal_selected") {:limit 1000} {})
             true)]
    (is (true? (:ok out)))
    (is (true? (:truncated out)))
    (is (= 20 (count (:selected out))))
    (is (str/includes? (:hint out) "select fewer"))
    (is (<= (count (:edn (:last out))) 100))
    ;; empty selection degrades to {:ok false} without throwing
    (let [empty-wb (reify proto/Workbench
                     (-portal-selection [_] {:last nil :selected []})
                     (-url [_] "u") (-portal-url [_] nil)
                     (-publish! [_ _]) (-await-human! [_ _])
                     (-attach-selection! [_]) (-submit-portal! [_ _ _])
                     (-clear-portal! [_]) (-snapshot [_])
                     (-tools [_]) (-close! [_]))]
      (is (false? (:ok (json/parse-string
                        (tool/invoke-tool
                         (get (tools/registry empty-wb) "portal_selected")
                         {} {})
                        true)))))))

(deftest portal-selection-nil-tolerant
  (require 'kschltz.agent.workbench.portal)
  (let [out ((ns-resolve 'kschltz.agent.workbench.portal 'selection) nil)]
    (is (= {:last nil :selected []} out))))
