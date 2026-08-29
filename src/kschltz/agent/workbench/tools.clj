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
  (-name [_] "portal_submit")
  (-description [_]
    "PRIMARY visualization tool — use optimistically. Push rich artifacts into
     the Portal pane (right side): HTML/CSS demos (preferred for charts — one
     HTML/SVG doc, multi-chart = one page), tables (array of maps), markdown,
     code, hiccup. Optional kind: html|table|vega|markdown|code|auto.
     Returns JSON with :cite \"@portal/<full-uuid>\" — paste that exact cite
     in a short chat reply. Never invent ids. Prefer this over pasting into
     chat. INTERACTIVE: HTML artifacts may include a tiny JS helper that
     POSTs interaction events back to /api/portal-event (same-origin
     iframe) — the human's clicks arrive as ⟨portal-event⟩ input on the
     next exchange. See the guidance boilerplate; keep event payloads
     small and named.")
  (-input-schema [_] schemas/PortalSubmitInput)
  (-output-schema [_] :string)
  (-invoke [_ args _ctx]
    (json/generate-string (submit-payload workbench args))))

(defrecord PortalClearTool [workbench]
  tool/Tool
  (-name [_] "portal_clear")
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
  (-name [_] "portal_focus")
  (-description [_]
    "Resolve a Portal ref the human attached (@portal/<id> or by label)
     and return its preview. Then derive follow-up visuals with portal_submit
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

(defn- preview-of
  "Short single-line preview (bounded by portal/max-value-chars)."
  [value]
  (let [s (pr-str value)]
    (if (> (count s) 160)
      (str (subs s 0 157) "...")
      s)))

(def ^:private default-selection-limit 20000)
(def ^:private max-selected-count 20)

(defn- clamp-str
  [s limit]
  (let [s (str s)]
    (if (> (count s) limit)
      (subs s 0 limit)
      s)))

(defn- selection-payload
  "Serialize the Portal selection for the model. Values are pr-str'd
   (JSON-safe) and clamped; the count of dropped/extra selections is
   reported so the model knows when to ask the human to select less."
  [workbench {:keys [limit] :or {limit default-selection-limit}}]
  (let [{:keys [last selected]} (wb/portal-selection (resolve-wb workbench))
        per-value (max 100 (int (/ limit (inc (min max-selected-count
                                                   (count selected))))))]
    (cond-> {:ok (boolean (or (some? last) (seq selected)))
             :count (count selected)}
      (some? last)
      (assoc :last {:edn (clamp-str (pr-str last) per-value)
                    :preview (preview-of last)})
      (seq selected)
      (assoc :selected (mapv #(clamp-str (pr-str %) per-value)
                                (take max-selected-count selected)))
      (> (count selected) max-selected-count)
      (assoc :truncated true
             :hint (str (+ (count selected) (- max-selected-count))
                        " more selected values not shown; ask the human to select fewer")))))

(defrecord PortalSelectedTool [workbench]
  tool/Tool
  (-name [_] "portal_selected")
  (-description [_]
    "Read the value(s) the human currently has SELECTED in the Portal pane
     back into the conversation (UI → agent, the reverse of portal_submit).
     Ask the human to select a value in Portal, then call this to inspect
     it. Returns the most recent selected value (:last) plus any
     multi-selection (:selected), serialized as EDN strings and clamped.
     Use before portal_focus when the human points at something not yet
     attached as a ref.")
  (-input-schema [_] schemas/PortalSelectedInput)
  (-output-schema [_] :string)
  (-invoke [_ args _ctx]
    (json/generate-string (selection-payload workbench args))))

(defn registry
  "Tool registry for a live workbench instance (or atom/delay of one)."
  [workbench]
  {"portal_submit"   (->PortalSubmitTool workbench)
   "portal_clear"    (->PortalClearTool workbench)
   "portal_selected" (->PortalSelectedTool workbench)
   "portal_focus"    (->PortalFocusTool workbench)})
