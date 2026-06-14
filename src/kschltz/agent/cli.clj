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
            [integrant.core :as ig]
            [kschltz.agent.runtime :as runtime]
            [kschltz.agent.system :as system]))

(def ^:const version "lateralus-v2 MVP (Step 8 CLI)")

;; ---- Flags that take a value (a set for O(1) lookup) ----

(def ^:private flags-with-value
  ;; The keyword used in the parsed options map. --session stores
  ;; under :session-id (the runtime treats it as the user-visible
  ;; session identifier). All other long flags map 1:1.
  {:session-id #{:s :session}
   :config     #{:config}
   :model      #{:model}
   :base-url   #{:base-url}
   :api-key    #{:api-key}})

(defn- flag->opt-key
  "Return the options-map key for a long flag name, or nil if
   the flag doesn't take a value."
  [flag]
  (some (fn [[k vs]] (when (contains? vs flag) k))
        flags-with-value))

(def ^:private short->long
  {"-s" "--session"})

;; ---- parse-args ----

(defn parse-args
  "Parse a seq of CLI strings into a CLI options map.

   The :action key is one of:
     :help        — print help and exit 0
     :version     — print version and exit 0
     :one-shot    — run the prompt (or stdin) and exit
     :interactive — read lines from stdin and respond to each
     :error       — print error-msg to stderr and exit 1"
  [args]
  (let [;; Normalize short flags to their long form so the rest of
        ;; the parser only has to deal with --flag style. This is a
        ;; small step but it makes the cond below much simpler.
        normalize (fn [a]
                    (or (short->long a) a))]
    (loop [args  (seq args)
           opts  {:action :one-shot}]
      (if-let [a (first args)]
        (let [a (normalize a)]
          (cond
            ;; Boolean flags (no value)
            (#{"-h" "--help"} a)             (recur (rest args) (assoc opts :action :help))
            (#{"--version"} a)                (recur (rest args) (assoc opts :action :version))
            (#{"-i" "--interactive"} a)       (recur (rest args) (assoc opts :action :interactive))
            (#{"--no-interactive"} a)         (recur (rest args) (assoc opts :action :one-shot))

            ;; --flag=value form
            (and (str/starts-with? a "--") (str/includes? a "="))
            (let [[k v] (str/split a #"=" 2)]
              (recur (rest args) (assoc opts (keyword (subs k 2)) v)))

            ;; --flag value form (for flags that take a value)
            (and (str/starts-with? a "--")
                 (let [flag-kw (keyword (subs a 2))]
                   (some? (flag->opt-key flag-kw))))
            (let [flag-kw (keyword (subs a 2))
                  opt-key (flag->opt-key flag-kw)]
              (if-let [v (second args)]
                (recur (drop 2 args) (assoc opts opt-key v))
                (assoc opts :action :error
                       :error-msg (str "flag " a " requires a value"))))

            ;; --flag (long, no value, not recognized)
            (str/starts-with? a "-")
            (assoc opts :action :error
                   :error-msg (str "unknown flag: " a))

            ;; Positional: the prompt (one-shot)
            :else
            (recur (rest args) (assoc opts :prompt a))))
        opts))))

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

(defn- build-system
  "Build an Integrant system from the cli options.

   The :config path (if set) is read as EDN with Integrant's
   tag literals bound and merged over system/default-config.
   CLI LLM flags (--model, --base-url, --api-key) override the
   resulting :lateralus/llm-client entry.

   If :config is not set, returns the default config."
  [{:keys [config model base-url api-key] :as _opts}]
  (let [base (if config
               (merge system/default-config
                      (ig/read-string (slurp config)))
               system/default-config)
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
  [out result]
  (let [text (or (:exchange/response result)
                 (str "lateralus: no response (chain returned: "
                      (pr-str result) ")"))]
    (.println out text)
    (.flush out)))

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
   (let [o   (java.io.PrintWriter. (io/writer out :append true) true)
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
           (do (.println o "interactive mode not yet implemented (Step 8 MVP: one-shot only)")
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