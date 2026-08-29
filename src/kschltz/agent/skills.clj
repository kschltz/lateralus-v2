(ns kschltz.agent.skills
  "Skill packs: progressive disclosure for agent expertise.

   A skill is a pure-data `.edn` file conforming to the Malli
   [[SkillSchema]]:

     {:name         \"deploy-runbook\"     ; how the model refers to it
      :description  \"Steps to deploy. Use when deploying.\"  ; selector
      :body         \"...full instructions...\"  ; Tier 2, loaded on trigger
      :resources    [{:path \"references/env.md\"       ; Tier 3, read on demand
                      :description \"env var matrix\"}]} ; optional key

   Storage: a directory of `*.edn` files, each one skill. Loading is
   validated up front against Malli — one bad skill file fails plugin
   init loudly (fail-closed, matching the project's Integrant posture).

   Disclosure: Tier 1 (name + description) lives in the system prompt
   catalog fragment — selector-sized, never a manual. Tier 2 (body)
   enters context ONLY as the `load_skill` tool result
   (conversation-scoped, trim-able later). Tier 3 (`resources`)
   resolves ONLY through `read_skill_file`, contained inside the
   skill's own directory.

   Empirical guardrails encoded here (LoongDoc arXiv 2607.17598): flat
   single level only — the catalog entry is the selector, the body is
   the whole skill; no nested routing levels."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [malli.core :as m]
            [malli.error :as me])
  (:import [java.io File]))

;; ---- Schema ----

(def name-regex
  "Conservative lowercase-kebab skill names."
  #"^[a-z][a-z0-9-]{0,63}$")

(def SkillSchema
  "Malli schema for ONE skill (.edn file, pure data, closed map)."
  [:map {:closed true}
   [:name        [:string {:min 1 :max 64}]]
   [:description [:string {:min 1 :max 1024}]]
   [:body        [:string {:min 1}]]
   [:resources   {:optional true}
    [:vector [:map {:closed true}
              [:path        :string]
              [:description {:optional true} :string]]]]])

(defn valid-resource-path?
  "Cheap static gate: relative, no `.`/`..` segments. Runtime containment
   is enforced by canonicalization in [[read-resource]]."
  [path]
  (and (string? path)
       (not (str/starts-with? path "/"))
       (not (str/blank? path))
       (not-any? #(contains? #{".." "."} %)
                 (str/split path #"/"))))

(defn- validate-resources
  [{:keys [name] :as skill}]
  (doseq [{:keys [path]} (:resources skill)]
    (when-not (valid-resource-path? path)
      (throw (ex-info (str "Invalid resource path in skill '" name
                           "': " (pr-str path))
                      {:kind :invalid-skill-resource
                       :skill name :path path}))))
  (let [declared (mapv :path (:resources skill))]
    (when (not= (count declared) (count (distinct declared)))
      (throw (ex-info (str "Duplicate resource path in skill '" name "'")
                      {:kind :invalid-skill-resource :skill name}))))
  skill)

(defn validate-skill
  "Validate one skill map against [[SkillSchema]]. Returns the value on
   success; throws ex-info with humanized problems otherwise."
  [{:keys [name] :as skill}]
  (if-let [problems (m/explain SkillSchema skill)]
    (throw (ex-info (str "Invalid skill"
                         (when name (str " '" name "'"))
                         ": " (pr-str (me/humanize problems)))
                    {:kind :invalid-skill :skill (pr-str skill)}))
    (do
      (when-not (re-matches name-regex (str name))
        (throw (ex-info (str "Invalid skill name: " (pr-str name))
                        {:kind :invalid-skill :name name})))
      (validate-resources skill))))

;; ---- Storage ----

(defrecord SkillStore [root skills-by-name])

(defn load-skills-dir
  "Scan `root` for `*.edn` skill files (one pure-data skill each).
   Returns a [[SkillStore]]. Throws on any invalid file (fail-closed)."
  ^SkillStore
  [^String root]
  (let [dir (io/file root)]
    (when-not (.isDirectory dir)
      (throw (ex-info (str "Skills directory does not exist: " root)
                      {:kind :missing-skills-dir :path root})))
    (->SkillStore
     (.getPath dir)
     (into {}
           (map (fn [^File f]
                  (try
                    (let [sk (-> f slurp edn/read-string validate-skill validate-resources)]
                      [(:name sk) sk])
                    (catch Throwable e
                      (throw (ex-info (str "Skill file failed to load: "
                                           (.getPath f))
                                      {:kind :invalid-skill-file
                                       :path (.getPath f)}
                                      e))))))
           (filter (fn [^File f]
                     (and (.isFile f)
                          (str/ends-with? (.getName f) ".edn")))
                   (.listFiles dir))))))

(defn catalog
  "Tier 1: the always-loaded selector list. Deterministically sorted
   for cache stability."
  [^SkillStore store]
  (->> (vals (:skills-by-name store))
       (map (fn [{:keys [name description]}]
              {:name name :description description}))
       (sort-by :name)
       vec))

(defn catalog-fragment
  "The Tier-1 text appended to :agent/system-append. Byte-stable given
   the same skill set; nil when the store is empty."
  [^SkillStore store]
  (let [entries (catalog store)]
    (when (seq entries)
      (str "## Skill packs\n"
           "A skill pack is NOT a tool: call the \"load_skill\" tool with its "
           "name to load its full instructions, then follow them. Load a skill "
           "proactively whenever a task matches its description.\n\n"
           (str/join "\n"
                     (map (fn [{:keys [name description]}]
                            (str "- " name " — " description))
                          entries))))))

(defn get-skill
  "Tier 2 lookup: full skill by name, or nil."
  [^SkillStore store name]
  (get (:skills-by-name store) (str name)))

(defn- skill-dir ^File [^SkillStore store skill-name]
  (io/file (:root store) (str skill-name)))

(defn read-resource
  "Tier 3: read a resource file for `skill-name`, contained inside the
   skill's own directory (canonicalization closes symlink/`..` bypass).
   Returns `{:ok content}` or `{:error msg}` — the error string is safe
   to show to the model."
  [^SkillStore store skill-name resource-path]
  (let [skill   (get-skill store skill-name)
        paths   (set (map :path (:resources skill)))]
    (cond
      (nil? skill)
      {:error (str "Unknown skill: " skill-name)}

      (not (contains? paths (str resource-path)))
      {:error (str "Resource not declared by skill '" skill-name
                   "'; use only paths listed in its :resources")}

      :else
      (let [base    (.. (skill-dir store skill-name) getCanonicalFile)
            target  (.. (File. base (str resource-path)) getCanonicalFile)
            base'   (.getPath base)
            target' (.getPath target)]
        (cond
          (or (not (.startsWith target' base'))
              (= target' base'))
          {:error "resource path escapes the skill directory"}

          (not (.exists target))
          {:error "resource file not found"}

          :else
          {:ok (slurp target)})))))

;; ---- Model-visible tools ----

(def load-skill-tool-name "load_skill")

(defn load-skill-tool
  "Tier-2 disclosure as a Tool: full body + declared resources, as a
   tool result (conversation-scoped, append-only)."
  [^SkillStore store]
  (reify tool/Tool
    (-name [_] load-skill-tool-name)
    (-description [_]
      "Load a skill pack's full instructions by name. Use proactively when the current task matches a skill's catalog description.")
    (-input-schema [_]
      [:map {:closed true} [:name [:string {:min 1}]]])
    (-output-schema [_] :string)
    (-invoke [_ args _ctx]
      (if-let [skill (get-skill store (:name args))]
        (if (seq (:resources skill))
          (str "INSTRUCTIONS:\n" (:body skill)
               "\n\nRESOURCES (read with read_skill_file):"
               (str/join "" (map (fn [{:keys [path description]}]
                                   (str "\n- " path
                                        (when description (str " — " description))))
                                 (:resources skill))))
          (str "INSTRUCTIONS:\n" (:body skill)))
        (str "Unknown skill '" (:name args) "'. Available skills: "
             (str/join ", " (sort (keys (:skills-by-name store)))))))))

(def read-skill-file-tool-name "read_skill_file")

(defn read-skill-file-tool
  "Tier-3 disclosure as a Tool: reads one declared resource."
  [^SkillStore store]
  (reify tool/Tool
    (-name [_] read-skill-file-tool-name)
    (-description [_]
      "Read a resource file bundled inside a skill. The path must be one of the skill's declared :resources.")
    (-input-schema [_]
      [:map {:closed true}
       [:skill [:string {:min 1}]]
       [:path  [:string {:min 1}]]])
    (-output-schema [_] :string)
    (-invoke [_ args _ctx]
      (let [{:keys [ok error]} (read-resource store (:skill args) (:path args))]
        (or ok error)))))