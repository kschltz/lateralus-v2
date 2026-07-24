(ns kschltz.agent.tools.mcp.names
  "Portable tool-name remapping for MCP → Lateralus.

   Locked policy: always prefix with a sanitized server id
   (`filesystem_read_file`) unless `:tool-name-prefix` is explicitly
   set (including `\"\"` to opt out of prefixing). Hyphens become
   underscores; other illegal characters are stripped. Init fails if a
   name is still non-portable or collides after resolution."
  (:require [clojure.string :as str]
            [kschltz.agent.tool :as tool]))

(defn sanitize-segment
  "Sanitize a server id or MCP tool name fragment into the portable
   charset subset: ASCII letters, digits, underscore. Leading digits
   get an `m_` prefix so the result can start a portable tool name."
  [s]
  (let [raw (-> (str s)
                (str/replace "-" "_")
                (str/replace #"[^A-Za-z0-9_]" "")
                (str/replace #"_+" "_")
                (str/replace #"^_+" "")
                (str/replace #"_+$" ""))]
    (cond
      (str/blank? raw) "tool"
      (re-matches #"^[0-9].*" raw) (str "m_" raw)
      :else raw)))

(defn sanitize-tool-name
  "Remap a raw MCP tool name (no server prefix) to a portable fragment."
  [mcp-name]
  (sanitize-segment mcp-name))

(defn default-prefix
  "Default tool-name prefix for `server-id`: `<sanitized>_`."
  [server-id]
  (str (sanitize-segment server-id) "_"))

(defn resolve-prefix
  "Resolve the prefix string for a server. `nil` tool-name-prefix means
   use `default-prefix`. Explicit `\"\"` means no prefix."
  [server-id tool-name-prefix]
  (if (nil? tool-name-prefix)
    (default-prefix server-id)
    (str tool-name-prefix)))

(defn qualify-name
  "Build the Lateralus tool name from server id, optional prefix override,
   and raw MCP tool name."
  [server-id tool-name-prefix mcp-name]
  (let [prefix (resolve-prefix server-id tool-name-prefix)
        base   (sanitize-tool-name mcp-name)
        full   (str prefix base)
        ;; Cap at 64 chars (portable-tool-name max).
        clipped (if (> (count full) 64)
                  (subs full 0 64)
                  full)]
    clipped))

(defn resolve-tool-names
  "Resolve MCP tool descriptors for one server into a map of
   portable-name → descriptor (with `::mcp-name` and `::lateralus-name`).

   `already` is a set of names claimed by earlier servers. Throws
   `ex-info` with `:phase :protocol` on non-portable or colliding names."
  [server-id tool-name-prefix descriptors already]
  (reduce
   (fn [acc desc]
     (let [mcp-name (:name desc)
           lateralus (qualify-name server-id tool-name-prefix mcp-name)]
       (when-not (tool/portable-tool-name? lateralus)
         (throw (ex-info (str "MCP tool name is not portable after remap: "
                              (pr-str mcp-name) " → " (pr-str lateralus))
                         {:phase :protocol
                          :server server-id
                          :mcp-name mcp-name
                          :lateralus-name lateralus})))
       (when (or (contains? already lateralus)
                 (contains? acc lateralus))
         (throw (ex-info (str "MCP tool name collision: " (pr-str lateralus))
                         {:phase :protocol
                          :server server-id
                          :mcp-name mcp-name
                          :lateralus-name lateralus
                          :already (vec already)})))
       (assoc acc lateralus
              (assoc desc
                     ::mcp-name mcp-name
                     ::lateralus-name lateralus
                     ::server-id server-id))))
   {}
   descriptors))
