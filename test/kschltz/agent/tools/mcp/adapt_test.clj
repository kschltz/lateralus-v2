(ns kschltz.agent.tools.mcp.adapt-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.mcp.adapt :as adapt]
            [kschltz.agent.tools.mcp.names :as names]
            [kschltz.agent.tools.mcp.protocol :as proto]
            [kschltz.agent.tools.mcp.test-util :as tu]))

(deftest adapt-echo-tool
  (let [c (tu/fake-loopback-client)
        descs (proto/-list-tools c)
        resolved (names/resolve-tool-names "fake" nil descs #{})
        registry (adapt/adapt-tools c resolved {:server-id "fake"
                                                :max-result-bytes 1000})
        echo (get registry "fake_echo")]
    (is (tool/tool? echo))
    (is (tool/portable-tool-name? (tool/-name echo)))
    (let [result (json/parse-string (tool/-invoke echo {:message "yo"} {}) true)]
      (is (= "ok" (:status result)))
      (is (= "yo" (:content result))))
    (let [fail (get registry "fake_fail")
          result (json/parse-string (tool/-invoke fail {:reason "x"} {}) true)]
      (is (= "error" (:status result)))
      (is (true? (:isError result))))
    (proto/close! c)))

(deftest tool-definition-portable
  (let [c (tu/fake-loopback-client)
        descs (proto/-list-tools c)
        resolved (names/resolve-tool-names "fake" nil descs #{})
        registry (adapt/adapt-tools c resolved {:server-id "fake"})
        defn (tool/tool-definition (get registry "fake_echo"))]
    (is (= "fake_echo" (get-in defn [:function :name])))
    (is (map? (get-in defn [:function :parameters])))
    (proto/close! c)))
