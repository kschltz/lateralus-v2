(ns kschltz.agent.store.protocol
  "Local persistence substrate for harness tables.

   This is not a network boundary and not the interceptor engine. Tools
   and interceptors never see JDBC. Leaf I/O lives in `store.duckdb`;
   tests use `store.memory`.")

(defprotocol StoreEngine
  "Structured table store. Implementations must not raise for empty
   results; they raise `ex-info` with `:error` on contract violations."
  (-upsert! [engine table pk-cols row]
    "Insert or replace `row` keyed by `pk-cols`. Returns {:rows n}.")
  (-insert! [engine table row]
    "Append `row`. Returns {:rows n}.")
  (-select [engine table opts]
    "Return a vector of row maps. `opts` may include `:where`, `:order`,
     and `:limit`.")
  (-delete! [engine table where]
    "Delete rows matching `where`. Returns {:rows n}.")
  (-close [engine]
    "Release resources. Idempotent."))

(defn store-engine?
  [x]
  (satisfies? StoreEngine x))
