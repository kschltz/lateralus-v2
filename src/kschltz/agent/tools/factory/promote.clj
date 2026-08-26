(ns kschltz.agent.tools.factory.promote
  "Write a runtime tool spec to disk as a reusable Tool / plugin."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.tools.factory.protocol :as proto]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def catalog-relpath
  ".lateralus/promoted/catalog.edn")

(defn- raise
  [phase msg data]
  (throw (ex-info msg (merge {:phase phase} data))))

(defn kebab-name
  "Portable tool name `add_two` → `add-two`."
  [tool-name]
  (str/replace (str tool-name) #"_" "-"))

(defn record-name
  "Portable tool name `add_two` → `AddTwoTool`."
  [tool-name]
  (str (->> (str/split (str tool-name) #"[_\-]+")
            (map str/capitalize)
            (str/join))
       "Tool"))

(defn project-ns
  [tool-name]
  (symbol (str "kschltz.agent.tools.promoted." (kebab-name tool-name))))

(defn project-plugin-ns
  [tool-name]
  (symbol (str "kschltz.agent.plugins.promoted." (kebab-name tool-name))))

(defn workspace-ns
  [tool-name]
  (symbol (str "lateralus.promoted." (kebab-name tool-name))))

(defn workspace-plugin-ns
  [tool-name]
  (symbol (str "lateralus.promoted." (kebab-name tool-name) ".plugin")))

(defn- ns->path
  [root ns-sym]
  (str (.getPath (io/file root
                          (-> (str ns-sym)
                              (str/replace #"\." "/")
                              (str/replace #"-" "_")
                              (str ".clj"))))))

(defn- test-ns
  [src-ns]
  (symbol (str src-ns "-test")))

(defn- write-file!
  [path contents]
  (io/make-parents path)
  (spit path contents)
  path)

(defn- tool-source
  [ns-sym spec]
  (let [rec (record-name (:name spec))]
    (str "(ns " ns-sym "\n"
         "  \"Promoted runtime tool: " (:name spec) "\"\n"
         "  (:require [kschltz.agent.tool :as tool]))\n\n"
         "(def input-schema\n  " (:input-schema spec) ")\n\n"
         "(def invoke*\n  " (:invoke spec) ")\n\n"
         "(defrecord " rec " []\n"
         "  tool/Tool\n"
         "  (-name [_] " (pr-str (:name spec)) ")\n"
         "  (-description [_] " (pr-str (:description spec)) ")\n"
         "  (-input-schema [_] input-schema)\n"
         "  (-output-schema [_] :string)\n"
         "  (-invoke [_ args ctx]\n"
         "    (let [ret (invoke* args ctx)]\n"
         "      (if (string? ret) ret (pr-str ret)))))\n\n"
         "(defn registry\n"
         "  []\n"
         "  {" (pr-str (:name spec)) " (->" rec ")})\n")))

(defn- plugin-source
  [plugin-ns tool-ns spec]
  (let [slot (or (:interceptor-slot spec) :guard)
        extra? (some spec [:interceptor-enter :interceptor-leave :interceptor-error])
        seed (str "   {:name ::seed\n"
                  "    :slot :guard\n"
                  "    :enter (fn [ctx]\n"
                  "             (update ctx :agent/tool-registry merge (t/registry)))}")
        custom (when extra?
                 (str "\n   {:name ::custom\n"
                      "    :slot " slot "\n"
                      (when (:interceptor-enter spec)
                        (str "    :enter " (:interceptor-enter spec) "\n"))
                      (when (:interceptor-leave spec)
                        (str "    :leave " (:interceptor-leave spec) "\n"))
                      (when (:interceptor-error spec)
                        (str "    :error " (:interceptor-error spec) "\n"))
                      "}"))]
    (str "(ns " plugin-ns "\n"
         "  (:require [" tool-ns " :as t]))\n\n"
         "(defn plugin\n"
         "  []\n"
         "  (with-meta\n"
         "    [" seed custom "]\n"
         "    {:plugin/name " (pr-str (keyword "promoted" (:name spec))) "\n"
         "     :plugin/rebuild plugin}))\n")))

(defn- test-source
  [test-ns tool-ns spec]
  (str "(ns " test-ns "\n"
       "  (:require [clojure.test :refer [deftest is]]\n"
       "            [kschltz.agent.tool :as tool]\n"
       "            [" tool-ns " :as t]))\n\n"
       "(deftest registry-exposes-promoted-tool\n"
       "  (let [reg (t/registry)\n"
       "        tool (get reg " (pr-str (:name spec)) ")]\n"
       "    (is (tool/tool? tool))\n"
       "    (is (= " (pr-str (:name spec)) " (tool/-name tool)))))\n"))

(defn read-catalog
  "Read `{workspace}/.lateralus/promoted/catalog.edn`, or []."
  [workspace-root]
  (let [f (io/file workspace-root catalog-relpath)]
    (if (.isFile f)
      (let [parsed (edn/read-string (slurp f))]
        (if (vector? parsed) parsed []))
      [])))

(defn- write-catalog!
  [workspace-root entries]
  (let [path (.getPath (io/file workspace-root catalog-relpath))]
    (write-file! path (pr-str (vec entries)))
    path))

(defn- upsert-catalog
  [entries entry]
  (let [name (:name entry)]
    (conj (vec (remove #(= name (:name %)) entries)) entry)))

(defn- load-ns!
  [{:keys [target path ns-sym]}]
  (if (and (= :project target)
           (try (require ns-sym :reload) true
                (catch Throwable _ false)))
    {:loaded ns-sym :path path :via :require}
    (do (load-file path)
        {:loaded ns-sym :path path :via :load-file})))

(defn promote-spec
  "Write tool (and optional plugin + test) files for `spec`.

   `:target` is `:workspace` (default, under `.lateralus/promoted/`)
   or `:project` (`src/kschltz/agent/...` + matching test).
   Returns a status map with paths and catalog entry."
  [spec {:keys [workspace-root target as-plugin]
         :or {workspace-root "."
              target :workspace
              as-plugin false}}]
  (when-not (proto/valid-tool-spec? spec)
    (raise :promote "invalid tool spec" {:spec spec}))
  (let [root (str workspace-root)
        target (keyword (or target :workspace))
        name (:name spec)
        as-plugin? (boolean as-plugin)
        tool-ns (if (= :project target) (project-ns name) (workspace-ns name))
        plugin-ns (when as-plugin?
                    (if (= :project target)
                      (project-plugin-ns name)
                      (workspace-plugin-ns name)))
        src-root (if (= :project target)
                   (.getPath (io/file root "src"))
                   (.getPath (io/file root ".lateralus" "promoted" name)))
        tool-path (if (= :project target)
                    (ns->path src-root tool-ns)
                    (.getPath (io/file src-root "tool.clj")))
        plugin-path (when as-plugin?
                      (if (= :project target)
                        (ns->path src-root plugin-ns)
                        (.getPath (io/file src-root "plugin.clj"))))
        test-path (when (= :project target)
                    (ns->path (.getPath (io/file root "test"))
                              (test-ns tool-ns)))
        written [(write-file! tool-path (tool-source tool-ns spec))]
        written (cond-> written
                  plugin-path
                  (conj (write-file! plugin-path
                                     (plugin-source plugin-ns tool-ns spec)))
                  test-path
                  (conj (write-file! test-path
                                     (test-source (test-ns tool-ns) tool-ns spec))))
        loaded (load-ns! {:target target :path tool-path :ns-sym tool-ns})
        plugin-loaded (when plugin-path
                        (load-ns! {:target target :path plugin-path :ns-sym plugin-ns}))
        entry (cond-> {:name name
                       :ns (str tool-ns)
                       :path tool-path
                       :target (keyword target)}
                plugin-ns (assoc :plugin-ns (str plugin-ns)
                                 :plugin-path plugin-path))
        catalog (write-catalog! root (upsert-catalog (read-catalog root) entry))]
    {:ok true
     :tool-name name
     :target (keyword target)
     :ns (str tool-ns)
     :paths written
     :catalog catalog
     :loaded loaded
     :plugin-loaded plugin-loaded
     :entry entry}))

(m/=> kebab-name [:=> [:cat :string] :string])
(m/=> record-name [:=> [:cat :string] :string])
(m/=> promote-spec [:=> [:cat proto/ToolSpec :map] :map])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.factory.promote)]}))

(instrument!)
