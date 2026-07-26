(ns kschltz.agent.cli.profile.templates
  "Pure Integrant builders for lateralus CLI profiles.
   Profiles persist as plain settings maps (no secrets); `build`
   expands them to Integrant with `(ig/ref …)` at load time."
  (:require [clojure.string :as str]
            [integrant.core :as ig]))

(def local-base-url "http://localhost:11434/v1")
(def cloud-base-url "https://ollama.com/v1")
(def docker-local-base-url "http://ollama:11434/v1")

(defn- normalize-base-url
  "Strip trailing slashes so model-list URLs do not become `…/v1//v1/models`."
  [url]
  (when url (str/replace (str url) #"/+$" "")))

(defn default-local-base-url
  "Host default is localhost; inside Docker compose use the `ollama` service."
  []
  (if (= "1" (System/getenv "LATERALUS_IN_DOCKER"))
    (or (not-empty (System/getenv "LATERALUS_DOCKER_OLLAMA_URL"))
        docker-local-base-url)
    local-base-url))

(def tool-group-catalog
  "Ordered tool groups shown in the interactive profile checklist."
  [{:id :files
    :label "files"
    :description "workspace file read/write"
    :ref :lateralus/file-tools}
   {:id :self
    :label "self"
   :description "self_status awareness"
    :ref :lateralus/self-awareness-tools}
   {:id :config
    :label "config"
    :description "set_llm_config + list_llm_models"
    :ref :lateralus/config-tools}
   {:id :clojure
    :label "clojure"
    :description "structured Clojure editing"
    :ref :lateralus/clojure-tools}
   {:id :web
    :label "web"
    :description "search / fetch / extract"
    :ref :lateralus/web-tools}
   {:id :runtime
    :label "runtime"
   :description "clojure_eval + dependency loading"
    :ref :lateralus/runtime-tools}
   {:id :workbench
    :label "workbench"
    :description "portal submit tools"
    :ref :lateralus/workbench-tools
    :requires-workbench? true}])

(defn tool-group-meta
  [id]
  (first (filter #(= id (:id %)) tool-group-catalog)))

(defn default-tool-groups
  "All core groups on; workbench tools follow workbench?."
  [workbench?]
  {:files true
   :self true
   :config true
   :clojure true
   :web true
   :runtime true
   :workbench (boolean workbench?)})

(defn normalize-tool-groups
  "Coerce a tool-groups map. Unknown keys dropped; missing keys default on.
   Workbench tools forced off when workbench? is false."
  [raw workbench?]
  (let [base (default-tool-groups workbench?)
        allowed (set (keys base))
        incoming (into {}
                       (keep (fn [[k v]]
                               (let [id (keyword k)]
                                 (when (allowed id) [id (boolean v)])))
                             (or raw {})))
        merged (merge base incoming)]
    (cond-> merged
      (not workbench?) (assoc :workbench false))))

(defn default-settings
  "Fresh profile defaults. `:base-url` follows the runtime (Docker vs host)."
  []
  {:backend      :ollama-local
   :base-url     (default-local-base-url)
   :model        nil
   :web-provider :ddg
   :workbench?   false
   :tool-groups  (default-tool-groups false)})

(defn normalize-settings
  "Fill defaults and coerce a settings map. Never keeps `:api-key`.
   When `:base-url` is omitted, derive it from `:backend`."
  [settings]
  (let [defaults (default-settings)
        input (dissoc settings :api-key)
        backend (keyword (or (:backend input) (:backend defaults)))
        url (normalize-base-url
             (or (not-empty (:base-url input))
                 (case backend
                   :ollama-cloud cloud-base-url
                   :custom       (or (not-empty (:base-url defaults))
                                     (default-local-base-url))
                   (default-local-base-url))))
        workbench? (boolean (if (contains? input :workbench?)
                              (:workbench? input)
                              (:workbench? defaults)))]
    {:backend      backend
     :base-url     url
     :model        (not-empty (:model input))
     :web-provider (keyword (or (:web-provider input)
                                (:web-provider defaults)))
     :workbench?   workbench?
     :tool-groups  (normalize-tool-groups (:tool-groups input) workbench?)}))

(defn- llm-keys
  [{:keys [base-url model]}]
  (let [client (cond-> {:impl :http :base-url base-url}
                 model (assoc :model model))
        cfg    (cond-> {:base-url base-url}
                 model (assoc :model model))]
    {:lateralus/llm-client client
     :lateralus/llm-config cfg}))

(defn- tool-registry
  [tool-groups]
  (into []
        (keep (fn [{:keys [id ref]}]
                (when (get tool-groups id)
                  (ig/ref ref))))
        tool-group-catalog))

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
  (let [{:keys [base-url model web-provider workbench? tool-groups] :as s}
        (normalize-settings settings)
        wb? (boolean workbench?)
        groups (normalize-tool-groups tool-groups wb?)
        web-prov (if (:web groups) web-provider :none)]
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
              :lateralus/config-tools         {:catalog :http}
              :lateralus/clojure-tools        {}
              :lateralus/logging              {}
              :lateralus/cli-ui               {:enabled? :auto :theme :default}
              :lateralus/thinking             {:mode :preview}
              :lateralus/web-tools            {:provider web-prov}
              :lateralus/runtime-tools        {:enabled? (boolean (:runtime groups))
                                               :network? true}
              ;; MCP client: always present, empty by default (air-gapped).
              :lateralus/mcp-tools            {:servers {}}
              :lateralus/loop-opts            {:max-tool-calls-per-turn 100
                                               :max-tool-calls-per-exchange 20
                                               :tool-content-caps {"clojure_eval" 12000
                                                                   "clojure_add_lib" 12000}}
              :lateralus/tool-registry        (conj (tool-registry groups)
                                                    (ig/ref :lateralus/mcp-tools))
              :lateralus/tools-plugin         {:registry (ig/ref :lateralus/tool-registry)}
              :lateralus/plugins              (plugins wb?)
              :lateralus/agent                (agent-map wb?)})
      wb? (merge
           (let [portal-port (try (some-> (System/getenv "LATERALUS_PORTAL_PORT")
                                          not-empty Integer/parseInt)
                                  (catch Exception _ nil))]
             {:lateralus/workbench
              (cond-> {:enabled? true
                       ;; Docker / Tailscale: LATERALUS_WORKBENCH_HOST=0.0.0.0 (bind
                       ;; CHAT + Portal). Iframe host follows the browser Host
                       ;; header at request time; PUBLIC_HOST only seeds startup links.
                       :host (or (not-empty (System/getenv "LATERALUS_WORKBENCH_HOST"))
                                 "127.0.0.1")
                       ;; Keep Portal on the same bind interface as CHAT so a
                       ;; remote/MagicDNS CHAT viewer can also reach :portal-port.
                       :portal-host (or (not-empty (System/getenv "LATERALUS_WORKBENCH_HOST"))
                                        "127.0.0.1")
                       :port 7860
                       :portal? true
                       :open-browser? false
                       :app false
                       :window-title "lateralus"}
                portal-port (assoc :portal-port portal-port))
              :lateralus/workbench-plugin {:workbench (ig/ref :lateralus/workbench)}
              :lateralus/workbench-tools  {:workbench (ig/ref :lateralus/workbench)}})))))

(defn summarize
  "Human-facing summary of profile settings."
  [settings]
  (normalize-settings settings))

(defn format-summary
  "One-line description for menus."
  [settings]
  (let [{:keys [backend base-url model web-provider workbench? tool-groups]}
        (normalize-settings settings)
        enabled (count (filter val tool-groups))]
    (format "%s  model=%s  web=%s  workbench=%s  tools=%d/%d  url=%s"
            (name backend)
            (or model "(unset)")
            (name web-provider)
            (if workbench? "yes" "no")
            enabled
            (count tool-groups)
            base-url)))
