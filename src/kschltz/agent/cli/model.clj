(ns kschltz.agent.cli.model
  "Interactive model picker for the lateralus CLI when `:http` LLM
   config has no `:model`. Also hosts the filterable catalog picker
   used by the profile gate (`?` list, `/term` search)."
  (:require [clojure.string :as str]
            [kschltz.agent.llm.http :as llm-http]))

(defn parse-selection
  "Pure: interpret a user's `input` against a vector of model `ids`.
   Returns the chosen model-id string, or one of:
     :blank   — empty/whitespace input (caller uses the default/first)
     :invalid — not parseable to a valid choice
   A 1-based integer selects that index; an exact id string selects it.
   Input is trimmed first."
  [input ids]
  (let [s (str/trim (str input))]
    (cond
      (str/blank? s) :blank
      :else
      (let [n (try (some-> (Long/parseLong s) int) (catch Throwable _ nil))]
        (cond
          (and n (<= 1 n (count ids)))
          (nth ids (dec n))
          (some #{s} ids)
          s
          :else :invalid)))))

(defn filter-models
  "Pure: case-insensitive substring filter over model ids.
   Blank/`nil` term returns `ids` unchanged."
  [ids term]
  (let [ids (vec (filter string? ids))
        t   (some-> term str str/trim str/lower-case)]
    (if (str/blank? t)
      ids
      (vec (filter #(str/includes? (str/lower-case %) t) ids)))))

(defn parse-catalog-command
  "Pure: classify a catalog-picker line.
   Returns one of:
     :blank
     :list          — `?` alone (show full catalog)
     {:filter t}    — `/term` or `?term` (substring search)
     {:raw s}       — anything else (number, id, or free text)"
  [input]
  (let [s (str/trim (str input))]
    (cond
      (str/blank? s) :blank
      (= "?" s) :list
      (or (str/starts-with? s "/") (str/starts-with? s "?"))
      (let [term (str/trim (subs s 1))]
        (if (str/blank? term) :list {:filter term}))
      :else {:raw s})))

(defn- default-read-line
  "Read one trimmed line from the terminal. Prefer `System/console`
   so one-shot piped stdin is not consumed; when the Clojure CLI leaves
   console nil on a real TTY, fall back to `clojure.core/read-line`.
   Returning `nil` signals 'cannot prompt'."
  []
  (if-let [c (System/console)]
    (some-> (.readLine c) str/trim)
    (try
      (some-> (read-line) str/trim)
      (catch Throwable _ nil))))

(defn- pw
  [out]
  (if (instance? java.io.PrintWriter out)
    out
    (java.io.PrintWriter. ^java.io.Writer out true)))

(defn- print-catalog!
  [^java.io.PrintWriter out ids term]
  (let [shown (filter-models ids term)]
    (if (str/blank? term)
      (.println out (str (count shown) " models:"))
      (.println out (str (count shown) " models matching /" term ":")))
    (if (seq shown)
      (doseq [[i id] (map-indexed vector shown)]
        (.println out (format "  %d) %s" (inc i) id)))
      (.println out "  (no matches — try ? for the full list, or another /term)"))
    shown))

(defn catalog-pick!
  "Interactive filterable catalog picker over `ids`.

   Commands:
     Enter     — accept `default` (or first visible)
     ?         — show full list
     /term     — filter by substring (also accepts ?term)
     N / id    — pick by number (against the current view) or exact id

   Optional `:initial-term` starts already filtered (from a leading `/term`
   on the profile Model prompt).

   Returns the chosen model-id string, or `nil` when there is no TTY.
   `default` may be nil (then first of current view / full list)."
  [{:keys [out read-line-fn ids default initial-term]
    :or   {read-line-fn default-read-line}}]
  (let [out  (pw out)
        ids  (vec (filter string? ids))
        read-line-fn (or read-line-fn default-read-line)]
    (when (seq ids)
      (.println out "  Tip: ? lists all, /term filters (e.g. /deepseek), or type a number/name.")
      (loop [term (not-empty (some-> initial-term str str/trim))
             attempts 0]
        (let [shown (print-catalog! out ids term)
              preferred (or (when (and default (some #{default} (if (str/blank? term) ids shown)))
                              default)
                            (llm-http/preferred-default-model shown)
                            (first shown)
                            default
                            (first ids))]
          (.print out (str "Select model"
                           (when preferred (str " [" preferred "]"))
                           " (? /term / name): "))
          (.flush out)
          (let [line (read-line-fn)
                accept! (fn [id]
                          (when id (.println out (str "Using " id)))
                          id)]
            (when (some? line)
              (let [cmd (parse-catalog-command line)]
                (cond
                  (= cmd :blank)
                  (accept! preferred)

                  (= cmd :list)
                  (recur nil attempts)

                  (and (map? cmd) (contains? cmd :filter))
                  (recur (:filter cmd) attempts)

                  :else
                  (let [raw (:raw cmd)
                        sel (parse-selection raw shown)]
                    (cond
                      (= sel :blank) (accept! preferred)
                      (= sel :invalid)
                      (if (>= attempts 9)
                        (accept! preferred)
                        (do (.println out "Invalid choice — try ? , /term, a number, or an id.")
                            (recur term (inc attempts))))
                      :else (accept! sel))))))))))))

(defn default-model-selector
  "Default `:model-selector` seam. Fetches the model list from
   `base-url`, then runs the filterable catalog picker.

   Seams in the `ctx` map (all optional):
     :out            a Writer to print the menu to
     :list-models-fn  0-arg fn returning model-id strings
     :read-line-fn    0-arg fn returning trimmed input, or nil (no TTY)

   Returns the chosen model-id string, or `nil` to mean 'give up'."
  [{:keys [base-url api-key out list-models-fn read-line-fn]}]
  (let [read-line-fn (or read-line-fn default-read-line)
        out (pw out)
        ids (try (if list-models-fn
                   (list-models-fn)
                   (llm-http/list-models-thorough base-url api-key))
                 (catch Throwable t
                   (.println out (str "  (could not list models from "
                                      base-url ": " (ex-message t) ")"))
                   nil))]
    (if (seq ids)
      (do
        (.println out (str "\nNo model configured. " (count ids)
                           " models available via " base-url "."))
        (or (catalog-pick! {:out out
                            :read-line-fn read-line-fn
                            :ids ids
                            :default (llm-http/preferred-default-model ids)})
            (let [fallback (or (llm-http/preferred-default-model ids) (first ids))]
              (.println out (str "\n(no TTY available; defaulting to " fallback ")"))
              fallback)))
      (do
        (.println out (str "\nNo model configured and could not list models "
                           "from " base-url "."))
        (.print out "Type a model name to use (Enter to cancel): ")
        (.flush out)
        (let [line (read-line-fn)]
          (cond
            (nil? line)       (do (.println out "\n(no TTY available; cancelled)")
                                  nil)
            (str/blank? line) (do (.println out "\nCancelled.")
                                  nil)
            :else             (do (.println out (str "\nUsing " (str/trim line)))
                                  (str/trim line))))))))
