(ns kschltz.agent.tools.mcp.names-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.mcp.names :as names]))

(deftest sanitize-hyphens
  (is (= "foo_bar" (names/sanitize-tool-name "foo-bar")))
  (is (= "read_file" (names/sanitize-tool-name "read_file"))))

(deftest always-prefix-by-default
  (testing "default policy prefixes with server id"
    (is (= "filesystem_read_file"
           (names/qualify-name "filesystem" nil "read-file")))
    (is (tool/portable-tool-name?
         (names/qualify-name "filesystem" nil "read-file")))))

(deftest explicit-empty-prefix
  (is (= "echo" (names/qualify-name "fake" "" "echo")))
  (is (tool/portable-tool-name? (names/qualify-name "fake" "" "echo"))))

(deftest resolve-tool-names-happy-path
  (let [resolved (names/resolve-tool-names
                  "fake" nil
                  [{:name "echo"} {:name "add"}]
                  #{})]
    (is (= #{"fake_echo" "fake_add"} (set (keys resolved))))
    (is (= "echo" (::names/mcp-name (get resolved "fake_echo"))))
    (doseq [n (keys resolved)]
      (is (tool/portable-tool-name? n)))))

(deftest resolve-tool-names-collision
  (is (thrown-with-msg?
       Exception #"collision"
       (names/resolve-tool-names
        "fake" ""
        [{:name "echo"}]
        #{"echo"}))))
