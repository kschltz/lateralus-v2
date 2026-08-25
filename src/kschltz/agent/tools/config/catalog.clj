(ns kschltz.agent.tools.config.catalog
  "ModelCatalog protocol — boundary for listing LLM models over the
   network. Implementations are Malli-instrumented on the public
   constructor helpers; `-list-models` stays on the protocol so tools
   never call HTTP directly."
  (:require [malli.core :as m]
            [malli.instrument :as mi]))

(def ListModelsOpts
  [:map
   [:base-url :string]
   [:api-key {:optional true} [:maybe :string]]])

(defprotocol ModelCatalog
  "External capability: enumerate models at an OpenAI-compatible endpoint."
  (-list-models [this opts]
    "Return a vector of model-id strings for `opts` (`:base-url`, optional
     `:api-key`). Throws on transport/HTTP errors."))

(defn model-catalog?
  "True when `x` satisfies `ModelCatalog`."
  [x]
  (satisfies? ModelCatalog x))

(defn stub-catalog
  "Deterministic catalog for tests / offline runs. Ignores opts and
   returns `ids` (default `[\"stub/v0\"]`)."
  ([] (stub-catalog ["stub/v0"]))
  ([ids]
   (let [ids* (vec ids)]
     (reify ModelCatalog
       (-list-models [_ _opts] ids*)))))

(defn http-catalog
  "Live catalog that delegates to `kschltz.agent.llm.http/list-models-thorough`.
   Resolved lazily so native-image / offline classpaths that omit the
   HTTP stack can still load this namespace for the stub."
  []
  (let [list-fn (requiring-resolve 'kschltz.agent.llm.http/list-models-thorough)]
    (reify ModelCatalog
      (-list-models [_ {:keys [base-url api-key]}]
        (vec (list-fn base-url api-key))))))

(defn list-models
  "Instrumented wrapper around `-list-models`."
  [catalog opts]
  (-list-models catalog opts))

(m/=> stub-catalog [:function
                    [:=> [:cat] :any]
                    [:=> [:cat [:sequential :string]] :any]])
(m/=> http-catalog [:=> [:cat] :any])
(m/=> list-models [:=> [:cat :any ListModelsOpts] [:vector :string]])

(defn instrument!
  []
  (mi/instrument! {:filters [(mi/-filter-ns 'kschltz.agent.tools.config.catalog)]}))

(instrument!)
