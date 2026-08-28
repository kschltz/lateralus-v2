(ns kschltz.agent.session.protocol
  "Catalog of agent sessions: list, persist, switch, delete.

   Disk I/O stays behind this protocol. The workbench and runtime never
   read catalog files directly.")

(def session-id-pattern
  "CLI `-s` names and generated UUIDs."
  #"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

(defn session-id?
  [id]
  (and (string? id) (boolean (re-matches session-id-pattern id))))

(defprotocol SessionStore
  "Durable catalog of session metadata + workspace snapshots."
  (-list [store]
    "Seq of public session maps (no workspace payloads).")
  (-get [store id]
    "Full record including :turns/:refs/:agent-state, or nil.")
  (-upsert! [store record]
    "Insert or replace a full record. Returns the public view.")
  (-delete! [store id]
    "Remove a record. Returns true when it existed.")
  (-current-id [store]
    "Id of the active session, or nil.")
  (-set-current! [store id]
    "Mark `id` current. Returns id."))

(defn session-store?
  [x]
  (satisfies? SessionStore x))
