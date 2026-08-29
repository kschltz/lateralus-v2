(ns kschltz.agent.skills-test
  "Tests for the Malli-enforced .edn skill store and the two disclosure
   tools. Includes the required assertion that a fully-speced system
   prompt is SMALLER with the skills plugin (catalog only) than with
   the equivalent knowledge inlined (the current 'everything in the
   prompt' approach)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.skills :as skills]
            [kschltz.agent.tool :as tool]))

(defn- make-skill-dir
  "Create a temp skills dir from a map of file-name -> EDN string.
   Returns the dir path."
  [files]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "lat-skills-" (System/currentTimeMillis) "-" (rand-int 100000)))]
    (.mkdirs dir)
    (doseq [[nm content] files]
      (spit (io/file dir (str nm ".edn")) content))
    (.getPath dir)))

(defn- rm-r [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (rm-r c)))
  (when (.exists f) (.delete f)))

(defn- cleanup! [dir] (rm-r (io/file dir)))

(def ^:private ok-skill
  {:name "deploy-runbook"
   :description "Steps to deploy the workbench. Use when deploying the workbench stack."
   :body "1. Build the uberjar.\n2. Run scripts/start-workbench.\n3. Verify the profile gate."
   :resources [{:path "references/env.md" :description "env var matrix"}]})

(deftest malli-schema-enforces-skill-shape
  (testing "a valid skill passes"
    (is (= ok-skill (skills/validate-skill ok-skill))))
  (testing "missing body is rejected with a humanized problem"
    (is (thrown-with-msg? Exception #"Invalid skill"
                          (skills/validate-skill
                           {:name "x" :description "d" :extra "closed map rejects me"}))))
  (testing "bad name (upper case) is rejected"
    (is (thrown-with-msg? Exception #"Invalid skill name"
                          (skills/validate-skill
                           {:name "DeployRunbook" :description "d" :body "b"}))))
  (testing "escaping resource paths are rejected"
    (is (thrown-with-msg? Exception #"Invalid resource path"
                          (skills/validate-skill
                           (assoc-in ok-skill [:resources 0 :path] "../other/secret.md"))))))

(deftest load-skills-dir-validates
  (testing "a directory with one invalid file fails closed"
    (let [dir (make-skill-dir {"good" (pr-str ok-skill)
                               "bad" "{:name \"bad-name\" ;; missing body\n}"})]
      (try
        (is (thrown-with-msg? Exception #"Skill file failed to load"
                              (skills/load-skills-dir dir)))
        (finally (cleanup! dir)))))
  (testing "a valid directory loads all skills"
    (let [dir (make-skill-dir {"deploy-runbook" (pr-str ok-skill)
                               "web-guard"
                               (pr-str {:name "web-guard"
                                        :description "URL guard rules. Use when enabling web tools."
                                        :body "Allowlist domains before first use."})})
          store (skills/load-skills-dir dir)]
      (try
        (is (= #{"deploy-runbook" "web-guard"} (set (keys (:skills-by-name store)))))
        (is (= [{:name "deploy-runbook"
                 :description (:description ok-skill)}
                {:name "web-guard"
                 :description "URL guard rules. Use when enabling web tools."}]
               (skills/catalog store)))  ; sorted by name
        (finally (cleanup! dir))))))

(deftest catalog-fragment-is-selector-only
  (testing "the Tier-1 fragment carries names + descriptions but never bodies"
    (let [dir (make-skill-dir {"s1" (pr-str ok-skill)})
          store (skills/load-skills-dir dir)]
      (try
        (let [frag (skills/catalog-fragment store)]
          (is (str/includes? frag "deploy-runbook"))
          (is (str/includes? frag (:description ok-skill)))
          (is (str/includes? frag "load_skill"))
          (is (not (str/includes? frag "Run scripts/start-workbench"))))
        (finally (cleanup! dir))))))

(deftest load-skill-tool-discloses-body
  (testing "the body arrives only through the tool, with resources listed"
    (let [dir (make-skill-dir {"s1" (pr-str ok-skill)})
          store (skills/load-skills-dir dir)
          t (skills/load-skill-tool store)]
      (try
        (let [out (tool/invoke-tool t {:name "deploy-runbook"} nil)]
          (is (str/includes? out "INSTRUCTIONS:"))
          (is (str/includes? out "start-workbench"))
          (is (str/includes? out "references/env.md")))
        (let [out (tool/invoke-tool t {:name "nope"} nil)]
          (is (str/includes? out "Unknown skill"))
          (is (str/includes? out "deploy-runbook")))
        (finally (cleanup! dir))))))

(deftest read-skill-file-tool-is-contained
  (testing "declared resources read; undeclared or escaping paths refused"
    (let [dir (make-skill-dir {"s1" (pr-str ok-skill)})
          store (skills/load-skills-dir dir)
          res-dir (io/file dir "deploy-runbook" "references")]
      (.mkdirs res-dir)
      (spit (io/file res-dir "env.md") "OLLAMA_URL=http://localhost:11434")
      (try
        (let [t (skills/read-skill-file-tool store)
              out (tool/invoke-tool t {:skill "deploy-runbook" :path "references/env.md"} nil)]
          (is (str/includes? out "OLLAMA_URL")))
        (is (str/includes? (tool/invoke-tool (skills/read-skill-file-tool store)
                                             {:skill "deploy-runbook" :path "references/nope.md"} nil)
                           "Resource not declared"))
        ;; traversal attempts are refused before any file is touched
        ;; (undeclared gate first; canonicalization is the second lock)
        (is (str/includes? (tool/invoke-tool (skills/read-skill-file-tool store)
                                             {:skill "deploy-runbook" :path "../../../etc/passwd"} nil)
                           "Resource not declared"))
        (finally (cleanup! dir))))))

(defn- inline-baseline-prompt
  "What a 'fully speced' system prompt looks like TODAY — the catalog
   plus every skill body inlined (the everything-in-the-prompt
   approach the skills plugin replaces)."
  [catalog]
  (str/join "\n\n"
            (cons "lateralus-v2 MVP"
                  (concat (map (fn [{:keys [name description]}]
                                 (str "## " name "\n" description))
                               (skills/catalog catalog))
                          (map :body (vals (:skills-by-name catalog)))))))

(deftest ^:system-prompt-size system-prompt-is-smaller-with-skills-plugin
  (testing "REQUIRED: fully-speced system prompt before > after"
    (let [dir (make-skill-dir
               {"runbook" (pr-str (assoc ok-skill :body
                                         (str/join "\n" (repeat 80 "Step: do a part of the deployment runbook with all its subtleties."))))
                "audit"
                (pr-str {:name "audit-protocol"
                         :description "Security audit checklist. Use when auditing tool output."
                         :body (str/join "\n" (repeat 80 "Audit step: verify a specific attack surface and record findings."))})
                "research"
                (pr-str {:name "research-protocol"
                         :description "Deep web research method. Use when researching unfamiliar topics."
                         :body (str/join "\n" (repeat 80 "Research step: query, read, extract, cross-check, and record sources."))})})
          store (skills/load-skills-dir dir)]
      (try
        (let [catalog-frag (skills/catalog-fragment store)
              ;; fully speced (current-style): everything the model could
              ;; know about the skills IN the system prompt
              before (inline-baseline-prompt store)
              ;; skills-plugin composition: catalog of selectors ONLY
              after (str "lateralus-v2 MVP\n\n" catalog-frag)]
          ;; the required assertion
          (is (> (count before) (count after)))
          ;; and meaningfully so: bodies are thousands of chars
          (is (> (- (count before) (count after)) 5000))
          (is (< (count after) 2000))
          ;; the catalog still tells the model everything EXISTS
          (is (str/includes? after "deploy-runbook"))
          (is (str/includes? after "audit-protocol"))
          (is (str/includes? after "research-protocol")))
        (finally (cleanup! dir))))))