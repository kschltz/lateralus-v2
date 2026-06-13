(ns kschltz.agent.plugin
  "Plugin system for the agent interceptor chain.

   A plugin is a pure data map:

     {:plugin/name     :my-plugin
      :plugin/slots    {:guard   [<interceptor>]
                        :enrich  [<interceptor> ...]
                        :tools   [<interceptor> ...]
                        :persist [<interceptor>]}
      :plugin/register (fn [state tool-defs] new-state)  ; optional

   Named slots (in execution order; see `default-slot-order`):
     :guard    — security / safety checks before compose
     :enrich   — RAG / memory recall before compose
     :compose  — context construction interceptors
     :llm      — interceptors that wrap the LLM call
     :dispatch — tool-loop dispatch interceptors
     :tools    — tool interceptors (typically no-ops; the dispatcher
                 routes tool-calls to the registered tool defs)
     :finalize — after the loop, before leave
     :history  — leave stage for history updates
     :persist  — leave stage for memory persistence
     :observe  — leave stage for tracing / metrics
     :notify   — leave stage for event callbacks

   `assemble-chain` is a pure fold: same plugins in the same order
   produce the same chain. Within a slot, plugin declaration order
   determines interceptor order."
  (:require [clojure.string :as str]
            [kschltz.agent.interceptors.schema :as schema]
            [malli.core :as m]))

(def default-slot-order
  "The default order in which slots are folded into a chain."
  [:guard :enrich :compose :llm :dispatch
   :tools :finalize
   :history :persist :observe :notify])

(def Plugin
  "Shape of a plugin map. Either `:plugin/slots` (contributes to
   the chain) or `:plugin/chain` (replaces the chain). At least one
   of the two must be present."
  [:map {:closed false}
   [:plugin/name :keyword]
   [:plugin/slots {:optional true}
    [:map-of :keyword [:vector :any]]]
   [:plugin/chain {:optional true}
    [:vector :any]]
   [:plugin/doc {:optional true} :string]
   [:plugin/register {:optional true} fn?]])

(defn- build-interceptor
  "Wrap a plugin's slot interceptor with metadata."
  [plugin-name slot ix]
  {:name (keyword (str (name plugin-name) "." (name slot)))
   :enter (:enter ix)
   :leave (:leave ix)
   :error (:error ix)
   :plugin/name plugin-name
   :plugin/slot slot
   :plugin/original-name (:name ix)})

(defn- explain-plugins
  "Run Malli on a plugin seq. Returns the Malli error map
   `{:schema ... :value ... :errors [...]}` or nil when valid.
   `:errors` is a vector of problem maps (one per failing entry)."
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
   when the explain result is nil/empty. Always returns a vector
   for predictable caller access."
  [explain-result]
  (when-let [errs (and explain-result (:errors explain-result))]
    (when (seq errs) (vec errs))))

(defn assemble-chain
  "Fold a seq of plugins into a single chain (vector of interceptors).

   Reserved slots (per `default-slot-order`): interceptors from each
   plugin in declaration order, appending to the accumulator.

   A plugin with `:plugin/chain` (no slots) is appended verbatim
   after the slot-folded interceptors.

   Throws ex-info with {:problems ..., :plugins ...} when any plugin
   violates the `Plugin` schema. The `:problems` vector contains
   Malli's actual failure descriptions; `:plugins` echoes the input
   for caller diagnostics."
  [plugins]
  (let [plugins-vec (vec plugins)
        explain     (explain-plugins plugins-vec)
        problems    (explain-errors explain)]
    (when (seq problems)
      (throw (ex-info (str "Invalid plugin map: " (format-problems problems))
                      {:problems problems
                       :plugins  plugins-vec}))))
  (let [acc (atom [])]
    (doseq [slot default-slot-order]
      (doseq [plugin plugins
              ix  (get-in plugin [:plugin/slots slot])]
        (swap! acc conj (build-interceptor (:plugin/name plugin) slot ix))))
    (doseq [plugin plugins
            :when (contains? plugin :plugin/chain)]
      (swap! acc into (:plugin/chain plugin)))
    @acc))

(defn validate-plugins
  "Validate a seq of plugins against the `Plugin` schema.

   Returns nil on success. On failure returns a map
   `{:problems [...] :message \"...\"}` with the Malli problem
   vector and a rendered message. Callers may pass either the map
   to `ex-data` or surface the `:message` directly."
  [plugins]
  (when-let [problems (explain-errors (explain-plugins plugins))]
    {:problems problems
     :message  (format-problems problems)}))
