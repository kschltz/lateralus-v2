(ns kschltz.agent.tools.mcp.adapt
  "Adapt MCP tool descriptors into Lateralus `Tool` records."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.mcp.guards :as guards]
            [kschltz.agent.tools.mcp.json-schema :as json-schema]
            [kschltz.agent.tools.mcp.names :as names]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.schemas :as schemas]))

(defn- json-envelope
  [m]
  (json/generate-string m))

(defn- content->text
  "Flatten MCP content blocks into a single string."
  [content]
  (->> (or content [])
       (map (fn [block]
              (cond
                (string? block) block
                (map? block) (or (:text block)
                                 (when (= "text" (or (:type block)
                                                     (get block "type")))
                                   (or (:text block) (get block "text")))
                                 (pr-str block))
                :else (pr-str block))))
       (str/join "\n")))

(defn- phase-of
  [^Throwable t]
  (let [p (:phase (ex-data t))]
    (if (keyword? p) (name p) "tool")))

(defn adapt-tool
  "Build a `Tool` for one resolved descriptor.

   `desc` must include `::names/mcp-name` and `::names/lateralus-name`
   (as produced by `names/resolve-tool-names`). `client` is the live
   `McpClient`. `opts` may include `:max-result-bytes`."
  [client desc {:keys [max-result-bytes server-id]}]
  (let [lateralus-name (::names/lateralus-name desc)
        mcp-name (::names/mcp-name desc)
        server-id (or server-id (::names/server-id desc) "mcp")
        input-schema (json-schema/json-schema->malli (:inputSchema desc))
        description (or (:description desc)
                        (str "MCP tool " mcp-name " from server " server-id))]
    (reify tool/Tool
      (-name [_] lateralus-name)
      (-description [_] description)
      (-input-schema [_] input-schema)
      (-output-schema [_] schemas/OutputString)
      (-invoke [_ args _ctx]
        (try
          (let [result (proto/-call-tool client mcp-name (or args {}))
                raw (content->text (:content result))
                guarded (guards/guard-result-text
                         raw
                         {:max-result-bytes max-result-bytes})
                base {:server server-id
                      :tool lateralus-name
                      :mcp-tool mcp-name
                      :isError (boolean (:isError result))
                      :content (:text guarded)
                      :truncated? (:truncated? guarded)
                      :blocked? (:blocked? guarded)
                      :status (cond
                                (:blocked? guarded) "blocked"
                                (:isError result) "error"
                                (:truncated? guarded) "truncated"
                                :else "ok")}]
            (json-envelope
             (cond-> base
               (:reason guarded) (assoc :reason (:reason guarded)))))
          (catch Throwable t
            (json-envelope
             {:error (ex-message t)
              :phase (phase-of t)
              :server server-id
              :tool lateralus-name
              :status "error"})))))))

(defn adapt-tools
  "Adapt a map of lateralus-name → descriptor into a Tool registry map."
  [client resolved-descs opts]
  (into {}
        (map (fn [[_lateralus desc]]
               (let [tool (adapt-tool client desc opts)]
                 [(tool/-name tool) tool])))
        resolved-descs))
