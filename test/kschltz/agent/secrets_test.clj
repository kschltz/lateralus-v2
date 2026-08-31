(ns kschltz.agent.secrets-test
  "Tests for the sealed secret store, handle substitution, redaction,
   and the wrapping Tool."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.secrets :as secrets]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.compile :as factory.compile]
            [kschltz.agent.tools.factory.protocol :as factory.proto]))

(defn- temp-path
  "A fresh unique file path under java.io.tmpdir."
  [tag]
  (str (System/getProperty "java.io.tmpdir") "/latsec-" tag "-" (System/currentTimeMillis) "/secrets.sealed"))

(defn- cleanup! [path]
  (let [f (java.io.File. path)]
    (when (.exists f) (.delete f))
    (when-let [p (.getParentFile f)] (when (.exists p) (.delete p)))))

(defn- with-store
  "Run `(f store)` against a fresh sealed store (literal passphrase);
   cleaned up around the invocation."
  [tag f]
  (let [path (temp-path tag)
        store (secrets/sealed-file-store {:path path :passphrase "test-passphrase"})]
    (try
      (f store)
      (finally (cleanup! path)))))

(defrecord EchoTool []
  tool/Tool
  (-name [_] "echo")
  (-description [_] "echoes args")
  (-input-schema [_] [:map])
  (-output-schema [_] :string)
  (-invoke [_ args _ctx] (pr-str args)))

(defrecord CredentialProbeTool [seen]
  tool/Tool
  (-name [_] "credential_probe")
  (-description [_] "Trusted protocol-bound credential consumer")
  (-input-schema [_] [:map [:token :string]])
  (-output-schema [_] :string)
  (-invoke [_ args _ctx]
    (reset! seen args)
    (if (= "sk-protocol-value-789" (:token args))
      "available"
      "missing")))

(deftest sealed-store-roundtrip
  (testing "put/get/exists/delete cycle"
    (with-store :roundtrip (fn [store] 
      (secrets/-put-secret! store "github-token" "ghp_abc123xyz")
      (secrets/-put-secret! store "db/url" "postgres://u:p@h/db")
      (is (= "ghp_abc123xyz" (secrets/-get-secret store "github-token")))
      (is (= "postgres://u:p@h/db" (secrets/-get-secret store "db/url")))
      (is (true? (secrets/-secret-exists? store "github-token")))
      (is (nil? (secrets/-get-secret store "nope")))
      (is (= ["db/url" "github-token"] (secrets/-secret-labels store)))
      (secrets/-delete-secret! store "github-token")
      (is (false? (secrets/-secret-exists? store "github-token")))
      (is (nil? (secrets/-get-secret store "github-token")))))))

(deftest persisted-across-instances
  (testing "a second store instance with the same passphrase reads the values"
    (let [path (temp-path :persist)
          store1 (secrets/sealed-file-store {:path path :passphrase "pw"})
          store2 (secrets/sealed-file-store {:path path :passphrase "pw"})]
      (try
        (secrets/-put-secret! store1 "k" "v-12345678")
        (is (= "v-12345678" (secrets/-get-secret store2 "k")))
        (finally
          (cleanup! path))))))

(deftest wrong-passphrase-fails
  (testing "opening with the wrong passphrase throws on first decrypt (GCM auth)"
    (let [path (temp-path :wrong)
          store1 (secrets/sealed-file-store {:path path :passphrase "good"})
          _ (secrets/-put-secret! store1 "k" "v-12345678")
          store2 (secrets/sealed-file-store {:path path :passphrase "bad"})]
      (try
        (is (thrown? Exception (secrets/-get-secret store2 "k")))
        (finally
          (cleanup! path))))))

(deftest missing-passphrase-throws
  (testing "a store cannot be created without a passphrase or env var"
    (let [path (temp-path :nopass)]
      (when-not (not-empty (System/getenv "LATERALUS_SECRETS_PASSPHRASE"))
        (is (thrown? Exception
                     (secrets/sealed-file-store {:path path :passphrase nil})))))))

(deftest invalid-label-rejected
  (testing "labels outside the charset throw"
    (with-store :label (fn [store] 
      (is (thrown? Exception
                   (secrets/-put-secret! store "bad label!" "v-12345678")))))))

(deftest substitute-handles-resolves-and-throws-on-missing
  (testing "handles deep in args resolve to plaintext"
    (with-store :sub (fn [store] 
      (secrets/-put-secret! store "tok" "sekrit-value-99")
      (let [args {"auth" "{{secret:tok}}"
                  "nested" {"h" "Bearer {{secret:tok}}", "plain" "no-handle"}}
            out (secrets/substitute-handles store args)]
        (is (= "sekrit-value-99" (get out "auth")))
        (is (= "Bearer sekrit-value-99" (get-in out ["nested" "h"])))
        (is (= "no-handle" (get-in out ["nested" "plain"])))
        ;; the original args are untouched (resolution is a copy)
        (is (= "{{secret:tok}}" (get args "auth")))))))
  (testing "a missing label throws an ex-info naming the label without values"
    (with-store :missing (fn [store] 
      (let [e (try
                (secrets/substitute-handles store {"x" "{{secret:nope}}"})
                nil
                (catch Exception e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "nope")))))))

(deftest redaction-scrubs-values
  (testing "values replaced everywhere, short secrets not used as needles"
    (with-store :redact (fn [store] 
      (secrets/-put-secret! store "github-token" "ghp_supersecret77")
      (let [pairs (secrets/collect-redaction-pairs store)]
        (is (= "res: [REDACTED:github-token] done"
               (secrets/redact-string pairs "res: ghp_supersecret77 done")))
        (is (= {:a ["x [REDACTED:github-token]" "keep"]}
               (secrets/redact-data pairs {:a ["x ghp_supersecret77" "keep"]})))))))
  (testing "values under min-redact-length are skipped by the sweep"
    (with-store :short (fn [store] 
      (secrets/-put-secret! store "tiny" "ok")
      (is (= "look ok here"
             (secrets/redact-string (secrets/collect-redaction-pairs store)
                                     "look ok here")))))))

(deftest wrap-tool-substitutes-and-redacts
  (testing "handle in args reaches the delegate resolved; leaked value back is redacted"
    (with-store :wrap (fn [store] 
      (secrets/-put-secret! store "tok" "sk-leaky-value-42")
      ;; the delegate echoes args AND the model managed to smuggle a
      ;; raw value into a second arg — both must come back safe
      (let [wrapped (secrets/wrap-tool
                     store (->EchoTool) {:labels #{"tok"}})
            out (tool/invoke-tool wrapped {"tok" "{{secret:tok}}"
                                           "echo-back" "sk-leaky-value-42"} {})]
        (is (str/includes? out "[REDACTED:tok]"))
        ;; both occurrences (resolved arg + smuggled value) redacted:
        ;; the plaintext never survives the round trip through the model
        (is (= 2 (count (re-seq #"\[REDACTED:tok\]" out))))
        (is (= "echo" (tool/-name wrapped))))))))

(deftest wrap-tool-denies-secret-handles-without-a-capability
  (with-store :deny-capability
    (fn [store]
      (secrets/-put-secret! store "tok" "sk-denied-value-42")
      (let [out (tool/invoke-tool
                 (secrets/wrap-tool store (->EchoTool))
                 {:token "{{secret:tok}}"}
                 {})]
        (is (str/includes? out "not authorized"))
        (is (not (str/includes? out "sk-denied-value-42")))))))

(deftest wrap-tool-refreshes-redaction-after-store-mutation
  (with-store :fresh-needles
    (fn [store]
      (let [wrapped (secrets/wrap-tool
                     store (->EchoTool) {:labels #{"first" "second"}})]
        (secrets/-put-secret! store "first" "sk-first-value-123")
        (is (str/includes?
             (tool/invoke-tool wrapped {:token "{{secret:first}}"} {})
             "[REDACTED:first]"))
        (secrets/-put-secret! store "second" "sk-second-value-456")
        (let [out (tool/invoke-tool wrapped
                                    {:token "{{secret:second}}"}
                                    {})]
          (is (str/includes? out "[REDACTED:second]"))
          (is (not (str/includes? out "sk-second-value-456"))))))))

(deftest wrap-tool-preserves-programmatic-calls
  (testing "args without handles pass through untouched to the delegate"
    (with-store :passthrough (fn [store] 
      (let [wrapped (secrets/wrap-tool store (->EchoTool))]
        (is (= "{:a 1}" (tool/invoke-tool wrapped {:a 1} {}))))))))

(deftest sandboxed-runtime-tool-can-compose-an-allowlisted-host-tool
  (with-store :sandbox-compose
    (fn [store]
      (secrets/-put-secret! store "protocol-token" "sk-protocol-value-789")
      (let [seen (atom nil)
            host-tool (secrets/wrap-tool
                       store
                       (->CredentialProbeTool seen)
                       {:labels #{"protocol-token"}})
            compiler (factory.compile/jvm-compiler
                      nil
                      {:sandbox? true
                       :call-tools #{"credential_probe"}})
            compiled (factory.proto/-compile-spec
                      compiler
                      {:name "safe_composer"
                       :description "Delegate credential use to a host tool"
                       :input-schema "[:map [:token :string]]"
                       :invoke "(fn [args _ctx] (lateralus.runtime/call-tool \"credential_probe\" {:token (:token args)}))"})
            runtime-tool (secrets/wrap-tool store (:tool compiled))
            ctx {:agent/tool-registry {"credential_probe" host-tool}}]
        (is (true? (:ok compiled)) (pr-str compiled))
        (is (= "available"
               (tool/invoke-tool runtime-tool
                                 {:token "{{secret:protocol-token}}"}
                                 ctx)))
        (is (= "sk-protocol-value-789" (:token @seen)))
        (is (not= "sk-protocol-value-789"
                  (tool/invoke-tool runtime-tool
                                    {:token "{{secret:protocol-token}}"}
                                    ctx)))))))