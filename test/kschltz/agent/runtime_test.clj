(ns kschltz.agent.runtime-test
  "Tests for the agent outer-loop runtime.

   The runtime is the thin layer between the caller (CLI, test,
   web server) and the chain. Its job is bookkeeping only:
     1. Build a per-exchange ctx with traceability IDs
        (session-id, user-msg-id, assistant-msg-id)
     2. Call chain/execute with the chain from agent-map
     3. Merge :agent/state-delta into the runtime's state atom
     4. Expose the current state via `stop`

   MVP scope: single-threaded. send-message runs the chain
   synchronously on the caller thread. A queue + worker thread
   is a follow-up — there is no use case for it in the MVP CLI
   (single user, one prompt at a time)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.interceptors.schema :as schema]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.base :as plugins.base]
            [kschltz.agent.runtime :as runtime]))

;; ---- Helpers ----

(defn- default-exchange-chain []
  (plugin/assemble-chain [(plugins.base/base-plugin)]))

(defn- noop-chain
  "A trivial chain that records the ctx on an atom and returns it.
   Useful for asserting what the runtime injected."
  [events-atom]
  [{:name    ::record
    :enter   (fn [ctx]
               (swap! events-atom conj [:enter ctx])
               ctx)
    :leave   (fn [ctx]
               (swap! events-atom conj [:leave ctx])
               (assoc ctx :agent/state-delta {:ran? true}))}])

(defn- echo-chain
  "A chain that emits :agent/state-delta {:n 1} on every send. The
   runtime's plain-merge semantic replaces the previous :n value
   with the new one, so the state always reads {:n 1} regardless
   of how many times send-message is called. A separate test
   (counter-chain) verifies that the runtime preserves the base
   state across calls."
  []
  [{:name ::echo
    :leave (fn [ctx]
             (assoc ctx :agent/state-delta {:n 1}))}])

(defn- counter-chain
  "A chain that increments :n in the base state and emits it as
   state-delta. Tests that the runtime threads the base state
   through correctly."
  []
  [{:name ::counter
    :leave (fn [ctx]
             (let [prev (:n (:agent/state ctx) 0)]
               (assoc ctx :agent/state-delta {:n (inc prev)})))}])

(defn- throwing-chain
  "A chain whose middle stage throws. The default chain's
   error-boundary catches the throw, annotates the ctx with
   :error/raised, and lets the :leave phases still run. This is
   the realistic failure mode: a custom plugin throws, the engine
   keeps going, and the runtime sees a final ctx with :error/raised."
  []
  [ix/error-boundary
   {:name ::bomb
    :enter (fn [_ctx] (throw (ex-info "boom" {:boom true})))}
   {:name ::post-leave
    :leave (fn [ctx] (assoc ctx :post-leave-ran? true))}])

;; ---- Tests ----

(deftest start-creates-runtime
  (testing "start with just an agent-map creates a runtime"
    (let [r (runtime/start {:exchange-chain (default-exchange-chain)})]
      (is (some? r) "runtime is created")
      (is (satisfies? runtime/AgentRuntime r) "runtime satisfies the AgentRuntime protocol"))))

(deftest start-falls-back-to-default-chain
  (testing "an agent-map without :exchange-chain still works; the
   default chain is used. The MVP CLI hits this path (the user
   has not provided a custom chain)."
    (let [runtime (runtime/start {})]
      (is (some? (try
                   (runtime/send-message runtime "hi")
                   :ok
                   (catch Throwable _t
                     :threw)))
          "send-message runs the default chain end-to-end without throwing"))))

(deftest start-with-explicit-session-id
  (testing "3-arity start honors an explicit session-id"
    (let [sid "test-session-42"
          r   (runtime/start {:exchange-chain (default-exchange-chain)}
                             sid)]
      (is (= sid (runtime/session-id r))
          "session-id is stored on the runtime"))))

(deftest send-message-injects-traceability-ids
  (testing "send-message generates session-id, user-msg-id, assistant-msg-id
   on the per-exchange ctx"
    (let [events  (atom [])
          runtime (runtime/start {:exchange-chain (noop-chain events)})]
      (runtime/send-message runtime "hello")
      (let [entered-ctx (-> @events first second)]
        (is (some? (:exchange/session-id entered-ctx))
            "session-id is present on the per-exchange ctx")
        (is (some? (:exchange/user-msg-id entered-ctx))
            "user-msg-id is present on the per-exchange ctx")
        (is (some? (:exchange/assistant-msg-id entered-ctx))
            "assistant-msg-id is present on the per-exchange ctx")
        (is (= "hello" (:exchange/user-text entered-ctx))
            "user-text is present on the per-exchange ctx")
        (is (= (runtime/session-id runtime) (:exchange/session-id entered-ctx))
            "the per-exchange session-id matches the runtime's session-id")))))

(deftest send-message-runs-chain-synchronously
  (testing "send-message runs the chain on the caller thread (MVP simplification).
   The :enter and :leave events both fire before send-message returns."
    (let [events  (atom [])
          runtime (runtime/start {:exchange-chain (noop-chain events)})]
      (runtime/send-message runtime "hi")
      (is (= 2 (count @events))
          "one :enter + one :leave event fired")
      (is (= :enter (-> @events first first))
          "enter fired first")
      (is (= :leave (-> @events second first))
          "leave fired second"))))

(deftest send-message-merges-state-delta
  (testing "send-message merges :agent/state-delta into the runtime's state,
   threading the base state through correctly"
    (let [runtime (runtime/start {:exchange-chain (counter-chain)})]
      (is (= {} (runtime/stop runtime))
          "fresh runtime has empty state")
      (runtime/send-message runtime "first")
      (is (= {:n 1} (runtime/stop runtime))
          "after one send, state is {:n 1}")
      (runtime/send-message runtime "second")
      (is (= {:n 2} (runtime/stop runtime))
          "after two sends, state is {:n 2} (the chain saw the prior state)")
      (runtime/send-message runtime "third")
      (is (= {:n 3} (runtime/stop runtime))
          "after three sends, state is {:n 3}"))))

(deftest send-message-deep-merges-nested-state-delta
  (testing "nested maps in :agent/state-delta are merged deeply, while
   scalars remain last-write-wins across exchanges"
    (let [chain [{:name ::nested-delta
                  :leave (fn [ctx]
                           (let [turn (inc (:n (:agent/state ctx) 0))]
                             (assoc ctx :agent/state-delta
                                    {:n turn
                                     :config (case turn
                                               1 {:turn 1 :extra :one}
                                               2 {:turn 2}
                                               3 {:extra :three})})))}]
          runtime (runtime/start {:exchange-chain chain})]
      (runtime/send-message runtime "first")
      (is (= {:n 1 :config {:turn 1 :extra :one}} (runtime/stop runtime)))
      (runtime/send-message runtime "second")
      (is (= {:n 2 :config {:turn 2 :extra :one}} (runtime/stop runtime))
          "nested config map is merged, preserving sibling :extra from turn 1")
      (runtime/send-message runtime "third")
      (is (= {:n 3 :config {:turn 2 :extra :three}} (runtime/stop runtime))
          "scalar :extra is last-write-wins; nested :turn keeps its prior value"))))

(deftest send-message-uses-custom-chain
  (testing "send-message runs the chain from :exchange-chain in agent-map"
    (let [events  (atom [])
          runtime (runtime/start {:exchange-chain (noop-chain events)})]
      (runtime/send-message runtime "hi")
      (is (= 2 (count @events))
          "the custom chain (not the default) ran"))))

(deftest send-message-puts-prior-state-on-ctx
  (testing "the per-exchange ctx carries :agent/state with the prior
   merged state, so the chain can read what the runtime has accumulated"
    (let [seen-states (atom [])
          chain       [{:name ::spy
                        :leave (fn [ctx]
                                 (swap! seen-states conj (:agent/state ctx))
                                 (assoc ctx :agent/state-delta
                                        {:calls (count @seen-states)}))}]
          runtime     (runtime/start {:exchange-chain chain})]
      (runtime/send-message runtime "first")
      (is (= [{}] @seen-states)
          "the first exchange's ctx has the empty prior state")
      (runtime/send-message runtime "second")
      (is (= [{} {:calls 1}] @seen-states)
          "the second exchange's ctx has the post-first-send state"))))

(deftest send-message-returns-final-ctx
  (testing "send-message returns the final ctx from chain/execute"
    (let [chain   [{:name ::annotate
                    :leave (fn [ctx] (assoc ctx :marker true))}]
          runtime (runtime/start {:exchange-chain chain})]
      (let [result (runtime/send-message runtime "hi")]
        (is (true? (:marker result))
            "the result carries the marker the chain put on it")))))

(deftest send-message-handles-chain-error
  (testing "when a chain stage throws and error-boundary is in the chain,
   send-message does not let the exception escape. The final ctx
   carries :error/raised and the runtime is still usable."
    (let [runtime  (runtime/start {:exchange-chain (throwing-chain)})
          result-1 (try
                     (runtime/send-message runtime "boom")
                     :ok
                     (catch Throwable _t
                       :threw))]
      (is (= :ok result-1)
          "send-message does not throw; error-boundary catches the bomb")
      (is (= :ok (try
                   (runtime/send-message runtime "recover")
                   :ok
                   (catch Throwable _t
                     :threw)))
          "the runtime is still usable after a chain error"))))

(deftest stop-returns-current-state
  (testing "stop returns the current merged state"
    (let [runtime (runtime/start {:exchange-chain (echo-chain)})]
      (is (= {} (runtime/stop runtime))
          "stop on a fresh runtime returns the empty state")
      (runtime/send-message runtime "x")
      (is (= {:n 1} (runtime/stop runtime))
          "stop after one send returns the merged state"))))

(deftest send-message-prewires-dependencies-in-ctx
  (testing "send-message pre-wires :llm/client, :memory/backend and
   :embedder directly on the per-exchange ctx, so interceptors do not
   need to copy them."
    (let [llm-client     (reify Object)
          memory-backend (reify Object)
          embedder       (reify Object)
          seen-ctx       (atom nil)
          spy-chain      [{:name ::spy
                           :enter (fn [ctx]
                                    (reset! seen-ctx ctx)
                                    (assoc ctx :agent/state-delta {:spied? true}))}]
          runtime        (runtime/start
                          {:exchange-chain    spy-chain
                           :agent/llm-client  llm-client
                           :memory-backend    memory-backend
                           :embedder          embedder})]
      (runtime/send-message runtime "hi")
      (is (identical? llm-client (:llm/client @seen-ctx))
          "the agent-map's LLM client is on ctx as :llm/client")
      (is (identical? memory-backend (:memory/backend @seen-ctx))
          "the agent-map's memory backend is on ctx as :memory/backend")
      (is (identical? embedder (:embedder @seen-ctx))
          "the agent-map's embedder is on ctx as :embedder"))))

(deftest runtime-is-small
  (testing "the runtime ns is small (plan verification: < 150 LOC)"
    (let [lines (-> "src/kschltz/agent/runtime.clj"
                    slurp
                    str/split-lines
                    count)]
      (is (< lines 150)
          (str "runtime.clj is " lines " lines; plan requires < 150")))))