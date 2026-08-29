(ns kschltz.agent.plugins.secrets-test
  "Tests for the secrets plugin: registry wrapping (guard slot) and the
   redaction sweep (:tools slot)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.plugins.secrets :as plugins.secrets]
            [kschltz.agent.plugins.tools :as plugins.tools]
            [kschltz.agent.secrets :as secrets]
            [kschltz.agent.tool :as tool]))

(defrecord EchoTool []
  tool/Tool
  (-name [_] "echo")
  (-description [_] "echoes args")
  (-input-schema [_] [:map])
  (-output-schema [_] :string)
  (-invoke [_ args _ctx] (pr-str args)))

(def store-path
  (str (System/getProperty "java.io.tmpdir") "/latsec-plugin/secrets.sealed"))

(defn- cleanup! [_]
  (let [f (java.io.File. store-path)]
    (when (.exists f) (.delete f))))

(use-fixtures :each cleanup!)

(def ^:private store
  (delay (secrets/sealed-file-store {:path store-path :passphrase "test-passphrase"})))

(defn- assembled-chain-ixs
  "Assembled chain interceptors for base + tools + secrets plugins."
  []
  (let [chain (plugin/assemble-chain
               [(plugins.base/base-plugin)
                (plugins.tools/tools-plugin {"echo" (->EchoTool)})
                (plugins.secrets/secrets-plugin {:store @store})])]
    {:seed   (some #(when (= :kschltz.agent.plugins.tools/seed-registry (:name %)) %) chain)
     :wrap   (some #(when (= :kschltz.agent.plugins.secrets/wrap-registry (:name %)) %) chain)
     :redact (some #(when (= :kschltz.agent.plugins.secrets/redact (:name %)) %) chain)}))

(defn- seed-and-wrap-ctx
  []
  (let [{:keys [seed wrap]} (assembled-chain-ixs)]
    (as-> {:llm/request {:messages [{:role "user" :content "hi"}]}} c
      ((:enter seed) c)
      ((:enter wrap) c))))

(deftest wraps-static-registry-tools-on-guard
  (testing "after seeding, the registry tools are wrapped (slots + behavior)"
    (let [ctx (seed-and-wrap-ctx)
          {:keys [wrap redact]} (assembled-chain-ixs)]
      (is (= :guard (:plugin/slot wrap)))
      (is (= :tools (:plugin/slot redact)))
      (let [reg (:agent/tool-registry ctx)]
        (is (contains? reg "echo"))
        ;; invoke through the wrapper: handle resolved INSIDE invoke,
        ;; the leaked value redacted before returning to the model
        (let [out (tool/invoke-tool (get reg "echo")
                                    {"h" "{{secret:tok}}", "leak" "sk-wrap-value-77"}
                                    nil)]
          ;; the redacted sweep removed every occurrence, including the
          ;; one echoed from a raw-value argument
          (is (str/includes? out "[REDACTED:tok]"))
          (is (not (str/includes? out "sk-wrap-value-77"))))))
    ;; the store must contain the secret for this test to be meaningful
    (is (secrets/-secret-exists? @store "tok"))))

(deftest redact-sweep-scrubs-context
  (testing ":tools-slot enter scrubbs tool results, transcript, and messages"
    (let [store @store
          _ (secrets/-put-secret! store "tok" "sk-sweep-value-88")
          {:keys [redact]} (assembled-chain-ixs)
          paired (assoc {} :x "sk-sweep-value-88")
          _ (is (= "[REDACTED:tok]" (get (secrets/redact-data (secrets/collect-redaction-pairs store) paired) :x)))
          leak-ctx {:tool/results [{:call {}, :result "leak sk-sweep-value-88"}]
                    :agent/tool-transcript [{:role "tool", :content "sk-sweep-value-88"}]
                    :llm/request {:messages [{:role "tool", :content "sk-sweep-value-88"}]}
                    :exchange/response "sk-sweep-value-88"
                    :agent/state-delta {:memory {:content "sk-sweep-value-88"}}}
          out ((:enter redact) leak-ctx)]
      (is (= "leak [REDACTED:tok]" (-> out :tool/results peek :result)))
      (is (= "[REDACTED:tok]" (-> out :agent/tool-transcript peek :content)))
      (is (= "[REDACTED:tok]" (-> out :llm/request :messages peek :content)))
      (is (= "[REDACTED:tok]" (:exchange/response out)))
      (is (= "[REDACTED:tok]" (get-in out [:agent/state-delta :memory :content]))))))

(deftest wrap-cache-identity-stable
  (testing "same static registry identity → same wrapped tool instances"
    (let [ctx1 (seed-and-wrap-ctx)
          ctx2 (seed-and-wrap-ctx)]
      (is (identical? (get (:agent/tool-registry ctx1) "echo")
                      (get (:agent/tool-registry ctx2) "echo"))))))
(deftest handles-tool-is-advertised
  (testing "the plugin registers secret_list_handles + system guidance by default"
    (let [store @store
          _ (secrets/-put-secret! store "tok" "sk-handle-value-55")
          ctx (seed-and-wrap-ctx)
          reg (:agent/tool-registry ctx)]
      (is (contains? reg "secret_list_handles"))
      (let [out (tool/invoke-tool (get reg "secret_list_handles") {} nil)]
        ;; labels visible, values never
        (is (str/includes? out "tok"))
        (is (not (str/includes? out "sk-handle-value-55"))))
      (is (str/includes? (str (:agent/system-append ctx))
                         "secret_list_handles"))
      (is (str/includes? (str (:agent/system-append ctx)) "{{secret:")))))

(deftest handles-tool-can-be-disabled
  (testing ":advertise-handle-tool? false keeps the registry clean"
    (let [p (plugins.secrets/secrets-plugin {:store @store :advertise-handle-tool? false})
          wrap (-> p first :enter)
          ctx (wrap {:agent/static-tool-registry {"echo" (->EchoTool)}
                     :agent/tool-registry {"echo" (->EchoTool)}})]
      (is (not (contains? (:agent/tool-registry ctx) "secret_list_handles")))
      (is (not (contains? (:agent/static-tool-registry ctx) "secret_list_handles")))
      (is (nil? (:agent/system-append ctx))))))
