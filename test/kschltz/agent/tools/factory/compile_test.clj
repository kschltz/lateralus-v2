(ns kschltz.agent.tools.factory.compile-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.compile :as compile]
            [kschltz.agent.tools.factory.protocol :as proto]
            [kschltz.agent.tools.filesystem :as filesystem]))

(deftest compile-spec-builds-invokable-tool
  (let [compiler (compile/jvm-compiler)
        spec {:name "add_two"
              :description "Add two integers"
              :input-schema "[:map [:a :int] [:b :int]]"
              :invoke "(fn [args _ctx] (str (+ (:a args) (:b args))))"}
        result (proto/-compile-spec compiler spec)]
    (is (true? (:ok result)))
    (is (tool/tool? (:tool result)))
    (is (= "3" (tool/invoke-tool (:tool result) {:a 1 :b 2} {})))))

(deftest compiled-tool-accepts-model-authored-one-arity-function
  (let [compiler (compile/jvm-compiler)
        result (proto/-compile-spec
                compiler
                {:name "credential_status"
                 :description "Classify credential presence"
                 :input-schema "[:map [:credential :string]]"
                 :invoke "(fn [{:keys [credential]}] (if (seq credential) \"available\" \"missing\"))"})]
    (is (true? (:ok result)))
    (is (= "available"
           (tool/invoke-tool (:tool result) {:credential "secret"} {})))))

(deftest sandboxed-compiler-runs-pure-tools-without-host-context
  (let [compiler (compile/jvm-compiler nil {:sandbox? true})
        result (proto/-compile-spec
                compiler
                {:name "credential_status"
                 :description "Classify an opaque credential handle"
                 :input-schema "[:map [:credential :string]]"
                 :invoke "(fn [args ctx] (if (and (nil? ctx) (clojure.string/starts-with? (:credential args) \"{{secret:\")) \"opaque\" \"unsafe\"))"})]
    (is (true? (:ok result)))
    (is (= :untrusted-runtime (tool/trust-tier (:tool result))))
    (is (= "opaque"
           (tool/invoke-tool (:tool result)
                             {:credential "{{secret:token}}"}
                             {:agent/tool-registry {"danger" (-> Object)}})))))

(deftest sandboxed-compiler-rejects-host-escape-surfaces
  (let [compiler (compile/jvm-compiler nil {:sandbox? true})
        base {:name "unsafe"
              :description "Must not compile"
              :input-schema "[:map]"
              :invoke "(fn [_args _ctx] \"ok\")"}
        attempts [(assoc base :invoke "(fn [_ _] (System/getenv \"HOME\"))")
                  (assoc base :invoke "(fn [_ _] (slurp \"https://example.com\"))")
                  (assoc base :libs "{foo/bar {:mvn/version \"1\"}}")
                  (assoc base :require "clojure.java.io")
                  (assoc base
                         :interceptor-slot :guard
                         :interceptor-enter "(fn [ctx] ctx)")]]
    (doseq [spec attempts]
      (let [result (proto/-compile-spec compiler spec)]
        (is (false? (:ok result)) (pr-str spec))
        (is (= "sandbox" (:phase result)) (pr-str result))))))

(deftest compile-spec-rejects-bad-schema
  (let [compiler (compile/jvm-compiler)
        result (proto/-compile-spec compiler
                                    {:name "bad"
                                     :description "x"
                                     :input-schema "not-a-schema-!!!"
                                     :invoke "(fn [args _ctx] \"ok\")"})]
    (is (false? (:ok result)))
    (is (= "compile" (:phase result)))
    (is (re-find #"Provided: not-a-schema-!!!" (:error result)))
    (is (re-find #"\[:map \[:handle :string\]\]" (:error result)))
    (is (not= ":malli.core/invalid-schema" (:error result)))))

(deftest compile-spec-humanizes-flattened-map-schema
  (let [compiler (compile/jvm-compiler)
        result (proto/-compile-spec
                compiler
                {:name "bad"
                 :description "x"
                 :input-schema "[:map :handle :string]"
                 :invoke "(fn [args _ctx] \"ok\")"})]
    (is (false? (:ok result)))
    (is (re-find #"Provided: \[:map :handle :string\]" (:error result)))
    (is (re-find #":malli.core/invalid-entry" (:error result)))
    (is (re-find #"entry :handle" (:error result)))
    (is (re-find #"\[key schema\]" (:error result)))))

(deftest compile-fn-requires-ifn
  (is (thrown-with-msg? Exception #"function"
                        (compile/compile-fn "42"))))
(deftest compile-fn-reads-fn-literals-and-regexes
  ;; regression: clojure.edn/read raised "No dispatch macro for: (" on
  ;; model bodies using #(…) and #"…" — the tool then silently vanished
  ;; at every rehydrate (sessions 675706dd / 92150f99).
  (let [f (compile/compile-fn
           "(fn [args _ctx] (mapv #(clojure.string/upper-case (str %)) (re-seq #\"[a-z]+\" (str (:text args)))))")]
    (is (= ["AB" "CD"] (f {:text "ab cd"} nil)))))

(deftest read-form-still-blocks-reader-eval
  (is (thrown? Exception (compile/compile-fn "#=(+ 1 2)"))))

(def ^:private workspace-snippet-spec
  {:name "workspace_snippet"
   :description "Confirm a workspace fixture marker via allowlisted file_read"
   :input-schema "[:map [:path :string]]"
   :invoke "(fn [args] (let [raw (lateralus.runtime/call-tool \"file_read\" {:path (:path args)})] (if (clojure.string/includes? raw \"SNIPPET:OK\") \"SNIPPET:OK\" \"MISSING\")))"})

(deftest sandboxed-compiler-can-call-allowlisted-file-read
  (let [root (.getCanonicalPath (io/file "."))
        registry (filesystem/filesystem-registry {:workspace-root root})
        compiler (compile/jvm-compiler nil {:sandbox? true
                                            :call-tools #{"file_read"}})
        result (proto/-compile-spec compiler workspace-snippet-spec)
        fixture "resources/lateralus/self-tooling-fixture.txt"]
    (is (true? (:ok result)))
    (is (.isFile (io/file fixture)))
    (is (= "SNIPPET:OK"
           (tool/invoke-tool (:tool result)
                             {:path fixture}
                             {:agent/tool-registry registry})))))

(deftest sandboxed-compiler-denies-file-read-without-allowlist
  (let [root (.getCanonicalPath (io/file "."))
        registry (filesystem/filesystem-registry {:workspace-root root})
        compiler (compile/jvm-compiler nil {:sandbox? true :call-tools #{}})
        result (proto/-compile-spec compiler workspace-snippet-spec)
        actual (tool/invoke-tool (:tool result)
                                 {:path "resources/lateralus/self-tooling-fixture.txt"}
                                 {:agent/tool-registry registry})]
    (is (true? (:ok result)))
    (is (str/includes? actual "not allowed"))
    (is (str/includes? actual "file_read"))))
