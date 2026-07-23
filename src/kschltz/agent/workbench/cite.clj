(ns kschltz.agent.workbench.cite
  "Guardrails for @portal/<id> cites in assistant chat text."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def cite-pattern
  #"@portal/([A-Za-z0-9-]{6,64})")

(def repair-prompt
  (str "Workbench repair: you claimed a Portal visualization but either "
       "did not call portal/submit, or cited a fake @portal id. "
       "Call portal/submit now with the full artifact (prefer one HTML/SVG "
       "document for charts). In chat, cite ONLY the exact :cite string "
       "from the tool result. Do not invent ids."))

(defn- tool-name
  [entry]
  (or (get-in entry [:call :function :name])
      (get-in entry [:function :name])
      (:name entry)))

(defn- tool-result-body
  [entry]
  (or (:result entry)
      (:content entry)
      (:output entry)))

(defn parse-submit-result
  "Parse a portal/submit tool result string/map → {:ok :cite :id} or nil."
  [body]
  (let [m (cond
            (map? body) body
            (string? body) (try (json/parse-string body true) (catch Throwable _))
            :else nil)]
    (when (map? m)
      (let [cite (or (:cite m)
                     (when-let [id (or (:id m) (get-in m [:ref :id]))]
                       (str "@portal/" id)))
            id   (or (get-in m [:ref :id])
                     (:id m)
                     (when (string? cite)
                       (second (re-find cite-pattern cite))))]
        {:ok (boolean (:ok m))
         :cite cite
         :id id
         :viewer (:viewer m)}))))

(defn portal-submit-results
  "Successful portal/submit results from an exchange's tool list."
  [tool-results]
  (->> (or tool-results [])
       (filter #(= "portal/submit" (tool-name %)))
       (keep (fn [entry]
               (let [parsed (parse-submit-result (tool-result-body entry))]
                 (when (and parsed (:ok parsed) (:cite parsed))
                   parsed))))
       vec))

(defn portal-submit-succeeded?
  [tool-results]
  (boolean (seq (portal-submit-results tool-results))))

(defn claims-portal-delivery?
  "Heuristic: assistant text asserts something is in Portal / cites a ref."
  [text]
  (let [s (str text)]
    (boolean
     (or (re-find cite-pattern s)
         (re-find #"(?i)\b(live in Portal|no Portal|enviado ao Portal|no portal|Portal:)\b" s)
         (re-find #"(?i)@portal/" s)))))

(defn needs-portal-repair?
  "True when the reply claims Portal delivery without a successful submit."
  [text tool-results]
  (and (claims-portal-delivery? text)
       (not (portal-submit-succeeded? tool-results))))

(defn- resolve-cite
  "Map a cited token to a known full id, or nil if unknown."
  [token known-ids]
  (let [token (str/lower-case (str token))
        ids   (map str known-ids)
        exact (first (filter #(= token (str/lower-case %)) ids))
        prefs (filter #(str/starts-with? (str/lower-case %) token) ids)]
    (or exact
        (when (= 1 (count prefs)) (first prefs)))))

(defn sanitize-portal-cites
  "Rewrite @portal/<token> to full known ids; replace unknowns with a warning."
  ([text known-ids]
   (sanitize-portal-cites text known-ids nil))
  ([text known-ids submit-cites]
   (let [fallback (or (first submit-cites)
                      "`(invalid @portal cite — call portal/submit and use its :cite)`")]
     (str/replace
      (str text)
      cite-pattern
      (fn [[_ token]]
        (if-let [id (resolve-cite token known-ids)]
          (str "@portal/" id)
          (if (and (string? fallback) (str/starts-with? fallback "@portal/"))
            fallback
            (str fallback))))))))

(defn known-ids-from-snapshot
  [snapshot]
  (keys (or (:refs snapshot) {})))

(defn assistant-text-guard
  "Sanitize cites; return {:text :repaired? :needs-repair?}."
  [text tool-results known-ids]
  (let [submits (portal-submit-results tool-results)
        cites   (mapv :cite submits)
        ids     (vec (distinct (concat known-ids (keep :id submits))))
        clean   (sanitize-portal-cites text ids cites)
        needs   (needs-portal-repair? text tool-results)]
    {:text          clean
     :repaired?     (not= (str text) clean)
     :needs-repair? needs
     :submit-cites  cites}))
