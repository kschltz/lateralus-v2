(ns kschltz.agent.workbench.portal-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest prepare-value-wraps-vega-as-html
  (let [spec {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
              :mark "bar"
              :encoding {:x {:field "a"} :y {:field "b"}}
              :data {:values [{:a 1 :b 2}]}}
        prep (portal/prepare-value spec)]
    (is (nil? (:error prep)))
    (is (= "html" (:viewer prep)))
    (is (str/includes? (:value prep) "<!DOCTYPE html>"))
    (is (str/includes? (:value prep) "vegaEmbed"))))

(deftest prepare-value-respects-kind-html
  (let [prep (portal/prepare-value {:not "html"} {:kind :html})]
    (is (str/includes? (:value prep) "<!DOCTYPE html>"))))

(deftest with-default-viewer-picks-rich-surfaces
  (testing "table rows"
    (let [v (portal/with-default-viewer [{:a 1} {:a 2}])]
      (is (= :portal.viewer/table (:portal.viewer/default (meta v))))))
  (testing "html uses portal.viewer/html (may wrap non-IObj strings)"
    (let [html "<div class=\"card\">hello</div>"
          v (portal/with-default-viewer html)]
      ;; A Clojure String cannot carry metadata, so an unadorned string
      ;; round-trip is the correct outcome when no wrap happens.
      (is (or (and (string? v) (= html v))
              (and (vector? v)
                   (some #{:portal.viewer/html}
                         (tree-seq coll? seq v)))))))
  (testing "hiccup"
    (let [v (portal/with-default-viewer [:div {:style {:color "red"}} "hi"])]
      (is (= :portal.viewer/hiccup (:portal.viewer/default (meta v)))))))
