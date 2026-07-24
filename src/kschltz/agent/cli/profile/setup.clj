(ns kschltz.agent.cli.profile.setup
  "AWS-style interactive profile gate for lateralus.

   When the CLI is started without `--config` on a TTY, this wizard
   always runs: pick a profile (or create/edit), with Enter keeping
   the current values. Secrets are never written — only env-var
   instructions are printed."
  (:require [clojure.string :as str]
            [kschltz.agent.cli.model :as model]
            [kschltz.agent.cli.profile.store :as store]
            [kschltz.agent.cli.profile.templates :as templates]
            [kschltz.agent.cli.profile.tool-groups :as tool-groups]
            [kschltz.agent.llm.http :as llm-http]))

(defn- pw
  [out]
  (if (instance? java.io.PrintWriter out)
    out
    (java.io.PrintWriter. ^java.io.Writer out true)))

(defn- default-read-line
  "Read one line from the controlling terminal (not trimmed — callers
   that need trim do it; tool-group spacebar toggle needs raw spaces).
   Prefer `System/console` (keeps piped stdin intact for one-shot prompts).
   The Clojure CLI often leaves `System/console` nil even on a real TTY —
   fall back to `clojure.core/read-line` in that case."
  []
  (if-let [c (System/console)]
    (.readLine c)
    (try
      (read-line)
      (catch Throwable _ nil))))

(defn- interactive-terminal?
  "True when we should run the profile gate interactively."
  [opts tty-override]
  (cond
    (some? tty-override) (boolean tty-override)
    (System/console) true
    ;; `clojure` launcher often nulls System/console; -i means a human is driving.
    (= :interactive (:action opts)) true
    :else false))

(defn- prompt-line
  "Print `label [default]: ` and read a line. Blank/nil → default.
   Returns nil only when read-line-fn returns nil (no TTY)."
  [^java.io.PrintWriter out read-line-fn label default]
  (.print out (str label
                   (when (some? default) (str " [" default "]"))
                   ": "))
  (.flush out)
  (let [line (read-line-fn)]
    (cond
      (nil? line) nil
      (str/blank? line) default
      :else (str/trim line))))

(defn- yn?
  [s default?]
  (let [t (some-> s str/lower-case)]
    (cond
      (or (nil? t) (str/blank? t)) default?
      (#{"y" "yes"} t) true
      (#{"n" "no"} t) false
      :else default?)))

(defn- format-list-models-error
  "Human-readable list-models failure, never an empty paren."
  [t base-url]
  (let [msg (or (not-empty (ex-message t))
                (some-> t class .getSimpleName)
                "unknown error")
        status (some-> (ex-data t) :status)
        docker-localhost?
        (and (= "1" (System/getenv "LATERALUS_IN_DOCKER"))
             (re-find #"(?i)(?:localhost|127\.0\.0\.1):11434" (str base-url)))]
    (str msg
         (when status (str " (HTTP " status ")"))
         (when docker-localhost?
           " — inside Docker use http://ollama:11434/v1 (profile 'docker'), not localhost"))))

(defn- workbench-available?
  []
  (try
    (requiring-resolve 'org.httpkit.server/run-server)
    true
    (catch Throwable _ false)))

(defn- print-env-guidance!
  [^java.io.PrintWriter out backend]
  (let [set? (not (str/blank? (System/getenv "OLLAMA_API_KEY")))]
    (.println out "")
    (.println out "API keys are never saved to profile files.")
    (when (#{:ollama-cloud :custom} backend)
      (.println out "For cloud / authenticated endpoints, set:")
      (.println out "  export OLLAMA_API_KEY=…"))
    (.println out (str "OLLAMA_API_KEY is currently "
                       (if set? "set" "unset")
                       " in this environment."))))

(defn- apply-settings
  "Attach expanded profile EDN + name onto CLI opts."
  [opts name settings]
  (assoc opts
         :profile-name name
         :profile-settings settings
         :profile-edn (templates/build settings)))

(defn- edit-settings
  "Interactive field editor. Returns updated settings, or nil if no TTY."
  [^java.io.PrintWriter out read-line-fn list-models-fn settings]
  (let [cur (templates/normalize-settings settings)]
    (.println out "")
    (.println out "Edit profile settings (Enter keeps the current value):")
    (let [backend-s (prompt-line out read-line-fn
                                 "Backend (ollama-local / ollama-cloud / custom)"
                                 (name (:backend cur)))]
      (when (nil? backend-s) (throw (ex-info "no-tty" {:phase :no-tty})))
      (let [backend (keyword backend-s)
            default-url (case backend
                          :ollama-cloud templates/cloud-base-url
                          :ollama-local (templates/default-local-base-url)
                          (:base-url cur))
            base-url (prompt-line out read-line-fn "Base URL" default-url)
            _ (when (nil? base-url) (throw (ex-info "no-tty" {:phase :no-tty})))
            model-default (:model cur)
            model-line (do
                         (.print out (str "Model"
                                          (when model-default (str " [" model-default "]"))
                                          " (Enter keep, ? list, /term search): "))
                         (.flush out)
                         (read-line-fn))]
        (when (nil? model-line) (throw (ex-info "no-tty" {:phase :no-tty})))
        (let [model (let [cmd (model/parse-catalog-command model-line)]
                      (cond
                        (= cmd :blank) model-default

                        (or (= cmd :list) (and (map? cmd) (contains? cmd :filter)))
                        (try
                          (let [ids (list-models-fn base-url
                                                     (System/getenv "OLLAMA_API_KEY"))
                                initial-term (when (map? cmd) (:filter cmd))]
                            (cond
                              (empty? ids)
                              (prompt-line out read-line-fn "Model name" model-default)

                              :else
                              (or (model/catalog-pick!
                                   {:out out
                                    :read-line-fn read-line-fn
                                    :ids ids
                                    :default model-default
                                    :initial-term initial-term})
                                  (throw (ex-info "no-tty" {:phase :no-tty})))))
                          (catch clojure.lang.ExceptionInfo e
                            (if (= :no-tty (:phase (ex-data e))) (throw e)
                                (do (.println out (str "  (could not list models: "
                                                       (format-list-models-error e base-url)
                                                       ")"))
                                    (prompt-line out read-line-fn "Model name" model-default))))
                          (catch Throwable t
                            (.println out (str "  (could not list models: "
                                               (format-list-models-error t base-url)
                                               ")"))
                            (prompt-line out read-line-fn "Model name" model-default)))

                        :else (:raw cmd)))
              web-s (prompt-line out read-line-fn
                                 "Web search (ddg / none / mojeek)"
                                 (name (:web-provider cur)))
              _ (when (nil? web-s) (throw (ex-info "no-tty" {:phase :no-tty})))
              wb-default (if (:workbench? cur) "y" "n")
              wb-s (prompt-line out read-line-fn "Enable workbench (y/n)" wb-default)
              _ (when (nil? wb-s) (throw (ex-info "no-tty" {:phase :no-tty})))
              workbench? (yn? wb-s (:workbench? cur))
              tool-groups* (tool-groups/prompt!
                            out read-line-fn (:tool-groups cur) workbench?)]
          (when (and workbench? (not (workbench-available?)))
            (.println out "  Note: workbench deps not on classpath; run with -M:workbench:run")
            (.println out "  or the workbench keys will fail at system init."))
          (print-env-guidance! out backend)
          (templates/normalize-settings
           {:backend      backend
            :base-url     base-url
            :model        model
            :web-provider (keyword web-s)
            :workbench?   workbench?
            :tool-groups  tool-groups*}))))))

(defn- starter-settings
  [kind]
  (case kind
    :workbench (templates/normalize-settings
                {:backend :ollama-local :workbench? true})
    :cloud     (templates/normalize-settings
                {:backend :ollama-cloud :workbench? false})
    (templates/normalize-settings
     {:backend :ollama-local :workbench? false})))

(defn- create-profile!
  [^java.io.PrintWriter out read-line-fn list-models-fn root]
  (.println out "")
  (.println out "Create profile — pick a starter:")
  (.println out "  1) ollama-local (CLI)")
  (.println out "  2) ollama-local + workbench")
  (.println out "  3) ollama-cloud")
  (let [pick (prompt-line out read-line-fn "Starter" "1")]
    (when (nil? pick) (throw (ex-info "no-tty" {:phase :no-tty})))
    (let [base (case pick
                 "2" (starter-settings :workbench)
                 "3" (starter-settings :cloud)
                 (starter-settings :local))
          edited (edit-settings out read-line-fn list-models-fn base)
          name (prompt-line out read-line-fn "Save as profile name" "default")]
      (when (nil? name) (throw (ex-info "no-tty" {:phase :no-tty})))
      (let [name (str/lower-case name)]
        (store/write-profile! root name edited)
        (store/set-active! root name)
        [name edited]))))

(defn- select-existing
  "Menu over existing profiles. Returns [name settings] or :create / :edit."
  [^java.io.PrintWriter out read-line-fn root names active]
  (.println out "")
  (.println out "lateralus profiles (no --config):")
  (doseq [[i name] (map-indexed vector names)]
    (let [settings (store/read-profile root name)
          mark (if (= name active) " [active]" "")]
      (.println out (format "  %d) %s%s — %s"
                            (inc i) name mark
                            (templates/format-summary settings)))))
  (let [create-n (inc (count names))
        edit-n   (inc create-n)]
    (.println out (format "  %d) create new profile…" create-n))
    (.println out (format "  %d) edit a profile…" edit-n))
    (let [default-idx (or (when active
                            (some (fn [[i n]] (when (= n active) (inc i)))
                                  (map-indexed vector names)))
                          1)
          pick (prompt-line out read-line-fn "Use profile" (str default-idx))]
      (when (nil? pick) (throw (ex-info "no-tty" {:phase :no-tty})))
      (let [n (try (Long/parseLong pick) (catch Throwable _ nil))]
        (cond
          (= n create-n) :create
          (= n edit-n) :edit
          (and n (<= 1 n (count names)))
          (let [name (nth names (dec n))]
            [name (store/read-profile root name)])
          (some #{pick} names)
          [pick (store/read-profile root pick)]
          :else
          (let [name (or active (first names))]
            [name (store/read-profile root name)]))))))

(defn run-wizard
  "Full interactive gate. Returns opts with `:profile-edn` set.
   Throws `ex-info` `{:phase :no-tty}` if the TTY disappears mid-flight."
  [opts {:keys [out read-line-fn list-models-fn profile-root]}]
  (let [out  (pw out)
        read-line-fn (or read-line-fn default-read-line)
        root (or profile-root (store/default-root))
        list-models-fn (or list-models-fn
                           (fn [base api]
                             (llm-http/list-models-thorough base api)))
        names (store/list-profiles root)
        active (store/active-profile root)]
    (.println out "")
    (.println out "No --config flag: configure which profile to use.")
    (.println out "Enter keeps the suggested value. Secrets are never written.")
    (try
      (if (seq names)
        (let [sel (select-existing out read-line-fn root names active)]
          (cond
            (= sel :create)
            (let [[name settings] (create-profile! out read-line-fn list-models-fn root)]
              (apply-settings opts name settings))

            (= sel :edit)
            (let [target (prompt-line out read-line-fn
                                      "Profile to edit"
                                      (or active (first names)))]
              (when (nil? target) (throw (ex-info "no-tty" {:phase :no-tty})))
              (let [cur (or (store/read-profile root target)
                            (starter-settings :local))
                    edited (edit-settings out read-line-fn list-models-fn cur)]
                (store/write-profile! root target edited)
                (store/set-active! root target)
                (apply-settings opts target edited)))

            :else
            (let [[name settings] sel
                  keep (prompt-line out read-line-fn
                                    (str "Keep ALL current values for '" name "' and continue? (Y/n)")
                                    "Y")]
              (when (nil? keep) (throw (ex-info "no-tty" {:phase :no-tty})))
              (if (yn? keep true)
                (do (store/set-active! root name)
                    (apply-settings opts name settings))
                (let [edited (edit-settings out read-line-fn list-models-fn settings)]
                  (store/write-profile! root name edited)
                  (store/set-active! root name)
                  (apply-settings opts name edited))))))
        ;; First run — no profiles yet.
        (let [[name settings] (create-profile! out read-line-fn list-models-fn root)]
          (apply-settings opts name settings)))
      (catch clojure.lang.ExceptionInfo e
        (if (= :no-tty (:phase (ex-data e)))
          opts
          (throw e))))))

(defn load-quietly
  "Non-TTY path: load active profile settings into opts when present."
  [opts profile-root]
  (let [root (or profile-root (store/default-root))]
    (if-let [settings (store/load-active-settings root)]
      (apply-settings opts (store/active-profile root) settings)
      opts)))

(defn ensure-profile
  "CLI entry: when `:config` is absent, run the profile gate on a TTY or
   load the active profile quietly otherwise. Seams:
     :out :read-line-fn :list-models-fn :profile-root :profile-setup-fn
     :tty?  — optional override (tests)"
  [opts ^java.io.PrintWriter out
   {:keys [read-line-fn list-models-fn profile-root profile-setup-fn tty?]
    :as   seams}]
  (cond
    (:config opts) opts
    profile-setup-fn (profile-setup-fn opts out seams)
    :else
    (let [read-line-fn (or read-line-fn default-read-line)
          tty (interactive-terminal? opts tty?)]
      (if tty
        (run-wizard opts {:out out
                          :read-line-fn read-line-fn
                          :list-models-fn list-models-fn
                          :profile-root profile-root})
        (load-quietly opts profile-root)))))
