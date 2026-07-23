(ns kschltz.agent.workbench.portal-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.workbench.portal :as portal]))

(deftest coerce-value-parses-json-strings
  (is (= [{:a 1}] (portal/coerce-value "[{\"a\":1}]")))
  (is (= {:a 1} (portal/coerce-value {:a 1})))
  (is (= "plain" (portal/coerce-value "plain"))))

(deftest coerce-value-keeps-html-strings
  (let [html "<!DOCTYPE html><html><body><div class=\"x\">hi</div></body></html>"]
    (is (= html (portal/coerce-value html))))
  (is (= "<div style=\"color:red\">x</div>"
         (portal/coerce-value "<div style=\"color:red\">x</div>"))))

(deftest with-default-viewer-picks-rich-surfaces
  (testing "table rows"
    (let [v (portal/with-default-viewer [{:a 1} {:a 2}])]
      (is (= :portal.viewer/table (:portal.viewer/default (meta v))))))
  (testing "html uses portal.viewer/html (may wrap non-IObj strings)"
    (let [html "<div class=\"card\">hello</div>"
          v (portal/with-default-viewer html)]
      (is (or (= :portal.viewer/html (:portal.viewer/default (meta v)))
              (and (vector? v)
                   (some #{:portal.viewer/html}
                         (tree-seq coll? seq v)))))))
  (testing "markdown"
    (let [md "# Title\n\nSome **bold** text."
          v (portal/with-default-viewer md)]
      (is (or (= :portal.viewer/markdown (:portal.viewer/default (meta v)))
              (and (vector? v)
                   (some #{:portal.viewer/markdown}
                         (tree-seq coll? seq v)))))))
  (testing "hiccup"
    (let [v (portal/with-default-viewer [:div {:style {:color "red"}} "hi"])]
      (is (= :portal.viewer/hiccup (:portal.viewer/default (meta v))))))
  (testing "vega-lite"
    (let [spec {:mark "bar" :encoding {:x {:field "a"} :y {:field "b"}}}
          v (portal/with-default-viewer spec)]
      (is (= :portal.viewer/vega-lite (:portal.viewer/default (meta v)))))))
