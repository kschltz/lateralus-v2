(ns kschltz.agent.tools.web.none-test
  "Tests for the `:none` web provider.

   The `:none` provider is the air-gapped default for the lateralus
   web tool suite. It performs zero network I/O: `search` and
   `fetch` raise a typed `:phase :disabled` exception, `extract`
   runs the shared zero-dep regex stripper from `guards.clj`, and
   `capabilities` returns a static descriptor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools.web.none :as web.none]
            [kschltz.agent.tools.web.protocol :as protocol]))

(def ^:private fixture-html
  "<html><head><title>Hi</title></head><body><p>hello <b>world</b></p></body></html>")

(deftest capabilities-static-map
  (testing "the :none provider advertises extract but not search/fetch/live"
    (is (= {:search?  false
            :fetch?   false
            :extract? true
            :live?    false}
           (protocol/-capabilities (web.none/provider {}))))))

(deftest search-raises-disabled
  (testing "-search always raises ex-info with :phase :disabled"
    (let [provider (web.none/provider {})
          thrown   (try
                     (protocol/-search provider "anything" {})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown))
      (is (= :disabled (:phase (ex-data thrown))))
      (is (= :none (:provider (ex-data thrown)))))))

(deftest fetch-raises-disabled
  (testing "-fetch always raises ex-info with :phase :disabled"
    (let [provider (web.none/provider {})
          thrown   (try
                     (protocol/-fetch provider "https://example.com/" {})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown))
      (is (= :disabled (:phase (ex-data thrown))))
      (is (= :none (:provider (ex-data thrown)))))))

(deftest extract-strips-fixture-html
  (testing "extract over a fixture HTML string returns stripped text + title"
    (let [result (protocol/-extract (web.none/provider {}) fixture-html {})]
      (is (string? (:text result)))
      (is (str/includes? (:text result) "hello"))
      (is (str/includes? (:text result) "world"))
      (is (= "Hi" (:title result)))
      (is (= [] (:selectors-hit result)))
      (is (= :none (:provider result))))))

(deftest extract-on-empty-string
  (testing "extract of an empty string yields the empty-result envelope"
    (let [result (protocol/-extract (web.none/provider {}) "" {})]
      (is (= {:text          ""
              :title         nil
              :selectors-hit []
              :provider      :none}
             result)))))

(deftest http-fn-is-ignored
  (testing ":http-fn in the config map is silently ignored"
    ;; Throwing fn proves the config key is never invoked.
    (let [poison  (fn [& _] (throw (ex-info "should never be called" {})))
          provider (web.none/provider {:http-fn poison})]
      (is (= {:search? false :fetch? false :extract? true :live? false}
             (protocol/-capabilities provider)))
      ;; extract also doesn't touch http-fn; it must still strip the fixture.
      (let [r (protocol/-extract provider fixture-html {})]
        (is (= "Hi" (:title r)))))))