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
     --api-key KEY          LLM API key (overrides config; env support is a follow-up)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [integrant.core :as ig]
            [kschltz.agent.cli.spinner :as spinner]
            [kschltz.agent.llm.http :as llm-http]
            [kschltz.agent.memory.http-embedding]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.system :as system]
            [kschltz.agent.tool :as tool]))

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
   [nil "--api-key KEY" "LLM API key (overrides config)"
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
     --api-key KEY            LLM API key (overrides config; env support is a follow-up)

   When --model is omitted for an :http LLM, lateralus prompts you
   to pick one from the endpoint's /models list (auto-picks the first
   in a non-TTY; pass --model NAME to skip).

   Examples:
     echo 'ping' | lateralus                       # one-shot, stdin
     lateralus 'tell me a joke'                    # one-shot, prompt
     lateralus -i                                 # interactive REPL
     lateralus -s my-session 'hello'              # named session
     lateralus --config my.edn 'hello'            # custom Integrant config

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

(defn- config-base
  "Read the base Integrant config map for CLI opts. Pure except for
   reading `resources/lateralus/config.edn` and, when `--config PATH` is
   set, that file. The default config is the hardcoded
   `system/default-config`; the resource config is merged over it; a
   `--config` file is merged over both. Extracted so `build-system`
   and `resolve-llm-config` share one source of truth."
  [{:keys [config] :as _opts}]
  (let [resource-config (some-> (io/resource "lateralus/config.edn")
                                slurp
                                ig/read-string)]
    (cond
      config          (merge system/default-config
                             resource-config
                             (ig/read-string (slurp config)))
      resource-config (merge system/default-config resource-config)
      :else           system/default-config)))

(defn resolve-llm-config
  "Pure (modulo reading config files): compute the effective
   `:lateralus/llm-client` map that `build-system` would produce for the
   given CLI opts, i.e. the config's client map with `--model`,
   `--base-url`, and `--api-key` overrides applied. Returns a map with
   at least `:impl`; `:base-url`/`:model`/`:api-key` are present only when
   the merged config or CLI flags set them.

   Used by the model picker to decide whether a model is missing and,
   if so, which endpoint to list models from — without initializing
   Integrant."
  [{:keys [model base-url api-key] :as opts}]
  (cond-> (:lateralus/llm-client (config-base opts))
    model    (assoc :model model)
    base-url (assoc :base-url base-url)
    api-key  (assoc :api-key api-key)))

(defn build-system
  "Build an Integrant system config from the cli options.

   The default base is the classpath resource `resources/lateralus/config.edn`
   (read with `ig/read-string` so `#ig/ref` tags work), merged over
   `system/default-config` as a hardcoded fallback. If `--config PATH` is
   given, that file is also read with `ig/read-string` and merged over
   the default.

   CLI LLM flags (--model, --base-url, --api-key) override the
   resulting :lateralus/llm-client entry."
  [{:keys [model base-url api-key] :as opts}]
  (let [base        (config-base opts)
        client-llm  (cond-> (:lateralus/llm-client base)
                     model    (assoc :model model)
                     base-url (assoc :base-url base-url)
                     api-key  (assoc :api-key api-key))
        config-llm  (cond-> (or (:lateralus/llm-config base) {})
                     model    (assoc :model model)
                     base-url (assoc :base-url base-url)
                     api-key  (assoc :api-key api-key))]
    (assoc base
           :lateralus/llm-client client-llm
           :lateralus/llm-config config-llm)))

(defn parse-selection
  "Pure: interpret a user's `input` against a vector of model `ids`.
   Returns the chosen model-id string, or one of:
     :blank   — empty/whitespace input (caller uses the default/first)
     :invalid — not parseable to a valid choice
   A 1-based integer selects that index; an exact id string selects it.
   Input is trimmed first. Public so tests can exercise the parser
   without a selector."
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

(defn- default-read-line
  "Read one trimmed line from the terminal when a TTY console is
   attached, or return `nil` when there is none (piped stdin /
   non-interactive run). Uses `java.lang.System/console` deliberately:
   in one-shot mode the piped `System/in` stream carries the *prompt*,
   so the model picker must read from the separate TTY channel, not from
   stdin. Returning `nil` signals 'cannot prompt' so the caller can
   auto-pick a default instead of blocking."
  []
  (when-let [c (System/console)]
    (some-> (.readLine c) str/trim)))

(defn default-model-selector
  "Default `:model-selector` seam. Fetches the model list from
   `base-url`, prints a numbered menu, and reads the user's choice.

   Seams in the `ctx` map (all optional):
     :out            a Writer to print the menu to (wrapped in a
                     PrintWriter if needed). In production `run-cli`
                     passes its PrintWriter.
     :list-models-fn  0-arg fn returning a vector of model-id strings.
                     Defaults to `(llm-http/list-models base-url api-key)`.
     :read-line-fn    0-arg fn returning the user's trimmed input, or `nil`
                     to mean 'no TTY / cannot prompt'. Defaults to
                     [[default-read-line]].

   Behavior:
     - list succeeds  → print menu; a blank or `nil` line picks the first
       model; an invalid line re-prompts (auto-picks the first after ~10
       bad tries); a number or exact id picks it.
     - list fails     → free-text prompt for a model name; a blank or `nil`
       line gives up (returns `nil`).

   Returns the chosen model-id string, or `nil` to mean 'give up' —
   `run-cli` then prints guidance and lets the build surface the missing
   model."
  [{:keys [base-url api-key out list-models-fn read-line-fn]
    :or   {read-line-fn default-read-line}}]
  (let [pw (if (instance? java.io.PrintWriter out)
             out
             (java.io.PrintWriter. ^java.io.Writer out true))
        ids (try (if list-models-fn
                  (list-models-fn)
                  (llm-http/list-models base-url api-key))
                (catch Throwable t
                  (.println pw (str "  (could not list models from "
                                    base-url ": " (ex-message t) ")"))
                  nil))]
    (if (seq ids)
      ;; List available: show a numbered menu and read a selection.
      (do
        (.println pw (str "\nNo model configured. Models available at "
                          base-url ":"))
        (doseq [[i id] (map-indexed vector ids)]
          (.println pw (format "  %d) %s" (inc i) id)))
        (.print pw "Select a model by number or name (Enter for the first): ")
        (.flush pw)
        (loop [attempts 0]
          (let [line (read-line-fn)
                sel  (when (some? line) (parse-selection line ids))]
            (cond
              (nil? sel)
              (do (.println pw (str "\n(no TTY available; defaulting to "
                                    (first ids) ")"))
                  (first ids))
              (= sel :blank)
              (do (.println pw (str "\nUsing " (first ids)))
                  (first ids))
              (= sel :invalid)
              (if (>= attempts 9)
                (do (.println pw (str "\nToo many invalid tries; using "
                                      (first ids)))
                    (first ids))
                (do (.println pw "\nInvalid choice, try again: ")
                    (.flush pw)
                    (recur (inc attempts))))
              :else
              (do (.println pw (str "\nUsing " sel))
                  sel)))))
      ;; No list: free-text prompt.
      (do
        (.println pw (str "\nNo model configured and could not list models "
                          "from " base-url "."))
        (.print pw "Type a model name to use (Enter to cancel): ")
        (.flush pw)
        (let [line (read-line-fn)]
          (cond
            (nil? line)        (do (.println pw "\n(no TTY available; cancelled)")
                                  nil)
            (str/blank? line) (do (.println pw "\nCancelled.")
                                  nil)
            :else              (do (.println pw (str "\nUsing " line))
                                  line)))))))

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
   results so the REPL is not silent. When :agent/summary-failed? or
   :agent/empty-retry-failed? is set, prepend a one-line breadcrumb so
   the user knows the model did not produce a final answer (the
   tool-result-summary digest still prints so the REPL is not blank)."
  [^java.io.PrintWriter out result]
  (let [response (:exchange/response result)
        prefix  (cond
                 (:agent/tool-cap-hit result)
                 "[lateralus: hit the per-exchange tool-call cap; showing tool results instead]\n\n"
                 (:agent/summary-failed? result)
                 "[lateralus: model kept calling tools on the summary turn; showing tool results instead]\n\n"
                 (:agent/empty-retry-failed? result)
                 "[lateralus: model produced no response after retries]\n\n"
                 :else "")
        text (if (seq response)
               response
               (or (tool-result-summary result)
                   (str "lateralus: no response (chain returned: "
                        (pr-str result) ")")))]
    (.println out (str prefix text))))

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
  "Production interactive runner. Builds one runtime, then reads lines
   from stdin, sends each to the runtime, and prints responses until
   EOF or the user types `/quit` or `/exit`."
  [{:keys [agent-map session-id halt-fn]} ^java.io.PrintWriter out]
  (let [runtime (runtime/start agent-map session-id)
        rdr    (java.io.BufferedReader. (io/reader *in*))]
    (try
      (.println out "lateralus-v2 interactive mode (type /quit to exit)")
      (loop []
        (.print out "lateralus> ")
        (.flush out)
        (if-let [line (read-line-or-nil rdr)]
          (let [trimmed (str/trim line)]
            (cond
              (#{"/quit" "/exit"} trimmed)
              (.println out "Goodbye.")

              (seq trimmed)
              (let [spinner (spinner/start! out "thinking")
                    result  (try
                              (runtime/send-message runtime trimmed)
                              (finally
                                (spinner/stop! spinner)))]
                (print-response out result)
                (recur))

              :else
              (recur)))
          (.println out "\nEOF")))
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

   Extra seams for the model picker (only invoked when the resolved
   LLM is `:http` and no model is set):
     :model-selector  (fn [ctx] chosen-model-id-or-nil); defaults to
                      [[default-model-selector]].
     :list-models-fn  (fn [base-url api-key] [id …]) injectable model-list
                      fetch for tests; defaults to the real HTTP call.
     :read-line-fn    (fn [] line-or-nil) injectable TTY read for tests;
                      defaults to [[default-read-line]]."
  ([opts]
   (run-cli opts {}))
  ([opts {:keys [in out exit system-fn runner-fn
                  model-selector list-models-fn read-line-fn]
          :or   {in       System/in
                 out      System/out
                 exit     (fn [n] (System/exit n))
                 system-fn default-system-fn
                 runner-fn default-runner-fn
                 model-selector default-model-selector}}]
   (let [^java.io.PrintWriter o   (java.io.PrintWriter. (io/writer out :append true) true)
         seams {:model-selector model-selector
                :list-models-fn list-models-fn
                :read-line-fn   read-line-fn}
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
           (let [opts'  (ensure-model opts o seams)
                 prompt (read-prompt opts' in)
                 [agent-map session-id halt-fn] (system-fn opts')
                 result  (runner-fn {:prompt     prompt
                                     :agent-map  agent-map
                                     :session-id session-id
                                     :halt-fn    halt-fn})]
             (print-response o result)
             0)

           :interactive
           (let [opts'  (ensure-model opts o seams)
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