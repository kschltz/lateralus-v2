(ns kschltz.agent.cli.profile.tool-groups
  "Interactive checklist to enable/disable tool groups in profile setup.

   Line-mode keys (command then Enter): j/k move, space/t toggle cursor
   row, number toggles that row, a=all on, z=all off, Enter accepts.
   Reader must not trim: a lone spacebar + Enter toggles the cursor row."
  (:require [clojure.string :as str]
            [kschltz.agent.cli.profile.templates :as templates]))

(defn- visible-ids
  [workbench?]
  (cond->> (mapv :id templates/tool-group-catalog)
    (not workbench?) (filterv #(not= :workbench %))))

(defn- print-menu!
  [^java.io.PrintWriter out groups ids cursor]
  (.println out "")
  (.println out "Tool groups (j/k move, space toggle, Enter accept):")
  (doseq [[i id] (map-indexed vector ids)]
    (let [meta (templates/tool-group-meta id)
          on?  (get groups id)
          mark (if on? "x" " ")
          cur  (if (= i cursor) ">" " ")]
      (.println out (format " %s %d) [%s] %-10s — %s"
                            cur (inc i) mark (:label meta) (:description meta)))))
  (.print out "  [j/k/space/#/a/z, Enter=done]: ")
  (.flush out))

(defn apply-command
  "Pure transition for one checklist command.
   Returns {:groups m :cursor i :done? bool}.
   Whitespace-only input (e.g. spacebar) toggles; empty Enter accepts."
  [{:keys [groups ids cursor]} cmd]
  (let [n (count ids)
        c (if (pos? n) (max 0 (min cursor (dec n))) 0)
        raw (if (nil? cmd) nil (str cmd))
        trimmed (some-> raw str/trim)
        t (some-> trimmed str/lower-case)
        ;; str/blank? is true for \" \", so detect spacebar via count+trim
        space-only? (and (some? raw)
                         (pos? (count raw))
                         (str/blank? trimmed))]
    (cond
      (nil? raw)
      {:groups groups :cursor c :done? true}

      (or space-only? (#{"t" "toggle" "space"} t))
      (if (pos? n)
        (let [id (nth ids c)]
          {:groups (update groups id not) :cursor c :done? false})
        {:groups groups :cursor c :done? false})

      (or (nil? trimmed) (zero? (count trimmed)))
      {:groups groups :cursor c :done? true}

      (#{"j" "n" "down"} t)
      {:groups groups :cursor (min (dec n) (inc c)) :done? false}

      (#{"k" "p" "up"} t)
      {:groups groups :cursor (max 0 (dec c)) :done? false}

      (#{"a" "all"} t)
      {:groups (into groups (map (fn [id] [id true]) ids))
       :cursor c :done? false}

      (#{"z" "none"} t)
      {:groups (into groups (map (fn [id] [id false]) ids))
       :cursor c :done? false}

      (re-matches #"[1-9][0-9]*" t)
      (let [idx (dec (Long/parseLong t))]
        (if (and (<= 0 idx) (< idx n))
          (let [id (nth ids idx)]
            {:groups (update groups id not) :cursor idx :done? false})
          {:groups groups :cursor c :done? false}))

      :else
      {:groups groups :cursor c :done? false})))

(defn prompt!
  "Run the tool-group checklist. Returns updated tool-groups map.
   Throws ex-info {:phase :no-tty} when read-line-fn returns nil.
   `read-line-fn` should not trim: a lone space toggles the cursor row."
  [^java.io.PrintWriter out read-line-fn tool-groups workbench?]
  (let [ids (visible-ids workbench?)
        start (templates/normalize-tool-groups tool-groups workbench?)]
    (loop [groups start
           cursor 0]
      (print-menu! out groups ids cursor)
      (let [line (read-line-fn)]
        (when (nil? line) (throw (ex-info "no-tty" {:phase :no-tty})))
        (let [{:keys [groups cursor done?]}
              (apply-command {:groups groups :ids ids :cursor cursor} line)]
          (if done?
            (templates/normalize-tool-groups groups workbench?)
            (recur groups cursor)))))))
