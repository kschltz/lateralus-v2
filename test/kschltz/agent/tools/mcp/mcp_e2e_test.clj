(ns kschltz.agent.tools.mcp.mcp-e2e-test
  "End-to-end tests for the MCP client suite.

   Offline (default under LATERALUS_E2E_FAKE=true, or always when this
   ns is selected): spawns `fake-mcp-server` as a real stdio subprocess.

   Live (opt-in): LATERALUS_E2E_MCP=live runs against
   `@modelcontextprotocol/server-filesystem` in a temp sandbox.

       LATERALUS_E2E_FAKE=true clojure -M:e2e -n kschltz.agent.tools.mcp.mcp-e2e-test
       LATERALUS_E2E_MCP=live clojure -M:e2e -n kschltz.agent.tools.mcp.mcp-e2e-test"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [kschltz.agent.system :as system]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.tools :as tools])
  (:import [java.io File]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(def ^:private fake-e2e?
  (or (= "true" (System/getenv "LATERALUS_E2E_FAKE"))
      (= "fake" (System/getenv "LATERALUS_E2E_MCP"))
      ;; Run offline subprocess e2e whenever this ns is included via -i :e2e
      ;; unless the operator asked for live-only.
      (not= "live" (System/getenv "LATERALUS_E2E_MCP"))))

(def ^:private live-e2e?
  (= "live" (System/getenv "LATERALUS_E2E_MCP")))

(defn- npx-available?
  []
  (try
    (let [p (.start (ProcessBuilder. (into-array String ["npx" "--version"])))]
      (.waitFor p 10 TimeUnit/SECONDS)
      (zero? (.exitValue p)))
    (catch Throwable _ false)))

(defn- workspace-root
  []
  (System/getProperty "user.dir"))

(deftest ^:e2e e2e-mcp-fake-subprocess-round-trip
  (when fake-e2e?
    (testing "real stdio fake-mcp-server: discover, echo, fail, halt"
      (let [cfg {:servers
                 {"fake"
                  {:command "clojure"
                   :args ["-M:dev" "-m" "fake-mcp-server"]
                   :cwd (workspace-root)
                   :startup-timeout-ms 180000
                   :request-timeout-ms 30000}}}
            reg (tools/mcp-registry cfg)
            clients (:mcp/clients (meta reg))]
        (try
          (is (>= (count reg) 2))
          (is (contains? reg "fake_echo"))
          (let [echo (json/parse-string
                      (tool/-invoke (get reg "fake_echo")
                                    {:message "e2e-hi"} {})
                      true)]
            (is (= "e2e-hi" (:content echo)))
            (is (= "ok" (:status echo))))
          (let [fail (json/parse-string
                      (tool/-invoke (get reg "fake_fail")
                                    {:reason "boom"} {})
                      true)]
            (is (= "error" (:status fail)))
            (is (true? (:isError fail))))
          (finally
            (tools/halt-registry! reg)
            (doseq [c clients]
              (is (true? (:closed? (proto/-server-info c)))))))))))

(deftest ^:e2e e2e-mcp-system-with-fake-server
  (when fake-e2e?
    (testing "Integrant system with fake MCP server"
      (let [cfg (-> system/default-config
                    (assoc :lateralus/mcp-tools
                           {:servers
                            {"fake"
                             {:command "clojure"
                              :args ["-M:dev" "-m" "fake-mcp-server"]
                              :cwd (workspace-root)
                              :startup-timeout-ms 180000}}}))
            sys (ig/init cfg)]
        (try
          (let [reg (:lateralus/tool-registry sys)]
            (is (contains? reg "fake_echo"))
            (is (contains? reg "file_read"))
            (is (re-find #"system-e2e"
                         (tool/-invoke (get reg "fake_echo")
                                       {:message "system-e2e"} {}))))
          (finally
            (ig/halt! sys)))))))

(deftest ^:e2e e2e-mcp-filesystem-server-read
  (when live-e2e?
    (if-not (npx-available?)
      (println "SKIP e2e-mcp-filesystem-server-read: npx not available")
      (let [sandbox (Files/createTempDirectory "lateralus-mcp-sandbox" (make-array java.nio.file.attribute.FileAttribute 0))
            sandbox-file (File. (.toFile sandbox) "hello.txt")]
        (try
          (spit sandbox-file "sandbox-hello")
          (let [cfg {:servers
                     {"filesystem"
                      {:command "npx"
                       :args ["-y" "@modelcontextprotocol/server-filesystem"
                              (.toString sandbox)]
                       :startup-timeout-ms 180000
                       :request-timeout-ms 60000}}}
                reg (tools/mcp-registry cfg)]
            (try
              (is (seq reg) "filesystem server should expose tools")
              ;; Prefer a read-like tool; names vary by server version.
              (let [read-tool (or (get reg "filesystem_read_file")
                                  (get reg "filesystem_read_text_file")
                                  (->> reg vals
                                       (filter #(re-find #"(?i)read"
                                                         (tool/-name %)))
                                       first))
                    path (.getAbsolutePath sandbox-file)]
                (is (some? read-tool) (str "no read tool in " (keys reg)))
                (when read-tool
                  (let [raw (tool/-invoke read-tool {:path path} {})
                        body (try (json/parse-string raw true)
                                  (catch Exception _ {:content raw}))]
                    (is (re-find #"sandbox-hello"
                                 (str (:content body) raw))))))
              (finally
                (tools/halt-registry! reg))))
          (finally
            (io/delete-file sandbox-file true)
            (Files/deleteIfExists sandbox)))))))
