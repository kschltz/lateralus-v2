(ns kschltz.agent.tools.mcp.url
  "URL / SSRF guards for remote MCP HTTP endpoints.

   Reuses `tools.web.guards/validate-url` so we do not invent a second
   private-IP / metadata vocabulary. Defaults are stricter for remote
   MCP: https-only unless `:allow-http? true`, and loopback/private
   blocked unless explicitly allowed (local fake servers)."
  (:require [kschltz.agent.tools.web.guards :as web.guards]))

(def ^:private all-ports
  "When loopback is explicitly allowed (local fake MCP), any port is
   acceptable — Jetty binds ephemeral ports in tests."
  (into #{} (range 1 65536)))

(defn guard-config
  "Build a web-guards config map from an HTTP MCP server stanza.

   Note: `tools.web.guards` always blocks loopback via InetAddress
   checks. When `:allow-loopback?` is true we put loopback hosts on
   `:url-allow-list` so validation short-circuits before DNS (same
   escape hatch operators use for web tools)."
  [{:keys [allow-http? allow-loopback? block-private-ips?]
    :or {allow-http? false
         allow-loopback? false
         block-private-ips? true}}]
  (merge (web.guards/default-guard-config)
         {:allowed-schemes (if allow-http?
                             #{"http" "https"}
                             #{"https"})
          :block-loopback? (not allow-loopback?)
          :block-private-ips? (if allow-loopback?
                                false
                                (boolean block-private-ips?))
          :allowed-ports (if allow-loopback?
                           all-ports
                           #{80 443})
          :url-allow-list (if allow-loopback?
                            ["127.0.0.1" "localhost" "::1"]
                            [])}))

(defn validate-mcp-url
  "Validate `url` for an HTTP MCP stanza. Returns
   `{:allow? bool :url s :reason s}` (same shape as web guards).

   Raises nothing — callers raise `:phase :ssrf` on deny."
  [url server-cfg]
  (web.guards/validate-url url (guard-config server-cfg)))

(defn assert-mcp-url!
  "Validate `url` or throw `ex-info` with `:phase :ssrf`."
  [url server-cfg]
  (let [check (validate-mcp-url url server-cfg)]
    (when-not (:allow? check)
      (throw (ex-info (str "MCP URL rejected: " (:reason check))
                      {:phase :ssrf
                       :url url
                       :reason (:reason check)})))
    (:url check)))
