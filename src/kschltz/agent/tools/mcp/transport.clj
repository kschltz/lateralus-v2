(ns kschltz.agent.tools.mcp.transport
  "Stdio `McpTransport` — spawn a child process and speak newline JSON-RPC.

   Stderr is drained on a daemon thread so a chatty server cannot block.
   Incoming stdout lines are queued; `-recv!` polls with a timeout."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader
            OutputStreamWriter]
           [java.lang ProcessBuilder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean]))

(defn- raise
  [phase msg data]
  (throw (ex-info msg (merge {:phase phase} data))))

(defn- drain-stderr!
  [^java.lang.Process proc]
  (let [r (BufferedReader.
           (InputStreamReader. (.getErrorStream proc) StandardCharsets/UTF_8))]
    (doto (Thread.
           (fn []
             (try
               (loop []
                 (when-let [line (.readLine r)]
                   ;; Intentionally discard — never parse as protocol.
                   (when (System/getProperty "lateralus.mcp.debug")
                     (binding [*out* *err*]
                       (println "[mcp-stderr]" line)))
                   (recur)))
               (catch Throwable _))))
      (.setDaemon true)
      (.setName "mcp-stderr-drain")
      (.start))))

(defn- start-reader!
  [^BufferedReader reader ^LinkedBlockingQueue q ^AtomicBoolean closed?]
  (doto (Thread.
         (fn []
           (try
             (loop []
               (when-not (.get closed?)
                 (let [line (.readLine reader)]
                   (if (nil? line)
                     (.put q ::eof)
                     (do
                       (try
                         (.put q (json/parse-string line true))
                         (catch Throwable t
                           (.put q {:__parse-error true
                                    :message (ex-message t)
                                    :line line})))
                       (recur))))))
             (catch Throwable _
               (.put q ::eof)))))
    (.setDaemon true)
    (.setName "mcp-stdout-reader")
    (.start)))

(defn spawn-stdio!
  "Spawn `command`+`args` as a stdio MCP transport.

   Options: `:env` (string→string, merged into process env), `:cwd`.
   Raises `:phase :spawn` on failure to start."
  [{:keys [command args env cwd]}]
  (when (str/blank? command)
    (raise :spawn "MCP server :command is blank" {:command command}))
  (try
    (let [cmd (into-array String (cons command (map str (or args []))))
          pb (ProcessBuilder. ^"[Ljava.lang.String;" cmd)]
      (when cwd
        (.directory pb (io/file cwd)))
      (when (seq env)
        (let [penv (.environment pb)]
          (doseq [[k v] env]
            (.put penv (str k) (str v)))))
      (let [proc (.start pb)
            closed? (AtomicBoolean. false)
            q (LinkedBlockingQueue.)
            in (BufferedWriter.
                (OutputStreamWriter. (.getOutputStream proc) StandardCharsets/UTF_8))
            out (BufferedReader.
                 (InputStreamReader. (.getInputStream proc) StandardCharsets/UTF_8))]
        (drain-stderr! proc)
        (start-reader! out q closed?)
        (reify proto/McpTransport
          (-send! [_ message]
            (when (.get closed?)
              (raise :closed "MCP transport is closed" {:command command}))
            (try
              (let [line (json/generate-string (assoc message :jsonrpc "2.0"))]
                (.write in line)
                (.newLine in)
                (.flush in))
              (catch Throwable t
                (raise :closed (str "MCP transport write failed: " (ex-message t))
                       {:command command :cause t}))))
          (-recv! [_ timeout-ms]
            (when (.get closed?)
              (raise :closed "MCP transport is closed" {:command command}))
            (let [msg (.poll q (long (or timeout-ms 30000)) TimeUnit/MILLISECONDS)]
              (cond
                (nil? msg)
                (raise :timeout "MCP transport recv timed out"
                       {:command command :timeout-ms timeout-ms})

                (= ::eof msg)
                (raise :closed "MCP transport EOF"
                       {:command command})

                (:__parse-error msg)
                (raise :protocol (str "MCP stdout JSON parse failed: "
                                      (:message msg))
                       {:command command :line (:line msg)})

                :else msg)))
          (-close-transport! [_]
            (when (.compareAndSet closed? false true)
              (try (.close in) (catch Throwable _))
              (try
                (when (.isAlive proc)
                  (when-not (.waitFor proc 2 TimeUnit/SECONDS)
                    (.destroy proc)
                    (when-not (.waitFor proc 2 TimeUnit/SECONDS)
                      (.destroyForcibly proc))))
                (catch Throwable _))
              (try (.close out) (catch Throwable _))))
          (-alive? [_]
            (and (not (.get closed?)) (.isAlive proc))))))
    (catch Throwable t
      (raise :spawn (str "Failed to spawn MCP server: " (ex-message t))
             {:command command :args args :cause t}))))

(m/=> spawn-stdio!
      [:=>
       [:cat
        [:map
         [:command :string]
         [:args {:optional true} [:vector :string]]
         [:env {:optional true} [:map-of :string :string]]
         [:cwd {:optional true} [:maybe :string]]]]
       [:fn proto/transport?]])

(mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.mcp.transport)]})

(defn loopback-transport
  "In-process transport for tests: pairs two LinkedBlockingQueues.
   `handler` is `(fn [request-map] response-map-or-nil)` — nil means
   notification (no response). Returns a transport speaking the client side."
  [handler]
  (let [closed? (AtomicBoolean. false)
        inbound (LinkedBlockingQueue.)]
    (reify proto/McpTransport
      (-send! [_ message]
        (when (.get closed?)
          (raise :closed "loopback transport closed" {}))
        (when-let [resp (handler message)]
          (.put inbound resp)))
      (-recv! [_ timeout-ms]
        (when (.get closed?)
          (raise :closed "loopback transport closed" {}))
        (let [msg (.poll inbound (long (or timeout-ms 30000)) TimeUnit/MILLISECONDS)]
          (cond
            (nil? msg) (raise :timeout "loopback recv timed out" {})
            :else msg)))
      (-close-transport! [_] (.set closed? true))
      (-alive? [_] (not (.get closed?))))))
