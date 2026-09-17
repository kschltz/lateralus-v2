(ns kschltz.agent.evolution.process
  "Bounded argv-only process execution for trusted evolution adapters.

   This is deliberately not a model-facing shell. Programs, working roots,
   environment keys, output, time, and network posture are operator policy."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.evolution.protocol :as proto]
            [kschltz.agent.evolution.schemas :as schemas]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.io BufferedInputStream File]
           [java.lang ProcessHandle]
           [java.util.concurrent TimeUnit]))

(def RunnerOpts
  [:map
   [:allowed-programs [:set [:string {:min 1}]]]
   [:allowed-roots [:vector {:min 1} [:string {:min 1}]]]
   [:allowed-env {:optional true} [:set :string]]
   [:base-env {:optional true} [:map-of :string :string]]
   [:classpath-root {:optional true} [:string {:min 1}]]
   [:classpath {:optional true} [:string {:min 1}]]
   [:network-wrapper {:optional true} fn?]])

(defn- canonical
  [path]
  (.getCanonicalFile (io/file path)))

(declare within-root?)

(defn- workspace-classpath
  [classpath classpath-root cwd]
  (let [separator (System/getProperty "path.separator")
        root (when classpath-root (canonical classpath-root))
        workdir (canonical cwd)]
    (->> (str/split (or classpath "") (re-pattern (java.util.regex.Pattern/quote separator)))
         (map (fn [entry]
                (if (and root (not (str/blank? entry)))
                  (let [file (canonical entry)]
                    (if (within-root? file root)
                      (str (.resolve (.toPath workdir)
                                     (.relativize (.toPath root)
                                                  (.toPath file))))
                      entry))
                  entry)))
         (str/join separator))))

(defn- within-root?
  [^File file ^File root]
  (let [path (.toPath file)
        root-path (.toPath root)]
    (or (= path root-path) (.startsWith path root-path))))

(defn- bounded-slurp
  [stream max-bytes]
  (future
    (with-open [in (BufferedInputStream. stream)]
      (let [buf (byte-array 8192)
            out (java.io.ByteArrayOutputStream.)
            truncated? (volatile! false)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n)
              (let [remaining (- max-bytes (.size out))
                    keep (max 0 (min n remaining))]
                (when (pos? keep)
                  (.write out buf 0 keep))
                (when (< keep n)
                  (vreset! truncated? true)))
              (recur))))
        {:text (.toString out "UTF-8")
         :truncated? @truncated?}))))

(defn- workspace-profile
  [cwd network]
  (let [home (System/getProperty "user.home")
        canonical #(pr-str (.getCanonicalPath (io/file %)))
        dependency-roots (filter #(.exists (io/file %))
                                 [(str home "/.m2/repository")
                                  (str home "/.gitlibs/libs")])
        read-exceptions (cons cwd dependency-roots)]
    (str "(version 1) "
         "(allow default) "
         (when (= :deny network) "(deny network*) ")
         "(deny file-read-data (require-all "
         "(subpath " (canonical home) ") "
         (apply str
                (map #(str "(require-not (subpath " (canonical %) ")) ")
                     read-exceptions))
         ")) "
         "(deny file-write* (require-all "
         "(require-not (subpath " (canonical cwd) ")) "
         "(require-not (literal \"/dev/null\"))))")))

(defn- macos-isolation-wrapper
  [argv network isolation cwd]
  (let [sandbox (io/file "/usr/bin/sandbox-exec")
        available? (.canExecute sandbox)
        workspace? (= :workspace isolation)
        network? (and (= :deny network)
                      (contains? #{:network-only :workspace} isolation))
        profile (cond
                  workspace? (workspace-profile cwd network)
                  network? "(version 1) (allow default) (deny network*)"
                  :else nil)]
    (if (and profile available?)
      {:argv (into [(.getPath sandbox) "-p" profile "--"] argv)
       :network-isolated? (or (not= :deny network) network?)
       :workspace-isolated? workspace?
       :isolation-backend :sandbox-exec}
      {:argv argv
       :network-isolated? (not= :deny network)
       :workspace-isolated? (not workspace?)
       :isolation-backend :none})))

(defn- linux-isolation-wrapper
  [argv network isolation cwd]
  (let [bwrap (some #(let [file (io/file %)]
                       (when (.canExecute file) file))
                    ["/usr/bin/bwrap" "/bin/bwrap"])
        workspace? (= :workspace isolation)
        network? (and (= :deny network)
                      (contains? #{:network-only :workspace} isolation))]
    (if (and bwrap (or workspace? network?))
      (let [base [(.getPath ^File bwrap)
                  "--die-with-parent" "--new-session" "--unshare-all"]
            base (cond-> base
                   (not network?) (conj "--share-net"))
            mounts (if workspace?
                     ;; tmpfs must precede the workspace bind so /tmp worktrees
                     ;; remain reachable after the empty /tmp overlay.
                     ["--ro-bind" "/" "/"
                      "--tmpfs" "/tmp"
                      "--bind" cwd cwd
                      "--dev" "/dev" "--proc" "/proc"
                      "--chdir" cwd "--"]
                     ["--bind" "/" "/" "--dev-bind" "/dev" "/dev"
                      "--proc" "/proc" "--chdir" cwd "--"])]
        {:argv (into (into base mounts) argv)
         :network-isolated? (or (not= :deny network) network?)
         :workspace-isolated? workspace?
         :isolation-backend :bubblewrap})
      {:argv argv
       :network-isolated? (not= :deny network)
       :workspace-isolated? (not workspace?)
       :isolation-backend :none})))

(defn- platform-isolation-wrapper
  [argv network isolation cwd]
  (let [os (str/lower-case (System/getProperty "os.name" ""))]
    (cond
      (= :none isolation)
      {:argv argv
       :network-isolated? (not= :deny network)
       :workspace-isolated? true
       :isolation-backend :none}

      (str/includes? os "mac")
      (macos-isolation-wrapper argv network isolation cwd)

      (str/includes? os "linux")
      (linux-isolation-wrapper argv network isolation cwd)

      :else
      {:argv argv
       :network-isolated? (not= :deny network)
       :workspace-isolated? (not= :workspace isolation)
       :isolation-backend :none})))

(defn- isolation-error
  [network isolation network-isolated? workspace-isolated?]
  (cond
    (and (= :deny network)
         (contains? #{:network-only :workspace} isolation)
         (not network-isolated?))
    "required network isolation backend is unavailable"

    (and (= :workspace isolation) (not workspace-isolated?))
    "required workspace isolation backend is unavailable"))

(defn kill-process-tree!
  [^Process process]
  (try
    (with-open [descendants (.descendants (.toHandle process))]
      (doseq [^ProcessHandle handle (iterator-seq (.iterator descendants))]
        (.destroyForcibly handle)))
    (catch Throwable _))
  (.destroyForcibly process)
  nil)

(defn run-process!
  [{:keys [allowed-programs allowed-roots allowed-env base-env network-wrapper
           classpath-root classpath]}
   {:keys [argv cwd timeout-ms max-output-bytes env network isolation]}]
  (let [started (System/nanoTime)
        program (first argv)
        workdir (canonical cwd)
        roots (mapv canonical allowed-roots)]
    (cond
      (not (contains? allowed-programs program))
      {:status :rejected :exit-code nil :stdout "" :stderr ""
       :duration-ms 0 :truncated? false :network-isolated? false
       :workspace-isolated? false
       :error (str "program is not allowlisted: " program)}

      (not (some #(within-root? workdir %) roots))
      {:status :rejected :exit-code nil :stdout "" :stderr ""
       :duration-ms 0 :truncated? false :network-isolated? false
       :workspace-isolated? false
       :error (str "cwd is outside allowed roots: " (.getPath workdir))}

      (not (.isDirectory workdir))
      {:status :rejected :exit-code nil :stdout "" :stderr ""
       :duration-ms 0 :truncated? false :network-isolated? false
       :workspace-isolated? false
       :error (str "cwd is not a directory: " (.getPath workdir))}

      (not-every? #(contains? (or allowed-env #{}) %) (keys (or env {})))
      {:status :rejected :exit-code nil :stdout "" :stderr ""
       :duration-ms 0 :truncated? false :network-isolated? false
       :workspace-isolated? false
       :error "command requested a non-allowlisted environment key"}

      :else
      (let [isolation (or isolation :none)
            argv (mapv #(if (= "{{workspace-classpath}}" %)
                          (workspace-classpath classpath classpath-root
                                               (.getPath workdir))
                          %)
                       argv)
            tmp-dir (io/file workdir ".lateralus" "evolution-tmp")
            _ (when (= :workspace isolation) (.mkdirs tmp-dir))
            {wrapped :argv
             network-isolated? :network-isolated?
             workspace-isolated? :workspace-isolated?
             isolation-backend :isolation-backend}
            ((or network-wrapper platform-isolation-wrapper)
             argv network isolation (.getPath workdir))
            unavailable (isolation-error network isolation
                                         network-isolated?
                                         workspace-isolated?)]
        (if unavailable
          {:status :rejected :exit-code nil :stdout "" :stderr ""
           :duration-ms (long (/ (- (System/nanoTime) started) 1000000))
           :truncated? false
           :network-isolated? (boolean network-isolated?)
           :workspace-isolated? (boolean workspace-isolated?)
           :isolation-backend (or isolation-backend :none)
           :error unavailable}
          (let [builder (ProcessBuilder. ^java.util.List wrapped)
                _ (.directory builder workdir)
                process-env (.environment builder)
                _ (.clear process-env)
                command-env (cond-> (merge (or base-env {}) (or env {}))
                              (= :workspace isolation)
                              (assoc "HOME" (.getPath tmp-dir)
                                     "TMPDIR" (.getPath tmp-dir)))
                _ (.putAll process-env command-env)]
            (try
              (let [process (.start builder)
                stream-cap (max 1 (quot max-output-bytes 2))
                stdout (bounded-slurp (.getInputStream process) stream-cap)
                stderr (bounded-slurp (.getErrorStream process) stream-cap)
                completed? (.waitFor process timeout-ms TimeUnit/MILLISECONDS)
                _ (when-not completed?
                    (kill-process-tree! process)
                    (try (.close (.getInputStream process))
                         (catch Throwable _))
                    (try (.close (.getErrorStream process))
                         (catch Throwable _)))
                fallback {:text "" :truncated? true}
                out (deref stdout 2000 fallback)
                err (deref stderr 2000 fallback)
                duration (long (/ (- (System/nanoTime) started) 1000000))]
                {:status (cond
                           (not completed?) :timeout
                           (zero? (.exitValue process)) :ok
                           :else :failed)
                 :exit-code (when completed? (.exitValue process))
                 :stdout (:text out)
                 :stderr (:text err)
                 :duration-ms duration
                 :truncated? (or (:truncated? out) (:truncated? err))
                 :network-isolated? (boolean network-isolated?)
                 :workspace-isolated? (boolean workspace-isolated?)
                 :isolation-backend (or isolation-backend :none)})
              (catch Throwable t
                {:status :failed :exit-code nil :stdout "" :stderr ""
                 :duration-ms (long (/ (- (System/nanoTime) started) 1000000))
                 :truncated? false
                 :network-isolated? (boolean network-isolated?)
                 :workspace-isolated? (boolean workspace-isolated?)
                 :isolation-backend (or isolation-backend :none)
                 :error (or (ex-message t) (.getName (class t)))}))))))))

(defrecord LocalCommandRunner [opts]
  proto/CommandRunner
  (-run-command! [_ command]
    (run-process! opts command)))

(defn local-command-runner
  [{:keys [allowed-env base-env] :as opts}]
  (let [system-env (System/getenv)
        env-keys (or allowed-env #{"PATH" "HOME" "TMPDIR" "JAVA_HOME"})
        inherited (into {}
                        (keep (fn [k]
                                (when-let [v (get system-env k)] [k v])))
                        env-keys)]
    (->LocalCommandRunner
     (assoc opts
            :allowed-env env-keys
            :classpath (or (:classpath opts)
                           (System/getProperty "java.class.path"))
            :base-env (merge inherited (or base-env {}))))))

(m/=> kill-process-tree! [:=> [:cat any?] :nil])
(m/=> run-process!
      [:=> [:cat RunnerOpts schemas/CommandSpec] schemas/CommandResult])
(m/=> local-command-runner
      [:=> [:cat RunnerOpts] [:fn proto/command-runner?]])

(defn instrument! []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.evolution.process)]}))

(instrument!)
