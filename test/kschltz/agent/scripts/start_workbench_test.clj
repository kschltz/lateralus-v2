(ns kschltz.agent.scripts.start-workbench-test
  "Launcher flags and TTY prompt for scripts/start-workbench.sh."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private script "./scripts/start-workbench")

(defn- run
  [& args]
  (apply sh/sh script args))

(deftest help-lists-runtimes
  (let [{:keys [exit out err]} (run "--help")]
    (is (zero? exit) err)
    (is (str/includes? out "--local"))
    (is (str/includes? out "--docker"))
    (is (str/includes? out "--dry-run"))))

(deftest dry-run-local
  (let [{:keys [exit out err]} (run "--dry-run" "--local")]
    (is (zero? exit) err)
    (is (str/includes? out "mode=local"))
    (is (str/includes? out "clojure -M:workbench:run -i"))
    (is (str/includes? out "Workspace:"))))

(deftest dry-run-docker
  (let [{:keys [exit out err]} (run "--dry-run" "--docker")]
    (is (zero? exit) err)
    (is (str/includes? out "mode=docker"))
    (is (str/includes? out "command=docker compose run"))
    (is (str/includes? out "/workspace"))))

(deftest dry-run-mode-flag
  (let [{:keys [exit out]} (run "--dry-run" "--mode" "local")]
    (is (zero? exit))
    (is (str/includes? out "mode=local"))))

(deftest env-runtime-skips-prompt
  (let [{:keys [exit out]} (sh/sh "env"
                                   "LATERALUS_WORKBENCH_RUNTIME=local"
                                   script "--dry-run")]
    (is (zero? exit))
    (is (str/includes? out "mode=local"))))

(deftest non-tty-defaults-to-docker
  (testing "piped stdin is not a TTY, so the prompt is skipped"
    (let [{:keys [exit out err]} (run "--dry-run")]
      (is (zero? exit) err)
      (is (str/includes? out "mode=docker"))
      (is (str/includes? err "no TTY")))))

(deftest pty-prompt-choice-1-is-local
  (testing "a real PTY answering 1 selects local Clojure"
    (let [py (str "import os, pty, select, sys\n"
                   "pid, fd = pty.fork()\n"
                   "if pid == 0:\n"
                   "    os.execv('./scripts/start-workbench', ['start-workbench', '--dry-run'])\n"
                   "buf = b''\n"
                   "sent = False\n"
                   "deadline = os.times().elapsed + 8 if hasattr(os.times(), 'elapsed') else None\n"
                   "import time\n"
                   "end = time.time() + 8\n"
                   "while time.time() < end:\n"
                   "    r, _, _ = select.select([fd], [], [], 0.5)\n"
                   "    if fd in r:\n"
                   "        try:\n"
                   "            chunk = os.read(fd, 4096)\n"
                   "        except OSError:\n"
                   "            break\n"
                   "        if not chunk:\n"
                   "            break\n"
                   "        buf += chunk\n"
                   "        if (not sent) and b'Choice' in buf:\n"
                   "            os.write(fd, b'1\\n')\n"
                   "            sent = True\n"
                   "    wpid, status = os.waitpid(pid, os.WNOHANG)\n"
                   "    if wpid == pid:\n"
                   "        break\n"
                   "else:\n"
                   "    os.kill(pid, 9)\n"
                   "    os.waitpid(pid, 0)\n"
                   "sys.stdout.buffer.write(buf)\n"
                   "sys.exit(0 if sent and b'mode=local' in buf else 1)\n")
          {:keys [exit out err]} (sh/sh "python3" "-c" py)]
      (is (zero? exit) (str err "\n" out))
      (is (str/includes? out "How do you want to run"))
      (is (str/includes? out "mode=local")))))
