(ns kschltz.agent.plugins.skills
  "Skills plugin: progressive disclosure of expert knowledge.

   Two interceptors:

     :guard  — AFTER `plugins.tools` seeded the registry (but this
               plugin must come AFTER `plugins.secrets` in the config
               so secrets wrapping still runs first — the added skill
               tools are operator-authored data, not secret carriers),
               register `load_skill` + `read_skill_file` on the
               effective registry.

     :compose — append the Tier-1 catalog fragment onto
               :agent/system-append. The fragment is a byte-stable,
               deterministically sorted selector list (cache-friendly);
               bodies NEVER enter the system prompt — they arrive as
               `load_skill` tool results, which the history trimmers
               can retire in later turns."
  (:require [kschltz.agent.skills :as skills]))

(defn- register-tools-enter
  [store]
  (fn [ctx]
    (let [reg (or (:agent/tool-registry ctx) {})]
      (assoc ctx :agent/tool-registry
             (merge reg
                    {skills/load-skill-tool-name    (skills/load-skill-tool store)
                     skills/read-skill-file-tool-name (skills/read-skill-file-tool store)})))))

(defn- catalog-enter
  [store]
  (fn [ctx]
    (if-let [fragment (skills/catalog-fragment store)]
      (update ctx :agent/system-append
              (fn [prior]
                (cond
                  (string? prior) (str prior "\n\n" fragment)
                  (sequential? prior) (conj (vec prior) fragment)
                  :else fragment)))
      ctx)))

(defn skills-plugin
  "`opts`:
     :store — required `SkillStore` from [[kschltz.agent.skills/load-skills-dir]]."
  [{:keys [store] :as _opts}]
  {:pre [(some? store)]}
  (with-meta
    [{:name  ::catalog
      :slot  :compose
      :enter (catalog-enter store)}
     {:name  ::register-tools
      :slot  :guard
      :enter (register-tools-enter store)}]
    {:plugin/name :skills
     :plugin/rebuild (fn [] (skills-plugin {:store store}))}))