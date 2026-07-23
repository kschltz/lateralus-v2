(ns kschltz.agent.cli.ui
  "Optional CLI visual styling pack for lateralus-v2.

   Mirrors `:lateralus/logging`: an Integrant config bag resolved into a
   `CliRenderer` and hung on the agent map as `:agent/cli-ui`. It is NOT
   an exchange-chain plugin — styling happens at the PrintWriter boundary
   after an exchange completes.

   Config (`CliUiConfig`):
     :enabled?  true | false | :auto (default :auto)
                :auto enables color only when stdout looks like a TTY
                and NO_COLOR is unset / blank.
     :theme     :default | :mono | map with :styles
     :styles    optional role -> {:fg :bold? :dim?} overrides merged
                over the theme
     :tty?      test seam — force TTY detection
     :no-color? test seam — force NO_COLOR detection

   Roles: :prompt :user :assistant :system :spinner :error :thinking."
  (:require [clojure.string :as str]
            [malli.core :as m])
  (:import [java.io PrintWriter]))

;; ---- Protocol (presentation boundary) ---------------------------------

(defprotocol CliRenderer
  "Styles short CLI strings by role. Implementations must be pure with
   respect to `text` (no I/O); writers go through the helpers below."
  (-enabled? [this]
    "True when this renderer emits ANSI (or other) styling codes.")
  (-style [this role text]
    "Return `text` styled for `role`. Unknown roles pass text through."))

(defn enabled?
  "Public wrapper for `-enabled?`."
  [renderer]
  (boolean (and renderer (-enabled? renderer))))

(defn style
  "Public wrapper for `-style`. Nil renderer or nil/blank text is a no-op."
  [renderer role text]
  (let [s (str text)]
    (cond
      (nil? renderer) s
      (nil? text)     ""
      :else           (-style renderer role s))))

(defn print-role
  "Write styled `text` to `out` without a trailing newline."
  [^PrintWriter out renderer role text]
  (.print out ^String (style renderer role text))
  (.flush out))

(defn println-role
  "Write styled `text` to `out` with a trailing newline."
  [^PrintWriter out renderer role text]
  (.println out ^String (style renderer role text)))

;; ---- Schemas ----------------------------------------------------------

(def Role
  "Known CLI style roles."
  [:enum :prompt :user :assistant :system :spinner :error :thinking])

(def ColorName
  "Named ANSI foreground colors supported by the default renderer."
  [:enum :black :red :green :yellow :blue :magenta :cyan :white
   :bright-black :bright-red :bright-green :bright-yellow
   :bright-blue :bright-magenta :bright-cyan :bright-white])

(def StyleAttrs
  "Per-role style attributes."
  [:map
   [:fg {:optional true} ColorName]
   [:bold? {:optional true} :boolean]
   [:dim? {:optional true} :boolean]])

(def StylesMap
  [:map-of Role StyleAttrs])

(def Theme
  [:or
   [:enum :default :mono]
   [:map [:styles {:optional true} StylesMap]]])

(def CliUiConfig
  "Malli schema for `:lateralus/cli-ui`."
  [:map
   [:enabled? {:optional true} [:or :boolean [:= :auto]]]
   [:theme {:optional true} Theme]
   [:styles {:optional true} StylesMap]
   [:tty? {:optional true} :boolean]
   [:no-color? {:optional true} :boolean]])

;; ---- ANSI ------------------------------------------------------------

(def ^:private reset-sgr "\u001b[0m")

(def ^:private fg-codes
  {:black 30 :red 31 :green 32 :yellow 33 :blue 34 :magenta 35 :cyan 36 :white 37
   :bright-black 90 :bright-red 91 :bright-green 92 :bright-yellow 93
   :bright-blue 94 :bright-magenta 95 :bright-cyan 96 :bright-white 97})

(defn- sgr
  "Build an SGR escape from integer codes."
  [& codes]
  (str "\u001b[" (str/join ";" codes) "m"))

(defn- attrs->prefix
  "Turn style attrs into an SGR prefix, or nil when nothing to emit."
  [{:keys [fg bold? dim?]}]
  (let [codes (cond-> []
                bold? (conj 1)
                dim?  (conj 2)
                fg    (conj (get fg-codes fg)))]
    (when (seq codes)
      (apply sgr codes))))

(defn wrap-ansi
  "Wrap `text` in ANSI SGR from `attrs`, always resetting afterward.
   Empty attrs return `text` unchanged. Exposed for tests."
  [attrs text]
  (if-let [prefix (attrs->prefix attrs)]
    (str prefix text reset-sgr)
    (str text)))

(def default-styles
  "Built-in :default theme — cyan prompt, green assistant, dim chrome."
  {:prompt    {:fg :cyan :bold? true}
   :user      {:fg :cyan}
   :assistant {:fg :green}
   :system    {:fg :bright-black :dim? true}
   :spinner   {:fg :bright-black :dim? true}
   :error     {:fg :red :bold? true}
   :thinking  {:fg :bright-black :dim? true}})

(def mono-styles
  "Built-in :mono theme — bold prompt only; everything else plain.
   Useful when color is unwanted but role weight still helps."
  {:prompt    {:bold? true}
   :user      {}
   :assistant {}
   :system    {:dim? true}
   :spinner   {:dim? true}
   :error     {:bold? true}
   :thinking  {:dim? true}})

(defn- resolve-styles
  "Merge theme styles with optional overrides."
  [theme overrides]
  (let [base (cond
               (= theme :mono)    mono-styles
               (map? theme)       (merge default-styles (:styles theme))
               :else              default-styles)]
    (merge-with merge base (or overrides {}))))

;; ---- Enablement -------------------------------------------------------

(defn- env-no-color?
  "True when the NO_COLOR env var is set to a non-blank value
   (https://no-color.org/)."
  []
  (let [v (System/getenv "NO_COLOR")]
    (and (some? v) (not (str/blank? v)))))

(defn- stdout-tty?
  "Best-effort TTY probe. `System/console` is non-nil when both stdin
   and stdout are connected to a terminal on HotSpot; good enough for
   :auto. Tests inject `:tty?` to override."
  []
  (some? (System/console)))

(defn resolve-enabled?
  "Decide whether styling should be active for `opts`. Exposed for tests."
  [{:keys [enabled? tty? no-color?] :as opts}]
  (let [enabled (if (contains? opts :enabled?) enabled? :auto)
        tty     (if (contains? opts :tty?) tty? (stdout-tty?))
        no-c    (if (contains? opts :no-color?) no-color? (env-no-color?))]
    (case enabled
      true  true
      false false
      :auto (and tty (not no-c))
      ;; unknown → treat as :auto
      (and tty (not no-c)))))

;; ---- Implementations --------------------------------------------------

(defrecord PlainRenderer []
  CliRenderer
  (-enabled? [_] false)
  (-style [_ _role text] (str text)))

(defrecord AnsiRenderer [styles]
  CliRenderer
  (-enabled? [_] true)
  (-style [_ role text]
    (wrap-ansi (get styles role) (str text))))

(defn plain-renderer
  "Renderer that never emits ANSI. Used when color is disabled and as
   the safe fallback when the agent map has no `:agent/cli-ui`."
  []
  (->PlainRenderer))

(defn build-renderer
  "Build a `CliRenderer` from a `CliUiConfig` map. Invalid configs throw
   ex-info with Malli problems (Integrant also asserts via assert-key)."
  [opts]
  (let [opts (or opts {})]
    (when-let [problems (m/explain CliUiConfig opts)]
      (throw (ex-info "Invalid :lateralus/cli-ui config"
                      {:problems (:errors problems)
                       :value opts})))
    (if (resolve-enabled? opts)
      (->AnsiRenderer (resolve-styles (:theme opts) (:styles opts)))
      (plain-renderer))))

(defn renderer-from-agent
  "Read `:agent/cli-ui` from an agent-map, falling back to plain."
  [agent-map]
  (or (:agent/cli-ui agent-map) (plain-renderer)))
