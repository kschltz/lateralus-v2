(ns kschltz.agent.workbench.tools
  "Agent tools that reach into the workbench Portal surface.

   Portal is the sole data/visualization channel for the workbench —
   see `kschltz.agent.workbench.guidance`."
  (:require [cheshire.core :as json]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.workbench.protocol :as wb]
            [kschltz.agent.workbench.schemas :as schemas]))

(defn- resolve-wb
  "Accept a Workbench or an atom/delay holding one."
  [workbench]
  (cond
    (instance? clojure.lang.IDeref workbench) @workbench
    :else workbench))

(defrecord PortalSubmitTool [workbench]
  tool/Tool
  (-name [_] "portal/submit")
  (-description [_]
    "PRIMARY visualization tool — use optimistically. Push rich artifacts into
     the Portal pane (right side): HTML/CSS demos, hiccup UI, markdown, code,
     tables (array of maps), vega-lite charts, maps/nested data.
     Prefer this over pasting HTML, CSS, code, or datasets into chat.
     `value` should be the artifact itself (HTML string, JSON array/object,
     hiccup vector). Stringified JSON is ok (host coerces). Always set `label`.
     Returns a portal ref id — cite as @portal/<id> in a short chat reply.")
  (-input-schema [_] schemas/PortalSubmitInput)
  (-output-schema [_] :string)
  (-invoke [_ {:keys [value label]} _ctx]
    (let [ref (wb/submit-portal! (resolve-wb workbench) (or label "value") value)]
      (json/generate-string {:ok true :ref ref}))))

(defrecord PortalClearTool [workbench]
  tool/Tool
  (-name [_] "portal/clear")
  (-description [_]
    "Clear the Portal visualization pane. Call before a fresh HTML/demo/table
     when leftover values would confuse the human.")
  (-input-schema [_] schemas/PortalClearInput)
  (-output-schema [_] :string)
  (-invoke [_ _args _ctx]
    (wb/clear-portal! (resolve-wb workbench))
    (json/generate-string {:ok true})))

(defrecord PortalFocusTool [workbench]
  tool/Tool
  (-name [_] "portal/focus")
  (-description [_]
    "Resolve a Portal ref the human attached (@portal/<id> or by label)
     and return its preview. Then derive follow-up visuals with portal/submit
     instead of pasting the artifact back into chat.")
  (-input-schema [_] schemas/PortalFocusInput)
  (-output-schema [_] :string)
  (-invoke [_ {:keys [id label]} _ctx]
    (let [snap (wb/snapshot (resolve-wb workbench))
          refs (vals (:refs snap))
          hit  (or (when id (get (:refs snap) id))
                   (when label
                     (first (filter #(= label (:label %)) refs))))]
      (if hit
        (json/generate-string {:ok true :ref hit})
        (json/generate-string {:ok false
                               :error "portal ref not found"
                               :id id
                               :label label})))))

(defn registry
  "Tool registry for a live workbench instance (or atom/delay of one)."
  [workbench]
  {"portal/submit" (->PortalSubmitTool workbench)
   "portal/clear"  (->PortalClearTool workbench)
   "portal/focus"  (->PortalFocusTool workbench)})
