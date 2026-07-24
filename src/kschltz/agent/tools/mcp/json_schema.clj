(ns kschltz.agent.tools.mcp.json-schema
  "Minimal JSON Schema (draft-ish object) → Malli conversion for MCP tools.

   Only what we need so `tool/tool-definition` can re-emit parameters to
   the LLM. Unsupported constructs fall back to permissive `:any` /
   open maps."
  )

(defn- json-type->malli
  [t]
  (case t
    "string"  :string
    "number"  number?
    "integer" :int
    "boolean" :boolean
    "array"   [:vector :any]
    "object"  [:map-of :any :any]
    "null"    :nil
    :any))

(defn json-schema->malli
  "Convert a JSON Schema map (keyword or string keys) to a Malli schema.
   Returns `schemas/OpenArgs`-equivalent `[:map]` when schema is absent
   or not an object."
  [schema]
  (let [schema (cond
                 (map? schema) schema
                 :else nil)
        ;; Normalize string keys → keywords for the top level we care about.
        norm (when schema
               (into {}
                     (map (fn [[k v]] [(if (string? k) (keyword k) k) v]))
                     schema))
        t (let [ty (:type norm)]
            (cond
              (string? ty) ty
              (keyword? ty) (name ty)
              :else nil))]
    (if (and norm (or (nil? t) (= t "object")))
      (let [props (or (:properties norm) {})
            required (set (map (fn [x]
                                 (if (keyword? x) (name x) (str x)))
                               (or (:required norm) [])))
            entries (for [[pk pv] props
                          :let [k (if (keyword? pk) pk (keyword pk))
                                kn (name k)
                                child (json-schema->malli
                                       (if (map? pv)
                                         (into {}
                                               (map (fn [[a b]]
                                                      [(if (string? a) (keyword a) a) b]))
                                               pv)
                                         pv))]]
                      (if (contains? required kn)
                        [k child]
                        [k {:optional true} child]))]
        (if (seq entries)
          (into [:map] entries)
          [:map]))
      (json-type->malli (or t "object")))))
