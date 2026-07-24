(ns demo-mcp-http-session
  "Recordable demo: Integrant session with remote Streamable HTTP MCP.

   Starts an in-process fake Streamable HTTP MCP server, wires it through
   `:lateralus/mcp-tools`, and runs one agent exchange that calls
   `remote_echo` over HTTP.

   Run:
     clojure -M:dev -m demo-mcp-http-session"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [fake-mcp-http-server :as fake-http]
            [integrant.core :as ig]
            [kschltz.agent.llm.client :as llm]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.system :as system]
            [kschltz.agent.tool :as tool])
  (:import [java.util.concurrent.atomic AtomicInteger]))

(defn- banner [s]
  (println)
  (println (str/join "" (repeat 64 "=")))
  (println s)
  (println (str/join "" (repeat 64 "=")))
  (flush))

(defn- pause
  "Give screen recordings time to show each step."
  ([ms]
   (flush)
   (Thread/sleep (long ms)))
  ([]
   (pause 1500)))

(defn- scripted-remote-llm
  []
  (let [step (AtomicInteger. 0)]
    (reify llm/LlmClient
      (-call [_ _req]
        (let [n (.incrementAndGet step)]
          (case n
            1 {:model "demo/mcp-http"
               :choices
               [{:message
                 {:role "assistant"
                  :content ""
                  :tool_calls
                  [{:id "call_remote_echo"
                    :type "function"
                    :function
                    {:name "remote_echo"
                     :arguments
                     (json/generate-string
                      {:message "hello from remote Streamable HTTP MCP"})}}]}}]}
            {:model "demo/mcp-http"
             :choices
             [{:message
               {:role "assistant"
                :content
                (str "Done. Called remote MCP tool `remote_echo` over "
                     "Streamable HTTP (POST JSON-RPC to the fake server) "
                     "and got the echoed payload back through the agent loop.")}}]}))))))

(defn -main
  [& _]
  (banner "Lateralus MCP client — remote Streamable HTTP demo")
  (println "Starting in-process fake Streamable HTTP MCP server…")
  (flush)
  (let [{:keys [url stop!]} (fake-http/start! 0)]
    (try
      (println "MCP endpoint:" url)
      (println "Transport   : Streamable HTTP (application/json)")
      (println "Guards      : allow-http? + allow-loopback? (local fake only)")
      (pause 2000)
      (let [cfg (-> system/default-config
                    (assoc :lateralus/llm-client {:impl :stub}
                           :lateralus/runtime-tools {:enabled? false}
                           :lateralus/web-tools {:provider :none}
                           :lateralus/mcp-tools
                           {:servers
                            {"remote"
                             {:transport :http
                              :url url
                              :allow-http? true
                              :allow-loopback? true
                              :request-timeout-ms 15000}}}))
            _ (do (println "ig/init — connecting remote MCP + building tool registry…")
                  (flush))
            sys (ig/init cfg)]
        (try
          (let [registry (:lateralus/tool-registry sys)
                mcp-tools (vec (sort (filter #(str/starts-with? (str %) "remote_")
                                             (map str (keys registry)))))]
            (println)
            (println "Remote MCP tools discovered:" (count mcp-tools))
            (if (seq mcp-tools)
              (doseq [t mcp-tools]
                (println " -" t
                         (str "(" (tool/-description (get registry t)) ")")))
              (println " - (none — unexpected)"))
            (pause 3000)
            (let [agent (-> (:lateralus/agent sys)
                            (assoc :agent/llm-client (scripted-remote-llm)))
                  rt (runtime/start agent "demo-mcp-http-session")
                  user "Please echo a greeting through remote MCP HTTP."]
              (banner "User")
              (println user)
              (pause)
              (banner "Agent exchange (tool call → HTTP MCP → final answer)")
              (runtime/send-message rt user)
              (let [history (:agent/history (runtime/stop rt))]
                (doseq [msg history]
                  (println)
                  (println (str "[" (name (or (:role msg) "?")) "]"))
                  (when (seq (:content msg))
                    (println (:content msg)))
                  (when-let [calls (:tool_calls msg)]
                    (doseq [c calls]
                      (println "  tool_call:" (get-in c [:function :name])
                               (get-in c [:function :arguments]))))
                  (when (= "tool" (:role msg))
                    (println "  tool_result:" (:content msg)))
                  (pause 1200))
                (banner "Session complete — HTTP client + fake server halt")
                (println "Final assistant text:")
                (println (->> history
                              (filter #(= "assistant" (name (or (:role %) ""))))
                              last
                              :content))
                (pause 2500))))
          (finally
            (ig/halt! sys)
            (println)
            (println "ig/halt! — remote MCP client closed."))))
      (finally
        (stop!)
        (println "fake HTTP MCP server stopped.")
        (banner "OK")))
    (flush)
    (System/exit 0)))
