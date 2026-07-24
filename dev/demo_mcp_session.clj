(ns demo-mcp-session
  "Recordable demo: real Integrant session with stdio MCP tools.

   Spawns `fake-mcp-server`, wires it through `:lateralus/mcp-tools`,
   and runs one agent exchange with a scripted LLM that calls
   `fake_echo` then answers the user.

   Run:
     clojure -M:dev -m demo-mcp-session"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [integrant.core :as ig]
            [kschltz.agent.llm.client :as llm]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.system :as system]
            [kschltz.agent.tool :as tool])
  (:import [java.util.concurrent.atomic AtomicInteger]))

(defn- banner [s]
  (println)
  (println (str/join "" (repeat 60 "=")))
  (println s)
  (println (str/join "" (repeat 60 "="))))

(defn- scripted-mcp-llm
  "LLM that first requests fake_echo, then returns a final answer."
  []
  (let [step (AtomicInteger. 0)]
    (reify llm/LlmClient
      (-call [_ _req]
        (let [n (.incrementAndGet step)]
          (case n
            1 {:model "demo/mcp"
               :choices
               [{:message
                 {:role "assistant"
                  :content ""
                  :tool_calls
                  [{:id "call_echo_1"
                    :type "function"
                    :function
                    {:name "fake_echo"
                     :arguments
                     (json/generate-string
                      {:message "hello from a real MCP session"})}}]}}]}
            {:model "demo/mcp"
             :choices
             [{:message
               {:role "assistant"
                :content
                (str "Done. I called the MCP tool `fake_echo` via the "
                     "stdio fake-mcp-server and got the echoed payload "
                     "back through the agent tool loop.")}}]}))))))

(defn- demo-config
  []
  (-> system/default-config
      (assoc :lateralus/llm-client {:impl :stub} ;; replaced after init
             :lateralus/mcp-tools
             {:servers
              {"fake"
               {:command "clojure"
                :args ["-M:dev" "-m" "fake-mcp-server"]
                :cwd (System/getProperty "user.dir")
                :startup-timeout-ms 180000
                :request-timeout-ms 30000}}}
             :lateralus/runtime-tools {:enabled? false}
             :lateralus/web-tools {:provider :none})))

(defn -main
  [& _]
  (banner "Lateralus MCP client — live session demo")
  (println "Spawning stdio MCP server: clojure -M:dev -m fake-mcp-server")
  (println "Wiring :lateralus/mcp-tools → tool registry → agent runtime")
  (println)
  (let [sys (ig/init (demo-config))]
    (try
      (let [registry (:lateralus/tool-registry sys)
            mcp-tools (sort (filter #(str/starts-with? % "fake_") (keys registry)))]
        (println "MCP tools discovered:")
        (doseq [t mcp-tools]
          (println " -" t (str "(" (tool/-description (get registry t)) ")")))
        (println)
        (let [agent (-> (:lateralus/agent sys)
                        (assoc :agent/llm-client (scripted-mcp-llm)))
              rt (runtime/start agent "demo-mcp-session")
              user "Please echo a greeting through MCP."]
          (banner "User")
          (println user)
          (banner "Agent exchange (tool call → MCP stdio → final answer)")
          (let [ctx (runtime/send-message rt user)
                hist (get-in ctx [:agent/state-delta :agent/history]
                             (get-in @(:state rt) [:agent/history]))
                ;; Prefer state after merge
                history (or (get (runtime/stop rt) :agent/history) hist)]
            (doseq [msg history]
              (let [role (name (or (:role msg) "?"))]
                (println)
                (println (str "[" role "]"))
                (when (seq (:content msg))
                  (println (:content msg)))
                (when-let [calls (:tool_calls msg)]
                  (doseq [c calls]
                    (println "  tool_call:" (get-in c [:function :name])
                             (get-in c [:function :arguments]))))
                (when (= "tool" (:role msg))
                  (println "  tool_result:" (:content msg)))))
            (banner "Session complete — MCP child will be halted")
            (println "Final assistant text:")
            (println (->> history
                          (filter #(= "assistant" (name (or (:role %) ""))))
                          last
                          :content)))))
      (finally
        (ig/halt! sys)
        (println)
        (println "ig/halt! — MCP server reaped.")
        (banner "OK")))
    (flush)
    (System/exit 0)))
