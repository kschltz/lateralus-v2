(ns kschltz.agent.tools.clojure
  "Structured Clojure/EDN editing tools for the lateralus agent loop.

   These tools wrap rewrite-clj to read, query, and modify Clojure source
   files while preserving whitespace and comments. Every write is guarded by a
   round-trip parse check so that malformed edits are never persisted. On
   success a `.bak` sidecar is written first.

   Paths may be absolute or relative; when a `:workspace-root` is provided,
   relative paths are resolved against it, matching the filesystem tool
   conventions."
  (:require [cheshire.core :as json]
            [kschltz.agent.tool :as tool]
            [kschltz.agent.tools.clojure-impl :as impl]
            [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n])
  (:import [java.nio.file Path]))

(def default-max-read-bytes impl/default-max-read-bytes)

(def ^:private OutputSchema:String :string)

(defn- ok-result [m] (json/generate-string m))
(defn- error-result [t] (format "Clojure tool error: %s" (ex-message t)))

(def InputSchema:Path
  [:map [:path {:description "Relative or absolute path to the Clojure/EDN file"} :string]])

(def InputSchema:Query
  [:map
   [:path {:description "Relative or absolute path to the Clojure file"} :string]
   [:query {:description "Optional filter: :defs, :requires, or :imports (or their string equivalents)", :optional true}
    [:enum :defs :requires :imports "defs" "requires" "imports"]]])

(def InputSchema:AddRequire
  [:map
   [:path {:description "Relative or absolute path to the Clojure file"} :string]
   [:libspec {:description "Namespace to require, e.g. \"clojure.string\""} :string]
   [:alias {:description "Optional alias, e.g. \"str\" to produce [clojure.string :as str]", :optional true}
    [:maybe :string]]])

(def InputSchema:RemoveDef
  [:map
   [:path {:description "Relative or absolute path to the Clojure file"} :string]
   [:name {:description "Name of the top-level def/defn to remove"} :string]])

(def InputSchema:RenameSymbol
  [:map
   [:path {:description "Relative or absolute path to the Clojure file"} :string]
   [:old {:description "Current symbol name as a string, e.g. \"foo\""} :string]
   [:new {:description "New symbol name as a string, e.g. \"bar\""} :string]])

(def InputSchema:InsertForm
  [:map
   [:path {:description "Relative or absolute path to the Clojure file"} :string]
   [:form {:description "A single Clojure form written as a string, e.g. \"(defn reverse-string [s] (apply str (reverse s)))\""} :string]
   [:position {:description "Where to insert the form: :end, :beginning, \"end\" or \"beginning\"", :optional true}
    [:enum :end :beginning "end" "beginning"]]])

(def InputSchema:EditDef
  [:map
   [:path {:description "Relative or absolute path to the Clojure file"} :string]
   [:name {:description "Name of the def/defn whose body should be replaced"} :string]
   [:body {:description "New body as a string. For defn this replaces everything after the arg vector, e.g. \"(+ x 1)\""} :string]])

(defn- normalize-query [q]
  (case q
    ("defs" :defs) :defs
    ("requires" :requires) :requires
    ("imports" :imports) :imports
    nil :all
    :all))

(defn- query-file [workspace-root path max-read-bytes query]
  (let [query'  (normalize-query query)
        path'   (impl/resolve-path workspace-root path)
        source  (impl/read-source path' max-read-bytes)
        zloc    (impl/parse-or-fail source (impl/path->str path'))
        forms   (impl/top-level-forms zloc)
        defs    (mapcat (fn [form]
                          (let [first-child (z/down form)
                                name-child  (when first-child (z/right first-child))]
                            (when (and first-child
                                       (= :token (z/tag first-child))
                                       (#{'def 'defn 'defn- 'defmacro 'defmulti 'defonce 'defrecord 'deftype}
                                        (z/sexpr first-child))
                                       name-child
                                       (= :token (z/tag name-child)))
                              [(pr-str (z/sexpr name-child))])))
                        forms)
        ns-zloc (impl/ns-form zloc)
        requires (when (and ns-zloc (or (= query' :all) (= query' :requires)))
                   (when-let [section (impl/find-keyword-child ns-zloc :require)]
                     (impl/libspecs-from-section (z/right section))))
        imports  (when (and ns-zloc (or (= query' :all) (= query' :imports)))
                   (when-let [section (impl/find-keyword-child ns-zloc :import)]
                     (impl/libspecs-from-section (z/right section))))]
    (ok-result {:path (impl/path->str path') :defs defs :requires requires :imports imports})))

(deftype QueryTool [workspace-root max-read-bytes]
  tool/Tool
  (-name [_] "clojure_query")
  (-description [_]
    "Inspect a Clojure source file. Returns JSON with `:defs`, `:requires`, and `:imports`. Query a specific category with :query (:defs, :requires, or :imports or their string equivalents).")
  (-input-schema [_] InputSchema:Query)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (query-file workspace-root (:path args) max-read-bytes (:query args))
      (catch Throwable t (error-result t)))))

(defn- add-require-to-file [workspace-root path max-read-bytes libspec alias-sym]
  (let [path'     (impl/resolve-path workspace-root path)
        source    (impl/read-source path' max-read-bytes)
        zloc      (impl/parse-or-fail source (impl/path->str path'))
        ns-zloc   (impl/ns-form zloc)]
    (if-not ns-zloc
      (error-result (ex-info "No ns form found" {:kind :clojure-tool/error :path (impl/path->str path')}))
      (let [require-kw     (impl/find-keyword-child ns-zloc :require)
            libspec-node   (impl/require-libspec-node libspec alias-sym)]
        (if (and require-kw (impl/require-exists? (z/right require-kw) libspec))
          (ok-result {:path (impl/path->str path') :changed false :reason "require already exists"})
          (let [new-zloc (if require-kw
                           (z/insert-right require-kw libspec-node)
                           (let [name-zloc (z/right (z/down ns-zloc))]
                             (z/insert-right name-zloc
                                             (impl/new-require-section-node libspec-node))))
                out      (impl/root-string-or-fail new-zloc (impl/path->str path'))]
            (impl/write-with-backup! path' out)
            (ok-result {:path (impl/path->str path') :changed true
                        :libspec (pr-str libspec) :alias (when alias-sym (pr-str alias-sym))})))))))

(deftype AddRequireTool [workspace-root max-read-bytes]
  tool/Tool
  (-name [_] "clojure_add_require")
  (-description [_]
    "Add a namespace require to a Clojure file. :libspec is the namespace string; :alias is optional. Writes the file only if the round-trip parse check passes.")
  (-input-schema [_] InputSchema:AddRequire)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (add-require-to-file workspace-root (:path args) max-read-bytes
                           (symbol (:libspec args))
                           (when (seq (:alias args)) (symbol (:alias args))))
      (catch Throwable t (error-result t)))))

(defn- remove-def-from-file [workspace-root path max-read-bytes name]
  (let [path'    (impl/resolve-path workspace-root path)
        source   (impl/read-source path' max-read-bytes)
        zloc     (impl/parse-or-fail source (impl/path->str path'))
        def-zloc (impl/find-top-level-def zloc name)]
    (if-not def-zloc
      (ok-result {:path (impl/path->str path') :changed false :reason "definition not found"})
      (let [new-zloc (z/remove def-zloc)
            out      (impl/root-string-or-fail new-zloc (impl/path->str path'))]
        (impl/write-with-backup! path' out)
        (ok-result {:path (impl/path->str path') :changed true :name (pr-str name)})))))

(deftype RemoveDefTool [workspace-root max-read-bytes]
  tool/Tool
  (-name [_] "clojure_remove_def")
  (-description [_]
    "Remove a top-level def/defn/etc. by name. Writes the file only if the round-trip parse check passes.")
  (-input-schema [_] InputSchema:RemoveDef)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (remove-def-from-file workspace-root (:path args) max-read-bytes (symbol (:name args)))
      (catch Throwable t (error-result t)))))

(defn- rename-symbol-in-file [workspace-root path max-read-bytes old new]
  (let [path'       (impl/resolve-path workspace-root path)
        source      (impl/read-source path' max-read-bytes)
        forms-zloc  (impl/parse-or-fail source (impl/path->str path'))
        occurrences (atom 0)]
    (let [renamed-zloc (z/prewalk forms-zloc
                                  (fn [zloc]
                                    (if (and (= :token (z/tag zloc)) (= old (z/sexpr zloc)))
                                      (do (swap! occurrences inc)
                                          (z/edit zloc (constantly (n/token-node new))))
                                      zloc)))]
      (if (zero? @occurrences)
        (ok-result {:path (impl/path->str path') :changed false :reason "symbol not found"})
        (let [out (impl/root-string-or-fail renamed-zloc (impl/path->str path'))]
          (impl/write-with-backup! path' out)
          (ok-result {:path (impl/path->str path') :changed true :renamed @occurrences
                      :old (pr-str old) :new (pr-str new)}))))))

(deftype RenameSymbolTool [workspace-root max-read-bytes]
  tool/Tool
  (-name [_] "clojure_rename_symbol")
  (-description [_]
    "Rename all occurrences of :old symbol to :new symbol in a Clojure file. Writes the file only if the round-trip parse check passes.")
  (-input-schema [_] InputSchema:RenameSymbol)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (rename-symbol-in-file workspace-root (:path args) max-read-bytes
                             (symbol (:old args)) (symbol (:new args)))
      (catch Throwable t (error-result t)))))

(defn- insert-form-into-file [workspace-root path max-read-bytes form-str position]
  (let [position' (case position
                     ("end" :end) :end
                     ("beginning" :beginning) :beginning
                     nil :end
                     :end)
        path'     (impl/resolve-path workspace-root path)
        source    (impl/read-source path' max-read-bytes)
        zloc      (impl/parse-or-fail source (impl/path->str path'))
        form-node (z/node (z/of-string* form-str))
        new-zloc  (if (= position' :beginning)
                    (z/insert-child zloc form-node)
                    (z/append-child zloc form-node))
        out       (impl/root-string-or-fail new-zloc (impl/path->str path'))]
    (impl/write-with-backup! path' out)
    (ok-result {:path (impl/path->str path') :changed true :position (name position')})))

(deftype InsertFormTool [workspace-root max-read-bytes]
  tool/Tool
  (-name [_] "clojure_insert_form")
  (-description [_]
    "Insert one complete top-level Clojure form into a file. :form must be a single quoted Clojure form as a string, e.g. '(defn reverse-string [s] (apply str (reverse s)))'. :path is the file name. :position can be :end (default) or :beginning. Writes the file only if the round-trip parse check passes.")
  (-input-schema [_] InputSchema:InsertForm)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (insert-form-into-file workspace-root (:path args) max-read-bytes
                             (:form args) (or (:position args) :end))
      (catch Throwable t (error-result t)))))

(defn- edit-def-in-file [workspace-root path max-read-bytes name body-str]
  (let [path'     (impl/resolve-path workspace-root path)
        source    (impl/read-source path' max-read-bytes)
        zloc      (impl/parse-or-fail source (impl/path->str path'))
        def-zloc  (impl/find-top-level-def zloc name)
        body-node (z/node (z/of-string* body-str))]
    (if-not def-zloc
      (ok-result {:path (impl/path->str path') :changed false :reason "definition not found"})
      (let [first-body (impl/first-body-node def-zloc)]
        (if-not first-body
          (throw (ex-info "Could not locate def body to replace"
                          {:kind :clojure-tool/error :path (impl/path->str path')}))
          (let [body-only (impl/remove-all-right first-body)
                new-zloc  (z/replace body-only body-node)
                out       (impl/root-string-or-fail new-zloc (impl/path->str path'))]
            (impl/write-with-backup! path' out)
            (ok-result {:path (impl/path->str path') :changed true :name (pr-str name)})))))))

(deftype EditDefTool [workspace-root max-read-bytes]
  tool/Tool
  (-name [_] "clojure_edit_def")
  (-description [_]
    "Replace the body of a defn/def by name. The :body string replaces the function/value body after the arg vector (for defn) or after the name/docstring/metadata (for def). Writes the file only if the round-trip parse check passes.")
  (-input-schema [_] InputSchema:EditDef)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (edit-def-in-file workspace-root (:path args) max-read-bytes
                        (symbol (:name args)) (:body args))
      (catch Throwable t (error-result t)))))

(defn- format-file-on-disk [workspace-root path max-read-bytes]
  (let [path'  (impl/resolve-path workspace-root path)
        source (impl/read-source path' max-read-bytes)
        zloc   (impl/parse-or-fail source (impl/path->str path'))
        out    (impl/root-string-or-fail zloc (impl/path->str path'))]
    (if (= source out)
      (ok-result {:path (impl/path->str path') :changed false})
      (do (impl/write-with-backup! path' out)
          (ok-result {:path (impl/path->str path') :changed true})))))

(deftype FormatFileTool [workspace-root max-read-bytes]
  tool/Tool
  (-name [_] "clojure_format_file")
  (-description [_]
    "Reformat a Clojure/EDN file using rewrite-clj's pretty printer. Writes the file only if the round-trip parse check passes.")
  (-input-schema [_] InputSchema:Path)
  (-output-schema [_] OutputSchema:String)
  (-invoke [_ args _ctx]
    (try
      (format-file-on-disk workspace-root (:path args) max-read-bytes)
      (catch Throwable t (error-result t)))))

(defn query
  "Return a new `clojure_query` Tool instance."
  ([] (query nil default-max-read-bytes))
  ([workspace-root] (query workspace-root default-max-read-bytes))
  ([workspace-root max-read-bytes]
   (->QueryTool workspace-root max-read-bytes)))

(defn add-require
  "Return a new `clojure_add_require` Tool instance."
  ([] (add-require nil default-max-read-bytes))
  ([workspace-root] (add-require workspace-root default-max-read-bytes))
  ([workspace-root max-read-bytes]
   (->AddRequireTool workspace-root max-read-bytes)))

(defn remove-def
  "Return a new `clojure_remove_def` Tool instance."
  ([] (remove-def nil default-max-read-bytes))
  ([workspace-root] (remove-def workspace-root default-max-read-bytes))
  ([workspace-root max-read-bytes]
   (->RemoveDefTool workspace-root max-read-bytes)))

(defn rename-symbol
  "Return a new `clojure_rename_symbol` Tool instance."
  ([] (rename-symbol nil default-max-read-bytes))
  ([workspace-root] (rename-symbol workspace-root default-max-read-bytes))
  ([workspace-root max-read-bytes]
   (->RenameSymbolTool workspace-root max-read-bytes)))

(defn insert-form
  "Return a new `clojure_insert_form` Tool instance."
  ([] (insert-form nil default-max-read-bytes))
  ([workspace-root] (insert-form workspace-root default-max-read-bytes))
  ([workspace-root max-read-bytes]
   (->InsertFormTool workspace-root max-read-bytes)))

(defn edit-def
  "Return a new `clojure_edit_def` Tool instance."
  ([] (edit-def nil default-max-read-bytes))
  ([workspace-root] (edit-def workspace-root default-max-read-bytes))
  ([workspace-root max-read-bytes]
   (->EditDefTool workspace-root max-read-bytes)))

(defn format-file
  "Return a new `clojure_format_file` Tool instance."
  ([] (format-file nil default-max-read-bytes))
  ([workspace-root] (format-file workspace-root default-max-read-bytes))
  ([workspace-root max-read-bytes]
   (->FormatFileTool workspace-root max-read-bytes)))

(defn clojure-registry
  "Return a map of Clojure tool name -> Tool instance.

   Accepts an optional `opts` map with:
     :workspace-root — root for resolving relative paths
     :max-read-bytes — cap for source reads (default 256 KB)

   When `:workspace-root` is omitted, the current working directory is
   used. No path containment is enforced."
  ([] (clojure-registry {}))
  ([{:keys [workspace-root max-read-bytes]}]
   {"clojure_query"         (query workspace-root (or max-read-bytes default-max-read-bytes))
    "clojure_add_require"   (add-require workspace-root (or max-read-bytes default-max-read-bytes))
    "clojure_remove_def"    (remove-def workspace-root (or max-read-bytes default-max-read-bytes))
    "clojure_rename_symbol" (rename-symbol workspace-root (or max-read-bytes default-max-read-bytes))
    "clojure_insert_form"   (insert-form workspace-root (or max-read-bytes default-max-read-bytes))
    "clojure_edit_def"      (edit-def workspace-root (or max-read-bytes default-max-read-bytes))
    "clojure_format_file"   (format-file workspace-root (or max-read-bytes default-max-read-bytes))}))
