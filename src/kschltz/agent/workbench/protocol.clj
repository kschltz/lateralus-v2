(ns kschltz.agent.workbench.protocol
  "Protocol boundary for the workbench (CHAT | Portal) plugin."
  (:refer-clojure :exclude [send]))

(defprotocol Workbench
  "Side-by-side chat + Portal visualizer surface."
  (-url [this] "Public URL of the workbench web UI.")
  (-portal-url [this] "Portal iframe URL, or nil when portal disabled.")
  (-publish! [this event] "Append a chat turn / system event.")
  (-await-human! [this opts] "Park until the human sends a chat message.")
  (-attach-selection! [this] "Capture current Portal selection as a ref chip.")
  (-submit-portal! [this label value] "Push a value into Portal; return a ref chip.")
  (-clear-portal! [this] "Clear Portal values.")
  (-snapshot [this] "Serializable UI state for the web client.")
  (-tools [this] "Map of tool-name -> Tool for the agent registry.")
  (-close! [this] "Stop HTTP + Portal resources."))

(defn url [wb] (-url wb))
(defn portal-url [wb] (-portal-url wb))
(defn publish! [wb event] (-publish! wb event))
(defn await-human! [wb] (-await-human! wb {}))
(defn await-human!* [wb opts] (-await-human! wb opts))
(defn attach-selection! [wb] (-attach-selection! wb))
(defn submit-portal! [wb label value] (-submit-portal! wb label value))
(defn clear-portal! [wb] (-clear-portal! wb))
(defn snapshot [wb] (-snapshot wb))
(defn tools [wb] (-tools wb))
(defn close! [wb] (-close! wb))
