(ns kschltz.agent.store.duckdb
  "DuckDB JDBC StoreEngine. JVM-only; excluded from the native-image
   classpath. Never auto-INSTALL extensions (air-gapped default)."
  (:require [clojure.string :as str]
            [kschltz.agent.store.protocol :as proto]
            [kschltz.agent.store.schemas :as schemas]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.sql Connection DriverManager PreparedStatement ResultSet]))

(def ^:private table-sql
  {:file_index "file_index"
   :file_edits "file_edits"})

(def ^:private file-index-cols
  [:path :sha256 :size :mtime :content :indexed-at])

(def ^:private file-edits-cols
  [:id :path :tool :sha256-before :sha256-after :start-line :end-line :ts])

(def ^:private col-sql
  {:indexed-at "indexed_at"
   :sha256-before "sha256_before"
   :sha256-after "sha256_after"
   :start-line "start_line"
   :end-line "end_line"})

(defn- sql-col
  [k]
  (or (get col-sql k) (name k)))

(defn- row->sql-vals
  [cols row]
  (mapv #(get row %) cols))

(defn- result-row
  [^ResultSet rs cols]
  (reduce (fn [acc [i k]]
            (let [v (.getObject rs (int i))]
              (assoc acc k (cond
                             (instance? Integer v) (long v)
                             (instance? Long v) (long v)
                             :else v))))
          {}
          (map-indexed (fn [i k] [(inc i) k]) cols)))

(defn- bind!
  [^PreparedStatement ps params]
  (doseq [[i v] (map-indexed vector params)]
    (if (nil? v)
      (.setObject ps (int (inc i)) nil)
      (.setObject ps (int (inc i)) v))))

(defn jdbc-execute!
  "Run a parameterized DML/DDL statement. Local I/O leaf."
  [{:keys [^Connection conn sql params]}]
  (with-open [ps (.prepareStatement conn sql)]
    (bind! ps (or params []))
    {:rows (.executeUpdate ps)}))

(defn jdbc-query
  "Run a parameterized SELECT and return Clojure row maps. Local I/O leaf."
  [{:keys [^Connection conn sql params cols]}]
  (with-open [ps (.prepareStatement conn sql)]
    (bind! ps (or params []))
    (with-open [rs (.executeQuery ps)]
      (loop [acc []]
        (if (.next rs)
          (recur (conj acc (result-row rs cols)))
          acc)))))

(m/=> jdbc-execute!
      [:=> [:cat [:map
                  [:conn any?]
                  [:sql :string]
                  [:params {:optional true} [:maybe [:vector :any]]]]]
       schemas/ExecResult])

(m/=> jdbc-query
      [:=> [:cat [:map
                  [:conn any?]
                  [:sql :string]
                  [:params {:optional true} [:maybe [:vector :any]]]
                  [:cols [:vector :keyword]]]]
       [:vector schemas/Row]])

(defn- ensure-schema!
  [^Connection conn]
  (doseq [sql ["SET autoinstall_known_extensions = false"
               "SET autoload_known_extensions = false"
               (str "CREATE TABLE IF NOT EXISTS file_index ("
                    "path VARCHAR PRIMARY KEY, "
                    "sha256 VARCHAR, "
                    "size BIGINT, "
                    "mtime BIGINT, "
                    "content VARCHAR, "
                    "indexed_at BIGINT)")
               (str "CREATE TABLE IF NOT EXISTS file_edits ("
                    "id VARCHAR PRIMARY KEY, "
                    "path VARCHAR, "
                    "tool VARCHAR, "
                    "sha256_before VARCHAR, "
                    "sha256_after VARCHAR, "
                    "start_line INTEGER, "
                    "end_line INTEGER, "
                    "ts BIGINT)")]]
    (jdbc-execute! {:conn conn :sql sql :params []})))

(defn- where-sql
  [where]
  (let [{:keys [path path-prefix id]} where
        parts (cond-> []
                path (conj ["path = ?" path])
                id (conj ["id = ?" id])
                path-prefix (conj ["(path = ? OR path LIKE ?)"
                                   path-prefix
                                   (str path-prefix "/%")]))]
    {:clause (if (seq parts)
               (str " WHERE " (str/join " AND " (map first parts)))
               "")
     :params (vec (mapcat rest parts))}))

(defn- cols-for
  [table]
  (case table
    :file_index file-index-cols
    :file_edits file-edits-cols
    (throw (ex-info "Unknown store table" {:error :unknown-table :table table}))))

(defrecord DuckDbEngine [^Connection conn]
  proto/StoreEngine
  (-upsert! [_ table pk-cols row]
    (let [cols (cols-for table)
          names (str/join ", " (map sql-col cols))
          marks (str/join ", " (repeat (count cols) "?"))
          sql (str "INSERT OR REPLACE INTO " (table-sql table)
                   " (" names ") VALUES (" marks ")")]
      (jdbc-execute! {:conn conn :sql sql :params (row->sql-vals cols row)})))
  (-insert! [_ table row]
    (let [cols (cols-for table)
          names (str/join ", " (map sql-col cols))
          marks (str/join ", " (repeat (count cols) "?"))
          sql (str "INSERT INTO " (table-sql table)
                   " (" names ") VALUES (" marks ")")]
      (jdbc-execute! {:conn conn :sql sql :params (row->sql-vals cols row)})))
  (-select [_ table {:keys [where order limit]}]
    (let [cols (cols-for table)
          {:keys [clause params]} (where-sql (or where {}))
          order-sql (when (seq order)
                      (str " ORDER BY " (str/join ", " (map sql-col order))))
          limit-sql (when limit (str " LIMIT " (long limit)))
          sql (str "SELECT " (str/join ", " (map sql-col cols))
                   " FROM " (table-sql table)
                   clause order-sql limit-sql)]
      (jdbc-query {:conn conn :sql sql :params params :cols cols})))
  (-delete! [_ table where]
    (let [{:keys [clause params]} (where-sql (or where {}))
          sql (str "DELETE FROM " (table-sql table) clause)]
      (jdbc-execute! {:conn conn :sql sql :params params})))
  (-close [_]
    (when-not (.isClosed conn)
      (.close conn))))

(defn- jdbc-url
  [path]
  (if (or (nil? path) (str/blank? path) (= ":memory:" path))
    "jdbc:duckdb:"
    (str "jdbc:duckdb:" path)))

(defn duckdb-store
  "Open a DuckDB StoreEngine. `path` nil or `:memory:` is in-memory.
   Disables extension auto-install so air-gapped runs do not phone home."
  ([] (duckdb-store {}))
  ([{:keys [path]}]
   (Class/forName "org.duckdb.DuckDBDriver")
   (let [conn (DriverManager/getConnection (jdbc-url path))]
     (ensure-schema! conn)
     (->DuckDbEngine conn))))

(m/=> duckdb-store
      [:function
       [:=> [:cat] [:fn proto/store-engine?]]
       [:=> [:cat :any] [:fn proto/store-engine?]]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.store.duckdb)]}))

(instrument!)
