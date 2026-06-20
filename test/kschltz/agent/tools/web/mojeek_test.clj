(ns kschltz.agent.tools.web.mojeek-test
  "Tests for the `:mojeek` live web provider.

   All tests drive the provider through a stub `:http-fn` so no
   real HTTP traffic is generated. The fixture HTML matches Mojeek's
   public results-page shape (`ul.results-standard > li` with an
   `h2 a.ob` title link and a `p.s` snippet). When Mojeek shifts
   its markup, both the selector map and the fixture need to move
   together — that is intentional, the fixture is the contract."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools.web.mojeek :as web.mojeek]
            [kschltz.agent.tools.web.protocol :as protocol]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private fixture-html
  "<!doctype html>
<html><head><title>mojeek search</title></head><body>
  <ul class=\"results-standard\">
    <li class=\"result-standard\">
      <h2><a class=\"ob\" href=\"https://example.com/one\">First result title</a></h2>
      <p class=\"s\">First result snippet line one and two.</p>
    </li>
    <li class=\"result-standard\">
      <h2><a class=\"ob\" href=\"https://example.com/two\">Second result title</a></h2>
      <p class=\"s\">Second result snippet.</p>
    </li>
    <li class=\"result-standard\">
      <h2><a class=\"ob\" href=\"https://example.com/three\">Third result title</a></h2>
      <p class=\"s\">Third result snippet.</p>
    </li>
  </ul>
</body></html>")

(def ^:private fixture-page-html
  "<!doctype html>
<html><head><title>Example Domain</title></head><body>
  <p>Hello <b>world</b> from <a href=\"/x\">example</a>.</p>
  <script>alert('x')</script>
</body></html>")

;; ---------------------------------------------------------------------------
;; Test helper: stub :http-fn that records requests and returns canned HTML
;; ---------------------------------------------------------------------------

(defn- make-stub-http
  "Return `[stub req-atom]` where `stub` is a fn suitable as `:http-fn`
   that returns `response` and stashes each request into `req-atom`."
  [response]
  (let [reqs (atom [])]
    [(fn [req]
       (swap! reqs conj req)
       (assoc response :request req))
     reqs]))

;; ---------------------------------------------------------------------------
;; search
;; ---------------------------------------------------------------------------

(deftest search-returns-parsed-results
  (testing "-search returns >=1 parsed result with :title, :url, :snippet"
    (let [[http-fn reqs] (make-stub-http
                          {:status 200
                           :body   fixture-html})
          provider (web.mojeek/provider {:http-fn http-fn})
          result   (protocol/-search provider "anything" {})]
      (is (= :mojeek (:provider result)))
      (is (vector? (:results result)))
      (is (>= (count (:results result)) 1))
      (let [r (first (:results result))]
        (is (string? (:title   r)))
        (is (string? (:url     r)))
        (is (string? (:snippet r)))
        (is (= "First result title"   (:title   r)))
        (is (= "https://example.com/one" (:url r)))
        (is (str/includes? (:snippet r) "First result snippet")))
      ;; http-fn was called once with a GET on the search URL
      (is (= 1 (count @reqs)))
      (let [{:keys [method url]} (first @reqs)]
        (is (= :get method))
        (is (str/includes? url "/search?q="))
        (is (str/includes? url "anything"))))))

(deftest search-uses-base-url-and-encodes-query
  (testing ":base-url override + URL-encoded query"
    (let [[http-fn reqs] (make-stub-http
                          {:status 200
                           :body   fixture-html})
          provider (web.mojeek/provider
                    {:http-fn  http-fn
                     :base-url "https://search.example.test"})
          _        (protocol/-search provider "clojure hickory" {})
          [{:keys [url]}] @reqs]
      (is (str/starts-with? url "https://search.example.test/search?q="))
      ;; The encoded query must contain the percent-encoded space
      (is (str/includes? url "clojure"))
      (is (str/includes? url "hickory"))
      (is (or (str/includes? url "%20")
              (str/includes? url "+"))))))

(deftest search-with-empty-query
  (testing "empty / blank query parses the returned fixture HTML"
    (let [[http-fn _] (make-stub-http
                       {:status 200 :body fixture-html})
          provider (web.mojeek/provider {:http-fn http-fn})]
      (is (pos? (count (:results (protocol/-search provider "" {})))))
      (is (pos? (count (:results (protocol/-search provider "   " {}))))))))

(deftest search-with-empty-body
  (testing "200 with empty body raises :phase :provider"
    (let [[http-fn _] (make-stub-http {:status 200 :body ""})
          provider (web.mojeek/provider {:http-fn http-fn})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"empty body"
           (protocol/-search provider "anything" {}))))))

(deftest search-non-2xx-raises
  (testing "non-2xx status raises :phase :provider"
    (let [[http-fn _] (make-stub-http {:status 503 :body "<html></html>"})
          provider (web.mojeek/provider {:http-fn http-fn})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"HTTP 503"
           (protocol/-search provider "anything" {}))))))

(deftest search-caps-result-count
  (testing ":max-result-count caps the result list"
    (let [[http-fn _] (make-stub-http {:status 200 :body fixture-html})
          provider (web.mojeek/provider {:http-fn http-fn})]
      (let [r (protocol/-search provider "x" {:max-result-count 1})]
        (is (= 1 (count (:results r))))
        (is (= "https://example.com/one"
               (-> r :results first :url))))
      (let [r (protocol/-search provider "x" {:max-result-count 2})]
        (is (= 2 (count (:results r))))))))

;; ---------------------------------------------------------------------------
;; fetch
;; ---------------------------------------------------------------------------

(deftest fetch-returns-bytes-and-stripped-body
  (testing "-fetch returns :bytes count, stripped body, and extracted title"
    (let [[http-fn reqs] (make-stub-http
                          {:status 200 :body fixture-page-html})
          provider (web.mojeek/provider {:http-fn http-fn})
          result   (protocol/-fetch provider
                                    "https://example.com/" {})]
      (is (= "https://example.com/" (:url result)))
      (is (= 200 (:status result)))
      (is (= "Example Domain" (:title result)))
      (is (string? (:body result)))
      (is (str/includes? (:body result) "Hello"))
      (is (str/includes? (:body result) "world"))
      ;; :script block stripped
      (is (not (str/includes? (:body result) "alert")))
      ;; :bytes count matches UTF-8 length of raw body, not stripped
      (is (= (count (.getBytes fixture-page-html "UTF-8"))
             (:bytes result)))
      ;; http-fn called once with the validated URL
      (is (= 1 (count @reqs)))
      (is (= "https://example.com/"
             (-> @reqs first :url))))))

(deftest fetch-enforces-max-page-bytes
  (testing "-fetch raises :phase :size-cap when body exceeds :max-page-bytes"
    (let [[http-fn _] (make-stub-http {:status 200 :body fixture-page-html})
          provider (web.mojeek/provider
                    {:http-fn       http-fn
                     :max-page-bytes 4})]
      (let [thrown (try
                     (protocol/-fetch provider "https://example.com/" {})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo thrown))
        (is (= :size-cap (:phase (ex-data thrown))))
        (is (= :mojeek (:provider (ex-data thrown))))))))

(deftest fetch-non-2xx-raises
  (testing "non-2xx fetch raises :phase :provider"
    (let [[http-fn _] (make-stub-http {:status 404 :body ""})
          provider (web.mojeek/provider {:http-fn http-fn})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"HTTP 404"
           (protocol/-fetch provider "https://example.com/" {}))))))

;; ---------------------------------------------------------------------------
;; extract
;; ---------------------------------------------------------------------------

(deftest extract-without-selector-returns-stripped-text
  (testing "-extract with no :selector returns the full stripped text"
    (let [provider (web.mojeek/provider {})]
      (let [r (protocol/-extract provider fixture-page-html {})]
        (is (string? (:text r)))
        (is (str/includes? (:text r) "Hello"))
        (is (str/includes? (:text r) "world"))
        (is (= "Example Domain" (:title r)))
        (is (= [] (:selectors-hit r)))
        (is (= :mojeek (:provider r)))))))

(deftest extract-with-tag-selector
  (testing "-extract with a bare tag selector returns matched text"
    (let [provider (web.mojeek/provider {})]
      (let [r (protocol/-extract provider fixture-page-html
                                 {:selector "p"})]
        (is (string? (:text r)))
        (is (str/includes? (:text r) "Hello"))
        (is (= ["p"] (:selectors-hit r)))))))

(deftest extract-with-tag-class-selector
  (testing "-extract with 'p.s' style selector returns matched text"
    (let [provider (web.mojeek/provider {})]
      (let [r (protocol/-extract provider fixture-html
                                 {:selector "p.s"})]
        (is (string? (:text r)))
        ;; fixture-html's p.s has 3 snippets
        (is (str/includes? (:text r) "First result snippet"))
        (is (str/includes? (:text r) "Second"))
        (is (str/includes? (:text r) "Third"))
        (is (= ["p.s"] (:selectors-hit r)))))))

(deftest extract-on-empty-string
  (testing "-extract of an empty string yields empty text and nil title"
    (let [provider (web.mojeek/provider {})
          r        (protocol/-extract provider "" {})]
      (is (= "" (:text r)))
      (is (nil? (:title r)))
      (is (= [] (:selectors-hit r)))
      (is (= :mojeek (:provider r))))))

;; ---------------------------------------------------------------------------
;; capabilities
;; ---------------------------------------------------------------------------

(deftest capabilities-live-map
  (testing "-capabilities returns the live descriptor"
    (is (= {:search?  true
            :fetch?   true
            :extract? true
            :live?    true}
           (protocol/-capabilities (web.mojeek/provider {}))))))

;; ---------------------------------------------------------------------------
;; :http-fn is wired through
;; ---------------------------------------------------------------------------

(deftest http-fn-overrides-default
  (testing ":http-fn in config replaces the default hato wrapper"
    (let [called? (atom false)
          stub    (fn [req]
                    (reset! called? true)
                    {:status 200
                     :body   fixture-html
                     :request req})
          provider (web.mojeek/provider {:http-fn stub})]
      ;; default-http-fn would have called hato; here the stub absorbs it
      (protocol/-search provider "anything" {})
      (is @called?))))

(deftest http-fn-receives-user-agent-and-timeout
  (testing ":http-fn is invoked with the configured user-agent and timeout"
    (let [[http-fn reqs] (make-stub-http {:status 200 :body fixture-html})
          provider (web.mojeek/provider
                    {:http-fn    http-fn
                     :user-agent "lateralus-test/1.0"
                     :timeout-ms 4242})
          _        (protocol/-search provider "x" {})
          req      (first @reqs)]
      (is (= "lateralus-test/1.0" (get-in req [:headers "User-Agent"])))
      (is (= 4242 (:timeout-ms req))))))