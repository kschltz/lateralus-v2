(ns kschltz.agent.tools.factory.sandbox-test
  (:require [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.factory.sandbox :as sandbox]))

(defrecord HostTool [name]
  tool/Tool
  (-name [_] name)
  (-description [_] "test host tool")
  (-input-schema [_] [:map])
  (-output-schema [_] :string)
  (-invoke [_ args _ctx] (pr-str args)))

(defrecord RuntimeTool []
  tool/Tool
  (-name [_] "runtime_target")
  (-description [_] "untrusted runtime target")
  (-input-schema [_] [:map])
  (-output-schema [_] :string)
  (-invoke [_ _args _ctx] "unsafe")
  tool/ToolTrust
  (-trust-tier [_] :untrusted-runtime))

(deftest call-tool-is-name-allowlisted-and-denies-runtime-targets
  (let [ctx {:agent/tool-registry
             {"approved" (->HostTool "approved")
              "clojure_eval" (->HostTool "clojure_eval")
              "runtime_target" (->RuntimeTool)}}]
    (binding [sandbox/*invocation*
              {:ctx ctx
               :allowed-tools #{"approved" "clojure_eval" "runtime_target"}}]
      (is (= "{:x 1}" (sandbox/call-tool "approved" {:x 1})))
      (is (thrown-with-msg?
           Exception #"not allowed"
           (sandbox/call-tool "clojure_eval" {})))
      (is (thrown-with-msg?
           Exception #"cannot invoke another runtime tool"
           (sandbox/call-tool "runtime_target" {}))))))

(deftest call-tool-allows-file-read-but-always-denies-file-write
  (let [ctx {:agent/tool-registry
             {"file_read" (->HostTool "file_read")
              "file_write" (->HostTool "file_write")}}]
    (binding [sandbox/*invocation*
              {:ctx ctx
               :allowed-tools #{"file_read" "file_write"}}]
      (is (= "{:path \"x\"}" (sandbox/call-tool "file_read" {:path "x"})))
      (is (thrown-with-msg?
           Exception #"not allowed"
           (sandbox/call-tool "file_write" {:path "x"}))))))

(deftest sandboxed-function-never-receives-host-context
  (let [seen (atom nil)
        f (fn [args ctx]
            (reset! seen {:args args :ctx ctx})
            "ok")
        host-ctx {:agent/tool-registry {"host" (->HostTool "host")}}]
    (is (= "ok" (sandbox/invoke-sandboxed f {:x 1} host-ctx #{"host"})))
    (is (= {:args {:x 1} :ctx nil} @seen))))
