(ns kschltz.agent.cli.thinking
  "Optional model-thinking / reasoning display pack.

   Mirrors `:lateralus/logging` and `:lateralus/cli-ui`: an Integrant
   config bag resolved onto the agent map as `:agent/thinking`. It is
   NOT an exchange-chain plugin — `parse-response` only extracts
   `:exchange/thinking`; this namespace decides how (or whether) the
   CLI surfaces it.

   Config (`ThinkingConfig`):
     :mode           :off | :preview | :full | :log  (default :off)
     :preview-chars  int, max chars shown in :preview (default 240)
     :log-dir        directory for :log files (default \"logs\")
     :log-file       optional absolute/relative file path; when set,
                     :log always appends here instead of per-session

   Modes:
     :off      — never print, never write
     :preview  — one dim block truncated to :preview-chars
     :full     — full reasoning block before the assistant reply
     :log      — append full reasoning to a file; CLI stays clean"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m])
  (:import [java.io PrintWriter]))

(def default-preview-chars 240)
(def default-log-dir "logs")

(def ThinkingMode
  [:enum :off :preview :full :log])

(def ThinkingConfig
  "Malli schema for `:lateralus/thinking`."
  [:map
   [:mode {:optional true} ThinkingMode]
   [:preview-chars {:optional true} [:int {:min 1}]]
   [:log-dir {:optional true} :string]
   [:log-file {:optional true} :string]])

(defn normalize
  "Fill defaults for a thinking config map. Nil/empty → `:mode :off`."
  [opts]
  (let [opts (or opts {})]
    {:mode          (or (:mode opts) :off)
     :preview-chars (or (:preview-chars opts) default-preview-chars)
     :log-dir       (or (:log-dir opts) default-log-dir)
     :log-file      (:log-file opts)}))

(defn validate!
  "Throw ex-info when `opts` fails `ThinkingConfig`. Returns normalized map."
  [opts]
  (let [opts (or opts {})]
    (when-let [problems (m/explain ThinkingConfig opts)]
      (throw (ex-info "Invalid :lateralus/thinking config"
                      {:problems (:errors problems)
                       :value opts})))
    (normalize opts)))

(defn truncate
  "Truncate `s` to at most `n` chars, appending an ellipsis when cut."
  [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 n) "…")
      s)))

(defn format-block
  "Build the user-visible thinking block for `:preview` / `:full`.
   Returns nil when `text` is blank or mode is `:off`/`:log`."
  [cfg text]
  (let [cfg  (normalize cfg)
        text (str text)
        mode (:mode cfg)]
    (when (and (not (str/blank? text))
               (or (= mode :preview) (= mode :full)))
      (let [body (if (= mode :preview)
                   (truncate text (:preview-chars cfg))
                   text)]
        (str "[thinking]\n" body)))))

(defn- resolve-log-file
  "Path for :log mode. Prefers explicit `:log-file`, else
   `<log-dir>/lateralus-thinking-<session-id>.txt`."
  [cfg session-id]
  (let [cfg (normalize cfg)]
    (or (when (seq (:log-file cfg)) (:log-file cfg))
        (str (:log-dir cfg)
             "/lateralus-thinking-"
             (or session-id "unknown")
             ".txt"))))

(defn append-log!
  "Append one thinking turn to the configured log file. No-op when
   mode is not `:log` or `text` is blank. Returns the path written,
   or nil."
  [cfg {:keys [session-id user-text text]}]
  (let [cfg  (normalize cfg)
        text (str text)]
    (when (and (= :log (:mode cfg)) (not (str/blank? text)))
      (let [path (resolve-log-file cfg session-id)
            f    (io/file path)]
        (when-let [parent (.getParentFile ^java.io.File f)]
          (.mkdirs parent))
        (with-open [^PrintWriter w (PrintWriter. (io/writer f :append true))]
          (.println w (str ";;; session=" (or session-id "?")
                           " ts=" (System/currentTimeMillis)))
          (when (seq user-text)
            (.println w (str ";;; user: " (truncate user-text 200))))
          (.println w text)
          (.println w "")
          (.flush w))
        path))))

(defn apply-thinking!
  "Apply the configured thinking mode for one exchange. For `:preview`
   / `:full`, returns a string to print before the assistant body.
   For `:log`, writes the file and returns nil. For `:off` / blank,
   returns nil. Pure w.r.t. stdout — caller prints the return value."
  [cfg {:keys [thinking session-id user-text]}]
  (let [cfg  (normalize cfg)
        text (str thinking)]
    (when-not (str/blank? text)
      (case (:mode cfg)
        :off     nil
        :preview (format-block cfg text)
        :full    (format-block cfg text)
        :log     (do (append-log! cfg {:session-id session-id
                                       :user-text  user-text
                                       :text       text})
                     nil)
        nil))))

(defn from-agent
  "Read `:agent/thinking` from an agent-map, defaulting to `:off`."
  [agent-map]
  (normalize (or (:agent/thinking agent-map) {:mode :off})))
