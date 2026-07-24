(ns kschltz.agent.tools.mcp.test-util
  "Shared helpers for MCP unit tests — loopback fake server client."
  (:require [fake-mcp-server :as fake]
            [kschltz.agent.tools.mcp.client :as client]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.transport :as transport]))

(defn fake-loopback-client
  "Initialized McpClient backed by the in-process fake-mcp-server handler."
  ([]
   (fake-loopback-client "fake"))
  ([server-id]
   (let [transport (transport/loopback-transport fake/handle-message)
         c (client/make-client transport {:server-id server-id
                                          :startup-timeout-ms 5000
                                          :request-timeout-ms 5000})]
     (proto/-initialize! c)
     c)))
