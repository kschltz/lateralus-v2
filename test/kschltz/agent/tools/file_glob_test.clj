(ns kschltz.agent.tools.file-glob-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.file-glob :as file-glob])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private tmp-dir
  (delay
    (let [dir (File/createTempFile "lateralus-glob-test" "")]
      (.delete dir)
      (.mkdirs dir)
      (.deleteOnExit dir)
      dir)))

(use-fixtures :each
  (fn [f]
    (doseq [^File entry (reverse (file-seq @tmp-dir))]
      (when (not= entry @tmp-dir)
        (.delete entry)))
    (f)))

(defn- write-file
  [path content]
  (let [f (io/file @tmp-dir path)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- invoke
  [t args]
  (-> (tool/invoke-tool t args {})
      (json/parse-string true)))

(deftest glob-discovers-root-and-nested-files-deterministically
  (write-file "root.clj" "(ns root)")
  (write-file "src/z.clj" "(ns z)")
  (write-file "src/a.clj" "(ns a)")
  (write-file "src/readme.md" "docs")
  (let [t (file-glob/file-glob (str @tmp-dir))
        result (invoke t {:pattern "**/*.clj"})]
    (is (= ["root.clj" "src/a.clj" "src/z.clj"]
           (mapv :path (:matches result))))
    (is (= 3 (:total-matches result)))
    (is (false? (:truncated result)))
    (is (every? integer? (map :size (:matches result))))))

(deftest glob-is-bounded
  (doseq [n (range 5)]
    (write-file (str "f" n ".txt") (str n)))
  (let [result (invoke (file-glob/file-glob (str @tmp-dir))
                       {:pattern "*.txt" :max-results 2})]
    (is (= ["f0.txt" "f1.txt"] (mapv :path (:matches result))))
    (is (= 5 (:total-matches result)))
    (is (true? (:truncated result)))))

(deftest glob-skips-blocked-trees
  (write-file ".git/config" "needle")
  (write-file "target/generated.clj" "(ns generated)")
  (write-file "src/visible.clj" "(ns visible)")
  (let [result (invoke (file-glob/file-glob (str @tmp-dir))
                       {:pattern "**/*"})]
    (is (= ["src/visible.clj"] (mapv :path (:matches result))))))

(deftest glob-rejects-invalid-or-outside-roots
  (let [t (file-glob/file-glob (str @tmp-dir))]
    (testing "invalid pattern"
      (is (= "invalid-pattern"
             (:error (invoke t {:pattern "["})))))
    (testing "outside root"
      (is (= "outside-workspace"
             (:error (invoke t {:pattern "*" :path ".."})))))))

(deftest glob-does-not-follow-directory-symlinks
  (let [outside (doto (io/file (System/getProperty "java.io.tmpdir")
                               (str "lateralus-glob-outside-" (random-uuid)))
                  (.mkdirs))
        secret (io/file outside "secret.clj")
        _ (spit secret "(ns secret)")
        link (io/file @tmp-dir "linked")
        _ (Files/createSymbolicLink (.toPath link)
                                    (.toPath outside)
                                    (make-array FileAttribute 0))
        result (invoke (file-glob/file-glob (str @tmp-dir))
                       {:pattern "**/*.clj"})]
    (is (empty? (:matches result)))
    (.delete link)
    (.delete secret)
    (.delete outside)))
