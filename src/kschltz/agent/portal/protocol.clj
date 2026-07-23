(ns kschltz.agent.portal.protocol
  "Protocol boundary for an interactive agent UI session.

   External UI surfaces (Portal, future web) implement `AgentUi`.
   The CLI interactive runner parks *only* this session between
   turns via `-await-human!`; the exchange chain / tool loop runs
   unparked once a human message arrives."
  (:refer-clojure :exclude [send]))

(defprotocol AgentUi
  "Bidirectional agent ↔ human surface."
  (-publish! [ui event]
    "Append a UI event (assistant/system/tool/…). Returns nil.")
  (-await-human! [ui opts]
    "Park the UI session until the human sends a reply.
     Returns a non-blank trimmed string. opts reserved for timeouts.")
  (-close! [ui]
    "Release UI resources. Idempotent."))

(defn publish!
  "Public wrapper for `-publish!`."
  [ui event]
  (-publish! ui event))

(defn await-human!
  "Public wrapper for `-await-human!`."
  ([ui] (await-human! ui {}))
  ([ui opts] (-await-human! ui opts)))

(defn close!
  "Public wrapper for `-close!`."
  [ui]
  (-close! ui))
