(ns kschltz.agent.tools.runtime.convergence
  "Classpath/basis preflight for `clojure_add_lib`.

   Reads the Clojure CLI basis file (local, no network) so we can warn
   when a requested lib or a known transitive needs a newer version than
   the already-loaded jar. Parent-first classloading will keep the stale
   version even after add-libs appends a newer jar."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def known-transitives
  "Hard-won conflicts from live Clerk/nippy sessions."
  {["io.github.nextjournal/clerk"]
   [{:lib 'com.taoensso/encore
     :min "3.148.0"
     :reason "clerk's nippy 3.4.2 needs encore/latom (absent before 3.148.0)"}]
   ["com.taoensso/nippy"]
   [{:lib 'com.taoensso/encore
     :min "3.148.0"
     :reason "nippy 3.4.2+ references taoensso.encore/latom"}]})

(defn- parse-version
  [s]
  (when (string? s)
    (mapv #(try (Integer/parseInt %) (catch Exception _ 0))
          (take 3 (str/split s #"[\.-]")))))

(defn version-older?
  "True when `have` is a strictly older dotted version than `need`."
  [have need]
  (let [a (parse-version have)
        b (parse-version need)]
    (boolean (and a b (neg? (compare a b))))))

(defn read-basis-libs
  "Return the `:libs` map from `clojure.basis`, or nil when absent."
  ([]
   (read-basis-libs (System/getProperty "clojure.basis")))
  ([basis-path]
   (when (seq basis-path)
     (let [f (io/file basis-path)]
       (when (.isFile f)
         (try
           (:libs (edn/read-string (slurp f)))
           (catch Throwable _ nil)))))))

(defn basis-version
  [libs lib-sym]
  (when (map? libs)
    (or (get-in libs [lib-sym :mvn/version])
        (get-in libs [(str lib-sym) :mvn/version]))))

(defn preflight
  "Return a conflict map when the basis already has an older version of
   `lib` or a known transitive, otherwise nil.

   `lib` is the model-facing string (\"group/artifact\")."
  ([lib] (preflight lib (read-basis-libs)))
  ([lib libs]
   (when (and (string? lib) (map? libs))
     (let [hits (into []
                      (keep (fn [{:keys [lib min reason]}]
                              (when-let [hv (basis-version libs lib)]
                                (when (version-older? hv min)
                                  {:lib (str lib)
                                   :basis hv
                                   :needed min
                                   :reason reason}))))
                      (get known-transitives [lib] []))]
       (when (seq hits)
         {:status "error"
          :reason "dep-convergence"
          :conflicts hits})))))
