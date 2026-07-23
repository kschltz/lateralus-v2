(ns kschltz.agent.cli.profile.templates
  "Pure Integrant builders for lateralus CLI profiles.
   Profiles persist as plain settings maps (no secrets); `build`
   expands them to Integrant with `(ig/ref …)` at load time."
  (:require [clojure.string :as str]
            [integrant.core :as ig]))

(def local-base-url "http://localhost:11434/v1")
(def cloud-base-url "https://ollama.com/v1")

(def default-settings
  {:backend      :ollama-local
   :base-url     local-base-url
   :model        nil
   :web-provider :ddg
   :workbench?   false})

(defn normalize-settings
  "Fill defaults and coerce a settings map. Never keeps `:api-key`.
   When `:base-url` is omitted, derive it from `:backend`."
  [settings]
  (let [input (dissoc settings :api-key)
        backend (keyword (or (:backend input) (:backend default-settings)))
        url (or (not-empty (:base-url input))
                (case backend
                  :ollama-cloud cloud-base-url
                  :custom       (or (not-empty (:base-url default-settings))
                                    local-base-url)
                  local-base-url))]
    {:backend      backend
     :base-url     url
     :model        (not-empty (:model input))
     :web-provider (keyword (or (:web-provider input)
                                (:web-provider default-settings)))
     :workbench?   (boolean (if (contains? input :workbench?)
                              (:workbench? input)
                              (:workbench? default-settings)))}))

(defn- llm-keys
  [{:keys [base-url model]}]
  (let [client (cond-> {:impl :http :base-url base-url}
                 model (assoc :model model))
        cfg    (cond-> {:base-url base-url}
                 model (assoc :model model))]
    {:lateralus/llm-client client
     :lateralus/llm-config cfg}))

(defn- tool-registry
  [workbench?]
  (cond-> [(ig/ref :lateralus/file-tools)
           (ig/ref :lateralus/self-awareness-tools)
           (ig/ref :lateralus/clojure-tools)
           (ig/ref :lateralus/web-tools)
           (ig/ref :lateralus/runtime-tools)]
    workbench? (conj (ig/ref :lateralus/workbench-tools))))

(defn- plugins
  [workbench?]
  (cond-> [(ig/ref :lateralus/memory-plugin)
           (ig/ref :lateralus/tools-plugin)]
    workbench? (conj (ig/ref :lateralus/workbench-plugin))))

(defn- agent-map
  [workbench?]
  (cond-> {:plugins        (ig/ref :lateralus/plugins)
           :llm-client     (ig/ref :lateralus/llm-client)
           :llm-config     (ig/ref :lateralus/llm-config)
           :embedder       (ig/ref :lateralus/embedder)
           :memory-backend (ig/ref :lateralus/memory-backend)
           :cli-ui         (ig/ref :lateralus/cli-ui)
           :thinking       (ig/ref :lateralus/thinking)
           :loop-opts      (ig/ref :lateralus/loop-opts)}
    workbench? (assoc :logging   (ig/ref :lateralus/logging)
                      :workbench (ig/ref :lateralus/workbench))))

(defn build
  "Expand profile settings into a full Integrant config map.
   Never includes `:api-key`."
  [settings]
  (let [{:keys [base-url model web-provider workbench?] :as s}
        (normalize-settings settings)
        wb? (boolean workbench?)]
    (cond-> (merge
             (llm-keys s)
             {:lateralus/embedder       {:method :noop}
              :lateralus/memory-backend {:impl  :kg-bm25
                                         :store {:backend :memory}}
              :lateralus/memory-plugin  {:backend  (ig/ref :lateralus/memory-backend)
                                         :embedder (ig/ref :lateralus/embedder)
                                         :top-y    3
                                         :last-n   5}
              :lateralus/file-tools           {}
              :lateralus/self-awareness-tools {}
              :lateralus/clojure-tools        {}
              :lateralus/logging              {}
              :lateralus/cli-ui               {:enabled? :auto :theme :default}
              :lateralus/thinking             {:mode :preview}
              :lateralus/web-tools            {:provider web-provider}
              :lateralus/runtime-tools        {:enabled? true :network? true}
              :lateralus/loop-opts            {:max-tool-calls-per-turn 100
                                               :max-tool-calls-per-exchange 20
                                               :tool-content-caps {"clojure/eval" 12000
                                                                   "clojure/add-lib" 12000}}
              :lateralus/tool-registry        (tool-registry wb?)
              :lateralus/tools-plugin         {:registry (ig/ref :lateralus/tool-registry)}
              :lateralus/plugins              (plugins wb?)
              :lateralus/agent                (agent-map wb?)})
      wb? (merge
           (let [portal-port (try (some-> (System/getenv "LATERALUS_PORTAL_PORT")
                                          not-empty Integer/parseInt)
                                  (catch Exception _ nil))]
             {:lateralus/workbench
              (cond-> {:enabled? true
                       ;; Docker: LATERALUS_WORKBENCH_HOST=0.0.0.0 (bind)
                       ;;         LATERALUS_WORKBENCH_PUBLIC_HOST=localhost (UI links)
                       :host (or (not-empty (System/getenv "LATERALUS_WORKBENCH_HOST"))
                                 "127.0.0.1")
                       :port 7860
                       :portal? true
                       :open-browser? false
                       :app false
                       :window-title "lateralus"}
                portal-port (assoc :portal-port portal-port
                                   :portal-host (or (not-empty (System/getenv "LATERALUS_WORKBENCH_HOST"))
                                                    "0.0.0.0")))
              :lateralus/workbench-plugin {:workbench (ig/ref :lateralus/workbench)}
              :lateralus/workbench-tools  {:workbench (ig/ref :lateralus/workbench)}})))))

(defn summarize
  "Human-facing summary of profile settings."
  [settings]
  (normalize-settings settings))

(defn format-summary
  "One-line description for menus."
  [settings]
  (let [{:keys [backend base-url model web-provider workbench?]}
        (normalize-settings settings)]
    (format "%s  model=%s  web=%s  workbench=%s  url=%s"
            (name backend)
            (or model "(unset)")
            (name web-provider)
            (if workbench? "yes" "no")
            base-url)))
