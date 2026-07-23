(ns kschltz.agent.workbench.tools
  "Agent tools that reach into the workbench Portal surface.

   Portal is the sole data/visualization channel for the workbench —
   see `kschltz.agent.workbench.guidance`."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.workbench.portal :as portal]
            [kschltz.agent.workbench.protocol :as wb]
            [kschltz.agent.workbench.schemas :as schemas]))

(defn- resolve-wb
  "Accept a Workbench or an atom/delay holding one."
  [workbench]
  (cond
    (instance? clojure.lang.IDeref workbench) @workbench
    :else workbench))

(defn- submit-payload
  [workbench {:keys [value label kind]}]
  (let [prep (portal/prepare-value value {:kind kind})]
    (if-let [err (:error prep)]
      err
      (let [ref  (wb/submit-portal! (resolve-wb workbench)
                                    (or label "value")
                                    (:value prep))
            cite (str "@portal/" (:id ref))]
        {:ok true
         :cite cite
         :ref ref
         :viewer (:viewer prep)
         :hint "Cite ONLY the :cite string in chat. Do not invent portal ids."}))))

(defrecord PortalSubmitTool [workbench]
  tool/Tool
  (-name [_] "portal/submit")
  (-description [_]
    "PRIMARY visualization tool — use optimistically. Push rich artifacts into
     the Portal pane (right side): HTML/CSS demos (preferred for charts — one
     HTML/SVG doc, multi-chart = one page), tables (array of maps), markdown,
     code, hiccup. Optional kind: html|table|vega|markdown|code|auto.
     Returns JSON with :cite \"@portal/<full-uuid>\" — paste that exact cite
     in a short chat reply. Never invent ids. Prefer this over pasting into chat.")
  (-input-schema [_] schemas/PortalSubmitInput)
  (-output-schema [_] :string)
  (-invoke [_ args _ctx]
    (json/generate-string (submit-payload workbench args))))

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
                   (when (and id (not (get (:refs snap) id)))
                     (first (filter #(or (= id (:id %))
                                         (str/starts-with? (str (:id %)) (str id)))
                                    refs)))
                   (when label
                     (first (filter #(= label (:label %)) refs))))]
      (if hit
        (json/generate-string {:ok true :ref hit
                               :cite (str "@portal/" (:id hit))})
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
