(ns kschltz.agent.secrets
  "Secret store for lateralus agents: use-without-seeing.

   The model NEVER holds a secret. Tools reference secrets by handle
   (`{{secret:label}}`) in their arguments; this namespace resolves the
   handle to plaintext only INSIDE the tool invocation, and redacts any
   stored secret value out of tool results, messages, and transcripts
   before they can re-enter the model's context.

   Storage: `sealed-file-store`, a single AES-256-GCM sealed file
   (PBKDF2-WithHmacSHA256 master key, JDK-only, no new deps). The
   passphrase comes from the environment (`:passphrase-env`), never
   from config text.

   Trust model: runtime-authored tools are untrusted and must remain in
   the SCI sandbox; only operator-granted host tools may resolve selected
   labels. Literal redaction is defense-in-depth, not the boundary. The
   broker/store still share the JVM with trusted host code, so a malicious
   host dependency or implementation remains outside this guarantee."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [kschltz.agent.tool :as tool])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Paths StandardOpenOption]
           [java.security SecureRandom]
           [java.util Base64]
           [javax.crypto Cipher SecretKeyFactory]
           [javax.crypto.spec GCMParameterSpec PBEKeySpec SecretKeySpec]))

;; ---- Protocol ----

(defprotocol SecretStore
  "A secret store. Plaintext never crosses back to the model: only
   tool-argument substitution (inside `-invoke`) and internal redaction
   consume the values."
  (-put-secret!  [store label value] "Create or overwrite `label`.")
  (-get-secret   [store label] "Plaintext for `label`, or nil.")
  (-secret-exists? [store label])
  (-delete-secret! [store label])
  (-secret-labels [store]))

(def handle-regex
  "Matches `{{secret:label}}` handles in model-supplied tool arguments.
   Conservative label charset: letters, digits, dot, dash, underscore,
   slash (no quotes, no braces)."
  #"\{\{secret:([A-Za-z0-9._/-]+)\}\}")

(def min-redact-length
  "Secret values shorter than this are NOT used as redaction needles —
   tiny strings would mangle unrelated tool output. Such secrets are
   still substituted and never echoed by the store, but the redaction
   catch-all assumes real credentials are >= 8 chars."
  8)

(def redaction-marker-prefix "[REDACTED:")

(defn redaction-marker
  "Model-visible stand-in for a redacted secret value."
  [label]
  (str "[REDACTED:" label "]"))

;; ---- Sealed-file implementation ----

(def ^:private store-create-lock
  "Serializes first-open checks across store instances."
  (Object.))

(def ^:private file-magic "LATSEC1")
(def ^:private kdf-iterations 210000)
(def ^:private gcm-tag-bits 128)
(def ^:private iv-length 12)
(def ^:private salt-length 16)

(defn- b64-encode ^String [^bytes bs]
  (.encodeToString (Base64/getEncoder) ^bytes bs))

(defn- b64-decode ^bytes [^String s]
  (.decode (Base64/getDecoder) ^String s))

(defn- derive-key
  "PBKDF2-HmacSHA256 master key from passphrase + per-file salt."
  [^String passphrase ^bytes salt]
  (let [fk (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
        spec (PBEKeySpec. (.toCharArray passphrase) salt kdf-iterations 256)]
    (try
      (SecretKeySpec. (.getEncoded (.generateSecret fk spec)) "AES")
      (finally (.clearPassword spec)))))

(defn- random-bytes ^bytes [n]
  (let [bs (byte-array n)]
    (.nextBytes (SecureRandom.) bs)
    bs))

(defn- seal-bytes
  "AES-256-GCM encrypt `plaintext` under `key` with a fresh random IV.
   Returns `^bytes` iv||ciphertext."
  [^SecretKeySpec key ^String plaintext]
  (let [iv (random-bytes iv-length)
        cipher (doto (Cipher/getInstance "AES/GCM/NoPadding")
                 (.init Cipher/ENCRYPT_MODE key (GCMParameterSpec. gcm-tag-bits iv)))]
    (byte-array (into-array Byte/TYPE
                            (concat (seq iv)
                                    (seq (.doFinal cipher
                                                   (.getBytes plaintext StandardCharsets/UTF_8))))))))

(defn- open-bytes
  "Decrypt `iv||ciphertext` under `key`. Throws on wrong passphrase or
   tampering (GCM auth failure)."
  [^SecretKeySpec key ^bytes sealed]
  (let [iv (byte-array iv-length)
        _ (System/arraycopy sealed 0 iv 0 iv-length)
        ct (byte-array (- (alength sealed) iv-length))
        _ (System/arraycopy sealed iv-length ct 0 (alength ct))
        cipher (doto (Cipher/getInstance "AES/GCM/NoPadding")
                 (.init Cipher/DECRYPT_MODE key (GCMParameterSpec. gcm-tag-bits iv)))]
    (String. (.doFinal cipher ct) StandardCharsets/UTF_8)))

(defrecord SealedFileStore [path key salt plaintext-cache lock])

(defn- read-sealed-file
  "Returns {:salt bytes :envelopes {\"label\" ^bytes sealed}} or
   {:salt nil :envelopes {}} when the file does not exist yet."
  [^java.nio.file.Path path]
  (if (Files/exists path (into-array java.nio.file.LinkOption []))
    (let [raw (String. (Files/readAllBytes path) StandardCharsets/UTF_8)]
      (when-not (str/starts-with? raw file-magic)
        (throw (ex-info "Sealed secret store has an unexpected format"
                        {:path (str path)})))
      (let [body (try
                   (json/parse-string (subs raw (count file-magic)))
                   (catch Throwable e
                     (throw (ex-info "Sealed secret store body is not valid JSON"
                                     {:path (str path)} e))))]
        {:salt (b64-decode (get body "salt"))
         :envelopes (into {}
                          (map (fn [[k v]] [k (b64-decode v)]))
                          (get body "secrets"))}))
    {:salt nil :envelopes {}}))

(defn- write-sealed-file!
  [^java.nio.file.Path path ^bytes salt envelopes]
  (when-let [parent (.getParent path)]
    (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
  (let [body (json/generate-string
              {:salt (b64-encode salt)
               :secrets (into {} (map (fn [[k v]] [k (b64-encode v)])) envelopes)})]
    (Files/write path
                 (.getBytes (str file-magic body) StandardCharsets/UTF_8)
                 (into-array StandardOpenOption
                             [StandardOpenOption/CREATE StandardOpenOption/WRITE
                              StandardOpenOption/TRUNCATE_EXISTING]))))

(defn sealed-file-store
  "Build a `SecretStore` backed by one sealed AES-256-GCM file.

   `opts`:
     :path           — sealed file path (default `.lateralus/secrets.sealed`)
     :passphrase     — literal passphrase (tests only)
     :passphrase-env — env var name to read the passphrase from
                       (default `LATERALUS_SECRETS_PASSPHRASE`)

   Throws when no passphrase is available, and surfaces a GCM auth
   failure when an existing store was sealed with a different
   passphrase."
  [{:keys [path passphrase passphrase-env]
    :or   {path ".lateralus/secrets.sealed"
           passphrase-env "LATERALUS_SECRETS_PASSPHRASE"}}]
  (let [pass (or (not-empty passphrase)
                 (System/getenv passphrase-env)
                 (throw (ex-info
                         (str "No secret-store passphrase available; set "
                              passphrase-env)
                         {:kind :missing-secret-passphrase})))
        p (Paths/get (str path) (make-array String 0))]
    (locking store-create-lock
      (let [{:keys [salt envelopes]} (read-sealed-file p)
            salt' (or salt (random-bytes salt-length))
            _ (when-not salt ;; first create: persist immediately
                (write-sealed-file! p salt' envelopes))]
        (->SealedFileStore p (derive-key pass salt') salt'
                           (volatile! {}) (Object.))))))

(def ^:private valid-label-regex #"^[A-Za-z0-9._/-]+$")

(extend-protocol SecretStore
  SealedFileStore
  (-put-secret!  [store label value]
    (when-not (and (string? label) (re-matches valid-label-regex label))
      (throw (ex-info "Invalid secret label"
                      {:kind :invalid-secret-label :label (pr-str label)})))
    (locking (:lock store)
      (let [{:keys [envelopes]} (read-sealed-file (:path store))]
        (write-sealed-file! (:path store) (:salt store)
                            (assoc envelopes label (seal-bytes (:key store) (str value))))
        (vreset! (:plaintext-cache store) {}) ;; stale plaintext must not survive a write
        nil)))

  (-get-secret [store label]
    (locking (:lock store)
      (or (get @(:plaintext-cache store) label)
          (let [{:keys [salt envelopes]} (read-sealed-file (:path store))]
            (when-not (java.util.Arrays/equals ^bytes salt ^bytes (:salt store))
              (throw (ex-info "Sealed-store salt changed on disk; reload the store"
                              {:path (str (:path store))})))
            (when-let [sealed (get envelopes label)]
              (let [plaintext (open-bytes (:key store) sealed)]
                ;; negative cache for missing labels requires distinct
                ;; representation; only cache FOUND values
                (vswap! (:plaintext-cache store) assoc label plaintext)
                plaintext))))))

  (-secret-exists? [store label]
    (locking (:lock store)
      (contains? (:envelopes (read-sealed-file (:path store))) label)))

  (-delete-secret! [store label]
    (locking (:lock store)
      (let [{:keys [envelopes]} (read-sealed-file (:path store))]
        (when (contains? envelopes label)
          (write-sealed-file! (:path store) (:salt store) (dissoc envelopes label))
          (vswap! (:plaintext-cache store) dissoc label))
        nil)))

  (-secret-labels [store]
    (locking (:lock store)
      (vec (sort (keys (:envelopes (read-sealed-file (:path store)))))))))

(defn all-secret-values
  "Map of label -> plaintext for every secret in `store`. Used ONLY for
   redaction (this side of the trust boundary); never exposed to the
   model."
  [store]
  (into {}
        (keep (fn [label]
                (when-let [v (-get-secret store label)] [label v])))
        (-secret-labels store)))

;; ---- Redaction ----

(defn- redaction-pairs
  "Needle/replacement string pairs for `values`. Values below
   [[min-redact-length]] are skipped (they would mangle output)."
  [values]
  (->> values
       (map (fn [[label v]] [v (redaction-marker label)]))
       (filter (fn [[v _]] (and v (>= (count v) min-redact-length))))
       (distinct)
       (vec)))

(defn redact-string
  "Replace every occurrence of any secret value in `s` with its
   `[REDACTED:label]` marker. `pairs` is the needle/replacement vector
   from [[collect-redaction-pairs]]."
  [pairs s]
  (if (string? s)
    (reduce (fn [acc [needle replacement]]
              (str/replace acc needle replacement))
            s
            pairs)
    s))

(defn redact-data
  "Walk `x` (any data) and redact secret values found in strings.
   `pairs` is the needle/replacement vector from
   [[collect-redaction-pairs]]."
  [pairs x]
  (walk/postwalk
   (fn [v]
     (if (string? v)
       (redact-string pairs v)
       v))
   x))

(defn collect-redaction-pairs
  "One-shot needle/replacement vector for a store or a values map."
  [store-or-values]
  (let [values (if (satisfies? SecretStore store-or-values)
                 (all-secret-values store-or-values)
                 store-or-values)]
    (redaction-pairs values)))

;; ---- Handle substitution ----

(def ^:private handle-prefix-tok "{{secret:")

(defn substitute-handles
  "Walk `args` (parsed tool arguments data) replacing every
   `{{secret:label}}` with the store's plaintext. Throws an ex-info
   naming the MISSING label when a handle has no secret — the error is
   model-visible (via invoke-tool's error envelope) but contains no
   values. Already-resolved handles inside larger strings are replaced
   in place."
  [store args]
  (walk/postwalk
   (fn [v]
     (if (string? v)
       (if (str/includes? v handle-prefix-tok)
         (str/replace v handle-regex
                      (fn [[_ label]]
                        (or (-get-secret store label)
                            (throw (ex-info
                                    (str "Secret handle could not be resolved: '"
                                         label "' is not in the secret store")
                                    {:kind :missing-secret :label label})))))
         v)
       v))
   args))

(defn- handle-labels
  [args]
  (let [labels (volatile! #{})]
    (walk/postwalk
     (fn [v]
       (when (string? v)
         (doseq [[_ label] (re-seq handle-regex v)]
           (vswap! labels conj label)))
       v)
     args)
    @labels))

(defn authorized-substitute-handles
  "Resolve handles only when `capability` authorizes every referenced label.
   A capability is `{:labels :all}` or `{:labels #{\"label\" ...}}`.
   A missing capability leaves handles opaque so transport/control tools can
   safely forward them. An explicit capability with an unauthorized label
   fails closed before delegate invocation."
  [store capability args]
  (let [referenced (handle-labels args)
        allowed (:labels capability)
        unauthorized
        (when (and capability (seq referenced))
          (if (= :all allowed)
            #{}
            (set (remove (set (or allowed #{})) referenced))))]
    (when (seq unauthorized)
      (throw (ex-info
              (str "Secret handle use is not authorized for labels: "
                   (str/join ", " (sort unauthorized)))
              {:kind :secret-capability-denied
               :labels (vec (sort unauthorized))})))
    (if (and capability (seq referenced))
      (substitute-handles store args)
      args)))



;; ---- Model-visible handle inventory ----
;;
;; Per operator decision: the model may KNOW the labels (the store's
;; inventory) but never the values. `handles-tool` is the model-facing
;; "what secrets exist" index; the values themselves remain
;; unreadable — there is no LLM-callable path that returns plaintext.

(def handles-tool-name "secret_list_handles")
(def presence-tool-name "secret_check")

(defn handles-tool
  "A Tool that lists the secret HANDLE labels (never values). Registers
   as a model-visible tool so the model can discover which handles it
   may pass as {{secret:label}} to other tools."
  [store]
  (reify tool/Tool
    (-name [_] handles-tool-name)
    (-description [_]
      "List the secret handle labels available for use in other tools' arguments via the {{secret:label}} syntax. Values are never returned, only names. When a tool argument needs a credential, pass a handle instead of asking the user to paste a secret.")
    (-input-schema [_] [:map])
    (-output-schema [_] :string)
    (-invoke [_ _args _ctx]
      (json/generate-string {:handles (-secret-labels store)}))))

(defn presence-tool
  "Trusted capability endpoint used by sandboxed runtime tools to verify that
   an operator-authorized handle resolved. It never returns the value."
  []
  (reify tool/Tool
    (-name [_] presence-tool-name)
    (-description [_]
      "Check whether an operator-authorized secret handle resolves. Input is a {{secret:label}} handle. Returns only available true/false, never the secret value.")
    (-input-schema [_] [:map [:handle :string]])
    (-output-schema [_] :string)
    (-invoke [_ {:keys [handle]} _ctx]
      (json/generate-string
       {:available (and (string? handle)
                        (not (str/includes? handle handle-prefix-tok))
                        (not (str/blank? handle)))}))))

;; ---- Tool wrapping ----

(defn wrap-tool
  "Wrap a [[kschltz.agent.tool/Tool]] so that:

   - every `{{secret:label}}` handle in the model-supplied args is
     resolved to plaintext ONLY inside `-invoke` (the substituted args
     never land on the ctx);
   - the tool's result string is scanned for stored secret values and
     every hit is replaced with `[REDACTED:label]` BEFORE the result
     can enter `:tool/results`, messages, history, or memory.

   Substitution + redaction are one atomic unit: a tool that echoes its
   input will echo handles spiked with nothing — the input carries only
   handles, and the output redact sweep covers a tool that expands
   them."
  ([store delegate]
   (wrap-tool store delegate nil))
  ([store delegate capability]
   (reify
     tool/Tool
     (-name [_] (tool/-name delegate))
     (-description [_] (tool/-description delegate))
     (-input-schema [_] (tool/-input-schema delegate))
     (-output-schema [_] (tool/-output-schema delegate))
     (-invoke [_ args ctx]
       (let [untrusted? (tool/untrusted-runtime-tool? delegate)
             resolved (if untrusted?
                        args
                        (authorized-substitute-handles
                         store capability args))
             result (tool/invoke-tool delegate resolved ctx)
             ;; Store mutations take effect immediately; literal redaction is
             ;; a backup layer, never the capability boundary.
             pairs (collect-redaction-pairs store)]
         (redact-string pairs result)))

     tool/ToolTrust
     (-trust-tier [_] (tool/trust-tier delegate)))))