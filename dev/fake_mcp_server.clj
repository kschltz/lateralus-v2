(ns fake-mcp-server
  "Deterministic stdio MCP server for Lateralus tests and demos.

   Speaks JSON-RPC newline framing on stdin/stdout. Implements:
     initialize / notifications/initialized
     tools/list
     tools/call  — echo, add, fail

   Launch:
     clojure -M:dev -m fake-mcp-server

   Stderr may receive diagnostics; stdout is protocol-only."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader InputStreamReader]
           [java.nio.charset StandardCharsets]))

(def ^:private tools
  [{:name "echo"
    :description "Echo back the provided message"
    :inputSchema {:type "object"
                  :properties {:message {:type "string"}}
                  :required ["message"]}}
   {:name "add"
    :description "Add two numbers"
    :inputSchema {:type "object"
                  :properties {:a {:type "number"}
                               :b {:type "number"}}
                  :required ["a" "b"]}}
   {:name "fail"
    :description "Always fails with isError true"
    :inputSchema {:type "object"
                  :properties {:reason {:type "string"}}}}])

(defn handle-message
  "Pure request handler. Returns a response map, or nil for notifications."
  [msg]
  (let [{:keys [id method params]} msg]
    (case method
      "initialize"
      {:jsonrpc "2.0"
       :id id
       :result {:protocolVersion "2024-11-05"
                :capabilities {:tools {}}
                :serverInfo {:name "fake-mcp-server"
                             :version "0.1.0"}}}

      "notifications/initialized"
      nil

      "tools/list"
      {:jsonrpc "2.0"
       :id id
       :result {:tools tools}}

      "tools/call"
      (let [name (:name params)
            args (or (:arguments params) {})]
        (case name
          "echo"
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (str (:message args))}]
                    :isError false}}

          "add"
          (let [a (:a args)
                b (:b args)
                sum (+ (double (or a 0)) (double (or b 0)))]
            {:jsonrpc "2.0"
             :id id
             :result {:content [{:type "text" :text (str sum)}]
                      :isError false}})

          "fail"
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (or (:reason args) "forced failure")}]
                    :isError true}}

          {:jsonrpc "2.0"
           :id id
           :error {:code -32601
                   :message (str "Unknown tool: " name)}}))

      ;; Unknown methods
      (when id
        {:jsonrpc "2.0"
         :id id
         :error {:code -32601
                 :message (str "Method not found: " method)}}))))

(defn -main
  [& _]
  (let [reader (BufferedReader.
                (InputStreamReader. System/in StandardCharsets/UTF_8))]
    (loop []
      (when-let [line (.readLine reader)]
        (when-not (clojure.string/blank? line)
          (try
            (let [msg (json/parse-string line true)
                  resp (handle-message msg)]
              (when resp
                (binding [*out* (io/writer System/out)]
                  (println (json/generate-string resp))
                  (flush))))
            (catch Throwable t
              (binding [*out* *err*]
                (println "fake-mcp-server error:" (ex-message t))))))
        (recur)))))
