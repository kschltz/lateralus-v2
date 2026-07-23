(ns kschltz.agent.cli.ui-test
  "Comprehensive tests for the optional CLI styling pack."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [malli.core :as m]
            [kschltz.agent.cli.ui :as ui]
            [kschltz.agent.system :as system])
  (:import [java.io PrintWriter StringWriter]))

(def ^:private esc "\u001b")

(defn- has-ansi? [s]
  (str/includes? (str s) esc))

(defn- strip-ansi [s]
  (str/replace (str s) #"\u001b\[[0-9;]*m" ""))

;; ---- schema -----------------------------------------------------------

(deftest cli-ui-config-schema
  (testing "valid configs"
    (is (m/validate ui/CliUiConfig {}))
    (is (m/validate ui/CliUiConfig {:enabled? :auto :theme :default}))
    (is (m/validate ui/CliUiConfig {:enabled? false}))
    (is (m/validate ui/CliUiConfig {:enabled? true :theme :mono}))
    (is (m/validate ui/CliUiConfig
                    {:enabled? true
                     :theme :default
                     :styles {:assistant {:fg :yellow :bold? true}}}))
    (is (m/validate ui/CliUiConfig
                    {:enabled? true
                     :theme {:styles {:prompt {:fg :magenta}}}
                     :tty? true
                     :no-color? false})))
  (testing "invalid configs"
    (is (not (m/validate ui/CliUiConfig {:enabled? :sometimes})))
    (is (not (m/validate ui/CliUiConfig {:theme :neon})))
    (is (not (m/validate ui/CliUiConfig {:styles {:assistant {:fg :pink}}})))
    (is (not (m/validate ui/CliUiConfig {:styles {:unknown-role {:fg :red}}})))))

(deftest build-renderer-rejects-invalid-config
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid :lateralus/cli-ui"
                        (ui/build-renderer {:enabled? :maybe}))))

;; ---- enablement -------------------------------------------------------

(deftest resolve-enabled-explicit
  (is (true? (ui/resolve-enabled? {:enabled? true :tty? false :no-color? true}))
      "explicit true wins over non-TTY and NO_COLOR")
  (is (false? (ui/resolve-enabled? {:enabled? false :tty? true :no-color? false}))
      "explicit false disables even on a TTY"))

(deftest resolve-enabled-auto
  (is (true? (ui/resolve-enabled? {:enabled? :auto :tty? true :no-color? false})))
  (is (false? (ui/resolve-enabled? {:enabled? :auto :tty? false :no-color? false}))
      "auto off when not a TTY")
  (is (false? (ui/resolve-enabled? {:enabled? :auto :tty? true :no-color? true}))
      "auto off when NO_COLOR is set")
  (is (true? (ui/resolve-enabled? {:tty? true :no-color? false}))
      "missing :enabled? defaults to :auto"))

;; ---- plain renderer ---------------------------------------------------

(deftest plain-renderer-is-inert
  (let [r (ui/plain-renderer)]
    (is (false? (ui/enabled? r)))
    (is (= "hello" (ui/style r :assistant "hello")))
    (is (= "hello" (ui/style r :prompt "hello")))
    (is (not (has-ansi? (ui/style r :error "boom"))))))

(deftest style-nil-renderer-and-text
  (is (= "x" (ui/style nil :assistant "x")))
  (is (= "" (ui/style (ui/plain-renderer) :assistant nil))))

;; ---- ansi renderer ----------------------------------------------------

(deftest ansi-default-theme-colors-roles
  (let [r (ui/build-renderer {:enabled? true :theme :default :tty? true})]
    (is (true? (ui/enabled? r)))
    (doseq [role [:prompt :user :assistant :system :spinner :error :thinking]]
      (let [styled (ui/style r role "body")]
        (is (has-ansi? styled) (str role " should carry ANSI"))
        (is (str/includes? styled "body"))
        (is (str/ends-with? styled "\u001b[0m") "always reset")
        (is (= "body" (strip-ansi styled)))))))

(deftest ansi-mono-theme-prompt-bold-only
  (let [r (ui/build-renderer {:enabled? true :theme :mono})
        prompt (ui/style r :prompt "p")
        asst   (ui/style r :assistant "a")]
    (is (str/includes? prompt "\u001b[1m") "mono prompt is bold")
    (is (= "a" asst)
        "mono assistant has no fg/bold attrs by default")))

(deftest ansi-style-overrides-merge
  (let [r (ui/build-renderer {:enabled? true
                              :theme :default
                              :styles {:assistant {:fg :yellow}}})
        styled (ui/style r :assistant "hi")]
    (is (str/includes? styled "\u001b[33m") "yellow fg code 33")
    (is (= "hi" (strip-ansi styled)))))

(deftest ansi-inline-theme-map
  (let [r (ui/build-renderer {:enabled? true
                              :theme {:styles {:prompt {:fg :magenta :bold? true}}}})
        styled (ui/style r :prompt "x")]
    (is (str/includes? styled "\u001b[1;35m")
        "bold + magenta combine into one SGR sequence")
    (is (= "x" (strip-ansi styled)))))

(deftest wrap-ansi-empty-attrs
  (is (= "plain" (ui/wrap-ansi {} "plain")))
  (is (= "plain" (ui/wrap-ansi nil "plain"))))

(deftest auto-disabled-yields-plain
  (let [r (ui/build-renderer {:enabled? :auto :tty? false})]
    (is (false? (ui/enabled? r)))
    (is (= "hi" (ui/style r :assistant "hi")))))

;; ---- print helpers ----------------------------------------------------

(deftest println-role-writes-styled-line
  (let [sw (StringWriter.)
        pw (PrintWriter. sw true)
        r  (ui/build-renderer {:enabled? true :theme :default})]
    (ui/println-role pw r :assistant "answer")
    (let [out (str sw)]
      (is (has-ansi? out))
      (is (str/includes? (strip-ansi out) "answer"))
      (is (str/ends-with? out "\n")))))

(deftest print-role-no-newline
  (let [sw (StringWriter.)
        pw (PrintWriter. sw true)
        r  (ui/plain-renderer)]
    (ui/print-role pw r :prompt "lateralus> ")
    (is (= "lateralus> " (str sw)))))

;; ---- agent / integrant wiring -----------------------------------------

(deftest renderer-from-agent
  (is (= false (ui/enabled? (ui/renderer-from-agent {}))))
  (let [r (ui/build-renderer {:enabled? true})
        from (ui/renderer-from-agent {:agent/cli-ui r})]
    (is (true? (ui/enabled? from)))))

(deftest integrant-cli-ui-component
  (testing "default-config builds a CliRenderer onto the agent"
    (let [sys (ig/init (assoc system/default-config
                               :lateralus/cli-ui {:enabled? true :theme :default}))
          agent (:lateralus/agent sys)
          r (:agent/cli-ui agent)]
      (try
        (is (some? r))
        (is (true? (ui/enabled? r)))
        (is (has-ansi? (ui/style r :assistant "x")))
        (finally (ig/halt! sys)))))
  (testing "explicit disable yields plain renderer"
    (let [sys (ig/init (assoc system/default-config
                               :lateralus/cli-ui {:enabled? false}))
          r (get-in sys [:lateralus/agent :agent/cli-ui])]
      (try
        (is (false? (ui/enabled? r)))
        (is (= "x" (ui/style r :prompt "x")))
        (finally (ig/halt! sys)))))
  (testing "missing cli-ui on agent opts falls back to plain"
    (let [cfg (-> system/default-config
                  (dissoc :lateralus/cli-ui)
                  (assoc :lateralus/agent
                         {:plugins        (ig/ref :lateralus/plugins)
                          :llm-client     (ig/ref :lateralus/llm-client)
                          :llm-config     (ig/ref :lateralus/llm-config)
                          :embedder       (ig/ref :lateralus/embedder)
                          :memory-backend (ig/ref :lateralus/memory-backend)}))
          sys (ig/init cfg)
          r (get-in sys [:lateralus/agent :agent/cli-ui])]
      (try
        (is (false? (ui/enabled? r))
            "absent :cli-ui key still produces a safe plain renderer")
        (finally (ig/halt! sys))))))

(deftest integrant-assert-rejects-bad-cli-ui
  (is (thrown? clojure.lang.ExceptionInfo
               (ig/init (assoc system/default-config
                               :lateralus/cli-ui {:enabled? :nope})))))
