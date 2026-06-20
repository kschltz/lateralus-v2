(ns kschltz.agent.tools.web.schemas
  "Malli schemas for the lateralus web tool suite.

   Schemas fall into three families:

   1. **Op input/output** — one pair per web tool op (search, fetch,
      extract). Inputs are Malli maps; outputs are `:string` because
      `tool.clj` requires every tool result to be a string (the
      tool record JSON-serializes the envelope before returning).

   2. **Provider config** — `WebConfig` and `WebToolConfig`. They
      encode the provider choice plus every guard toggle from the
      `decisions.md` \"Defense checklist\". `WebToolConfig` reuses
      `WebConfig` so a tool deftype can call `(m/validate
      WebToolConfig cfg)` directly.

   3. **Guard result shapes** — `SafeUrl`, `SanitizedQuery`,
      `SanitizedSnippet`. These are the public return types of
      `guards.clj` so a model or a test can pattern-match on the
      shape regardless of which guard fired."
  (:require [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Op input/output schemas
;;
;; Output is `:string` because `kschltz.agent.tool/invoke-tool`
;; (see `tool.clj`) validates the return value with `string?` before
;; passing it to the model. The actual data envelope is JSON-encoded
;; inside each tool deftype, NOT in the protocol layer.
;; ---------------------------------------------------------------------------

(def WebSearchInput
  "Malli input schema for `web/search`. `query` is required and must
   be a non-empty string; `result-count` defaults to 5 in the tool
   and is hard-capped at 20 in the guard pipeline."
  [:map
   [:query [:string {:min 1}]]
   [:result-count {:optional true} :int]])

(def WebSearchOutput
  "Malli output schema for `web/search`. The tool deftype JSON-serializes
   its envelope before returning."
  :string)

(def WebFetchInput
  "Malli input schema for `web/fetch`. `url` is required and must
   be a non-empty string; `max-bytes` optionally overrides the
   configured `:max-page-bytes` cap."
  [:map
   [:url [:string {:min 1}]]
   [:max-bytes {:optional true} :int]])

(def WebFetchOutput
  "Malli output schema for `web/fetch`. Tool JSON-serializes its envelope."
  :string)

(def WebExtractInput
  "Malli input schema for `web/extract`. `html` is required;
   `selector` optionally narrows the parsed tree to a CSS selector."
  [:map
   [:html [:string {:min 1}]]
   [:selector {:optional true} :string]])

(def WebExtractOutput
  "Malli output schema for `web/extract`. Tool JSON-serializes its envelope."
  :string)

;; ---------------------------------------------------------------------------
;; Provider config schemas
;;
;; `WebConfig` encodes the provider choice and every guard toggle from
;; `decisions.md` \"Defense checklist\". `WebToolConfig` is an alias
;; because the tool deftypes want to validate a single config map that
;; covers both runtime options (provider, http-fn) and guard toggles.
;; ---------------------------------------------------------------------------

(def WebConfig
  "Provider + guard toggle config. Every toggle defaults to `true` per
   `decisions.md` §\"Defense checklist\"; `:policy-model?` defaults to
   `false` because it requires an LLM snippet classifier that is not
   shipped by default.

   `:provider` is one of `:none | :mojeek | :searxng`. `:http-fn` is the
   test seam — providers call `(http-fn req opts)` instead of calling
   `hato.client/request` directly so every live path is unit-testable
   offline."
  [:map
   [:provider  [:enum :none :mojeek :searxng]]
   [:http-fn {:optional true} :any]
   [:max-query-length {:default 400 :optional true} :int]
   [:max-result-count {:default 20 :optional true} :int]
   [:max-page-bytes   {:default 2097152 :optional true} :int]
   [:max-snippet-bytes {:default 16384 :optional true} :int]
   [:timeout-ms       {:default 15000 :optional true} :int]
   [:user-agent       {:optional true} :string]
   [:base-url         {:optional true} :string]
   [:block-private-ips?         {:default true :optional true} :boolean]
   [:block-loopback?            {:default true :optional true} :boolean]
   [:block-metadata-endpoints?  {:default true :optional true} :boolean]
   [:block-file-scheme?         {:default true :optional true} :boolean]
   [:block-protocol-relative?   {:default true :optional true} :boolean]
   [:block-injection-markers?   {:default true :optional true} :boolean]
   [:block-self-activation?     {:default true :optional true} :boolean]
   [:block-exfiltration-patterns? {:default true :optional true} :boolean]
   [:strip-html?                {:default true :optional true} :boolean]
   [:allowed-schemes            {:default #{"http" "https"} :optional true} [:set :string]]
   [:allowed-ports              {:default #{80 443} :optional true} [:set :int]]
   [:injection-markers
    {:default #{"ignore previous" "system instruction" "you are now"
                "disregard" "developer mode" "jailbreak" "DAN mode"} :optional true}
    [:set :string]]
   [:url-allow-list {:default [] :optional true} [:vector :string]]
   [:url-block-list  {:default [] :optional true} [:vector :string]]
   [:policy-model?  {:default false :optional true} :boolean]])

(def WebToolConfig
  "Alias for `WebConfig`. The tool deftypes use this name to make the
   call site read as \"validate the tool config\"."
  WebConfig)

;; ---------------------------------------------------------------------------
;; Guard result schemas
;;
;; These encode the public return shapes of `guards.clj` so consumers
;; (the tool record, future policy classifier) can pattern-match on a
;; well-defined Malli type instead of free-form maps.
;; ---------------------------------------------------------------------------

(def SafeUrl
  "Return shape of `guards/validate-url`. `:allow?` is the explicit
   boolean — NOT a MapEntry — so a guard rejection is unmistakable.
   This is the explicit fix for the `(first url-check)` bug from the
   prior rolled-back implementation."
  [:map
   [:allow? :boolean]
   [:url    :string]
   [:reason :string]])

(def SanitizedQuery
  "Return shape of `guards/sanitize-query`. Either `:ok` carries the
   cleaned query or `:error` carries the rejection reason.

   Encoded as a tagged union over `:map` shapes so the schema is
   stable across Malli versions (multi-dispatch with anonymous
   `first` is fragile because the dispatch fn is reconstructed on
   every compile)."
  [:or
   [:map [:ok :string]]
   [:map [:error :string]]])

(def SanitizedSnippet
  "Return shape of `guards/sanitize-snippet`. Either `:ok` carries the
   cleaned snippet or `:error` carries the rejection reason."
  [:or
   [:map [:ok :string]]
   [:map [:error :string]]])