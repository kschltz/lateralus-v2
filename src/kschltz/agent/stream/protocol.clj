(ns kschltz.agent.stream.protocol
  "In-process bus for live + historic exchange metadata.
   Not a network boundary — subscribers are local (workbench HTTP).")

(defprotocol StreamBus
  "Publish/subscribe store for one workbench/runtime process."
  (-open-turn! [bus meta]
    "Start a live turn. Returns turn-id.")
  (-emit! [bus turn-id event]
    "Append a StreamEvent (or map) to the turn.")
  (-close-turn! [bus turn-id status extra]
    "Mark the turn done/error. `status` is :done or :error.")
  (-snapshot [bus turn-id]
    "Historic-or-live public map, or nil when unknown.")
  (-current-id [bus]
    "Id of the open live turn, or nil.")
  (-events-since [bus turn-id seq-n]
    "Events with :seq > seq-n, plus the turn's :rev.")
  (-latest-id [bus]
    "Most recently opened turn id, live or historic, or nil."))

(defn stream-bus? [x]
  (satisfies? StreamBus x))
