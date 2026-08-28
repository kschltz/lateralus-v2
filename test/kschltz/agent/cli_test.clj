(ns kschltz.agent.cli-test
  "Tests for the clean-slate CLI.

   The CLI is a thin layer: parse args, build a system, run the
   runtime, print results. The testable surface is:

     parse-args    pure (args → cli-options)
     run-cli       takes cli-options + a runtime config (in/out/exit/
                   system-fn/runner-fn); returns exit code
     -main         a thin shell wrapper that calls run-cli with
                   System/in, System/out, and System/exit

   We do not test -main directly. We do not load Integrant or
   talk to a real LLM in these tests. The :system-fn and
   :runner-fn callbacks are the test seam."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [kschltz.agent.cli :as cli]
            [kschltz.agent.cli.ui :as ui]
            [kschltz.agent.llm.http :as llm-http])
  (:import [java.io File]
           [java.util UUID]))

;; Isolate profile I/O from the developer's real ~/.config/lateralus.
(use-fixtures :each
  (fn [f]
    (let [dir (str (System/getProperty "java.io.tmpdir")
                   "/lateralus-cli-test-" (UUID/randomUUID))
          prev (System/getProperty "lateralus.config.home")]
      (.mkdirs (io/file dir))
      (System/setProperty "lateralus.config.home" dir)
      (try
        (f)
        (finally
          (if prev
            (System/setProperty "lateralus.config.home" prev)
            (System/clearProperty "lateralus.config.home")))))))

;; ---- capture helpers ----

(defn- capture-out
  "Run `f` with *out* bound to a StringWriter; return [rv captured-str]."
  [f]
  (let [sw (java.io.StringWriter.)]
    (binding [*out* sw]
      [(f) (str sw)])))

(defn- silent-exit
  "An :exit fn that records the code and returns it. We use
   (constantly nil) in tests where we don't care about the code,
   or this fn where we do."
  ([recorded] (fn [n] (reset! recorded n) n))
  ([]          (constantly nil)))

;; ---- parse-args: pure ----

(deftest parse-args-help
  (testing "-h and --help both yield :help"
    (is (= :help (:action (cli/parse-args ["-h"]))))
    (is (= :help (:action (cli/parse-args ["--help"]))))))

(deftest parse-args-version
  (testing "--version yields :version"
    (is (= :version (:action (cli/parse-args ["--version"]))))))

(deftest parse-args-one-shot-prompt
  (testing "single positional arg is a one-shot prompt"
    (let [r (cli/parse-args ["hello world"])]
      (is (= :one-shot (:action r)))
      (is (= "hello world" (:prompt r))))))

(deftest parse-args-no-prompt-means-stdin
  (testing "no positional args and no -i defaults to one-shot reading stdin"
    (let [r (cli/parse-args [])]
      (is (= :one-shot (:action r)))
      (is (nil? (:prompt r))
          "no prompt arg; the runner should read stdin"))))

(deftest parse-args-interactive
  (testing "-i / --interactive forces interactive mode"
    (is (= :interactive (:action (cli/parse-args ["-i"]))))
    (is (= :interactive (:action (cli/parse-args ["--interactive"]))))))

(deftest parse-args-no-interactive
  (testing "--no-interactive forces one-shot mode"
    (is (= :one-shot (:action (cli/parse-args ["--no-interactive"]))))))

(deftest parse-args-session
  (testing "-s / --session sets the session id"
    (is (= "my-session" (:session-id (cli/parse-args ["-s" "my-session"]))))
    (is (= "my-session" (:session-id (cli/parse-args ["--session" "my-session"]))))))

(deftest parse-args-config
  (testing "--config sets the Integrant config path"
    (is (= "my.edn" (:config (cli/parse-args ["--config" "my.edn"]))))))

(deftest parse-args-llm-opts
  (testing "--model, --base-url, --api-key are captured"
    (let [r (cli/parse-args ["--model" "gpt-4"
                             "--base-url" "http://localhost:8080"
                             "--api-key" "sk-123"])]
      (is (= "gpt-4" (:model r)))
      (is (= "http://localhost:8080" (:base-url r)))
      (is (= "sk-123" (:api-key r))))))

(deftest parse-args-unknown-flag
  (testing "unknown flag yields an :error action"
    (let [r (cli/parse-args ["--not-a-flag"])]
      (is (= :error (:action r)))
      (is (re-find #"not-a-flag" (:error-msg r))))))

(deftest parse-args-flag-needs-value
  (testing "a flag that takes a value, with no value, yields an :error"
    (let [r (cli/parse-args ["--model"])]
      (is (= :error (:action r)))
      (is (re-find #"(?i)model" (:error-msg r))))))

(deftest parse-args-flag-value-forms
  (testing "value flags accept both --flag=value and --flag value forms"
    (doseq [args [["--session=abc"] ["-s" "abc"] ["--session" "abc"]]]
      (is (= "abc" (:session-id (cli/parse-args args)))
          (str "session parsed from " (pr-str args))))
    (doseq [args [["--model=gpt-4"] ["--model" "gpt-4"]]]
      (is (= "gpt-4" (:model (cli/parse-args args)))
          (str "model parsed from " (pr-str args))))
    (doseq [args [["--config=a.edn"] ["--config" "a.edn"]]]
      (is (= "a.edn" (:config (cli/parse-args args)))
          (str "config parsed from " (pr-str args))))))

(deftest parse-args-unknown-flags
  (testing "unknown long and short flags yield :error with the flag name in the message"
    (let [long (cli/parse-args ["--not-a-flag"])
          short (cli/parse-args ["-x"])]
      (is (= :error (:action long)))
      (is (re-find #"not-a-flag" (:error-msg long)))
      (is (= :error (:action short)))
      (is (re-find #"\-x" (:error-msg short))))))

(deftest parse-args-help-text-includes-all-flags
  (testing "the help text mentions every flag"
    (let [h   (cli/help-text)
          all ["--help" "-h" "--version" "--interactive" "-i"
               "--no-interactive" "--session" "-s" "--config"
               "--model" "--base-url" "--api-key"]]
      (doseq [f all]
        (is (str/includes? h f)
            (str "help text mentions " f))))))

;; ---- run-cli: in-memory in/out ----

(deftest run-cli-help
  (testing "help action prints help and returns exit 0"
    (let [[rv out] (capture-out
                    #(cli/run-cli {:action :help}
                                  {:in  (java.io.StringReader. "")
                                   :out *out*
                                   :exit (constantly nil)}))]
      (is (= 0 rv))
      (is (str/includes? out "Usage:"))
      (is (str/includes? out "--help")))))

(deftest run-cli-version
  (testing "version action prints the version and returns exit 0"
    (let [[rv out] (capture-out
                    #(cli/run-cli {:action :version}
                                  {:in  (java.io.StringReader. "")
                                   :out *out*
                                   :exit (constantly nil)}))]
      (is (= 0 rv))
      (is (str/includes? out "lateralus-v2")))))

(deftest run-cli-error
  (testing "error action prints to stderr and returns exit 1"
    (let [err-sw  (java.io.StringWriter.)
          captured (atom nil)
          rv      (binding [*err* err-sw]
                    (cli/run-cli {:action :error :error-msg "bad flag"}
                                 {:in  (java.io.StringReader. "")
                                  :out *out*
                                  :exit (silent-exit captured)}))]
      (is (= 1 rv))
      (is (= 1 @captured) "exit code 1 was passed to the :exit fn")
      (is (str/includes? (str err-sw) "bad flag")))))

(deftest run-cli-one-shot-uses-prompt
  (testing "one-shot mode with a :prompt arg sends that prompt to the runner
   and prints the response. No stdin read happens."
    (let [sent     (atom nil)
          runner   (fn [{:keys [prompt] :as args}]
                     (reset! sent args)
                     {:exchange/response (str "echo: " prompt)})
          captured (atom nil)
          [rv out] (capture-out
                    #(cli/run-cli
                      {:action :one-shot :prompt "hello"}
                      {:in        (java.io.StringReader. "") ; unused
                       :out       *out*
                       :exit      (silent-exit captured)
                       :runner-fn runner}))]
      (is (= 0 rv))
      (is (= 0 @captured) "exit code 0 on success")
      (is (= "hello" (:prompt @sent))
          "the runner was called with the parsed prompt")
      (is (str/includes? out "echo: hello")
          "the response was printed to stdout"))))

(deftest run-cli-one-shot-reads-stdin
  (testing "one-shot mode without a :prompt arg reads stdin"
    (let [sent     (atom nil)
          runner   (fn [args]
                     (reset! sent args)
                     {:exchange/response "ok"})
          captured (atom nil)]
      (with-in-str "from-stdin"
        (let [[rv out] (capture-out
                        #(cli/run-cli
                          {:action :one-shot}
                          {:in        *in*
                           :out       *out*
                           :exit      (silent-exit captured)
                           :runner-fn runner}))]
          (is (= 0 rv))
          (is (= "from-stdin" (:prompt @sent))
              "the runner was called with the stdin content")
          (is (str/includes? out "ok")))))))

;; ---- verify-round-3 FIX 2: summary-failed must surface a clear ----
;; ---- 'agent did not produce a final answer' message, NEVER the --
;; ---- internal 'model kept calling tools on the summary turn' ----
;; ---- fallback string, when the model keeps emitting tool_calls ----
;; ---- on the summary turn despite tool_choice:none. -----------

(deftest run-cli-summary-failed-surfaces-clear-no-final-answer-message
  (testing "verify-round-3 FIX 2: when :agent/summary-failed? is set with a
            blank response, the user-facing output leads with a clear
            'the agent did not produce a final answer' message and does NOT
            surface the internal 'model kept calling tools on the summary
            turn' string; the tool results are appended as reference"
    (let [runner  (fn [_args]
                    ;; Simulates the verify-round-2 glm-5.2 outcome: the
                    ;; summary mini-chain exhausted its retries with the
                    ;; model still emitting tool_calls, so summary-failed?
                    ;; is set and the response is blank. all-tool-results
                    ;; carry one add-lib call so the appendix is non-empty.
                    {:exchange/response       ""
                     :agent/summary-failed?  true
                     :agent/all-tool-results [{:call {:id "tc1"
                                                       :function {:name "clojure_add_lib"
                                                                  :arguments "{}"}}
                                               :result "{\"status\":\"ok\",\"loaded?\":false}"}]})
          captured (atom nil)]
      (let [[rv out] (capture-out
                      #(cli/run-cli
                        {:action :one-shot :prompt "do a thing"}
                        {:in        (java.io.StringReader. "")
                         :out       *out*
                         :exit      (silent-exit captured)
                         :runner-fn runner}))]
        (is (= 0 rv))
        (is (str/includes? out "the agent did not produce a final answer")
            "the user-facing message clearly states no final answer was produced")
        (is (not (str/includes? out "model kept calling tools on the summary turn"))
            "the internal 'model kept calling tools' fallback string is NOT shown to the user")
        (is (str/includes? out "clojure_add_lib")
            "the tool results produced are appended as reference so the REPL is not blank")))))

(deftest build-system-loads-bundled-config
  (testing "build-system reads the bundled resources/lateralus/config.edn
   with Integrant tag support and merges it over default-config"
    (let [config (cli/build-system {})]
      (is (contains? config :lateralus/agent))
      (is (set/subset?
           #{:plugins :llm-client :llm-config :embedder :memory-backend :loop-opts :cli-ui}
           (set (keys (:lateralus/agent config)))))
      ;; Pin that #ig/ref tags were resolved (not left as raw symbols).
      (is (every? ig/reflike?
                  (vals (select-keys (:lateralus/agent config)
                                     [:plugins :llm-client :llm-config
                                      :embedder :memory-backend :cli-ui])))
          "all agent refs are Integrant refs"))))

(deftest build-system-session-api-key-precedence
  (testing "interactive :session-api-key beats config/env but not --api-key"
    (let [with-key (fn [opts] (get-in (cli/build-system opts)
                                      [:lateralus/llm-client :api-key]))]
      (is (= "session-key"
             (with-key {:session-api-key "session-key"})))
      (is (= "flag-key"
             (with-key {:api-key "flag-key" :session-api-key "session-key"})))
      (let [cfg (java.io.File/createTempFile "lat-cfg" ".edn")]
        (spit cfg "{:lateralus/llm-client {:impl :http :api-key \"config-key\"}}")
        (is (= "session-key"
               (with-key {:config (.getPath cfg)
                          :session-api-key "session-key"})))
        (is (= "config-key"
               (with-key {:config (.getPath cfg)})))))))

(deftest build-system-merges-custom-config
  (testing "--config file overrides the bundled/default config with ig/read-string"
    (let [config (cli/build-system {:config "resources/lateralus/config.edn"
                                    :model "overridden"})]
      (is (= "overridden" (get-in config [:lateralus/llm-client :model])))
      (is (= "overridden" (get-in config [:lateralus/llm-config :model])))
      (is (ig/reflike? (get-in config [:lateralus/agent :llm-client]))))))

(deftest run-cli-honors-config-path
  (testing "--config is read with Integrant tag support and passed to the system-fn"
    (let [config-path "resources/lateralus/config.edn"
          seen-opts   (atom nil)
          system-fn   (fn [opts]
                        (reset! seen-opts opts)
                        [{:exchange-chain []} "sid" (constantly nil)])
          captured    (atom nil)]
      (capture-out
       #(cli/run-cli
         {:action :one-shot :config config-path :prompt "x"}
         {:out       *out*
          :exit      (silent-exit captured)
          :system-fn system-fn
          :runner-fn (fn [_] {:exchange/response "ok"})}))
      (is (= config-path (:config @seen-opts))
          "the config path is passed through to the system-fn"))))

(deftest run-cli-interactive-echoes-until-quit
  (testing "interactive mode reads lines, sends each to the runner, prints responses, and stops on /quit"
    (let [halted  (atom false)
          system-fn (fn [_]
                      [{:exchange-chain [{:name ::echo
                                           :enter (fn [ctx]
                                                    (assoc ctx :exchange/response
                                                           (str "echo: " (:exchange/user-text ctx))))}]}
                       "demo-session"
                       (fn [] (reset! halted true))])
          captured (atom nil)]
      (with-in-str "hello\nworld\n/quit\n"
        (let [[rv out] (capture-out
                        #(cli/run-cli
                          {:action :interactive}
                          {:in        *in*
                           :out       *out*
                           :exit      (silent-exit captured)
                           :tty?      false
                           :system-fn system-fn}))]
          (is (= 0 rv))
          (is (= 0 @captured))
          (is @halted "halt-fn was called when the loop finished")
          (is (str/includes? out "lateralus-v2 interactive mode"))
          (is (str/includes? out "lateralus>"))
          (is (str/includes? out "echo: hello"))
          (is (str/includes? out "echo: world"))
          (is (str/includes? out "Goodbye.")))))))

(deftest run-cli-interactive-shows-thinking-indicator
  (testing "interactive mode prints a 'thinking...' spinner and clears it before the response"
    (let [halted  (atom false)
          system-fn (fn [_]
                      [{:exchange-chain [{:name ::slow-echo
                                           :enter (fn [ctx]
                                                    (Thread/sleep 300)
                                                    (assoc ctx :exchange/response "done"))}]}
                       "demo-session"
                       (fn [] (reset! halted true))])
          captured (atom nil)]
      (with-in-str "hello\n/quit\n"
        (let [[rv out] (capture-out
                        #(cli/run-cli
                          {:action :interactive}
                          {:in        *in*
                           :out       *out*
                           :exit      (silent-exit captured)
                           :tty?      false
                           :system-fn system-fn}))]
          (is (= 0 rv))
          (is (str/includes? out "thinking"))
          (is (str/includes? out "done"))
          (is (re-find #"\rthinking\.\..*\r" out)
              "spinner line is cleared before the response is printed"))))))

(deftest run-cli-applies-cli-ui-colors-when-enabled
  (testing "one-shot response is ANSI-styled when agent carries an enabled CliRenderer"
    (let [renderer (ui/build-renderer {:enabled? true :theme :default})
          system-fn (fn [_]
                      [{:agent/cli-ui renderer} "sid" (constantly nil)])
          runner-fn (fn [_] {:exchange/response "colored-answer"})
          captured (atom nil)
          [_ out] (capture-out
                   #(cli/run-cli
                     {:action :one-shot :prompt "hi"}
                     {:out *out*
                      :exit (silent-exit captured)
                      :system-fn system-fn
                      :runner-fn runner-fn}))]
      (is (= 0 @captured))
      (is (str/includes? out "colored-answer"))
      (is (str/includes? out "\u001b[")
          "assistant response carries ANSI when cli-ui is enabled")
      (is (str/includes? out "\u001b[0m")
          "styled output resets SGR"))))

(deftest run-cli-plain-when-cli-ui-disabled
  (testing "no ANSI when agent has a plain/disabled renderer"
    (let [system-fn (fn [_]
                      [{:agent/cli-ui (ui/build-renderer {:enabled? false})}
                       "sid" (constantly nil)])
          runner-fn (fn [_] {:exchange/response "plain-answer"})
          captured (atom nil)
          [_ out] (capture-out
                   #(cli/run-cli
                     {:action :one-shot :prompt "hi"}
                     {:out *out*
                      :exit (silent-exit captured)
                      :system-fn system-fn
                      :runner-fn runner-fn}))]
      (is (str/includes? out "plain-answer"))
      (is (not (str/includes? out "\u001b["))
          "disabled cli-ui emits no ANSI"))))

(deftest run-cli-thinking-full-prints-before-response
  (let [system-fn (fn [_]
                    [{:agent/cli-ui (ui/plain-renderer)
                      :agent/thinking {:mode :full}}
                     "sid" (constantly nil)])
        runner-fn (fn [_] {:exchange/response "answer"
                           :exchange/thinking "I reasoned carefully"
                           :exchange/session-id "sid"
                           :exchange/user-text "hi"})
        captured (atom nil)
        [_ out] (capture-out
                 #(cli/run-cli
                   {:action :one-shot :prompt "hi"}
                   {:out *out*
                    :exit (silent-exit captured)
                    :system-fn system-fn
                    :runner-fn runner-fn}))]
    (is (= 0 @captured))
    (is (str/includes? out "[thinking]"))
    (is (str/includes? out "I reasoned carefully"))
    (is (str/includes? out "answer"))
    (is (< (str/index-of out "I reasoned carefully")
           (str/index-of out "answer"))
        "thinking block prints before assistant body")))

(deftest run-cli-thinking-off-hides-reasoning
  (let [system-fn (fn [_]
                    [{:agent/cli-ui (ui/plain-renderer)
                      :agent/thinking {:mode :off}}
                     "sid" (constantly nil)])
        runner-fn (fn [_] {:exchange/response "answer"
                           :exchange/thinking "secret thoughts"})
        captured (atom nil)
        [_ out] (capture-out
                 #(cli/run-cli
                   {:action :one-shot :prompt "hi"}
                   {:out *out*
                    :exit (silent-exit captured)
                    :system-fn system-fn
                    :runner-fn runner-fn}))]
    (is (str/includes? out "answer"))
    (is (not (str/includes? out "secret thoughts")))
    (is (not (str/includes? out "[thinking]")))))

(deftest run-cli-thinking-preview-truncates
  (let [system-fn (fn [_]
                    [{:agent/cli-ui (ui/plain-renderer)
                      :agent/thinking {:mode :preview :preview-chars 8}}
                     "sid" (constantly nil)])
        runner-fn (fn [_] {:exchange/response "ok"
                           :exchange/thinking "abcdefghijklmnop"})
        captured (atom nil)
        [_ out] (capture-out
                 #(cli/run-cli
                   {:action :one-shot :prompt "hi"}
                   {:out *out*
                    :exit (silent-exit captured)
                    :system-fn system-fn
                    :runner-fn runner-fn}))]
    (is (str/includes? out "abcdefgh…"))
    (is (not (str/includes? out "ijklmnop")))))

(deftest run-cli-interactive-styles-prompt-with-cli-ui
  (testing "interactive prompt uses :prompt role styling when enabled"
    (let [renderer (ui/build-renderer {:enabled? true :theme :default})
          system-fn (fn [_]
                      [{:agent/cli-ui renderer
                        :exchange-chain [{:name ::echo
                                          :enter (fn [ctx]
                                                   (assoc ctx :exchange/response "ok"))}]}
                       "sid"
                       (constantly nil)])
          captured (atom nil)]
      (with-in-str "/quit\n"
        (let [[_ out] (capture-out
                       #(cli/run-cli
                         {:action :interactive}
                         {:in *in*
                          :out *out*
                          :exit (silent-exit captured)
                          :tty? false
                          :system-fn system-fn}))]
          (is (str/includes? out "lateralus>"))
          (is (str/includes? out "\u001b[")
              "styled prompt emits ANSI")
          (is (str/includes? out "Goodbye.")))))))

;; ---- model selection: pure parser ----

(deftest parse-selection-numeric-and-name
  (let [ids ["a" "b" "c"]]
    (testing "1-based integer selects that index"
      (is (= "a" (cli/parse-selection "1" ids)))
      (is (= "b" (cli/parse-selection "2" ids)))
      (is (= "c" (cli/parse-selection "3" ids))))
    (testing "exact id string selects it"
      (is (= "c" (cli/parse-selection "c" ids)))
      (is (= "a" (cli/parse-selection "  a  " ids)) "input is trimmed"))
    (testing "out-of-range / unknown are :invalid"
      (is (= :invalid (cli/parse-selection "0" ids)))
      (is (= :invalid (cli/parse-selection "9" ids)))
      (is (= :invalid (cli/parse-selection "zzz" ids))))
    (testing "blank input is :blank"
      (is (= :blank (cli/parse-selection "" ids)))
      (is (= :blank (cli/parse-selection "   \t " ids))))))

;; ---- resolve-llm-config ----

(defn- temp-config-file
  "Write `m` as EDN to a temp file and return its absolute path. Only
   used for `:lateralus/llm-client` overrides here, so `pr-str` is
   enough (no #ig/ref needed)."
  [m]
  (let [f (File/createTempFile "lat-cli-test" ".edn")]
    (spit f (with-out-str (pr m)))
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(deftest resolve-llm-config-default-stub
  (testing "with no config the default is the stub impl"
    (let [cfg (cli/resolve-llm-config {})]
      (is (= :stub (:impl cfg))))))

(deftest resolve-llm-config-applies-cli-overrides
  (testing "--model/--base-url/--api-key override the resolved client map"
    (let [cfg (cli/resolve-llm-config {:model    "glm-5.1"
                                       :base-url "http://x/v1"
                                       :api-key  "k"})]
      (is (= :stub (:impl cfg)) "impl stays :stub from default config")
      (is (= "glm-5.1" (:model cfg)))
      (is (= "http://x/v1" (:base-url cfg)))
      (is (= "k" (:api-key cfg))))))

(deftest resolve-llm-config-http-config-without-model
  (testing "an :http config with no model resolves to :impl :http and no :model"
    (let [cfg-path (temp-config-file {:lateralus/llm-client
                                       {:impl :http :base-url "http://x/v1"}})
          cfg (cli/resolve-llm-config {:config cfg-path})]
      (is (= :http (:impl cfg)))
      (is (= "http://x/v1" (:base-url cfg)))
      (is (str/blank? (:model cfg))))))

;; ---- default-model-selector (seam-driven) ----

(defn- scripted-reader
  "Return a 0-arg fn that pops lines from `atom` (a vector) one at a
  time, returning nil when exhausted."
  [lines]
  (let [q (atom lines)]
    (fn [] (let [l (first @q)] (swap! q rest) l))))

(deftest model-selector-picks-by-number
  (let [out (java.io.StringWriter.)
        chosen (cli/default-model-selector
                 {:base-url "http://x/v1" :api-key "k" :out out
                  :list-models-fn (fn [] ["a" "b" "c"])
                  :read-line-fn   (scripted-reader ["2"])})
        s (str out)]
    (is (= "b" chosen))
    (is (str/includes? s "models available"))
    (is (str/includes? s "1) a"))
    (is (str/includes? s "2) b"))
    (is (str/includes? s "3) c"))
    (is (str/includes? s "Using b"))))

(deftest model-selector-picks-by-name
  (let [out (java.io.StringWriter.)
        chosen (cli/default-model-selector
                 {:base-url "http://x/v1" :out out
                  :list-models-fn (fn [] ["gemma4:31b" "glm-5.1"])
                  :read-line-fn   (scripted-reader ["glm-5.1"])})]
    (is (= "glm-5.1" chosen))))

(deftest model-selector-blank-picks-first
  (let [out (java.io.StringWriter.)
        chosen (cli/default-model-selector
                 {:base-url "http://x/v1" :out out
                  :list-models-fn (fn [] ["a" "b"])
                  :read-line-fn   (scripted-reader [""])})
        s (str out)]
    (is (= "a" chosen))
    (is (str/includes? s "Using a"))))

(deftest model-selector-no-tty-autopicks-first
  (testing "read-line-fn returning nil (no TTY) auto-picks the first model"
    (let [out (java.io.StringWriter.)
          chosen (cli/default-model-selector
                   {:base-url "http://x/v1" :out out
                    :list-models-fn (fn [] ["a" "b"])
                    :read-line-fn   (fn [] nil)})
          s (str out)]
      (is (= "a" chosen))
      (is (str/includes? s "defaulting to a")))))

(deftest model-selector-invalid-then-valid
  (let [out (java.io.StringWriter.)
        chosen (cli/default-model-selector
                 {:base-url "http://x/v1" :out out
                  :list-models-fn (fn [] ["a" "b"])
                  :read-line-fn   (scripted-reader ["zzz" "99" "1"])})]
    (is (= "a" chosen))
    (is (str/includes? (str out) "Invalid choice"))))

(deftest model-selector-listing-fails-free-text
  (testing "when the listing throws, the selector falls back to free text"
    (let [out (java.io.StringWriter.)
          chosen (cli/default-model-selector
                   {:base-url "http://x/v1" :out out
                    :list-models-fn (fn [] (throw (ex-info "boom" {})))
                    :read-line-fn   (scripted-reader ["typed-model"])})]
      (is (= "typed-model" chosen))
      (is (str/includes? (str out) "Type a model name")))))

(deftest model-selector-listing-fails-no-tty-gives-up
  (testing "no list + no TTY yields nil (run-cli then surfaces the missing model)"
    (let [out (java.io.StringWriter.)
          chosen (cli/default-model-selector
                   {:base-url "http://x/v1" :out out
                    :list-models-fn (fn [] (throw (ex-info "boom" {})))
                    :read-line-fn   (fn [] nil)})]
      (is (nil? chosen)))))

;; ---- run-cli wiring ----

(deftest run-cli-prompts-for-model-when-missing
  (testing "an :http config with no model triggers :model-selector and threads the choice into :system-fn"
    (let [cfg-path (temp-config-file {:lateralus/llm-client
                                       {:impl :http :base-url "http://x/v1"}})
          selected (atom nil)
          sys-opts (atom nil)
          opts     (cli/parse-args ["--config" cfg-path "hello"])
          code     (cli/run-cli opts
                     {:in     (java.io.ByteArrayInputStream. (.getBytes "hello"))
                      :out    (java.io.StringWriter.)
                      :exit   (fn [_] nil)
                      :model-selector (fn [ctx]
                                        (reset! selected ctx)
                                        "glm-5.1")
                      :system-fn (fn [o] (reset! sys-opts o)
                                  [{} "s" (fn [])])
                      :runner-fn (fn [{:keys [prompt]}]
                                  {:exchange/response (str "ran:" prompt)})})]
      (is (= 0 code))
      (is (= "glm-5.1" (:model @sys-opts))
          "the chosen model must reach build-system via the :model override")
      (is (= "http://x/v1" (:base-url @selected))
          "selector receives the resolved base-url"))))

(deftest run-cli-skips-selector-when-model-set-in-config
  (testing "a config that already specifies a model does not trigger the selector"
    (let [cfg-path (temp-config-file {:lateralus/llm-client
                                       {:impl :http :base-url "http://x/v1"
                                        :model "preset"}})
          called   (atom false)
          opts     (cli/parse-args ["--config" cfg-path "hi"])
          code     (cli/run-cli opts
                     {:in     (java.io.ByteArrayInputStream. (.getBytes "hi"))
                      :out    (java.io.StringWriter.)
                      :exit   (fn [_] nil)
                      :model-selector (fn [_] (reset! called true) "should-not")
                      :system-fn (fn [_] [{} "s" (fn [])])
                      :runner-fn (fn [_] {:exchange/response "ok"})})]
      (is (= 0 code))
      (is (false? @called)))))

(deftest run-cli-skips-selector-when-model-set-via-flag
  (testing "--model on the CLI also short-circuits the selector"
    (let [cfg-path (temp-config-file {:lateralus/llm-client
                                       {:impl :http :base-url "http://x/v1"}})
          called   (atom false)
          opts     (cli/parse-args ["--config" cfg-path "--model" "flagged" "hi"])
          code     (cli/run-cli opts
                     {:in     (java.io.ByteArrayInputStream. (.getBytes "hi"))
                      :out    (java.io.StringWriter.)
                      :exit   (fn [_] nil)
                      :model-selector (fn [_] (reset! called true) "should-not")
                      :system-fn (fn [_] [{} "s" (fn [])])
                      :runner-fn (fn [_] {:exchange/response "ok"})})]
      (is (= 0 code))
      (is (false? @called)))))

(deftest run-cli-skips-selector-for-stub-impl
  (testing "the default stub config never triggers the selector"
    (let [called (atom false)
          opts   (cli/parse-args ["hi"])
          code   (cli/run-cli opts
                    {:in     (java.io.ByteArrayInputStream. (.getBytes "hi"))
                     :out    (java.io.StringWriter.)
                     :exit   (fn [_] nil)
                     :tty?   false
                     :model-selector (fn [_] (reset! called true) "nope")
                     :system-fn (fn [_] [{} "s" (fn [])])
                     :runner-fn (fn [_] {:exchange/response "ok"})})]
      (is (= 0 code))
      (is (false? @called)))))

(deftest run-cli-profile-gate-on-tty-without-config
  (testing "no --config on a TTY runs the profile wizard before the system builds"
    (let [sys-opts (atom nil)
          out      (java.io.StringWriter.)
          ;; first-run: starter 1, keep all field defaults, skip API key,
          ;; accept tool groups, save
          lines    (atom ["1" "" "" "" "" "" "" "" "" "default"])
          opts     (cli/parse-args ["hi"])
          code     (cli/run-cli opts
                     {:in     (java.io.ByteArrayInputStream. (.getBytes "hi"))
                      :out    out
                      :exit   (fn [_] nil)
                      :tty?   true
                      :read-line-fn (fn []
                                      (let [l (first @lines)]
                                        (swap! lines rest)
                                        l))
                      :list-models-fn (fn [_ _] [])
                      :system-fn (fn [o] (reset! sys-opts o)
                                  [{} "s" (fn [])])
                      :runner-fn (fn [_] {:exchange/response "ok"})})]
      (is (= 0 code))
      (is (some? (:profile-edn @sys-opts)))
      (is (= :http (get-in (cli/build-system @sys-opts)
                           [:lateralus/llm-client :impl])))
      (is (str/includes? (str out) "never saved")))))

(deftest run-cli-uses-default-selector-when-seam-omitted
  (testing "omitting :model-selector still works via the :or default (the -main path)"
    ;; Before the fix, -main -> (run-cli opts {}) left model-selector nil
    ;; and ensure-model NPE'd. Now the :or default + (or ...) fallback cover it.
    (let [cfg-path (temp-config-file {:lateralus/llm-client
                                       {:impl :http :base-url "http://x/v1"}})
          out      (java.io.StringWriter.)
          sys-opts (atom nil)
          opts     (cli/parse-args ["--config" cfg-path "hi"])
          code     (cli/run-cli opts
                     {:in     (java.io.ByteArrayInputStream. (.getBytes "hi"))
                      :out    out
                      :exit   (fn [_] nil)
                      ;; NO :model-selector seam — exercises the :or default.
                      :list-models-fn (fn [_base _api] ["a" "b"])
                      :read-line-fn   (fn [] "2")
                      :system-fn (fn [o] (reset! sys-opts o)
                                  [{} "s" (fn [])])
                      :runner-fn (fn [_] {:exchange/response "ok"})})]
      (is (= 0 code))
      (is (= "b" (:model @sys-opts))
          "the default selector ran and threaded the chosen model through"))))

(deftest run-cli-selector-give-up-keeps-opts-but-prints-guidance
  (testing "a nil selection does not assoc a model and prints guidance"
    (let [cfg-path (temp-config-file {:lateralus/llm-client
                                       {:impl :http :base-url "http://x/v1"}})
          out      (java.io.StringWriter.)
          seen     (atom nil)
          opts     (cli/parse-args ["--config" cfg-path "hi"])
          code     (cli/run-cli opts
                     {:in     (java.io.ByteArrayInputStream. (.getBytes "hi"))
                      :out    out
                      :exit   (fn [_] nil)
                      :model-selector (fn [_] nil)
                      :system-fn (fn [o] (reset! seen o)
                                  [{} "s" (fn [])])
                      :runner-fn (fn [_] {:exchange/response "ok"})})]
      (is (str/blank? (:model @seen))
          "ensure-model must NOT assoc a model when the selector gives up")
      (is (str/includes? (str out) "no model selected")
          "guidance is printed when no model is selected"))))

;; ---- ^:e2e: live model listing ----

(defn- ^:private host-port
  "Parse http(s)://host:port from a URL string."
  [base-url]
  (let [m (re-find #"^https?://([^:/]+)(?::(\d+))?" base-url)]
    (when m
      {:host (nth m 1)
       :port (if-let [p (nth m 2 nil)]
               (Integer/parseInt p)
               (if (str/starts-with? base-url "https://") 443 80))})))

(defn- ^:private reachable?
  [{:keys [host port]}]
  (let [sock (java.net.Socket.)]
    (try
      (.connect sock (java.net.InetSocketAddress. ^String host (int port)) 1500)
      (.close sock)
      true
      (catch Throwable _ false))))

(deftest ^:e2e list-models-returns-ids-from-configured-endpoint
  (testing "list-models returns a non-empty vector of strings from a live endpoint"
    (let [base-url (or (System/getenv "OLLAMA_BASE_URL")
                       "http://localhost:11434/v1")
          api-key  (System/getenv "OLLAMA_API_KEY")
          target   (host-port base-url)]
      (if (not (reachable? target))
        (println (str "SKIPPED list-models e2e: endpoint not reachable at " base-url))
        (let [ids (llm-http/list-models base-url api-key)]
          (is (vector? ids))
          (is (seq ids) (str "expected at least one model from " base-url))
          (is (every? string? ids)))))))
