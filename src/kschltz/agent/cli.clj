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
            [kschltz.agent.llm.http]
            [kschltz.agent.memory.http-embedding]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.system :as system]
            [kschltz.agent.cli.spinner :as spinner]))

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

   Examples:
     echo 'ping' | lateralus                       # one-shot, stdin
     lateralus 'tell me a joke'                    # one-shot, prompt
     lateralus -i                                 # interactive REPL
     lateralus -s my-session 'hello'              # named session
     lateralus --config my.edn 'hello'            # custom Integrant config

   Exit codes:
     0   success (or help / version)
     1   error (bad args, runtime failure)
"))

;; ---- run-cli ----

(defn build-system
  "Build an Integrant system config from the cli options.

   The default base is the classpath resource `resources/lateralus/config.edn`
   (read with `ig/read-string` so `#ig/ref` tags work), merged over
   `system/default-config` as a hardcoded fallback. If `--config PATH` is
   given, that file is also read with `ig/read-string` and merged over
   the default.

   CLI LLM flags (--model, --base-url, --api-key) override the
   resulting :lateralus/llm-client entry."
  [{:keys [config model base-url api-key] :as _opts}]
  (let [resource-config (some-> (io/resource "lateralus/config.edn")
                                slurp
                                ig/read-string)
        base (cond
               config        (merge system/default-config
                                    resource-config
                                    (ig/read-string (slurp config)))
               resource-config (merge system/default-config resource-config)
               :else           system/default-config)
        llm  (cond-> (:lateralus/llm-client base)
               model    (assoc :model model)
               base-url (assoc :base-url base-url)
               api-key  (assoc :api-key api-key))]
    (assoc base :lateralus/llm-client llm)))

(defn- read-stdin
  "Read all of stdin and return it as a string. Strips a trailing
   newline so `echo 'ping' | lateralus` doesn't include a `\n`."
  [in]
  (let [s (slurp in)]
    (if (str/ends-with? s "\n")
      (subs s 0 (dec (count s)))
      s)))

(defn- print-response
  "Print the assistant response from the final ctx. For MVP, the
   response is at :exchange/response; future agents may put it
   elsewhere (e.g. a streaming buffer)."
  [^java.io.PrintWriter out result]
  (let [text (or (:exchange/response result)
                 (str "lateralus: no response (chain returned: "
                      (pr-str result) ")"))]
    (.println out text)))

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
   it just records."
  ([opts]
   (run-cli opts {}))
  ([opts {:keys [in out exit system-fn runner-fn]
          :or   {in       System/in
                 out      System/out
                 exit     (fn [n] (System/exit n))
                 system-fn default-system-fn
                 runner-fn default-runner-fn}}]
   (let [^java.io.PrintWriter o   (java.io.PrintWriter. (io/writer out :append true) true)
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
           (let [prompt (read-prompt opts in)
                 [agent-map session-id halt-fn] (system-fn opts)
                 result  (runner-fn {:prompt     prompt
                                     :agent-map  agent-map
                                     :session-id session-id
                                     :halt-fn    halt-fn})]
             (print-response o result)
             0)

           :interactive
           (let [[agent-map session-id halt-fn] (system-fn opts)]
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