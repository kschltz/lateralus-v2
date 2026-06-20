(ns kschltz.agent.tools.web.ddg-test
  "Tests for the `:ddg` live web provider.

   All tests drive the provider through a stub `:http-fn` so no real
   HTTP traffic and no impersonator dep are needed. The fixture HTML
   matches DuckDuckGo's `html.duckduckgo.com/html` results shape:
   `div.result` blocks each with an `a.result__a` title link and an
   `a.result__snippet`. DDG wraps real destination URLs in a
   `//duckduckgo.com/l/?uddg=<enc>` redirect; the fixture exercises
   both that redirect form and a direct href so `decode-uddg` is
   covered."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools.web.ddg :as web.ddg]
            [kschltz.agent.tools.web.protocol :as protocol]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private fixture-html
  "<!doctype html>
<html><head><title>clojure programming - DuckDuckGo</title></head><body>
  <div class=\"result results_links results_links_deep web-result\">
    <h2 class=\"result__title\">
      <a class=\"result__a\"
         href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fclojure.org&amp;rut=abc\">
        Clojure - The Clojure Programming Language
      </a>
    </h2>
    <a class=\"result__snippet\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fclojure.org\">Clojure is a modern dialect of Lisp.</a>
  </div>
  <div class=\"result results_links results_links_deep web-result\">
    <h2 class=\"result__title\">
      <a class=\"result__a\" href=\"https://github.com/clojure/clojure\">clojure/clojure on GitHub</a>
    </h2>
    <a class=\"result__snippet\" href=\"https://github.com/clojure/clojure\">The Clojure programming language source repo.</a>
  </div>
</body></html>")

(def ^:private fixture-empty-html
  "<!doctype html><html><head><title>x</title></head><body><p>no results here</p></body></html>")

(def ^:private fixture-page-html
  "<!doctype html><html><head><title>Example Domain</title></head><body>
   <p>Hello <b>world</b> from <a href=\"/x\">example</a>.</p>
   <script>alert('x')</script></body></html>")

;; ---------------------------------------------------------------------------
;; Test helper: stub :http-fn that records requests and returns canned HTML
;; ---------------------------------------------------------------------------

(defn- make-stub-http
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
    (let [[http-fn reqs] (make-stub-http {:status 200 :body fixture-html})
          provider (web.ddg/provider {:http-fn http-fn})
          result   (protocol/-search provider "clojure" {})]
      (is (= :ddg (:provider result)))
      (is (vector? (:results result)))
      (is (= 2 (count (:results result))))
      (let [[r1 r2] (:results result)]
        ;; first result uses the uddg redirect form — must be decoded
        (is (= "Clojure - The Clojure Programming Language" (str/trim (:title r1))))
        (is (= "https://clojure.org" (:url r1))
            (str "decoded url was: " (pr-str (:url r1))))
        (is (str/includes? (:snippet r1) "modern dialect of Lisp"))
        ;; second result is a direct href — passed through unchanged
        (is (= "https://github.com/clojure/clojure" (:url r2)))
        (is (str/includes? (:title r2) "GitHub")))
      ;; http-fn was called once with a GET against the DDG html endpoint
      (is (= 1 (count @reqs)))
      (is (= "https://html.duckduckgo.com/html/?q=clojure"
             (:url (first @reqs)))))))

(deftest search-sends-browser-headers
  (testing "-search sends a browser-like User-Agent + Accept-Language so the
            impersonator fingerprint is consistent with the claimed browser"
    (let [[http-fn reqs] (make-stub-http {:status 200 :body fixture-html})
          provider (web.ddg/provider {:http-fn http-fn})
          _        (protocol/-search provider "x" {})]
      (let [hdrs (:headers (first @reqs))]
        (is (str/includes? (get hdrs "User-Agent") "Chrome"))
        (is (str/includes? (get hdrs "Accept-Language") "en"))))))

(deftest search-passes-impersonate-through-to-http-fn
  (testing "the :impersonate config key flows into the http-fn request map"
    (let [[http-fn reqs] (make-stub-http {:status 200 :body fixture-html})
          provider (web.ddg/provider {:http-fn http-fn :impersonate :android})
          _        (protocol/-search provider "x" {})]
      (is (= :android (:impersonate (first @reqs)))))))

(deftest search-throws-on-non-2xx
  (testing "-search raises ex-info with :phase :provider on a 403"
    (let [[http-fn] (make-stub-http {:status 403 :body "Forbidden"})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"HTTP 403"
           (protocol/-search (web.ddg/provider {:http-fn http-fn}) "x" {}))))))

(deftest search-throws-when-no-results
  (testing "-search raises ex-info when the page has no result blocks"
    (let [[http-fn] (make-stub-http {:status 200 :body fixture-empty-html})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"no parseable results"
           (protocol/-search (web.ddg/provider {:http-fn http-fn}) "x" {}))))))

;; ---------------------------------------------------------------------------
;; fetch
;; ---------------------------------------------------------------------------

(deftest fetch-returns-stripped-text-and-title
  (testing "-fetch strips HTML and returns :title, :body, :bytes, :status"
    (let [[http-fn reqs] (make-stub-http {:status 200 :body fixture-page-html})
          provider (web.ddg/provider {:http-fn http-fn})
          result   (protocol/-fetch provider "https://example.com/" {})]
      (is (= "https://example.com/" (:url result)))
      (is (= 200 (:status result)))
      (is (= "Example Domain" (:title result)))
      (is (str/includes? (:body result) "Hello world"))
      (is (not (str/includes? (:body result) "alert")))   ; script stripped
      (is (pos? (:bytes result))))))

(deftest fetch-throws-on-non-2xx
  (testing "-fetch raises ex-info with :phase :provider on a 404"
    (let [[http-fn] (make-stub-http {:status 404 :body "Not Found"})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"HTTP 404"
           (protocol/-fetch (web.ddg/provider {:http-fn http-fn})
                            "https://example.com/missing" {}))))))

;; ---------------------------------------------------------------------------
;; extract
;; ---------------------------------------------------------------------------

(deftest extract-returns-text-and-title
  (testing "-extract is a pure HTML->text transform (no network)"
    (let [provider (web.ddg/provider {})]
      (let [r (protocol/-extract provider fixture-page-html {})]
        (is (= :ddg (:provider r)))
        (is (string? (:text r)))
        (is (str/includes? (:text r) "Hello world"))
        (is (= "Example Domain" (:title r)))
        (is (vector? (:selectors-hit r)))))))

;; ---------------------------------------------------------------------------
;; capabilities
;; ---------------------------------------------------------------------------

(deftest capabilities-flags-live-provider
  (testing "-capabilities reports :search? :fetch? :extract? :live? all true"
    (let [caps (protocol/-capabilities (web.ddg/provider {}))]
      (is (:search?  caps))
      (is (:fetch?   caps))
      (is (:extract? caps))
      (is (:live?     caps)))))