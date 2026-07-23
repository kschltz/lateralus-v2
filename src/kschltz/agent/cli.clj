(ns kschltz.agent.cli
  "Clean-slate CLI for the lateralus-v2 agent.

   The CLI is a thin layer:

     parse-args    pure (args → cli-options)
     run-cli       takes cli-options + a runtime config and:
                    1. Builds an Integrant system from the config
                       path (default: system/default-config)
                    2. Creates an agent runtime
                    3. Sends the prompt (or reads stdin / -i)
                    4. Prints the response
                    5. Halts the system
     -main         a thin shell wrapper that calls run-cli with
                   System/in, System/out, and System/exit

   The runtime config is a map; the test seam is `:system-fn`
   and `:runner-fn`. Production code uses the defaults; tests
   inject fakes.

   Flag set (per plan Step 8):
     -h, --help             show help and exit
     --version              show version and exit
     -i, --interactive      read prompts from stdin line-by-line
     --no-interactive       force one-shot mode (default)
     -s, --session ID       session id (default: random-uuid)
     --config PATH          Integrant EDN config (default: built-in)
     --model NAME           LLM model name (overrides config)
     --base-url URL         LLM base URL (overrides config)
     --api-key KEY          LLM API key (overrides config; else OLLAMA_API_KEY)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [integrant.core :as ig]
            [kschltz.agent.cli.model :as model]
            [kschltz.agent.cli.profile.setup :as profile-setup]
            [kschltz.agent.cli.spinner :as spinner]
            [kschltz.agent.cli.ui :as ui]
            [kschltz.agent.cli.thinking :as thinking]
            [kschltz.agent.memory.http-embedding]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.system :as system]
            [kschltz.agent.tool :as tool]))

;; Re-export model-picker API so existing call sites / tests keep working.
(def parse-selection model/parse-selection)
(def default-model-selector model/default-model-selector)

(def ^:const version "lateralus-v2 MVP (Step 8 CLI)")

;; ---- tools.cli option spec ----

(def ^:private cli-options
  [["-h" "--help" "show this help and exit"
    :id :help
    :assoc-fn (fn [m _ _] (assoc m :action :help))]
   [nil "--version" "show version and exit"
    :id :version
    :assoc-fn (fn [m _ _] (assoc m :action :version))]
   ["-i" "--interactive" "read prompts from stdin, line-by-line"
    :id :interactive
    :assoc-fn (fn [m _ _] (assoc m :action :interactive))]
   [nil "--no-interactive" "force one-shot mode (default)"
    :id :no-interactive
    :assoc-fn (fn [m _ _] (assoc m :action :one-shot))]
   ["-s" "--session ID" "session id (default: random-uuid)"
    :id :session-id]
   [nil "--config PATH" "Integrant EDN config (default: built-in)"
    :id :config]
   [nil "--model NAME" "LLM model name (overrides config)"
    :id :model]
   [nil "--base-url URL" "LLM base URL (overrides config)"
    :id :base-url]
   [nil "--api-key KEY" "LLM API key (overrides config; else OLLAMA_API_KEY)"
    :id :api-key]])

(defn parse-args
  "Parse a seq of CLI strings into a CLI options map.

   The :action key is one of:
     :help        — print help and exit 0
     :version     — print version and exit 0
     :one-shot    — run the prompt (or stdin) and exit
     :interactive — read lines from stdin and respond to each
     :error       — print error-msg to stderr and exit 1"
  [args]
  (let [{:keys [options arguments errors]} (cli/parse-opts args cli-options)]
    (if (seq errors)
      {:action :error :error-msg (str/join "\n" errors)}
      (cond-> (merge {:action :one-shot} options)
        (seq arguments) (assoc :prompt (last arguments))))))

;; ---- help ----

(defn help-text
  "Return the help string. Includes every flag the CLI accepts,
   a one-line description, and a usage example."
  []
  (str
   "Usage: lateralus [flags] [prompt]

   Run the lateralus-v2 agent. With no flags and no positional
   prompt, reads a single line from stdin and sends it. With -i /
   --interactive, reads lines from stdin until EOF and prints
   each response.

   Flags:
     -h, --help               show this help and exit
     --version                show version and exit
     -i, --interactive        read prompts from stdin, line-by-line
     --no-interactive         force one-shot mode (default)
     -s, --session ID         session id (default: random-uuid)
     --config PATH            Integrant EDN config (default: built-in)
     --model NAME             LLM model name (overrides config)
     --base-url URL           LLM base URL (overrides config)
     --api-key KEY            LLM API key (overrides config; else OLLAMA_API_KEY)

   When --config is omitted on a TTY, lateralus always opens the
   profile gate (AWS-style): pick or edit a saved profile under
   ~/.config/lateralus/, Enter keeps current values. Pass --config
   PATH to skip. Secrets are never written — use OLLAMA_API_KEY.

   When --model is omitted for an :http LLM, lateralus prompts you
   to pick one from the endpoint's /models list (auto-picks the first
   in a non-TTY; pass --model NAME to skip).

   Examples:
     echo 'ping' | lateralus                       # one-shot, stdin
     lateralus 'tell me a joke'                    # one-shot, prompt
     lateralus -i                                 # interactive REPL
     lateralus -s my-session 'hello'              # named session
     lateralus --config my.edn 'hello'            # custom Integrant config

   Profiles (no --config):
     Saved under ~/.config/lateralus/profiles/<name>.edn with an
     active marker in ~/.config/lateralus/config.edn. Override the
     active name with LATERALUS_PROFILE. Non-TTY loads the active
     profile quietly (or classpath stub when none exists).

   Model selection:
     When the LLM impl is :http and no --model / no config :model is
     set, you are prompted to pick a model from the endpoint's
     /models list. In a non-TTY (piped stdin / CI) the first listed
     model is used automatically; pass --model NAME to skip it.

   Exit codes:
     0   success (or help / version)
     1   error (bad args, runtime failure)
"))

;; ---- run-cli ----

(defn- env-api-key
  "API key from the environment only — profiles never persist secrets."
  []
  (not-empty (System/getenv "OLLAMA_API_KEY")))

(defn- env-base-url
  "Optional Docker/ops override when CLI `--base-url` is omitted."
  []
  (not-empty (System/getenv "LATERALUS_BASE_URL")))

(defn- env-model
  "Optional Docker/ops override when CLI `--model` is omitted."
  []
  (not-empty (System/getenv "LATERALUS_MODEL")))

(defn- config-base
  "Read the base Integrant config map for CLI opts. Pure except for
   reading `resources/lateralus/config.edn` and, when `--config PATH` is
   set, that file. Merge order:

     system/default-config
       < classpath resources/lateralus/config.edn
       < :profile-edn (from the interactive profile gate / active profile)
       < --config PATH

   Extracted so `build-system` and `resolve-llm-config` share one source
   of truth."
  [{:keys [config profile-edn] :as _opts}]
  (let [resource-config (some-> (io/resource "lateralus/config.edn")
                                slurp
                                ig/read-string)
        base (cond-> (merge system/default-config (or resource-config {}))
               profile-edn (merge profile-edn))]
    (if config
      (merge base (ig/read-string (slurp config)))
      base)))

(defn- apply-llm-overrides
  "Apply CLI flags / env onto an llm client/config map.
   Precedence: CLI flag > existing profile/config value > LATERALUS_*/OLLAMA_API_KEY.
   Profile wins over compose env so an ollama-cloud profile is not forced
   back onto the local Docker Ollama URL."
  [m {:keys [model base-url api-key]}]
  (let [model* (or (not-empty model) (not-empty (:model m)) (env-model))
        base*  (or (not-empty base-url) (not-empty (:base-url m)) (env-base-url))
        key*   (or (not-empty api-key) (not-empty (:api-key m)) (env-api-key))]
    (cond-> (dissoc m :model :base-url :api-key)
      model* (assoc :model model*)
      base*  (assoc :base-url base*)
      key*   (assoc :api-key key*))))

(defn resolve-llm-config
  "Pure (modulo reading config files / env): compute the effective
   `:lateralus/llm-client` map that `build-system` would produce for the
   given CLI opts, i.e. the config's client map with `--model`,
   `--base-url`, and `--api-key` overrides applied. Env fallbacks:
   `LATERALUS_BASE_URL`, `LATERALUS_MODEL`, `OLLAMA_API_KEY` (never
   written to profiles).

   Used by the model picker to decide whether a model is missing and,
   if so, which endpoint to list models from — without initializing
   Integrant."
  [{:keys [model base-url api-key] :as opts}]
  (apply-llm-overrides (:lateralus/llm-client (config-base opts))
                       {:model model :base-url base-url :api-key api-key}))

(defn build-system
  "Build an Integrant system config from the cli options.

   Merge order matches [[config-base]]. CLI LLM flags and env
   (`LATERALUS_BASE_URL`, `LATERALUS_MODEL`, `OLLAMA_API_KEY`) override
   the resulting :lateralus/llm-client entry. Profiles never store
   api-keys."
  [{:keys [model base-url api-key] :as opts}]
  (let [base       (config-base opts)
        overrides  {:model model :base-url base-url :api-key api-key}
        client-llm (apply-llm-overrides (:lateralus/llm-client base) overrides)
        config-llm (apply-llm-overrides (or (:lateralus/llm-config base) {}) overrides)]
    (assoc base
           :lateralus/llm-client client-llm
           :lateralus/llm-config config-llm)))

(defn- needs-model-selection?
  "True when the resolved LLM impl is `:http` and no `:model` is set on
   the merged config (blank/nil). Stub and pre-configured-http runs never
   trigger the picker."
  [opts]
  (let [cfg (resolve-llm-config opts)]
    (and (= :http (:impl cfg))
         (str/blank? (:model cfg)))))

(defn- ensure-model
  "Resolve a missing model for an `:http` LLM via the selector seam.
   If `opts` already resolve to a model (or a non-:http impl), returns
   `opts` unchanged. Otherwise calls `model-selector` with a ctx built
   from the resolved base-url/api-key and the sub-seams, and assocs the
   returned model into `opts` (which `build-system` then honors via its
   `--model` override path). If the selector gives up (`nil`/blank),
   prints guidance and returns `opts` unchanged so the build surfaces the
   missing model."
  [opts ^java.io.PrintWriter out
   {:keys [model-selector list-models-fn read-line-fn]}]
  (if (not (needs-model-selection? opts))
    opts
    (let [selector (or model-selector default-model-selector)
          resolved (resolve-llm-config opts)
          base-url (:base-url resolved)
          api-key  (:api-key resolved)
          ctx     (cond-> {:base-url base-url :api-key api-key :out out}
                   list-models-fn (assoc :list-models-fn
                                         #(list-models-fn base-url api-key))
                   read-line-fn   (assoc :read-line-fn read-line-fn))
          chosen  (selector ctx)]
      (if (str/blank? chosen)
        (do (.println out (str "lateralus: no model selected; pass --model NAME "
                               "or set :model in your config."))
            opts)
        (assoc opts :model chosen)))))

(defn- read-stdin
  "Read all of stdin and return it as a string. Strips a trailing
   newline so `echo 'ping' | lateralus` doesn't include a `\n`."
  [in]
  (let [s (slurp in)]
    (if (str/ends-with? s "\n")
      (subs s 0 (dec (count s)))
      s)))

(defn- tool-result-summary
  "Build a concise, user-readable summary of tool results when the
   model produced no final text. This prevents the interactive REPL
   from appearing silent after a tool loop. Falls back to
   `:agent/all-tool-results` (results from earlier loop iterations)
   when the current turn has no `:tool/results`."
  [result]
  (let [all      (or (:agent/all-tool-results result)
                     (:tool/results result)
                     [])
        truncate (fn [s] (subs (str s) 0 (min 120 (count (str s)))))
        summaries (for [{:keys [call result]} all
                        :let [name (get-in call [:function :name])]]
                    (format "- %s: %s" name (truncate result)))
        body (str/join "\n\n" summaries)]
    (if (seq all)
      (str "The assistant used tools but produced no final text.\n\n" body)
      "The assistant produced no response for this turn.")))

(defn- print-response
  "Print the assistant response from the final ctx. For MVP, the
   response is at :exchange/response; future agents may put it
   elsewhere (e.g. a streaming buffer). When the model returns empty
   content after tool calls, prints a readable summary of those tool
   results so the REPL is not silent.

   Verify-round-3 FIX 2: when :agent/summary-failed? is set, the summary
   mini-chain exhausted its retries because the model kept emitting
   tool_calls on the summary turn despite :tools being stripped and
   tool_choice:none set (some providers, e.g. glm-5.2, ignore
   tool_choice:none). The internal 'model kept calling tools on the
   summary turn' state must NEVER be surfaced as the user-facing answer;
   instead the PRIMARY text is a clear 'the agent did not produce a
   final answer' message, with the tool results it did produce appended
   as a reference so the REPL is not blank. :agent/empty-retry-failed?
   (no tools ran and the model stayed blank across retries) gets a
   'model produced no response' breadcrumb for the same reason.

   `renderer` (optional) is a `CliRenderer` from `:agent/cli-ui`; system
   breadcrumbs use `:system`, the body uses `:assistant`. Optional
   `thinking-cfg` (from `:agent/thinking`) controls whether provider
   reasoning is printed (`:preview`/`:full`), written to a file
   (`:log`), or hidden (`:off`, default)."
  ([^java.io.PrintWriter out result]
   (print-response out result (ui/plain-renderer) (thinking/normalize {:mode :off})))
  ([^java.io.PrintWriter out result renderer]
   (print-response out result renderer (thinking/normalize {:mode :off})))
  ([^java.io.PrintWriter out result renderer thinking-cfg]
   (let [thinking-block (thinking/apply-thinking!
                         thinking-cfg
                         {:thinking   (:exchange/thinking result)
                          :session-id (:exchange/session-id result)
                          :user-text  (:exchange/user-text result)})
         _ (when (seq thinking-block)
             (.println out (ui/style renderer :thinking thinking-block)))
         response (:exchange/response result)
         raised   (:error/raised result)
         raised-msg (when raised
                      (let [ex (:exception raised)
                            data (when (instance? clojure.lang.ExceptionInfo ex) (ex-data ex))
                            body (:body data)]
                        (str "lateralus: exchange failed — "
                             (or (when (map? body) (:error body))
                                 (ex-message ex)
                                 "unknown error")
                             (when (:status data)
                               (str " (HTTP " (:status data) ")")))))
         prefix  (cond
                  raised-msg
                  ""
                  (:agent/tool-cap-hit result)
                  "[lateralus: hit the per-exchange tool-call cap; showing tool results instead]\n\n"
                  (:agent/summary-failed? result)
                  "[lateralus: the agent did not produce a final answer for this turn (the model kept emitting tool calls on the summary turn despite tool_choice:none). Tool results produced, for reference:]\n\n"
                  (:agent/empty-retry-failed? result)
                  "[lateralus: model produced no response after retries]\n\n"
                  :else "")
         text (or raised-msg
                  (if (seq response)
                    response
                    (or (tool-result-summary result)
                        (str "lateralus: no response (chain returned: "
                             (pr-str (select-keys result [:exchange/response
                                                          :agent/empty-retry-failed?
                                                          :error/raised]))
                             ")"))))
         styled (str (when (seq prefix) (ui/style renderer :system prefix))
                     (ui/style renderer (if raised-msg :system :assistant) text))]
     (.println out styled))))

(defn- default-system-fn
  "Production :system-fn. Builds and starts an Integrant system
   from the CLI options; returns [agent-map session-id halt-fn]."
  [opts]
  (let [config (build-system opts)
        sys    (ig/init config)
        agent-map (get-in sys [:lateralus/agent])]
    [agent-map
     (or (:session-id opts) (str (random-uuid)))
     (fn [] (ig/halt! sys))]))

(defn- default-runner-fn
  "Production :runner-fn. Creates a runtime, calls send-message
   with the given prompt, halts the system, returns the final ctx."
  [{:keys [prompt agent-map session-id halt-fn]}]
  (let [runtime (runtime/start agent-map session-id)]
    (try
      (runtime/send-message runtime prompt)
      (finally
        (halt-fn)))))

(defn- read-line-or-nil
  "Read one line from a BufferedReader. Returns the line, or nil at EOF."
  [^java.io.BufferedReader rdr]
  (.readLine rdr))

(defn- interactive-runner-fn
  "Production interactive runner. Builds one runtime, then parks on
   `:agent/workbench` (CHAT|Portal), else `:agent/portal` (legacy sticky
   composer), else stdin until `/quit` / `/exit` / EOF. UI session loops
   never park the exchange chain."
  [{:keys [agent-map session-id halt-fn]} ^java.io.PrintWriter out]
  (let [runtime    (runtime/start agent-map session-id)
        renderer   (ui/renderer-from-agent agent-map)
        workbench  (:agent/workbench agent-map)
        portal     (:agent/portal agent-map)]
    (try
      (cond
        workbench
        (do
          (ui/println-role out renderer :system
                           (str "lateralus workbench session — "
                                ((requiring-resolve 'kschltz.agent.workbench.protocol/url) workbench)
                                " (CHAT | Portal; type here or in the web UI; /quit to exit)"))
          ((requiring-resolve 'kschltz.agent.workbench.loop/run-session!)
           runtime workbench {:in *in*}))

        portal
        (do
          (ui/println-role out renderer :system
                           "lateralus portal UI session (composer in Portal, or type here; /quit to exit)")
          ((requiring-resolve 'kschltz.agent.portal.loop/run-session!)
           runtime portal {:in *in*}))

        :else
        (let [rdr (java.io.BufferedReader. (io/reader *in*))]
          (ui/println-role out renderer :system
                           "lateralus-v2 interactive mode (type /quit to exit)")
          (loop []
            (ui/print-role out renderer :prompt "lateralus> ")
            (if-let [line (read-line-or-nil rdr)]
              (let [trimmed (str/trim line)]
                (cond
                  (#{"/quit" "/exit"} trimmed)
                  (ui/println-role out renderer :system "Goodbye.")

                  (seq trimmed)
                  (let [spinner (spinner/start! out (ui/style renderer :spinner "thinking"))
                        result  (try
                                  (runtime/send-message runtime trimmed)
                                  (finally
                                    (spinner/stop! spinner)))]
                    (print-response out result renderer (thinking/from-agent agent-map))
                    (recur))

                  :else
                  (recur)))
              (ui/println-role out renderer :system "\nEOF")))))
      (finally
        (halt-fn)))))

(defn- read-prompt
  "Read the prompt for one-shot mode. If the parsed :prompt is
   non-nil, use it. Otherwise read stdin."
  [{:keys [prompt]} in]
  (or prompt (read-stdin in)))

(defn run-cli
  "Run the CLI. `opts` is the result of parse-args. `runtime-config`
   is a map with the test seams:
     :in         InputStream (defaults to System/in)
     :out        OutputStream (defaults to System/out)
     :exit       (fn [n] ...) to record / trigger exit
     :system-fn  (fn [opts] [agent-map session-id halt-fn])
     :runner-fn  (fn [{:keys [prompt agent-map session-id halt-fn]}] result-ctx)

   Returns the exit code (an int). The :exit fn is called with
   the same code; in production this calls System/exit; in tests
   it just records.

   Extra seams for the profile gate (no `--config`) and model picker
   (`:http` with no model):
     :profile-setup-fn  (fn [opts out seams] opts'); override/skip gate
     :profile-root      directory for saved profiles (tests)
     :tty?              force/disable the interactive profile gate
     :model-selector    (fn [ctx] chosen-model-id-or-nil); defaults to
                        [[default-model-selector]].
     :list-models-fn    (fn [base-url api-key] [id …]) injectable model-list
                        fetch for tests; defaults to the real HTTP call.
     :read-line-fn      (fn [] line-or-nil) injectable TTY read for tests;
                        defaults to the console reader in [[kschltz.agent.cli.model]]."
  ([opts]
   (run-cli opts {}))
  ([opts {:keys [in out exit system-fn runner-fn
                  model-selector list-models-fn read-line-fn
                  profile-setup-fn profile-root tty?]
          :or   {in       System/in
                 out      System/out
                 exit     (fn [n] (System/exit n))
                 system-fn default-system-fn
                 runner-fn default-runner-fn
                 model-selector default-model-selector}
          :as   seams}]
   (let [^java.io.PrintWriter o   (java.io.PrintWriter. (io/writer out :append true) true)
         seams {:model-selector    model-selector
                :list-models-fn    list-models-fn
                :read-line-fn      read-line-fn
                :profile-setup-fn  profile-setup-fn
                :profile-root      profile-root
                :tty?              tty?}
         act (:action opts)
         code
         (case act
           :help
           (do (.println o (help-text)) 0)

           :version
           (do (.println o version) 0)

           :error
           (let [err (or *err* *out*)]
             (binding [*out* err]
               (println (or (:error-msg opts) "unknown error")))
             1)

           :one-shot
           (let [opts'  (-> opts
                            (profile-setup/ensure-profile o seams)
                            (ensure-model o seams))
                 prompt (read-prompt opts' in)
                 [agent-map session-id halt-fn] (system-fn opts')
                 result  (runner-fn {:prompt     prompt
                                     :agent-map  agent-map
                                     :session-id session-id
                                     :halt-fn    halt-fn})]
             (print-response o result
                             (ui/renderer-from-agent agent-map)
                             (thinking/from-agent agent-map))
             0)

           :interactive
           (let [opts'  (-> opts
                            (profile-setup/ensure-profile o seams)
                            (ensure-model o seams))
                 [agent-map session-id halt-fn] (system-fn opts')]
             (interactive-runner-fn {:agent-map  agent-map
                                     :session-id session-id
                                     :halt-fn    halt-fn}
                                    o)
             0))]
     (exit code)
     code)))

;; ---- -main ----

(defn -main
  "Entry point. Parses args, runs the CLI, exits with the
   resulting code."
  [& args]
  (let [opts (parse-args args)
        code (run-cli opts)]
    (System/exit code)))