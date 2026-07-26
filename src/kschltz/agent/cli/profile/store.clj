(ns kschltz.agent.cli.profile.store
  "Filesystem-backed lateralus profile store under XDG config.

   Layout:
     <root>/config.edn              {:active-profile \"default\"}
     <root>/profiles/<name>.edn     plain settings map (no secrets)

   External I/O is isolated here. Public functions are Malli-instrumented."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.instrument :as mi]
            [kschltz.agent.cli.profile.templates :as templates]))

(def ToolGroups
  [:map
   [:files {:optional true} :boolean]
   [:self {:optional true} :boolean]
   [:config {:optional true} :boolean]
   [:clojure {:optional true} :boolean]
   [:web {:optional true} :boolean]
   [:runtime {:optional true} :boolean]
   [:workbench {:optional true} :boolean]])

(def Settings
  [:map
   [:backend {:optional true} [:enum :ollama-local :ollama-cloud :custom]]
   [:base-url {:optional true} [:maybe :string]]
   [:model {:optional true} [:maybe :string]]
   [:web-provider {:optional true} [:enum :ddg :none :mojeek]]
   [:workbench? {:optional true} :boolean]
   [:tool-groups {:optional true} ToolGroups]])

(def ProfileName
  [:and :string [:fn {:error/message "profile name must be [a-z0-9-]+"}
                 (fn [s] (boolean (re-matches #"[a-z0-9][a-z0-9-]*" s)))]])

(defn default-root
  "Resolve the profile root directory.
   Prefer JVM prop `lateralus.config.home` (tests), else
   `LATERALUS_CONFIG_HOME`, else `$XDG_CONFIG_HOME/lateralus`,
   else `~/.config/lateralus`."
  []
  (let [prop     (System/getProperty "lateralus.config.home")
        explicit (System/getenv "LATERALUS_CONFIG_HOME")
        xdg      (System/getenv "XDG_CONFIG_HOME")
        home     (System/getProperty "user.home")]
    (io/file (or (not-empty prop)
                 (not-empty explicit)
                 (when (not-empty xdg) (str xdg "/lateralus"))
                 (str home "/.config/lateralus")))))

(defn- profiles-dir
  [root]
  (io/file root "profiles"))

(defn- active-file
  [root]
  (io/file root "config.edn"))

(defn- profile-file
  [root name]
  (io/file (profiles-dir root) (str name ".edn")))

(defn- read-edn-file
  [f]
  (when (.isFile ^java.io.File f)
    (edn/read-string (slurp f))))

(defn- write-edn-file!
  [f value]
  (io/make-parents f)
  (spit f (pr-str value)))

(defn list-profiles
  "Return sorted profile name strings under `root`."
  [root]
  (let [files (or (.listFiles (profiles-dir root)) (into-array java.io.File []))]
    (->> files
         (filter #(.isFile ^java.io.File %))
         (map #(.getName ^java.io.File %))
         (filter #(str/ends-with? % ".edn"))
         (map #(subs % 0 (- (count %) 4)))
         (filter #(re-matches #"[a-z0-9][a-z0-9-]*" %))
         sort
         vec)))

(defn read-profile
  "Read settings for `name`, or nil when missing."
  [root name]
  (some-> (profile-file root name)
          read-edn-file
          templates/normalize-settings))

(defn write-profile!
  "Persist sanitized settings for `name`. Returns the normalized map."
  [root name settings]
  (let [normalized (templates/normalize-settings settings)]
    (write-edn-file! (profile-file root name) normalized)
    normalized))

(defn active-profile
  "Return the active profile name, or nil.
   `LATERALUS_PROFILE` env wins over the on-disk active marker."
  [root]
  (let [from-env (not-empty (System/getenv "LATERALUS_PROFILE"))
        from-file (when-let [m (read-edn-file (active-file root))]
                    (not-empty (:active-profile m)))]
    (or from-env from-file)))

(defn set-active!
  "Set the active profile name in <root>/config.edn."
  [root name]
  (write-edn-file! (active-file root) {:active-profile name})
  name)

(defn load-active-settings
  "Settings for the active profile, or nil when none / missing file."
  [root]
  (when-let [name (active-profile root)]
    (read-profile root name)))

(m/=> list-profiles [:=> [:cat :any] [:vector :string]])
(m/=> read-profile [:=> [:cat :any ProfileName] [:maybe Settings]])
(m/=> write-profile! [:=> [:cat :any ProfileName Settings] Settings])
(m/=> active-profile [:=> [:cat :any] [:maybe :string]])
(m/=> set-active! [:=> [:cat :any ProfileName] ProfileName])
(m/=> load-active-settings [:=> [:cat :any] [:maybe Settings]])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.cli.profile.store)]}))

(instrument!)
