(ns kschltz.agent.tools.web.protocol-test
  "Smoke tests for the WebProvider protocol.

   These tests do not exercise real providers — they confirm that the
   protocol surface exists, that a deftype can satisfy it, and that
   `-capabilities` is the only method that never raises."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools.web.protocol :as proto]))

;; A minimal in-memory provider that satisfies WebProvider. Used to
;; prove the protocol is well-formed and dispatchable.
(deftype FakeProvider [results]
  proto/WebProvider
  (-search [_ _ _]
    {:results results :provider :fake})
  (-fetch [_ url _]
    {:url url :title "fake-title" :body "fake-body" :bytes 9 :status 200})
  (-extract [_ html _]
    {:text html :title "fake-title" :selectors-hit ["html"]})
  (-capabilities [_]
    {:search? true :fetch? true :extract? true :live? false}))

(deftest protocol-methods-are-dispatchable
  (testing "All four protocol methods dispatch through the record."
    (let [p (->FakeProvider [{:title "a" :url "https://example.com/a" :snippet "A"}])]
      (is (= :fake (:provider (proto/-search p "ducks" {}))))
      (is (= 1 (count (:results (proto/-search p "ducks" {})))))
      (is (= "https://example.com/x" (:url (proto/-fetch p "https://example.com/x" {}))))
      (is (= 200 (:status (proto/-fetch p "https://example.com/x" {}))))
      (is (= "<p>html</p>" (:text (proto/-extract p "<p>html</p>" {}))))
      (is (= ["html"] (:selectors-hit (proto/-extract p "<p>html</p>" {}))))
      (is (= {:search? true :fetch? true :extract? true :live? false}
             (proto/-capabilities p))))))

(deftest capabilities-never-raises
  (testing "-capabilities must not raise even when other methods would."
    (let [thrown (deftype ThrowsProvider []
                   proto/WebProvider
                   (-search [_ _ _] (throw (ex-info "search broke" {:phase :provider})))
                   (-fetch [_ _ _] (throw (ex-info "fetch broke" {:phase :provider})))
                   (-extract [_ _ _] (throw (ex-info "extract broke" {:phase :provider})))
                   (-capabilities [_] {:search? false :fetch? false :extract? true :live? false}))
          p (->ThrowsProvider)]
      ;; Other methods raise as expected.
      (is (thrown? clojure.lang.ExceptionInfo (proto/-search p "q" {})))
      (is (thrown? clojure.lang.ExceptionInfo (proto/-fetch p "u" {})))
      (is (thrown? clojure.lang.ExceptionInfo (proto/-extract p "h" {})))
      ;; -capabilities still returns a value.
      (is (= {:search? false :fetch? false :extract? true :live? false}
             (proto/-capabilities p))))))