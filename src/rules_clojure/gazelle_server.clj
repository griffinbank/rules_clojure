(ns rules-clojure.gazelle-server
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [rules-clojure.fs :as fs]
            [rules-clojure.gen-build :as gen-build]
            [rules-clojure.namespace.parse :as parse])
  (:import (java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter))
  (:gen-class))

(set! *warn-on-reflection* true)

(defn read-request
  "Read a JSON line from reader, return parsed map with keyword keys or nil on EOF."
  [^BufferedReader reader]
  (when-let [line (.readLine reader)]
    (json/read-str line :key-fn keyword)))

(defn write-response
  "Write a map as a single JSON line to writer, then flush."
  [^BufferedWriter writer resp]
  (.write writer (json/write-str resp))
  (.newLine writer)
  (.flush writer))

(defn- strip-leading-colon
  "Aliases arriving from the Gazelle directive `clojure_aliases :foo,:bar`
  carry a leading colon; `(keyword \":foo\")` would produce a malformed
  keyword whose name is `:foo`. Strip the colon before calling keyword."
  [a]
  (cond-> a (str/starts-with? a ":") (subs 1)))

(s/def ::deps_edn_path string?)
(s/def ::repository_dir string?)
(s/def ::deps_repo_tag string?)
(s/def ::root_module_name (s/nilable string?))
(s/def ::aliases (s/coll-of string? :kind sequential?))
(s/def ::init-request
  (s/keys :req-un [::deps_edn_path ::repository_dir ::deps_repo_tag ::aliases]
          :opt-un [::root_module_name]))

(defn init-state
  "Process an init request. Returns `{:response wire :state internal}`.
   Splitting the return shape (rather than smuggling `:state` inside `wire`)
   makes it impossible for a future caller to forget to dissoc internal
   state before serialization.

   `clojure.data.json` stringifies keyword and symbol keys automatically,
   so the response map can carry native keyword/symbol keys directly."
  [{:keys [deps_edn_path repository_dir deps_repo_tag root_module_name aliases]}]
  (let [deps-edn-path    (-> deps_edn_path fs/->path fs/absolute)
        deps-edn-dir     (fs/dirname deps-edn-path)
        repository-dir   (fs/->path repository_dir)
        deps-repo-tag    deps_repo_tag
        root-module-name root_module_name
        aliases          (mapv (comp keyword strip-leading-colon) aliases)
        read-deps        (gen-build/read-deps deps-edn-path)
        deps-bazel       (gen-build/parse-deps-bazel read-deps (or root-module-name ""))
        basis            (gen-build/make-basis {:read-deps      read-deps
                                                :aliases        aliases
                                                :repository-dir repository-dir
                                                :deps-edn-path  deps-edn-path})
        dep-ns->label    (gen-build/->dep-ns->label {:basis         basis
                                                     :deps-bazel    deps-bazel
                                                     :deps-repo-tag deps-repo-tag})
        jar->lib         (gen-build/->jar->lib basis)
        class->jar       (gen-build/->class->jar basis)
        ignore-paths     (:ignore deps-bazel)]
    {:response {:type          "init"
                :dep_ns_labels dep-ns->label
                :deps_bazel    (dissoc deps-bazel :no-aot :ignore)
                :ignore_paths  (vec ignore-paths)
                :source_paths  (mapv str (:paths basis))}
     :state    {:basis         basis
                :deps-bazel    deps-bazel
                :jar->lib      jar->lib
                :class->jar    class->jar
                :dep-ns->label dep-ns->label
                :deps-edn-path deps-edn-path
                :deps-edn-dir  deps-edn-dir
                :deps-repo-tag deps-repo-tag
                :aliases       aliases}}))

(defn- ext->platforms
  "Map a file extension to the set of platforms it supports."
  [ext]
  (case ext
    "clj"  #{:clj}
    "cljs" #{:cljs}
    "cljc" #{:clj :cljs}
    #{}))

(defn- file-ext
  "Return the file extension (without dot) or nil."
  [filename]
  (let [s (str filename)]
    (when-let [i (str/last-index-of s ".")]
      (subs s (inc i)))))

(defn- file-for-platform
  "Return the first file in `files` whose extension supports `plat`, or nil."
  [plat files]
  (first (filter #(contains? (ext->platforms (file-ext %)) plat) files)))

(defn- rule-spec->wire
  "Translate a {:type :clojure_library :attrs {:name ... :deps [...]}} from
  ns-rules into wire shape: stringified type, qualified-keyword attr names
  (so the Go side can range a map[string]interface{}).

  Keep :deps for every kind. ns-rules-args supplies empty src-ns->label /
  dep-ns->label so ns-rules merges only the static parts (org_clojure_clojure
  + clojure-library-args + ns-library-meta + import-deps + gen-class-deps).
  Gazelle's Resolve seeds depSet from these and adds the per-require
  intra-repo / DepNsLabels resolution + per-target deps_bazel overrides."
  [{:keys [type attrs]}]
  {:kind (name type)
   :attrs (->> attrs (reduce-kv (fn [m k v] (assoc m (name k) v)) {}))})

(defn- ns-rules-args
  "Build the args map ns-rules expects from the persistent server state.
  src-ns->label is empty (intra-repo resolution is Gazelle's job — index
  isn't available here). dep-ns->label is empty so ns-rules' per-require
  external lookup is a no-op; the Go-side Resolve does that lookup against
  initResp.DepNsLabels with proper intra-repo-wins semantics."
  [state]
  (-> (select-keys state [:basis :deps-edn-dir :deps-bazel :deps-repo-tag
                          :jar->lib :class->jar])
      (assoc :src-ns->label {})
      (assoc :dep-ns->label {:clj {} :cljs {}})))

(defn- exception-chain
  "Walk a Throwable's getCause chain and join class+message for each link.
  Preserves root-cause class (EOFException vs FileNotFoundException etc.) that
  `.getMessage` alone collapses when an outer wrapper rewraps the cause."
  [^Throwable t]
  (->> (iterate #(.getCause ^Throwable %) t)
       (take-while some?)
       (mapv (fn [^Throwable x]
               (let [klass (.getName (class x))
                     msg   (.getMessage x)]
                 (if msg (str klass ": " msg) klass))))
       (str/join " -> ")))

(defn parse-namespace-group
  "Parse a group of files sharing a basename within dir. Returns:
   - empty vector when no file in the group declares a namespace AND has
     no JS files (e.g. resource-only files like data_readers.clj)
   - vector of one map with namespace info + rule specs on success.
     `:rules` comes from gen-build/ns-rules so the Go side doesn't
     re-implement AOT decisions, test-attr passthrough, or binary emission.
     Rules are keyword-shaped here; `handle-parse` wire-converts them at
     the response boundary. For JS-only groups :js-only? is true and
     :ns/:requires are absent — the Go side branches on Kind, not on :ns.
   - vector of one {:error ... :file ...} entry on parse failure
     (caller MUST treat as fatal — silent drop deletes existing rules).

  An `ns-rules-args` map can be precomputed by the caller and passed in via
  `:ns-rules-args` of `state` to skip per-group rebuild."
  [state dir files]
  (try
    (let [abs-dir (let [d (fs/->path dir)]
                    (if (fs/absolute? d) d (fs/->path (:deps-edn-dir state) dir)))
          abs-paths (mapv #(fs/->path abs-dir %) files)
          clj-files (filterv #(contains? #{"clj" "cljc" "cljs"} (file-ext %)) files)
          js-only? (and (empty? clj-files) (seq files))
          ns-args   (or (:ns-rules-args state) (ns-rules-args state))]
      (cond
        ;; All-JS group: java_library rule only. :ns/:requires are absent
        ;; because Go's Resolve short-circuits on Kind != "clojure_library"
        ;; (resolve.go:57), so neither field is ever read for JS-only.
        js-only?
        [{:file      (str (first files))
          :platforms ["js"]
          :rules     (vec (gen-build/ns-rules ns-args abs-paths))}]

        :else
        (let [platforms (apply set/union (map #(ext->platforms (file-ext %)) clj-files))
              primary-platform (if (contains? platforms :clj) :clj :cljs)
              ;; Read the ns-decl once per (file, platform) pair. A primary
              ;; file may not declare a namespace (resource-only data_readers
              ;; .clj); a sibling .cljs may still declare one. Build the full
              ;; map and pick primary if present, else any non-nil decl —
              ;; only treat the group as resource-only if NO file has ns.
              decls-by-plat (into {}
                                  (for [plat (sort-by name platforms)
                                        :let [f (file-for-platform plat clj-files)
                                              p (fs/->path abs-dir f)
                                              decl (gen-build/get-ns-decl p plat)]
                                        :when decl]
                                    [plat decl]))
              primary-file (file-for-platform primary-platform clj-files)
              ns-decl (or (get decls-by-plat primary-platform)
                          (some decls-by-plat (sort-by name platforms)))]
          (if (nil? ns-decl)
            ;; clj* files exist but none declare a namespace (resource-only,
            ;; e.g. data_readers.clj). Skip ns-rules — its get-ns-meta /
            ;; ns-deps callees would read the file as a non-ns form and
            ;; assert. Resource-only groups need no Bazel rule.
            []
            (let [ns-name (str (second ns-decl))
                  requires (into (sorted-map)
                                 (for [[plat decl] decls-by-plat
                                       :let [deps (mapv str (parse/deps-from-ns-decl decl))]
                                       :when (seq deps)]
                                   [(name plat) (vec (sort deps))]))
                  ;; Drop any abs-path whose natural platforms have no decl —
                  ;; otherwise ns-rules' get-ns-meta would assert on a resource-
                  ;; only sibling (e.g. data_readers.clj alongside a declaring
                  ;; data_readers.cljs).
                  rule-paths (filterv (fn [p]
                                        (some #(get decls-by-plat %)
                                              (ext->platforms (str (fs/extension p)))))
                                      abs-paths)
                  rules (if (seq rule-paths)
                          (vec (gen-build/ns-rules ns-args rule-paths))
                          [])]
              [{:ns        ns-name
                :file      (str primary-file)
                :requires  requires
                :platforms (vec (sort (map name platforms)))
                :rules     rules}])))))
    (catch Throwable t
      (binding [*out* *err*]
        (println "gazelle-server: error parsing" dir (mapv str files))
        (.printStackTrace t ^java.io.PrintWriter *err*))
      ;; Return a tagged error entry rather than [] so the Go caller can
      ;; distinguish "no ns form" from "parse failed" and abort instead of
      ;; silently deleting BUILD rules. Preserve the cause chain so root-
      ;; cause class (EOFException etc.) isn't collapsed by an outer wrap.
      [{:error (exception-chain t)
        :file  (str (first files))}])))

(def ^:private rollup-kinds
  "Rule types that contribute to the __clj_lib / __clj_files rollup. These
  are keywords because rules are kept keyword-shaped through aggregation
  (rule-spec->wire stringifies later at the response boundary)."
  #{:clojure_library :java_library})

(defn- lib-names-from-rules
  "Pull the names of rules eligible for __clj_lib rollup from per-namespace
  rule specs."
  [namespaces]
  (into []
        (comp (mapcat :rules)
              (filter (comp rollup-kinds :type))
              (map (comp :name :attrs))
              (distinct))
        namespaces))

(defn- src-files-from-rules
  "Pull file basenames for __clj_files rollup (all resources of the rollup
  rules — clojure_library + java_library)."
  [namespaces]
  (into []
        (comp (mapcat :rules)
              (filter (comp rollup-kinds :type))
              (mapcat (comp :resources :attrs))
              (distinct))
        namespaces))

(s/def ::dir string?)
(s/def ::files (s/coll-of string? :kind sequential?))
(s/def ::clojure_subdir_paths (s/coll-of string? :kind sequential?))
(s/def ::parse-request
  (s/keys :req-un [::dir ::files]
          :opt-un [::clojure_subdir_paths]))

(defn handle-parse
  "Process a parse request. Returns:
     {:type \"parse\"
      :namespaces [{...per-namespace + :rules} ...]
      :rollup_rules [...__clj_lib / __clj_files specs...]}

  Mirrors gen-dir: clj+js files are grouped by basename and each group flows
  through ns-rules so a `webauthn.clj` + `webauthn.js` pair produces ONE
  clojure_library. Rollup specs aggregate the local rule names plus any
  subdir labels the caller provides via `clojure_subdir_paths` — Gazelle's
  Go side knows which children transitively contain Clojure content.

  Rules are kept keyword-shaped through aggregation; `rule-spec->wire` is
  applied once at the response boundary so the lib/src-files extraction can
  read `:name`/`:resources` as keywords (not the stringified-attr shape)."
  [state {:keys [dir files clojure_subdir_paths] :as request}]
  (when-not (s/valid? ::parse-request request)
    (throw (ex-info (str "invalid parse request: "
                         (s/explain-str ::parse-request request))
                    {:request request})))
  (let [relevant (filterv (some-fn gen-build/clj*-path? gen-build/js-path?) files)
        groups (->> relevant
                    (group-by #(fs/basename (fs/->path %)))
                    (sort-by key)
                    (mapv val))
        ns-args (ns-rules-args state)
        state' (assoc state :ns-rules-args ns-args)
        parsed (into [] (mapcat #(parse-namespace-group state' dir %)) groups)
        rollup-specs (gen-build/rollup-rules
                      {:lib-deps             (lib-names-from-rules parsed)
                       :src-files            (src-files-from-rules parsed)
                       :clojure-subdir-paths (or clojure_subdir_paths [])})]
    {:type         "parse"
     :namespaces   (mapv #(update % :rules (partial mapv rule-spec->wire)) parsed)
     :rollup_rules (mapv rule-spec->wire rollup-specs)}))

(defn handle-request
  "Dispatch a single request. Returns the wire-response map and mutates the
  state atom on init. Wrapped in try/catch by `-main` so a single bad request
  returns an error envelope rather than killing the server."
  [!state request]
  (case (:type request)
    "init"  (let [{:keys [response state]} (init-state request)]
              (reset! !state state)
              response)
    "parse" (if (nil? @!state)
              {:type "error" :message "parse received before init"}
              (handle-parse @!state request))
    {:type "error" :message (str "unknown request type: " (:type request))}))

(defn -main [& _args]
  (let [reader (BufferedReader. (InputStreamReader. System/in))
        writer (BufferedWriter. (OutputStreamWriter. System/out))
        !state (atom nil)]
    (loop []
      (let [request (try
                      (read-request reader)
                      (catch Throwable t
                        (binding [*out* *err*]
                          (println "gazelle-server: read failed:" (.getMessage t)))
                        ::eof))]
        (when (and (some? request) (not= ::eof request))
          (let [response (try
                           (handle-request !state request)
                           (catch Throwable t
                             (binding [*out* *err*]
                               (.printStackTrace t ^java.io.PrintWriter *err*))
                             {:type "error" :message (exception-chain t)}))]
            (write-response writer response)
            (recur)))))))
