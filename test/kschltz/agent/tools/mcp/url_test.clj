(ns kschltz.agent.tools.mcp.url-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools.mcp.url :as url]))

(deftest https-allowed-by-default
  (let [r (url/validate-mcp-url "https://example.com/mcp" {})]
    (is (true? (:allow? r)))))

(deftest http-blocked-by-default
  (let [r (url/validate-mcp-url "http://example.com/mcp" {})]
    (is (false? (:allow? r)))))

(deftest http-allowed-when-opted-in
  (let [r (url/validate-mcp-url "http://example.com/mcp" {:allow-http? true})]
    (is (true? (:allow? r)))))

(deftest loopback-blocked-by-default
  (testing "loopback rejected unless allow-loopback?"
    (let [r (url/validate-mcp-url "https://127.0.0.1/mcp" {})]
      (is (false? (:allow? r))))
    (let [r (url/validate-mcp-url "http://127.0.0.1:9876/mcp"
                                  {:allow-http? true :allow-loopback? true})]
      (is (true? (:allow? r))))))

(deftest assert-raises-ssrf
  (is (thrown-with-msg?
       Exception #"rejected"
       (url/assert-mcp-url! "http://127.0.0.1/mcp" {})))
  (try
    (url/assert-mcp-url! "http://127.0.0.1/mcp" {})
    (is false)
    (catch Exception e
      (is (= :ssrf (:phase (ex-data e)))))))
