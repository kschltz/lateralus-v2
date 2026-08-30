(ns kschltz.agent.tools.factory.protocol
  "Runtime tool factory protocols.

   Workbench can define a Tool in-session (Clojure 1.12 eval / add-libs)
   and later promote the spec to an on-disk plugin. Compile and I/O sit
   behind these protocols so tests can stub them and implementations
   stay Malli-instrumented."
  (:require [kschltz.agent.plugin :as plugin]
            [kschltz.agent.tool :as tool]
            [malli.core :as m]
            [malli.instrument :as mi])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def portable-tool-name
  "Same conservative function-name subset as `tool/portable-tool-name?`."
  [:re tool/portable-tool-name-pattern])

(def InterceptorSlot
  (into [:enum] plugin/default-slot-order))

(def ToolSpec
  "Persistable recipe for a runtime-defined tool. `:invoke` is a Clojure
   form that evaluates to `(fn [args ctx] string-or-value)`. Optional
   interceptor fields compile into a same-session plugin interceptor."
  [:map {:closed true}
   [:name portable-tool-name]
   [:description [:string {:min 1}]]
   [:input-schema [:string {:min 1}]]
   [:invoke [:string {:min 1}]]
   [:libs {:optional true} [:string {:min 1}]]
   [:require {:optional true} [:string {:min 1}]]
   [:alias {:optional true} [:string {:min 1}]]
   [:interceptor-slot {:optional true} InterceptorSlot]
   [:interceptor-enter {:optional true} [:string {:min 1}]]
   [:interceptor-leave {:optional true} [:string {:min 1}]]
   [:interceptor-error {:optional true} [:string {:min 1}]]])

(def CompileResult
  [:map
   [:ok :boolean]
   [:tool {:optional true} [:fn tool/tool?]]
   [:interceptor {:optional true} :map]
   [:spec {:optional true} ToolSpec]
   [:error {:optional true} :string]
   [:phase {:optional true} :string]
   [:class {:optional true} :string]])

(defprotocol ToolCompiler
  "Compile a persistable spec into a live Tool (and optional interceptor)."
  (-compile-spec [compiler spec]
    "Return `CompileResult`. Must not raise for ordinary compile failures.")
  (-add-libs [compiler coords]
    "NETWORK. Resolve Maven/Git coords onto the live classpath. Returns
     the `ClojureRuntime` add-libs result map."))

(defprotocol RuntimeToolStore
  "Integrant-owned session of runtime-defined tools.

   `-define!` / `-forget!` / `-promote!` mutate the session. Tools never
   call these — the transitions apply interceptor does, matching MCP."
  (-define! [store spec opts]
    "Compile and register `spec`. `opts` may include `:reserved-names`.
     Returns a status map. Raises `ex-info` with `:phase` on failure.")
  (-forget! [store tool-name]
    "Drop a runtime tool. Idempotent when unknown. Returns status.")
  (-record-test! [store tool-name spec-id]
    "Record a passing `tool_test` for the current spec fingerprint.
     Rejects unknown tools and stale fingerprints.")
  (-promote! [store tool-name opts]
    "Write the registered spec to disk as a reusable Tool / plugin.
     `opts` may include `:as-plugin`, `:target` (`:workspace`|`:project`),
     `:workspace-root`. Returns a status map. Raises on failure.")
  (-registry [store]
    "Current name→Tool map (promoted ∪ ephemeral).")
  (-interceptors [store slot]
    "Runtime-defined interceptor maps for `slot` (possibly empty).")
  (-specs [store]
    "Persistable name→ToolSpec map for ephemeral (not yet promoted) tools.")
  (-status [store]
    "Serializable inventory. MUST NOT raise.")
  (-rehydrate! [store specs]
    "Synchronize ephemeral tools to `specs`: remove absent/stale entries,
     then compile missing/changed specs. Errors are reported, not raised.")
  (-dynamic-enabled? [store]
    "True when agent-driven define/forget/promote is allowed."))

(defn tool-compiler?
  [x]
  (satisfies? ToolCompiler x))

(defn runtime-tool-store?
  [x]
  (satisfies? RuntimeToolStore x))

(defn valid-tool-spec?
  [spec]
  (m/validate ToolSpec spec))

(defn spec-id
  "Stable SHA-256 fingerprint for promotion-test evidence."
  [spec]
  (let [canonical (pr-str (into (sorted-map) spec))
        digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes canonical StandardCharsets/UTF_8))]
    (str "sha256:"
         (apply str (map #(format "%02x" (bit-and 0xff %)) digest)))))

(m/=> spec-id [:=> [:cat ToolSpec] [:string {:min 71 :max 71}]])

(mi/instrument!
 {:filters [(mi/-filter-ns 'kschltz.agent.tools.factory.protocol)]})
