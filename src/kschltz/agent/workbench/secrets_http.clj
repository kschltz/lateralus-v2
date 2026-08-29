(ns kschltz.agent.workbench.secrets-http
  "Secrets management API for the workbench UI.

   The UI manages the operator-owned secret store: create/overwrite,
   delete, list. The store is the SAME `SecretStore` the secrets
   plugin wraps tools with, so a value set here becomes available to
   tools as {{secret:label}} immediately.

   Response discipline (mirrors docs/secrets.md's trust model): GET
   returns LABELS ONLY — a secret's plaintext leaves the store exactly
   twice: into `put!` (over the wire, operator-initiated) and into tool
   invocations. It is never served back by any route here."
  (:require [clojure.string :as str]
            [kschltz.agent.secrets :as secrets]))

(def label-regex #"[A-Za-z0-9][A-Za-z0-9._/-]{0,63}")

(defn secrets-view
  "Labels of the store, safe for the browser. Never values.
   {:enabled true/false :labels [...]}"
  [store]
  (if store
    {:enabled true
     :labels  (vec (sort (map str (secrets/-secret-labels store))))}
    {:enabled false
     :labels  []
     :error   "secret store not configured (enable :lateralus/secret-store)"}))

(defn- valid-label? [label]
  (and (string? label) (re-matches label-regex label) (not (str/blank? (str label)))))

(defn put-secret!
  "Upsert one secret. Value passes through the browser → store; it is
   never logged or echoed. Returns {:ok true :label label}."
  [store {:keys [label value] :as _op}]
  (cond
    (nil? store)
    {:ok false :error "secret store not configured"}

    (not (valid-label? label))
    {:ok false :error (str "invalid label (expected " (pr-str (str label-regex)) "): "
                           (pr-str (str label)))}

    (or (not (string? value)) (str/blank? value))
    {:ok false :error "secret value must be a non-empty string"}

    :else
    (do (secrets/-put-secret! store label value)
        {:ok true :label label})))

(defn delete-secret!
  "Remove one label. Returns {:ok true :label label} even when the
   label was absent (idempotent)."
  [store label]
  (cond
    (nil? store)
    {:ok false :error "secret store not configured"}

    (not (valid-label? label))
    {:ok false :error (str "invalid label: " (pr-str (str label)))}

    :else
    (do (secrets/-delete-secret! store label)
        {:ok true :label label})))
