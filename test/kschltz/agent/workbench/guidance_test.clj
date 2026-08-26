(ns kschltz.agent.workbench.guidance-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.workbench.guidance :as guidance]
            [kschltz.agent.workbench.protocol :as proto]
            [kschltz.agent.workbench.tools :as tools]))

(deftest guidance-mentions-portal-tools
  (is (re-find #"portal_submit" guidance/portal-system-guidance))
  (is (re-find #"portal_clear" guidance/portal-system-guidance))
  (is (re-find #"portal_focus" guidance/portal-system-guidance))
  (is (re-find #"(?i)mandatory|MUST" guidance/portal-system-guidance))
  (is (re-find #"(?i)HTML/SVG|:cite|optimistically" guidance/portal-system-guidance))
  (is (re-find #"(?i)Never invent|exact `:cite`" guidance/portal-system-guidance)))

(deftest guidance-mentions-self-update-playbook
  (is (re-find #"reload_runtime" guidance/self-update-system-guidance))
  (is (re-find #"clojure_add_lib" guidance/self-update-system-guidance))
  (is (re-find #"Do not announce a plan" guidance/self-update-system-guidance)))

(deftest submit-tool-description-stresses-portal-channel
  (let [wb (reify proto/Workbench
             (-url [_] "")
             (-portal-url [_] nil)
             (-publish! [_ _])
             (-await-human! [_ _] {})
             (-attach-selection! [_] nil)
             (-submit-portal! [_ _ _] {:id "x" :preview "p"})
             (-clear-portal! [_] {:ok true})
             (-snapshot [_] {})
             (-tools [_] {})
             (-close! [_] nil))
        t (get (tools/registry wb) "portal_submit")
        d (tool/-description t)]
    (is (re-find #"PRIMARY visualization" d))
    (is (re-find #"(?i):cite|HTML/SVG|optimistically" d))
    (is (re-find #"(?i)Never invent" d))))
