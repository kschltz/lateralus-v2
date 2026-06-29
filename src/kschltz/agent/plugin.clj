(ns kschltz.agent.plugin
  "Plugin system for the agent interceptor chain.

   A plugin is a vector of interceptor maps:

     [{:name  :my.interceptor
       :slot  :enrich          ; optional stage slot
       :enter (fn [ctx] ctx')
       :leave (fn [ctx] ctx')
       :error (fn [ctx ex] ctx')} ...]

   The vector may carry metadata `{:plugin/name :my-plugin}` for
   diagnostics and tooling.

   Reserved slots (in execution order; see `default-slot-order`):
     :guard             — security / safety checks before compose
     :enrich            — RAG / memory recall before compose
     :compose           — context construction interceptors
     :llm               — interceptors that wrap the LLM call
     :dispatch          — tool-loop dispatch interceptors
     :tools             — tool interceptors
     :finalize          — after the loop, before leave
     :history-summarize — leave stage for compacting long histories
                          (placed BEFORE :history so its :leave runs
                          AFTER :history's :leave; leave walks are
                          stack-reverse, so :history-summarize's
                          :leave sees the just-written
                          :agent/history in :agent/state-delta)
     :history           — leave stage for history updates
     :persist           — leave stage for memory persistence
     :observe           — leave stage for tracing / metrics
     :notify            — leave stage for event callbacks

   Interceptors without a `:slot` are appended after all slotted
   interceptors, in plugin declaration order. A plugin that is entirely
   slotless therefore replaces or extends the chain as a plain vector.

   `assemble-chain` is a pure fold: same plugins in the same order
   produce the same chain. Within a slot, plugin declaration order
   determines interceptor order."
  (:require [clojure.string :as str]
            [kschltz.agent.interceptors.schema :as schema]
            [malli.core :as m]))

(def default-slot-order
  "The default order in which slot-tagged interceptors are folded into
   a chain."
  [:guard :enrich :compose :llm :dispatch
   :tools :finalize
   :history-summarize :history :persist :observe :notify])

(def ^:private allowed-slots
  (set default-slot-order))

(def ^:private slot-rank
  "Map from slot keyword to its position in `default-slot-order`."
  (zipmap default-slot-order (range)))

(def Plugin
  "Shape of a plugin vector: a vector of interceptor maps. Extra keys
   on each interceptor are allowed by the open `Interceptor` schema."
  [:vector schema/Interceptor])

(defn- explain-plugins
  "Run Malli on a plugin seq. Returns the Malli error map
   `{:schema ... :value ... :errors [...]}` or nil when valid."
  [plugins]
  (m/explain [:sequential Plugin] (vec plugins)))

(defn- format-problems
  "Render Malli problem maps as a single human-readable string."
  [problems]
  (->> problems
       (map (fn [p]
              (let [path (or (:in p) [])
                    type (:type p)]
                (str (when (seq path) (str "at " (pr-str path) ": "))
                     "expected " (pr-str type)))))
       (str/join "; ")))

(defn- explain-errors
  "Return the `:errors` vector from a Malli explain result, or nil
   when the explain result is nil/empty."
  [explain-result]
  (when-let [errs (and explain-result (:errors explain-result))]
    (when (seq errs) (vec errs))))

(defn- plugin-name
  "Return the plugin name from vector metadata, if any."
  [plugin]
  (-> plugin meta :plugin/name))

(defn- check-interceptor
  "Ensure `ix` is a map with at least one stage function. Throws ex-info
   with the plugin name and interceptor index if it is malformed or would
   be a silent no-op."
  [plugin-name idx ix]
  (when-not (map? ix)
    (throw (ex-info "Plugin interceptor must be a map"
                    {:plugin/name  plugin-name
                     :index        idx
                     :interceptor  ix
                     :hint         "expected a map with :name and optional :enter/:leave/:error/:slot"})))
  (when (and (nil? (:enter ix))
             (nil? (:leave ix))
             (nil? (:error ix)))
    (throw (ex-info "Plugin interceptor has no stage fn (silent no-op)"
                    {:plugin/name  plugin-name
                     :index        idx
                     :interceptor  ix
                     :hint         "add at least one of :enter, :leave, :error"})))
  ix)

(defn- annotate-interceptor
  "Attach `:plugin/name` and `:plugin/slot` metadata to an interceptor
   for diagnostics, then remove the original `:slot` key from the
   interceptor body so the chain engine sees a normal interceptor map."
  [plugin-name slot ix]
  (cond-> (dissoc ix :slot)
    plugin-name (assoc :plugin/name plugin-name)
    slot        (assoc :plugin/slot slot)))

(defn- validate-slots
  "Throw if any interceptor declares an unknown slot."
  [interceptors]
  (let [unknown (into #{} (comp (keep :plugin/slot)
                                (remove allowed-slots))
                      interceptors)]
    (when (seq unknown)
      (throw (ex-info "Unknown plugin slot(s)"
                      {:slots   (vec unknown)
                       :allowed default-slot-order})))))

(defn assemble-chain
  "Fold a seq of plugin vectors into a single chain (vector of interceptors).

   Interceptors with a `:slot` keyword are grouped by slot and ordered
   according to `default-slot-order`. Within a slot, plugin declaration
   order is preserved (Clojure's sort-by is stable). Interceptors without
   a `:slot` are appended at the end in declaration order.

   Throws ex-info with {:problems ..., :plugins ...} when any plugin
   violates the `Plugin` schema, or when any interceptor has all-nil
   stages or declares an unknown slot."
  [plugins]
  (let [plugins-vec (vec plugins)
        explain     (explain-plugins plugins-vec)
        problems    (explain-errors explain)]
    (when (seq problems)
      (throw (ex-info (str "Invalid plugin: " (format-problems problems))
                      {:problems problems
                       :plugins  plugins-vec})))
    (let [annotated (for [plugin plugins-vec
                          [idx ix] (map-indexed vector plugin)]
                      (let [pname (plugin-name plugin)
                            slot  (:slot ix)
                            ix*   (check-interceptor pname idx (dissoc ix :slot))]
                        (annotate-interceptor pname slot ix*)))
          slotted  (filter :plugin/slot annotated)
          slotless (remove :plugin/slot annotated)]
      (validate-slots slotted)
      (vec (concat (sort-by (comp slot-rank :plugin/slot) slotted)
                   slotless)))))

(defn validate-plugins
  "Validate a seq of plugins against the `Plugin` schema.

   Returns nil on success. On failure returns a map
   `{:problems [...] :message \"...\"}` with the Malli problem
   vector and a rendered message."
  [plugins]
  (when-let [problems (explain-errors (explain-plugins plugins))]
    {:problems problems
     :message  (format-problems problems)}))
