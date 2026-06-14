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
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [kschltz.agent.cli :as cli]))

;; ---- capture helpers ----

(defn- capture-out
  "Run `f` with *out* bound to a StringWriter; return [rv captured-str]."
  [f]
  (let [sw (java.io.StringWriter.)]
    (binding [*out* sw]
      [(f) (str sw)])))

(defn- capture-err
  "Run `f` with *err* bound to a StringWriter; return [rv captured-str]."
  [f]
  (let [sw (java.io.StringWriter.)]
    (binding [*err* sw]
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

(deftest build-system-loads-bundled-config
  (testing "build-system reads the bundled resources/lateralus/config.edn
   with Integrant tag support and merges it over default-config"
    (let [config (cli/build-system {})]
      (is (contains? config :lateralus/agent))
      (is (= #{:plugins :llm-client :llm-config :embedder :memory-backend}
             (set (keys (:lateralus/agent config)))))
      ;; Pin that #ig/ref tags were resolved (not left as raw symbols).
      (is (every? ig/reflike?
                  (vals (select-keys (:lateralus/agent config)
                                     [:plugins :llm-client :llm-config
                                      :embedder :memory-backend])))
          "all agent refs are Integrant refs"))))

(deftest build-system-merges-custom-config
  (testing "--config file overrides the bundled/default config with ig/read-string"
    (let [config (cli/build-system {:config "resources/lateralus/config.edn"
                                    :model "overridden"})]
      (is (= "overridden" (get-in config [:lateralus/llm-client :model])))
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
